package io.github.selfsizing.iblt.smoke;

import io.github.selfsizing.iblt.controller.HttpSidecarClient;
import io.github.selfsizing.iblt.controller.SidecarClient;
import io.github.selfsizing.iblt.core.IbltDecodeResult;
import io.github.selfsizing.iblt.core.IbltDecoder;
import io.github.selfsizing.iblt.core.IbltHash;
import io.github.selfsizing.iblt.core.IbltSketch;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repeated failed-only measurement of the paper profile:
 * M0=512, M2=ceil(1.52*dHat), fresh rehash, round-1+round-2 joint peeling.
 *
 * <p>This deliberately stops after round 2 and does not resolve/recheck keys, so the reported
 * denominator measures decoder trials rather than database value-fetch work.</p>
 */
public final class IbltSecondRoundFailureSweep {

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 6) {
            System.err.println("usage: IbltSecondRoundFailureSweep <urlA> <urlB> "
                    + "[trials=100] [M0=512] [alpha=1.52] [baseSeed=20260720]");
            System.exit(2);
        }
        int trials = args.length >= 3 ? Integer.parseInt(args[2]) : 100;
        int m0 = args.length >= 4 ? Integer.parseInt(args[3]) : 512;
        double alpha = args.length >= 5 ? Double.parseDouble(args[4]) : 1.52;
        long baseSeed = args.length >= 6 ? Long.parseLong(args[5]) : 20260720L;
        if (trials <= 0 || m0 <= 0 || !Double.isFinite(alpha) || alpha <= 0) {
            throw new IllegalArgumentException("require trials>0, M0>0, finite alpha>0");
        }

        SidecarClient a = new HttpSidecarClient("A", args[0]);
        SidecarClient b = new HttpSidecarClient("B", args[1]);
        System.out.println("[sweep] A.health: " + a.health());
        System.out.println("[sweep] B.health: " + b.health());
        System.out.printf("[sweep] config trials=%d M0=%d alpha=%.3f baseSeed=%d%n",
                trials, m0, alpha, baseSeed);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int round1Success = 0;
        int round1Failures = 0;
        int round2Success = 0;
        int round2Failures = 0;
        double sumDhat = 0;
        int minM2 = Integer.MAX_VALUE;
        int maxM2 = 0;
        try {
            // Scan Redis once. All trials below reuse the immutable fingerprint stores and vary
            // only the independent round seeds, which is the probability space being measured.
            long firstSeed0 = trialSeed(baseSeed, 0);
            Future<SidecarClient.BuildSketchRemote> initialAFuture =
                    pool.submit(() -> a.buildSketch(m0, firstSeed0));
            Future<SidecarClient.BuildSketchRemote> initialBFuture =
                    pool.submit(() -> b.buildSketch(m0, firstSeed0));
            SidecarClient.BuildSketchRemote initialA = get(initialAFuture);
            SidecarClient.BuildSketchRemote initialB = get(initialBFuture);
            quietDecoderLogs();
            for (int trial = 0; trial < trials; trial++) {
                long seed0 = trialSeed(baseSeed, trial);
                long seed2 = nonZero(IbltHash.sm64(baseSeed + 2L * trial + 1L));
                if (seed2 == seed0) seed2 = nonZero(IbltHash.sm64(seed2 + 1));
                IbltSketch a0;
                IbltSketch b0;
                if (trial == 0) {
                    a0 = initialA.sketch();
                    b0 = initialB.sketch();
                } else {
                    final long round1Seed = seed0;
                    Future<IbltSketch> a0Future = pool.submit(
                            () -> a.rebucket(initialA.sessionId(), m0, round1Seed));
                    Future<IbltSketch> b0Future = pool.submit(
                            () -> b.rebucket(initialB.sessionId(), m0, round1Seed));
                    a0 = get(a0Future);
                    b0 = get(b0Future);
                }
                IbltDecodeResult first = IbltDecoder.decode(a0, b0);
                    if (first.isSuccess()) {
                        round1Success++;
                        if (trial < 3 || (trial + 1) % 100 == 0) {
                            System.out.printf("[sweep] trial=%d seed0=%d round1=SUCCESS dHat=%.1f%n",
                                    trial, seed0, first.getdHat());
                        }
                        continue;
                    }

                    round1Failures++;
                    sumDhat += first.getdHat();
                    int m2 = (int) Math.ceil(alpha * first.getdHat());
                    if (m2 <= 0) {
                        throw new IllegalStateException("invalid M2=" + m2 + " from dHat=" + first.getdHat());
                    }
                    minM2 = Math.min(minM2, m2);
                    maxM2 = Math.max(maxM2, m2);
                    final int round2M = m2;
                    final long round2Seed = seed2;
                    Future<IbltSketch> a2Future = pool.submit(
                            () -> a.rebucket(initialA.sessionId(), round2M, round2Seed));
                    Future<IbltSketch> b2Future = pool.submit(
                            () -> b.rebucket(initialB.sessionId(), round2M, round2Seed));
                    IbltSketch a2 = get(a2Future);
                    IbltSketch b2 = get(b2Future);
                    IbltDecodeResult second = IbltDecoder.decodeJoint(
                            List.of(a0, a2), List.of(b0, b2));
                    if (second.isSuccess()) {
                        round2Success++;
                    } else {
                        round2Failures++;
                    }
                    if (!second.isSuccess() || trial < 3 || (trial + 1) % 100 == 0) {
                        System.out.printf("[sweep] trial=%d seed0=%d seed2=%d dHat=%.1f M2=%d "
                                        + "round2=%s residual=%d plus=%d minus=%d%n",
                                trial, seed0, seed2, first.getdHat(), m2,
                                second.isSuccess() ? "SUCCESS" : "FAILED",
                                second.getResidualBuckets(),
                                second.getPlusIds().length, second.getMinusIds().length);
                    }
                }
        } finally {
            closeQuietly(a);
            closeQuietly(b);
            pool.shutdownNow();
        }

        double failureRate = round1Failures == 0
                ? Double.NaN : (double) round2Failures / round1Failures;
        double ruleOfThree = round2Failures == 0 && round1Failures > 0
                ? 3.0 / round1Failures : Double.NaN;
        System.out.printf("[sweep] SUMMARY trials=%d round1Success=%d round1Failures=%d "
                        + "round2Success=%d round2Failures=%d secondFailureRate=%.8f%n",
                trials, round1Success, round1Failures, round2Success, round2Failures, failureRate);
        if (round1Failures > 0) {
            System.out.printf("[sweep] dHatMean=%.3f M2Range=[%d,%d]%n",
                    sumDhat / round1Failures, minM2, maxM2);
        }
        if (round2Failures == 0 && round1Failures > 0) {
            System.out.printf("[sweep] zero-failure rule-of-three upper95≈%.8f%n", ruleOfThree);
        }
        System.exit(round2Failures == 0 ? 0 : 1);
    }

    private static long nonZero(long value) {
        return value == 0L ? 1L : value;
    }

    private static long trialSeed(long baseSeed, int trial) {
        return nonZero(IbltHash.sm64(baseSeed + 2L * trial));
    }

    private static void quietDecoderLogs() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.WARNING);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(Level.WARNING);
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    private static void closeQuietly(SidecarClient sidecar) {
        try {
            sidecar.closeSessions();
        } catch (Exception e) {
            System.err.println("[sweep] close sessions on " + sidecar.label() + " failed: " + e);
        }
    }

    private IbltSecondRoundFailureSweep() {
    }
}

package io.github.selfsizing.iblt.controller;

import io.github.selfsizing.iblt.core.IbltDecodeResult;
import io.github.selfsizing.iblt.core.IbltDecoder;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.sidecar.RecheckResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Default controller orchestration: each side reads its local database and
 * builds a sketch locally; the controller only <b>decodes locally</b> and asks
 * the two sides for sketch / resolve / recheck, all small control payloads.
 *
 * <p>Difference primary keys are returned in <b>full</b>; row values are fetched
 * for a sample per {@link IbltComparePlan#valueSampleLimit()}, avoiding pulling
 * massive row values over the network when there are many differences. A failed
 * decode still returns a result (success=false + the dHat size estimate).</p>
 */
public final class DefaultIbltCompareController implements IbltCompareController {

    private static final Logger LOG = Logger.getLogger(DefaultIbltCompareController.class.getName());

    @Override
    public IbltCompareResult compare(SidecarClient a, SidecarClient b, IbltComparePlan plan) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            return compareWithPool(a, b, plan, pool);
        } finally {
            closeSessionsQuietly(a);
            closeSessionsQuietly(b);
            pool.shutdownNow();
        }
    }

    private static void closeSessionsQuietly(SidecarClient sidecar) {
        try {
            sidecar.closeSessions();
        } catch (Exception e) {
            LOG.warning("[ctrl] close sessions on " + sidecar.label() + " failed: " + e);
        }
    }

    private IbltCompareResult compareWithPool(SidecarClient a, SidecarClient b, IbltComparePlan plan,
                                              ExecutorService pool) throws Exception {
        MapperHandshake.verify(a, b);
        int m = plan.bucketCount0();
        long seed0 = freshSeed(0L);

        // 1. each side builds its sketch locally (keeping the fingerprint cache in the session)
        final int initialM = m;
        Future<SidecarClient.BuildSketchRemote> fa =
                pool.submit(() -> a.buildSketch(initialM, seed0));
        Future<SidecarClient.BuildSketchRemote> fb =
                pool.submit(() -> b.buildSketch(initialM, seed0));
        SidecarClient.BuildSketchRemote ra = get(fa);
        SidecarClient.BuildSketchRemote rb = get(fb);
        LOG.info(() -> "[ctrl] buildSketch A=" + ra.metrics() + " (" + ra.sketchWireBytes() + "B, "
                + ra.roundTripMillis() + "ms rtt); B=" + rb.metrics() + " (" + rb.sketchWireBytes()
                + "B, " + rb.roundTripMillis() + "ms rtt)");

        // 2. local decode. On failure, do exactly one one-jump:
        //    M2=ceil(alpha*dHat), a fresh rehash in round 2, joint peeling with the round-1 residual.
        List<IbltSketch> asList = new ArrayList<>();
        List<IbltSketch> bsList = new ArrayList<>();
        asList.add(ra.sketch());
        bsList.add(rb.sketch());
        IbltDecodeResult res = IbltDecoder.decode(asList.get(0), bsList.get(0));
        int rebucketRounds = 0;
        if (!res.isSuccess()) {
            double dHatAtFail = res.getdHat();
            double rawTarget = Math.ceil(plan.effectiveSecondRoundAlpha() * dHatAtFail);
            if (!Double.isFinite(rawTarget) || rawTarget <= 0 || rawTarget > plan.bucketCap()) {
                LOG.warning(String.format(
                        "[ctrl] FALLBACK before round 2: dHat=%.1f alpha=%.3f target=%.1f cap=%d",
                        dHatAtFail, plan.effectiveSecondRoundAlpha(), rawTarget, plan.bucketCap()));
                return new IbltCompareResult(false, m, res.getPlusIds().length, res.getMinusIds().length,
                        res.getResidualBuckets(), res.getdHat(), List.of(), 0, null, null,
                        ra.sketchWireBytes(), rb.sketchWireBytes());
            }
            m = (int) rawTarget;
            final int nextM = m;
            final long seed2 = freshSeed(seed0);
            LOG.info(() -> String.format(
                    "[ctrl] one-jump: M0=%d seed0=%d -> M2=%d seed2=%d "
                            + "(dHat=%.1f alpha=%.3f margin=%.3f effectiveAlpha=%.3f jointPeel=%b)",
                    initialM, seed0, nextM, seed2, dHatAtFail,
                    plan.secondRoundAlpha(), plan.engineeringMargin(),
                    plan.effectiveSecondRoundAlpha(), plan.jointPeel()));
            Future<IbltSketch> rfa =
                    pool.submit(() -> a.rebucket(ra.sessionId(), nextM, seed2));
            Future<IbltSketch> rfb =
                    pool.submit(() -> b.rebucket(rb.sessionId(), nextM, seed2));
            asList.add(get(rfa));
            bsList.add(get(rfb));
            res = plan.jointPeel()
                    ? IbltDecoder.decodeJoint(asList, bsList)
                    : IbltDecoder.decode(asList.get(asList.size() - 1), bsList.get(bsList.size() - 1));
            rebucketRounds = 1;
        }
        int mAtDecode = m;
        final int roundsFinal = rebucketRounds;
        LOG.info(() -> "[ctrl] rebucketRounds=" + roundsFinal + " mAtDecode=" + mAtDecode);

        if (!res.isSuccess()) {
            LOG.warning("[ctrl] SECOND_ROUND_FAILED at M=" + m
                    + ", residual=" + res.getResidualBuckets()
                    + ", dHat=" + res.getdHat() + "; no further growth, returning FALLBACK");
            return new IbltCompareResult(false, mAtDecode, res.getPlusIds().length, res.getMinusIds().length,
                    res.getResidualBuckets(), res.getdHat(), List.of(), 0, null, null,
                    ra.sketchWireBytes(), rb.sketchWireBytes());
        }

        // 3. candidate id -> pk: plus resolved by side A, minus by side B (each holding its own side's rows), de-duplicated union (full)
        Set<String> union = new LinkedHashSet<>();
        union.addAll(a.resolveIds(ra.sessionId(), res.getPlusIds()));
        union.addAll(b.resolveIds(rb.sessionId(), res.getMinusIds()));
        List<String> candidatePks = sortedPks(union);

        // 4. row-value sampling: all pks, but fetch real row values for only the first N candidates (<=0 = all)
        List<String> samplePks = candidatePks;
        if (!plan.fullValueFetch() && candidatePks.size() > plan.valueSampleLimit()) {
            samplePks = new ArrayList<>(candidatePks.subList(0, plan.valueSampleLimit()));
            LOG.info("[ctrl] row-value sampling: difference pks=" + candidatePks.size()
                    + ", fetching only the first " + samplePks.size() + " (valueSampleLimit)");
        }

        // 5. recheck on both sides (only the sampled pks are sent)
        final List<String> recheckPks = samplePks;
        Future<RecheckResponse> rcAF = pool.submit(() -> a.recheck(recheckPks));
        Future<RecheckResponse> rcBF = pool.submit(() -> b.recheck(recheckPks));
        RecheckResponse rcA = get(rcAF);
        RecheckResponse rcB = get(rcBF);
        // recheck is an IBLT-specific cost: candidate pks do a WHERE id IN(...) point lookup for real row values. Server time is reported honestly as its own column.
        final long recheckMsA = rcA.queryMillis();
        final long recheckMsB = rcB.queryMillis();
        final int recheckN = recheckPks.size();
        LOG.info(() -> String.format(
                "[ctrl] recheck IN pointquery: serverA=%dms serverB=%dms (pks=%d, hitA=%d, hitB=%d)",
                recheckMsA, recheckMsB, recheckN, rcA.rowCount(), rcB.rowCount()));

        IbltCompareResult result = new IbltCompareResult(true, mAtDecode,
                res.getPlusIds().length, res.getMinusIds().length, res.getResidualBuckets(), res.getdHat(),
                candidatePks, samplePks.size(), rcA, rcB, ra.sketchWireBytes(), rb.sketchWireBytes());
        LOG.info(() -> "[ctrl] " + result);
        return result;
    }

    private static long freshSeed(long differentFrom) {
        long seed;
        do {
            seed = ThreadLocalRandom.current().nextLong();
        } while (seed == 0L || seed == differentFrom);
        return seed;
    }

    private static <T> T get(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /** Sort de-duplicated pks: numeric ascending if all are integers (keeps "first N" stable), otherwise lexicographic. */
    private static List<String> sortedPks(Set<String> pks) {
        List<String> list = new ArrayList<>(pks);
        boolean allLong = true;
        for (String p : list) {
            try {
                Long.parseLong(p);
            } catch (NumberFormatException e) {
                allLong = false;
                break;
            }
        }
        if (allLong) {
            list.sort((x, y) -> Long.compare(Long.parseLong(x), Long.parseLong(y)));
        } else {
            list.sort(null);
        }
        return list;
    }
}

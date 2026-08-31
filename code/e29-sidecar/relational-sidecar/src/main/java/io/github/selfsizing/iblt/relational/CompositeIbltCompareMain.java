package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.controller.IbltComparePlan;
import io.github.selfsizing.iblt.controller.IbltCompareResult;

/** Orchestrates two composite-key sidecars through the existing controller, keeping M1=512 and the original decode/recheck conventions. */
public final class CompositeIbltCompareMain {
    private CompositeIbltCompareMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: <source-url> <target-url> [controller-workers]");
        }
        int controllerWorkers = args.length >= 3 ? parsePositiveInt(args[2], "controller-workers") : 2;
        HttpSidecarClient sourceHttp = new HttpSidecarClient("source", args[0]);
        HttpSidecarClient targetHttp = new HttpSidecarClient("target", args[1]);
        System.out.println("source=" + sourceHttp.health());
        System.out.println("target=" + targetHttp.health());
        TimingSidecarClient source = new TimingSidecarClient(sourceHttp);
        TimingSidecarClient target = new TimingSidecarClient(targetHttp);
        // Start after health preflight so the clock covers only the
        // controller's end-to-end comparison path.
        long started = System.nanoTime();
        final IbltCompareResult result;
        final long elapsedMs;
        try {
            result = new ConcurrentResolveIbltCompareController().compare(
                    source, target, IbltComparePlan.defaults(), controllerWorkers);
            elapsedMs = Math.round((System.nanoTime() - started) / 1e6);
        } catch (Exception e) {
            // The normal success path prints its result before enqueueing cleanup.  Preserve
            // best-effort cleanup on failed requests without changing the measured interval.
            ConcurrentResolveIbltCompareController.closeQuietly(source);
            ConcurrentResolveIbltCompareController.closeQuietly(target);
            throw e;
        }
        System.out.println(result);
        System.out.println("candidatePks=" + result.candidatePks());
        System.out.println("E29_RESULT algorithm=IBLT e2e_ms=" + elapsedMs
                + " success=" + result.isSuccess()
                + " controllerWorkers=" + controllerWorkers
                + " M=" + result.bucketCountAtDecode()
                + " plus=" + result.plusCount()
                + " minus=" + result.minusCount()
                + " residual=" + result.residualBuckets()
                + " dHat=" + result.dHat()
                + " diffPks=" + result.candidatePks().size()
                + " sketchBytesA=" + result.sketchWireBytesA()
                + " sketchBytesB=" + result.sketchWireBytesB()
                + " buildMsA=" + source.buildMs()
                + " buildMsB=" + target.buildMs()
                + " partitionMsA=" + source.partitionMs()
                + " partitionMsB=" + target.partitionMs()
                + " rebucketMsA=" + source.rebucketMs()
                + " rebucketMsB=" + target.rebucketMs()
                + " resolveMsA=" + source.resolveMs()
                + " resolveMsB=" + target.resolveMs()
                + " resolveMsTotal=" + (source.resolveMs() + target.resolveMs())
                + " resolveMsWall=" + Math.max(source.resolveMs(), target.resolveMs())
                + " recheckHttpWorkMsA=" + source.recheckMs()
                + " recheckHttpWorkMsB=" + target.recheckMs()
                + " recheckHttpWorkMsTotal=" + (source.recheckMs() + target.recheckMs())
                + " recheckHttpWallMsA=" + source.recheckWallMs()
                + " recheckHttpWallMsB=" + target.recheckWallMs()
                + " recheckHttpWallMs=" + Math.max(source.recheckWallMs(), target.recheckWallMs())
                // Backward-compatible names: A/B are aggregate batch work; the historical Wall
                // field now has the corrected wall-span meaning.
                + " recheckHttpMsA=" + source.recheckMs()
                + " recheckHttpMsB=" + target.recheckMs()
                + " recheckHttpMsWall=" + Math.max(source.recheckWallMs(), target.recheckWallMs())
                + " recheckDbMsA=" + (result.recheckA() == null ? 0 : result.recheckA().queryMillis())
                + " recheckDbMsB=" + (result.recheckB() == null ? 0 : result.recheckB().queryMillis())
                + " recheckHitA=" + (result.recheckA() == null ? 0 : result.recheckA().rowCount())
                + " recheckHitB=" + (result.recheckB() == null ? 0 : result.recheckB().rowCount()));
        System.out.flush();
        long cleanupStarted = System.nanoTime();
        ConcurrentResolveIbltCompareController.closeQuietly(source);
        ConcurrentResolveIbltCompareController.closeQuietly(target);
        long cleanupEnqueueMs = Math.round((System.nanoTime() - cleanupStarted) / 1e6);
        System.out.println("E29_CLEANUP algorithm=IBLT enqueue_ms=" + cleanupEnqueueMs
                + " async=true");
        if (!result.isSuccess()) {
            throw new IllegalStateException("composite IBLT compare failed: " + result);
        }
    }

    private static int parsePositiveInt(String value, String name) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer: " + value, e);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be a positive integer: " + value);
        }
        return parsed;
    }
}

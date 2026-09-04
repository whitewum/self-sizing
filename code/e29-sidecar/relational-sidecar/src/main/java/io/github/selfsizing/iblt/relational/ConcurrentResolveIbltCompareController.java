package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.controller.IbltComparePlan;
import io.github.selfsizing.iblt.controller.IbltCompareResult;
import io.github.selfsizing.iblt.controller.SidecarClient;
import io.github.selfsizing.iblt.relational.StreamingResolveClient;
import io.github.selfsizing.iblt.core.IbltDecodeResult;
import io.github.selfsizing.iblt.core.IbltDecoder;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.controller.MapperHandshake;
import io.github.selfsizing.iblt.sidecar.RecheckResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/** Existing two-round IBLT controller flow with source/target resolve executed concurrently. */
final class ConcurrentResolveIbltCompareController {
    /** Oracle rejects an IN list over 1000 expressions; keep margin for future PK-shape changes. */
    private static final int MAX_RECHECK_KEYS_PER_BATCH = 900;
    IbltCompareResult compare(SidecarClient source, SidecarClient target, IbltComparePlan plan,
            int controllerWorkers) throws Exception {
        if (controllerWorkers < 1) {
            throw new IllegalArgumentException("controllerWorkers must be positive");
        }
        MapperHandshake.verify(source, target);
        ExecutorService pool = Executors.newFixedThreadPool(controllerWorkers);
        try {
            int decodedM = plan.bucketCount0();
            long seed0 = freshSeed(0);
            Future<SidecarClient.BuildSketchRemote> buildA = pool.submit(
                    () -> source.buildSketch(plan.bucketCount0(), seed0));
            Future<SidecarClient.BuildSketchRemote> buildB = pool.submit(
                    () -> target.buildSketch(plan.bucketCount0(), seed0));
            SidecarClient.BuildSketchRemote remoteA = get(buildA);
            SidecarClient.BuildSketchRemote remoteB = get(buildB);
            List<IbltSketch> sketchesA = new ArrayList<>(List.of(remoteA.sketch()));
            List<IbltSketch> sketchesB = new ArrayList<>(List.of(remoteB.sketch()));
            IbltDecodeResult decoded = IbltDecoder.decode(remoteA.sketch(), remoteB.sketch());

            if (!decoded.isSuccess()) {
                double raw = Math.ceil(plan.effectiveSecondRoundAlpha() * decoded.getdHat());
                if (!Double.isFinite(raw) || raw <= 0 || raw > plan.bucketCap()) {
                    return failed(plan.bucketCount0(), decoded, remoteA, remoteB);
                }
                decodedM = (int) raw;
                long seed2 = freshSeed(seed0);
                int roundM = decodedM;
                Future<IbltSketch> rebucketA = pool.submit(
                        () -> source.rebucket(remoteA.sessionId(), roundM, seed2));
                Future<IbltSketch> rebucketB = pool.submit(
                        () -> target.rebucket(remoteB.sessionId(), roundM, seed2));
                sketchesA.add(get(rebucketA));
                sketchesB.add(get(rebucketB));
                decoded = plan.jointPeel()
                        ? IbltDecoder.decodeJoint(sketchesA, sketchesB)
                        : IbltDecoder.decode(sketchesA.get(sketchesA.size() - 1),
                                sketchesB.get(sketchesB.size() - 1));
            }
            if (!decoded.isSuccess()) return failed(decodedM, decoded, remoteA, remoteB);

            RecheckBatchResult rechecked;
            if (source instanceof StreamingResolveClient sourceStreaming
                    && target instanceof StreamingResolveClient targetStreaming) {
                rechecked = resolveAndRecheckStreaming(pool, sourceStreaming, targetStreaming,
                        source, target, remoteA.sessionId(), remoteB.sessionId(),
                        decoded.getPlusIds(), decoded.getMinusIds(), plan, controllerWorkers);
            } else {
                List<String> candidatePks = resolveCandidatePks(pool, source, target,
                        remoteA.sessionId(), remoteB.sessionId(), decoded.getPlusIds(), decoded.getMinusIds());
                List<String> fetched = candidatePks;
                if (!plan.fullValueFetch() && fetched.size() > plan.valueSampleLimit()) {
                    fetched = new ArrayList<>(fetched.subList(0, plan.valueSampleLimit()));
                }
                rechecked = recheckBatched(source, target, candidatePks, fetched, controllerWorkers);
            }
            return new IbltCompareResult(true, decodedM, decoded.getPlusIds().length,
                    decoded.getMinusIds().length, decoded.getResidualBuckets(), decoded.getdHat(),
                    rechecked.candidatePks(), rechecked.fetchedCount(), rechecked.responseA(),
                    rechecked.responseB(), remoteA.sketchWireBytes(), remoteB.sketchWireBytes());
        } finally {
            // Session cleanup is deliberately owned by the main entry point, after the
            // algorithm clock stops.  The sidecars enqueue spill unlink asynchronously.
            pool.shutdownNow();
        }
    }

    static List<String> resolveCandidatePks(ExecutorService pool,
            SidecarClient source, SidecarClient target, String sourceSession, String targetSession,
            long[] plusIds, long[] minusIds) throws Exception {
        Future<List<String>> sourceResolved = pool.submit(
                () -> source.resolveIds(sourceSession, plusIds));
        Future<List<String>> targetResolved = pool.submit(
                () -> target.resolveIds(targetSession, minusIds));
        Set<String> union = new LinkedHashSet<>();
        union.addAll(get(sourceResolved));
        union.addAll(get(targetResolved));
        List<String> sorted = new ArrayList<>(union);
        sortPks(sorted);
        return sorted;
    }

    private static List<Future<RecheckResponse>> submitRechecks(ExecutorService pool,
            SidecarClient client, List<List<String>> batches) {
        List<Future<RecheckResponse>> futures = new ArrayList<>(batches.size());
        for (List<String> batch : batches) {
            futures.add(pool.submit(() -> client.recheck(batch)));
        }
        return futures;
    }

    private static RecheckBatchResult resolveAndRecheckStreaming(ExecutorService controllerPool,
            StreamingResolveClient sourceStreaming, StreamingResolveClient targetStreaming,
            SidecarClient source, SidecarClient target, String sourceSession, String targetSession,
            long[] plusIds, long[] minusIds, IbltComparePlan plan, int controllerWorkers)
            throws Exception {
        // Keep an independent pool per endpoint.  A shared 2W pool can temporarily schedule all
        // source batches and starve target batches when there are more than 2W requests; separate
        // pools make the contract explicit: at most W JDBC requests per side.
        ExecutorService recheckPoolA = Executors.newFixedThreadPool(controllerWorkers);
        ExecutorService recheckPoolB = Executors.newFixedThreadPool(controllerWorkers);
        StreamingRecheckDispatcher dispatcher = new StreamingRecheckDispatcher(
                recheckPoolA, recheckPoolB, source, target, plan);
        try {
            Future<?> sourceResolved = controllerPool.submit(() -> {
                sourceStreaming.resolveIdsStream(sourceSession, plusIds, dispatcher::accept);
                return null;
            });
            Future<?> targetResolved = controllerPool.submit(() -> {
                targetStreaming.resolveIdsStream(targetSession, minusIds, dispatcher::accept);
                return null;
            });
            get(sourceResolved);
            get(targetResolved);
            dispatcher.finish();
            RecheckResponse responseA = mergeRechecks(dispatcher.recheckA());
            RecheckResponse responseB = mergeRechecks(dispatcher.recheckB());
            List<String> candidatePks = new ArrayList<>(dispatcher.candidatePks());
            sortPks(candidatePks);
            return new RecheckBatchResult(candidatePks, dispatcher.fetchedCount(), responseA, responseB);
        } finally {
            recheckPoolA.shutdownNow();
            recheckPoolB.shutdownNow();
        }
    }

    private static RecheckBatchResult recheckBatched(SidecarClient source, SidecarClient target,
            List<String> candidatePks, List<String> fetched, int controllerWorkers) throws Exception {
        ExecutorService recheckPoolA = Executors.newFixedThreadPool(controllerWorkers);
        ExecutorService recheckPoolB = Executors.newFixedThreadPool(controllerWorkers);
        try {
            List<List<String>> batches = partition(fetched, controllerWorkers);
            List<Future<RecheckResponse>> recheckA = submitRechecks(recheckPoolA, source, batches);
            List<Future<RecheckResponse>> recheckB = submitRechecks(recheckPoolB, target, batches);
            return new RecheckBatchResult(candidatePks, fetched.size(), mergeRechecks(recheckA),
                    mergeRechecks(recheckB));
        } finally {
            recheckPoolA.shutdownNow();
            recheckPoolB.shutdownNow();
        }
    }

    private static void sortPks(List<String> pks) {
        boolean numeric = true;
        for (String value : pks) {
            try { Long.parseLong(value); } catch (NumberFormatException e) {
                numeric = false;
                break;
            }
        }
        if (numeric) pks.sort(Comparator.comparingLong(Long::parseLong));
        else pks.sort(Comparator.naturalOrder());
    }

    private record RecheckBatchResult(List<String> candidatePks, int fetchedCount,
                                      RecheckResponse responseA, RecheckResponse responseB) { }

    private static final class StreamingRecheckDispatcher {
        private final ExecutorService recheckPoolA;
        private final ExecutorService recheckPoolB;
        private final SidecarClient source;
        private final SidecarClient target;
        private final boolean fullValueFetch;
        private final int valueSampleLimit;
        private final Set<String> candidatePks = new LinkedHashSet<>();
        private final List<Future<RecheckResponse>> recheckA = new ArrayList<>();
        private final List<Future<RecheckResponse>> recheckB = new ArrayList<>();
        private final List<String> pending = new ArrayList<>(MAX_RECHECK_KEYS_PER_BATCH);
        private int fetchedCount;

        StreamingRecheckDispatcher(ExecutorService recheckPoolA, ExecutorService recheckPoolB,
                SidecarClient source, SidecarClient target, IbltComparePlan plan) {
            this.recheckPoolA = recheckPoolA;
            this.recheckPoolB = recheckPoolB;
            this.source = source;
            this.target = target;
            this.fullValueFetch = plan.fullValueFetch();
            this.valueSampleLimit = plan.valueSampleLimit();
        }

        synchronized void accept(String pk) throws Exception {
            if (pk == null || !candidatePks.add(pk)) return;
            if (!fullValueFetch && fetchedCount >= valueSampleLimit) return;
            pending.add(pk);
            fetchedCount++;
            if (pending.size() >= MAX_RECHECK_KEYS_PER_BATCH) flush();
        }

        synchronized void finish() throws Exception {
            flush();
        }

        private void flush() {
            if (pending.isEmpty()) return;
            List<String> batch = new ArrayList<>(pending);
            pending.clear();
            recheckA.add(recheckPoolA.submit(() -> source.recheck(batch)));
            recheckB.add(recheckPoolB.submit(() -> target.recheck(batch)));
        }

        Set<String> candidatePks() { return candidatePks; }
        int fetchedCount() { return fetchedCount; }
        List<Future<RecheckResponse>> recheckA() { return recheckA; }
        List<Future<RecheckResponse>> recheckB() { return recheckB; }
    }

    private static RecheckResponse mergeRechecks(List<Future<RecheckResponse>> futures)
            throws Exception {
        // An empty candidate set is a valid d=0 comparison: HttpSidecarClient.recheck(empty)
        // historically returns this same empty response without issuing SQL.
        if (futures.isEmpty()) return new RecheckResponse(List.of(), List.of(), 0L);
        List<String> columns = List.of();
        List<String[]> rows = new ArrayList<>();
        long queryMillis = 0L;
        for (Future<RecheckResponse> future : futures) {
            RecheckResponse response = get(future);
            if (columns.isEmpty()) {
                columns = response.columns();
            } else if (!columns.equals(response.columns())) {
                throw new IllegalStateException("recheck column headers differ between batches");
            }
            rows.addAll(response.rows());
            // Batches execute concurrently. Report the slowest batch as the DB-side wall-clock
            // approximation; summing would report aggregate DB work and grow with parallelism.
            queryMillis = Math.max(queryMillis, response.queryMillis());
        }
        return new RecheckResponse(columns, rows, queryMillis);
    }

    private static List<List<String>> partition(List<String> values, int maxBatches) {
        if (values.isEmpty()) return List.of();
        int targetBatchSize = (values.size() + maxBatches - 1) / maxBatches;
        int batchSize = Math.min(MAX_RECHECK_KEYS_PER_BATCH, targetBatchSize);
        int batchCount = (values.size() + batchSize - 1) / batchSize;
        List<List<String>> batches = new ArrayList<>(batchCount);
        for (int start = 0; start < values.size(); start += batchSize) {
            batches.add(new ArrayList<>(values.subList(start,
                    Math.min(values.size(), start + batchSize))));
        }
        return batches;
    }

    private static IbltCompareResult failed(int m, IbltDecodeResult decoded,
            SidecarClient.BuildSketchRemote remoteA, SidecarClient.BuildSketchRemote remoteB) {
        return new IbltCompareResult(false, m, decoded.getPlusIds().length,
                decoded.getMinusIds().length, decoded.getResidualBuckets(), decoded.getdHat(),
                List.of(), 0, null, null, remoteA.sketchWireBytes(), remoteB.sketchWireBytes());
    }

    private static long freshSeed(long previous) {
        long seed;
        do { seed = ThreadLocalRandom.current().nextLong(); } while (seed == 0 || seed == previous);
        return seed;
    }

    static void closeQuietly(SidecarClient client) {
        try { client.closeSessions(); } catch (Exception ignored) { }
    }

    private static <T> T get(Future<T> future) throws Exception {
        try { return future.get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw new RuntimeException(e.getCause());
        }
    }
}

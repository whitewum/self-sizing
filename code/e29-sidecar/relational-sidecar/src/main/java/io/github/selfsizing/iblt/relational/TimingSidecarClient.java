package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.controller.SidecarClient;
import io.github.selfsizing.iblt.controller.SidecarClient.BuildSketchRemote;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.sidecar.RecheckResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Delegating client that exposes controller-side timings for the E29 report. */
public final class TimingSidecarClient implements SidecarClient, StreamingResolveClient {
    private final SidecarClient delegate;
    private long buildMs;
    private long partitionMs;
    private long rebucketMs;
    private long resolveMs;
    private final AtomicLong recheckMs = new AtomicLong();
    private final AtomicLong recheckFirstStartNs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong recheckLastEndNs = new AtomicLong();

    public TimingSidecarClient(SidecarClient delegate) {
        this.delegate = delegate;
    }

    @Override public String label() { return delegate.label(); }
    @Override public String health() throws Exception { return delegate.health(); }

    @Override
    public BuildSketchRemote buildSketch(int bucketCount, long hashSeed) throws Exception {
        long started = System.nanoTime();
        try {
            BuildSketchRemote result = delegate.buildSketch(bucketCount, hashSeed);
            if (delegate instanceof HttpSidecarClient http) {
                partitionMs += http.lastPartitionMs();
            }
            return result;
        } finally {
            buildMs += elapsedMs(started);
        }
    }

    @Override
    public IbltSketch rebucket(String sessionId, int bucketCount, long hashSeed) throws Exception {
        long started = System.nanoTime();
        try {
            return delegate.rebucket(sessionId, bucketCount, hashSeed);
        } finally {
            rebucketMs += elapsedMs(started);
        }
    }

    @Override
    public List<String> resolveIds(String sessionId, long[] ids) throws Exception {
        long started = System.nanoTime();
        try {
            return delegate.resolveIds(sessionId, ids);
        } finally {
            resolveMs += elapsedMs(started);
        }
    }

    @Override
    public void resolveIdsStream(String sessionId, long[] ids,
            StreamingResolveClient.PkConsumer pkConsumer) throws Exception {
        if (!(delegate instanceof StreamingResolveClient streaming)) {
            throw new UnsupportedOperationException("delegate does not support streaming resolve");
        }
        long started = System.nanoTime();
        try {
            streaming.resolveIdsStream(sessionId, ids, pkConsumer);
        } finally {
            resolveMs += elapsedMs(started);
        }
    }

    @Override
    public RecheckResponse recheck(List<String> pks) throws Exception {
        long started = System.nanoTime();
        recheckFirstStartNs.accumulateAndGet(started, Math::min);
        try {
            return delegate.recheck(pks);
        } finally {
            recheckMs.addAndGet(elapsedMs(started));
            recheckLastEndNs.accumulateAndGet(System.nanoTime(), Math::max);
        }
    }

    @Override public void closeSessions() throws Exception { delegate.closeSessions(); }

    public long buildMs() { return buildMs; }
    public long partitionMs() { return partitionMs; }
    public long rebucketMs() { return rebucketMs; }
    public long resolveMs() { return resolveMs; }
    public long recheckMs() { return recheckMs.get(); }

    /** Wall span from the first recheck batch starting to the last batch finishing. */
    public long recheckWallMs() {
        long first = recheckFirstStartNs.get();
        long last = recheckLastEndNs.get();
        return first == Long.MAX_VALUE || last == 0
                ? 0L : Math.round((last - first) / 1e6);
    }

    private static long elapsedMs(long started) {
        return Math.round((System.nanoTime() - started) / 1e6);
    }
}

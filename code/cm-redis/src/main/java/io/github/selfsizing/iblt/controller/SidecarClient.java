package io.github.selfsizing.iblt.controller;

import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.sidecar.RecheckResponse;
import io.github.selfsizing.iblt.sidecar.SketchMetrics;

import java.util.List;

/**
 * The controller's "one sidecar" abstraction: hides local / HTTP / tunnel differences.
 *
 * <p>{@link HttpSidecarClient} is the cross-machine implementation (reaching a
 * remote {@code 127.0.0.1} sidecar through an {@code ssh -L} tunnel). The
 * controller only decodes locally and asks each side for sketch / resolve /
 * recheck, all small control payloads.</p>
 */
public interface SidecarClient {

    /** Side label (for logs / reports, e.g. {@code A@host1-mysql}). */
    String label();

    /** Health probe; returns the sidecar's self-description (dialect/table). */
    String health() throws Exception;

    /** Tells this side to read its local database and build a sketch (keeping the fingerprint cache in the session for later re-bucket / resolve). */
    BuildSketchRemote buildSketch(int bucketCount, long hashSeed) throws Exception;

    /** Re-buckets the session's retained fingerprint cache at a new M (<b>without re-scanning the database</b>); returns only the new sketch. */
    IbltSketch rebucket(String sessionId, int bucketCount, long hashSeed) throws Exception;

    /** On <b>this side</b>, resolves the decoded ids back to original primary keys in the session store (matches only). */
    List<String> resolveIds(String sessionId, long[] ids) throws Exception;

    /** Fetches real row values from the local database by candidate primary key (the row-value sampling cap is set by the controller; only the sampled pks are sent). */
    RecheckResponse recheck(List<String> pks) throws Exception;

    /** Releases the fingerprint session/cache this sidecar currently retains. */
    void closeSessions() throws Exception;

    /** One remote buildSketch result: session id + the reconstructed sketch + metrics + wire bytes + round-trip time. */
    final class BuildSketchRemote {
        private final String sessionId;
        private final IbltSketch sketch;
        private final SketchMetrics metrics;
        private final int sketchWireBytes;
        private final long roundTripMillis;

        public BuildSketchRemote(String sessionId, IbltSketch sketch, SketchMetrics metrics,
                                 int sketchWireBytes, long roundTripMillis) {
            this.sessionId = sessionId;
            this.sketch = sketch;
            this.metrics = metrics;
            this.sketchWireBytes = sketchWireBytes;
            this.roundTripMillis = roundTripMillis;
        }

        public String sessionId() {
            return sessionId;
        }

        public IbltSketch sketch() {
            return sketch;
        }

        public SketchMetrics metrics() {
            return metrics;
        }

        public int sketchWireBytes() {
            return sketchWireBytes;
        }

        public long roundTripMillis() {
            return roundTripMillis;
        }
    }
}

package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.core.FingerprintStore;
import io.github.selfsizing.iblt.core.IbltSketch;

/**
 * buildSketch response: the sketch itself + the retained fingerprint cache + metrics.
 *
 * <p>{@code store} is retained for <b>re-bucketing without re-scanning</b>: when
 * the controller decides to grow M and retry, it rebuilds straight from the
 * cache with {@code IbltSketch.build(store, newM)}, no second database scan
 * needed. The caller releases it with {@link FingerprintStore#close()} when done.</p>
 */
public final class BuildSketchResponse {

    private final IbltSketch sketch;
    private final FingerprintStore store;
    private final SketchMetrics metrics;

    public BuildSketchResponse(IbltSketch sketch, FingerprintStore store, SketchMetrics metrics) {
        this.sketch = sketch;
        this.store = store;
        this.metrics = metrics;
    }

    public IbltSketch sketch() {
        return sketch;
    }

    /** The fingerprint cache, for re-bucketing without re-scanning; the caller must close it. */
    public FingerprintStore store() {
        return store;
    }

    public SketchMetrics metrics() {
        return metrics;
    }
}

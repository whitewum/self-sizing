package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.core.FingerprintStore;
import io.github.selfsizing.iblt.core.IbltSketch;

/**
 * The result of one fingerprint-stream scan: the filled fingerprint cache + row count + scan time.
 *
 * <p>{@code sketch} is a <b>nullable</b> "fused fetch+build" result: when the
 * caller passes an initial bucket count M0, the scan loop both writes each row to
 * {@code store} and {@code insert}s it into the M0 sketch, skipping the first
 * full re-read ({@code forEachFpId}). It is {@code null} when M0 is not passed
 * ({@code <=0}), falling back to the original two-pass behavior.</p>
 */
public final class FingerprintScan {

    private final FingerprintStore store;
    private final long rows;
    private final long scanMillis;
    private final IbltSketch sketch;

    public FingerprintScan(FingerprintStore store, long rows, long scanMillis) {
        this(store, rows, scanMillis, null);
    }

    public FingerprintScan(FingerprintStore store, long rows, long scanMillis, IbltSketch sketch) {
        this.store = store;
        this.rows = rows;
        this.scanMillis = scanMillis;
        this.sketch = sketch;
    }

    public FingerprintStore store() {
        return store;
    }

    public long rows() {
        return rows;
    }

    public long scanMillis() {
        return scanMillis;
    }

    /** The M0 sketch built during the scan; {@code null} when fusion is not enabled. */
    public IbltSketch sketch() {
        return sketch;
    }
}

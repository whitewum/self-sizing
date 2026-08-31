package io.github.selfsizing.iblt.sidecar;

/**
 * buildSketch performance metrics. Scan and build are timed separately.
 */
public final class SketchMetrics {

    private final long scanRows;
    private final long scanMillis;
    private final long buildMillis;
    private final int bucketCount;

    public SketchMetrics(long scanRows, long scanMillis, long buildMillis, int bucketCount) {
        this.scanRows = scanRows;
        this.scanMillis = scanMillis;
        this.buildMillis = buildMillis;
        this.bucketCount = bucketCount;
    }

    public long scanRows() {
        return scanRows;
    }

    /** Time for JDBC streaming read + id computation + filling the store. */
    public long scanMillis() {
        return scanMillis;
    }

    /** Time to build the sketch from the store. */
    public long buildMillis() {
        return buildMillis;
    }

    public int bucketCount() {
        return bucketCount;
    }

    @Override
    public String toString() {
        return "SketchMetrics{scanRows=" + scanRows + ", scanMillis=" + scanMillis
                + ", buildMillis=" + buildMillis + ", M=" + bucketCount + '}';
    }
}

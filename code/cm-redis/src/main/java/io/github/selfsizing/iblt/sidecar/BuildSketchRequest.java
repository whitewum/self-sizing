package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.sql.FingerprintQuery;

/**
 * buildSketch request: which table's fingerprints to read ({@link FingerprintQuery}) + bucket count M + JDBC fetchSize.
 */
public final class BuildSketchRequest {

    private final FingerprintQuery query;
    private final int bucketCount;
    private final long hashSeed;
    private final int fetchSize;

    public BuildSketchRequest(FingerprintQuery query, int bucketCount, int fetchSize) {
        this(query, bucketCount, fetchSize, 0L);
    }

    public BuildSketchRequest(FingerprintQuery query, int bucketCount, int fetchSize, long hashSeed) {
        this.query = query;
        this.bucketCount = bucketCount;
        this.fetchSize = fetchSize;
        this.hashSeed = hashSeed;
    }

    public FingerprintQuery query() {
        return query;
    }

    public int bucketCount() {
        return bucketCount;
    }

    public long hashSeed() {
        return hashSeed;
    }

    /** JDBC streaming fetch row count (<=0 uses the driver default). */
    public int fetchSize() {
        return fetchSize;
    }
}

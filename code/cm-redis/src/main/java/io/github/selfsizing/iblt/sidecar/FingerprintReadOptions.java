package io.github.selfsizing.iblt.sidecar;

/**
 * Experiment-side fingerprint scan knobs.
 */
public final class FingerprintReadOptions {

    public enum PgReadMode {
        JDBC,
        COPY_BINARY
    }

    private final int scanWorkers;
    private final PgReadMode pgReadMode;

    public FingerprintReadOptions(int scanWorkers, PgReadMode pgReadMode) {
        this.scanWorkers = Math.max(1, scanWorkers);
        this.pgReadMode = pgReadMode == null ? PgReadMode.JDBC : pgReadMode;
    }

    public static FingerprintReadOptions defaults() {
        return new FingerprintReadOptions(1, PgReadMode.JDBC);
    }

    public int scanWorkers() {
        return scanWorkers;
    }

    public PgReadMode pgReadMode() {
        return pgReadMode;
    }
}

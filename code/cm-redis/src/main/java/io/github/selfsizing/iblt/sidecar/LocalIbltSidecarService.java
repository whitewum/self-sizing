package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.core.FingerprintStore;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.sql.IbltDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Logger;

/**
 * Local sidecar: holds one local JDBC connection + dialect and does buildSketch / recheck directly.
 *
 * <p>Used single-side, single-threaded (one instance per side). The connection
 * lifecycle is managed by the caller; this class does not open/close connections.</p>
 */
public final class LocalIbltSidecarService implements IbltSidecarService {

    private static final Logger LOG = Logger.getLogger(LocalIbltSidecarService.class.getName());

    private final Connection connection;
    private final JdbcFingerprintReader.ConnectionOpener connectionOpener;
    private final boolean closeOpenedConnections;
    private final IbltDialect dialect;
    private final FingerprintReadOptions readOptions;
    private final JdbcFingerprintReader fingerprintReader = new JdbcFingerprintReader();
    private final JdbcRecheckReader recheckReader = new JdbcRecheckReader();

    public LocalIbltSidecarService(Connection connection, IbltDialect dialect) {
        this(connection, dialect, FingerprintReadOptions.defaults());
    }

    public LocalIbltSidecarService(Connection connection, IbltDialect dialect, FingerprintReadOptions readOptions) {
        this.connection = connection;
        this.connectionOpener = () -> connection;
        this.closeOpenedConnections = false;
        this.dialect = dialect;
        this.readOptions = readOptions == null ? FingerprintReadOptions.defaults() : readOptions;
    }

    public LocalIbltSidecarService(String jdbcUrl, IbltDialect dialect, FingerprintReadOptions readOptions) {
        this.connection = null;
        this.connectionOpener = () -> DriverManager.getConnection(jdbcUrl);
        this.closeOpenedConnections = true;
        this.dialect = dialect;
        this.readOptions = readOptions == null ? FingerprintReadOptions.defaults() : readOptions;
    }

    @Override
    public BuildSketchResponse buildSketch(BuildSketchRequest request) throws Exception {
        int m0 = request.bucketCount();
        int fusedM = request.hashSeed() == 0L ? m0 : 0;
        FingerprintScan scan;
        // fused fetch+build: pass M0 into the reader so the scan loop builds the M0 sketch in place, skipping the first full re-read.
        if (closeOpenedConnections) {
            scan = fingerprintReader.read(connectionOpener, dialect, request.query(), request.fetchSize(), readOptions, fusedM);
        } else {
            scan = fingerprintReader.read(connection, dialect, request.query(), request.fetchSize(), readOptions, fusedM);
        }

        long t0 = System.nanoTime();
        // if fusion applied, use the sketch built during the scan (buildMillis≈0, the bucketing cost is already in scanMillis);
        // otherwise (e.g. memory-backend bypass or M0<=0) fall back to the original two-pass build.
        IbltSketch sketch = scan.sketch() != null
                ? scan.sketch()
                : IbltSketch.build(scan.store(), m0, request.hashSeed());
        long buildMillis = Math.round((System.nanoTime() - t0) / 1e6);

        SketchMetrics metrics = new SketchMetrics(
                scan.rows(), scan.scanMillis(), buildMillis, request.bucketCount());
        LOG.info(() -> "[iblt] sidecar buildSketch done: " + metrics);
        return new BuildSketchResponse(sketch, scan.store(), metrics);
    }

    /**
     * Re-bucket from the retained fingerprint cache (no database re-scan). Used when the controller grows M and retries.
     */
    public IbltSketch rebucket(FingerprintStore store, int newBucketCount) throws Exception {
        return IbltSketch.build(store, newBucketCount);
    }

    @Override
    public RecheckResponse recheck(RecheckRequest request) throws Exception {
        if (!closeOpenedConnections) {
            return recheckReader.recheck(connection, dialect, request.query(), request.candidatePks());
        }
        try (Connection conn = connectionOpener.open()) {
            return recheckReader.recheck(conn, dialect, request.query(), request.candidatePks());
        }
    }
}

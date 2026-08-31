package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.core.CompositeFingerprintStore;
import io.github.selfsizing.iblt.core.FileFingerprintStore;
import io.github.selfsizing.iblt.core.FingerprintStore;
import io.github.selfsizing.iblt.core.IbltHash;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.core.MemoryFingerprintStore;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import io.github.selfsizing.iblt.sql.FingerprintQuery;
import io.github.selfsizing.iblt.sql.IbltDialect;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * JDBC streaming fingerprint reader: runs the dialect's two-column fingerprint
 * SQL, reads back {@code (fp, rawPk)} row by row, computes
 * {@code id = cheap8(canonicalize(pk))} on the Java side, and fills a
 * {@link FingerprintStore}.
 *
 * <p>Depends only on {@code java.sql}, not on any specific driver ({@link Connection}
 * is injected by the caller).</p>
 *
 * <p><b>Streaming:</b> a forward-only / read-only Statement with
 * {@code setFetchSize}, to avoid pulling the whole table into memory at once. SQL
 * Server (mssql-jdbc) already streams with adaptive buffering by default;
 * fetchSize constrains it further. PG/MySQL also need {@code autoCommit=false} /
 * {@code MIN_VALUE} settings; those are handled per database below.</p>
 */
public final class JdbcFingerprintReader {

    private static final Logger LOG = Logger.getLogger(JdbcFingerprintReader.class.getName());

    /**
     * SQL log switch (for experiment sanity checks; turn off for measured runs):
     * prints the fingerprint SQL each scan-worker actually runs (including its
     * shard predicate) + thread name, to verify SQL correctness and that
     * concurrency took effect.
     * Enable: {@code -Dbench.sqlLog=true} or the environment variable {@code BENCH_SQL_LOG=true}.
     */
    private static final boolean SQL_LOG = Boolean.parseBoolean(
            System.getProperty("bench.sqlLog",
                    System.getenv().getOrDefault("BENCH_SQL_LOG", "false")));

    @FunctionalInterface
    public interface ConnectionOpener {
        Connection open() throws Exception;
    }

    /**
     * Scans one side's fingerprint stream and returns the filled cache.
     *
     * @param conn      an open JDBC connection (lifecycle managed by the caller)
     * @param dialect   dialect (generates the fingerprint SQL)
     * @param query     fingerprint query description
     * @param fetchSize JDBC fetch row count (&lt;=0 uses the driver default)
     */
    public FingerprintScan read(Connection conn, IbltDialect dialect, FingerprintQuery query, int fetchSize)
            throws Exception {
        return read(() -> conn, false, dialect, query, fetchSize, FingerprintReadOptions.defaults(), 0);
    }

    public FingerprintScan read(Connection conn, IbltDialect dialect, FingerprintQuery query, int fetchSize,
                                FingerprintReadOptions options) throws Exception {
        return read(conn, dialect, query, fetchSize, options, 0);
    }

    /**
     * Single-connection read, with optional fused fetch+build: when
     * {@code bucketCount0 > 0}, the scan loop also inserts each row into an M0
     * sketch, returned via {@link FingerprintScan#sketch()}, skipping the first
     * full re-read. A single connection forces workers=1.
     */
    public FingerprintScan read(Connection conn, IbltDialect dialect, FingerprintQuery query, int fetchSize,
                                FingerprintReadOptions options, int bucketCount0) throws Exception {
        FingerprintReadOptions singleConnectionOptions = options == null
                ? FingerprintReadOptions.defaults()
                : new FingerprintReadOptions(1, options.pgReadMode());
        return read(() -> conn, false, dialect, query, fetchSize, singleConnectionOptions, bucketCount0);
    }

    public FingerprintScan read(ConnectionOpener opener, IbltDialect dialect, FingerprintQuery query,
                                int fetchSize, FingerprintReadOptions options) throws Exception {
        return read(opener, true, dialect, query, fetchSize, options, 0);
    }

    /** Multi-connection (parallel shard) read + optional M0 fusion: each worker builds a partial sketch, XOR-merged into the whole M0 sketch at the end. */
    public FingerprintScan read(ConnectionOpener opener, IbltDialect dialect, FingerprintQuery query,
                                int fetchSize, FingerprintReadOptions options, int bucketCount0) throws Exception {
        return read(opener, true, dialect, query, fetchSize, options, bucketCount0);
    }

    private FingerprintScan read(ConnectionOpener opener, boolean closeConnection, IbltDialect dialect,
                                 FingerprintQuery query, int fetchSize, FingerprintReadOptions options,
                                 int bucketCount0)
            throws Exception {
        FingerprintReadOptions effective = options == null ? FingerprintReadOptions.defaults() : options;
        int workers = effectiveWorkers(dialect, effective.scanWorkers());
        if (workers <= 1) {
            Connection conn = opener.open();
            try {
                return readSingle(conn, closeConnection, dialect, query, fetchSize, effective, 0, 1, bucketCount0);
            } finally {
                if (closeConnection) {
                    conn.close();
                }
            }
        }

        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<FingerprintScan>> futures = new ArrayList<>();
        try {
            for (int workerId = 0; workerId < workers; workerId++) {
                final int wid = workerId;
                futures.add(pool.submit(() -> {
                    Connection conn = opener.open();
                    try {
                        return readSingle(conn, true, dialect, query, fetchSize, effective, wid, workers, bucketCount0);
                    } finally {
                        conn.close();
                    }
                }));
            }
            List<FingerprintStore> stores = new ArrayList<>();
            long rows = 0;
            // M0 fusion: each shard built its own partial sketch; XOR-merge them into the whole here (insert is
            // commutative and associative, independent of shard/order, same result as a single-stream full insert).
            // When bucketCount0<=0 there is no fusion and sketch stays null.
            IbltSketch merged = bucketCount0 > 0 ? new IbltSketch(bucketCount0) : null;
            for (Future<FingerprintScan> future : futures) {
                FingerprintScan scan = get(future);
                stores.add(scan.store());
                rows += scan.rows();
                if (merged != null && scan.sketch() != null) {
                    merged.mergeFrom(scan.sketch());
                }
            }
            long scanMillis = Math.round((System.nanoTime() - t0) / 1e6);
            final long finalRows = rows;
            final int finalWorkers = workers;
            LOG.info(() -> String.format("[iblt] scan fingerprints parallel: dialect=%s, workers=%d, rows=%d, elapsedMs=%d",
                    dialect.id(), finalWorkers, finalRows, scanMillis));
            return new FingerprintScan(new CompositeFingerprintStore(stores), rows, scanMillis, merged);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The per-worker fingerprint cache backend. Default <b>file</b> (spill to
     * disk, O(1) heap, avoids OOM on large tables); {@code FP_STORE=memory}
     * switches back to in-memory arrays. In file mode:
     * <ul>
     *   <li>{@code FP_STORE_DIR}: temp directory (default {@code java.io.tmpdir/iblt-fpstore}).</li>
     *   <li>{@code FP_STORE_BUF_MB}: <b>total write-buffer budget</b> (default
     *       64MB), split evenly across {@code 2*workers} write streams to
     *       amortize disk syscalls; released when ingest ends. <b>Hard cap of
     *       {@value #MAX_WRITE_BUF} bytes per stream</b>: beyond a dozen-odd MB a
     *       larger sequential-append buffer barely helps throughput (the OS page
     *       cache covers it) but pins a large array on the heap, working against
     *       the very OOM the file backend exists to prevent.</li>
     * </ul>
     * {@code close()} deletes the temp files. Non-file mode falls back to {@link MemoryFingerprintStore}.
     */
    private static final int MAX_WRITE_BUF = 16 << 20;  // per-write-stream buffer cap, 16MB

    private static FingerprintStore newStore(int workers) throws IOException {
        String mode = envOr("FP_STORE", "file").trim().toLowerCase(Locale.ROOT);
        if ("memory".equals(mode)) {
            return new MemoryFingerprintStore();
        }
        long budgetBytes = parseLongOr(envOr("FP_STORE_BUF_MB", "64"), 64L) * (1L << 20);
        int streams = 2 * Math.max(1, workers);
        int perStream = (int) Math.max(1 << 16, Math.min(MAX_WRITE_BUF, budgetBytes / streams));
        String dir = envOr("FP_STORE_DIR", System.getProperty("java.io.tmpdir") + "/iblt-fpstore");
        Path dirPath = Paths.get(dir);
        Files.createDirectories(dirPath);
        String base = dirPath.resolve("fp-" + UUID.randomUUID()).toString();
        return new FileFingerprintStore(base, perStream);
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static long parseLongOr(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private FingerprintScan readSingle(Connection conn, boolean connectionOwned, IbltDialect dialect,
                                       FingerprintQuery query, int fetchSize, FingerprintReadOptions options,
                                       int workerId, int workers, int bucketCount0) throws Exception {
        String sql = dialect.fingerprintSql(query);
        if (workers > 1) {
            sql = wrapWorkerSql(sql, dialect, workerId, workers);
        }
        if (SQL_LOG) {
            System.out.printf("[bench][iblt][sql] thread=%s phase=fp-scan db=%s worker=%d/%d mode=%s sql=%s%n",
                    Thread.currentThread().getName(), dialect.id(), workerId + 1, workers,
                    readMode(dialect, options, query.pkType()), sql);
        }
        PkType type = query.pkType();
        boolean lower = query.lowerForString();

        FingerprintStore store = newStore(workers);
        // fused fetch+build: when M0>0 each row is written to store and inserted into the sketch, so the M0 sketch is ready at end of scan with no re-read.
        IbltSketch sketch = bucketCount0 > 0 ? new IbltSketch(bucketCount0) : null;
        long t0 = System.nanoTime();
        long rows = 0;
        if (usePgCopyBinary(dialect, options, type)) {
            rows = readPgCopyBinary(conn, sql, type, lower, store, sketch);
        } else {
            // true streaming: otherwise the driver buffers the whole result set in client memory and a large table OOMs
            // (measured: 10M blows both sides). The spill backend only keeps our heap O(1), not the driver's client
            // buffer, so a cursor / row-level stream must be enabled explicitly per dialect.
            //   - MySQL: only fetchSize=Integer.MIN_VALUE triggers row-level streaming; setFetchSize(N>0) is ignored, still fully buffered.
            //   - PostgreSQL: cursor streaming requires autoCommit=false and fetchSize>0; autoCommit=true also fully buffers.
            boolean mysql = "mysql".equalsIgnoreCase(dialect.id());
            boolean pg = "postgres".equalsIgnoreCase(dialect.id());
            boolean restoreAutoCommit = false;
            if (pg && conn.getAutoCommit()) {
                conn.setAutoCommit(false);
                restoreAutoCommit = true;
            }
            int effFetch = mysql ? Integer.MIN_VALUE : (fetchSize > 0 ? fetchSize : 4096);
            try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                st.setFetchSize(effFetch);
                try (ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        long fp = rs.getLong(1);
                        String pk = rs.getString(2);
                        long id = IbltHash.cheap8(PkTupleCanonicalizer.encode(type, pk, lower));
                        store.add(fp, id, pk);
                        if (sketch != null) {
                            sketch.insert(fp, id);
                        }
                        rows++;
                    }
                }
            } finally {
                if (restoreAutoCommit) {
                    try {
                        conn.commit();
                    } catch (Exception ignore) {
                        // read-only transaction; a failed commit has no side effect
                    }
                    conn.setAutoCommit(true);
                }
            }
        }
        store.finishIngest();
        long scanMillis = Math.round((System.nanoTime() - t0) / 1e6);
        final long finalRows = rows;
        // performance instrumentation: the scan is a significant once-per-side step (not logged inside the row loop).
        LOG.info(() -> String.format("[iblt] scan fingerprints: dialect=%s, worker=%d/%d, mode=%s, rows=%d, elapsedMs=%d",
                dialect.id(), workerId + 1, workers, readMode(dialect, options, type), finalRows, scanMillis));
        return new FingerprintScan(store, rows, scanMillis, sketch);
    }

    private static int effectiveWorkers(IbltDialect dialect, int requested) {
        String id = dialect.id().toLowerCase(Locale.ROOT);
        if ("postgres".equals(id) || "mysql".equals(id) || "sqlserver".equals(id)) {
            return Math.max(1, requested);
        }
        return 1;
    }

    private static String wrapWorkerSql(String sql, IbltDialect dialect, int workerId, int workers) {
        String id = dialect.id().toLowerCase(Locale.ROOT);
        String predicate;
        if ("postgres".equals(id)) {
            predicate = "mod((hashint8(iblt_pk::bigint)::bigint + 2147483648), "
                    + workers + ") = " + workerId;
        } else if ("mysql".equals(id)) {
            predicate = "MOD(CRC32(CAST(iblt_pk AS CHAR)), " + workers + ") = " + workerId;
        } else if ("sqlserver".equals(id)) {
            // BIGINT primary key: the double modulo handles negatives, so every row lands in exactly one worker.
            predicate = "((iblt_pk % " + workers + ") + " + workers + ") % "
                    + workers + " = " + workerId;
        } else {
            return sql;
        }
        return "SELECT iblt_fp, iblt_pk FROM (" + sql + ") iblt_worker WHERE " + predicate;
    }

    private static boolean usePgCopyBinary(IbltDialect dialect, FingerprintReadOptions options, PkType pkType) {
        return "postgres".equalsIgnoreCase(dialect.id())
                && options.pgReadMode() == FingerprintReadOptions.PgReadMode.COPY_BINARY
                && pkType != PkType.STRING;
    }

    private static String readMode(IbltDialect dialect, FingerprintReadOptions options, PkType pkType) {
        return usePgCopyBinary(dialect, options, pkType) ? "COPY_BINARY" : "JDBC";
    }

    private long readPgCopyBinary(Connection conn, String fpSql, PkType pkType, boolean lower,
                                  FingerprintStore store, IbltSketch sketch) throws Exception {
        String copySql = "COPY ( SELECT iblt_fp, iblt_pk::bigint AS iblt_pk FROM ( "
                + fpSql + " ) iblt_bin ) TO STDOUT (FORMAT binary)";
        Class<?> pgConnectionClass = Class.forName("org.postgresql.PGConnection");
        Object pgConnection = conn.unwrap(pgConnectionClass);
        Class<?> copyStreamClass = Class.forName("org.postgresql.copy.PGCopyInputStream");
        Constructor<?> ctor = copyStreamClass.getConstructor(pgConnectionClass, String.class);
        long rows = 0;
        try (InputStream raw = (InputStream) ctor.newInstance(pgConnection, copySql);
             DataInputStream din = new DataInputStream(new BufferedInputStream(raw))) {
            skipFully(din, 11);
            din.readInt();
            skipFully(din, din.readInt());
            short nfields;
            while ((nfields = din.readShort()) != -1) {
                long fp = readBigintField(din);
                long pkVal = readBigintField(din);
                for (int i = 2; i < nfields; i++) {
                    skipField(din);
                }
                String pk = Long.toString(pkVal);
                long id = IbltHash.cheap8(PkTupleCanonicalizer.encode(pkType, pk, lower));
                store.add(fp, id, pk);
                if (sketch != null) {
                    sketch.insert(fp, id);
                }
                rows++;
            }
        }
        return rows;
    }

    private static long readBigintField(DataInputStream din) throws IOException {
        int len = din.readInt();
        if (len != 8) {
            throw new IOException("COPY binary expected int8 field, got len=" + len);
        }
        return din.readLong();
    }

    private static void skipField(DataInputStream din) throws IOException {
        int len = din.readInt();
        if (len > 0) {
            skipFully(din, len);
        }
    }

    private static void skipFully(DataInputStream din, int n) throws IOException {
        while (n > 0) {
            int skipped = din.skipBytes(n);
            if (skipped <= 0) {
                if (din.read() < 0) {
                    throw new EOFException("COPY binary ended early");
                }
                n--;
            } else {
                n -= skipped;
            }
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }
}

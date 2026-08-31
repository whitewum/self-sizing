package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.FingerprintStore;
import io.github.selfsizing.iblt.core.IbltHash;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import io.github.selfsizing.iblt.core.SketchCodec;
import io.github.selfsizing.iblt.relational.StreamingResolveClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runnable experiment implementation of the Oracle/MySQL composite-primary-key IBLT adapter layer.
 *
 * <p>It reuses the IBLT core from the sidecar jar and only replaces the
 * single-column SQL / recheck protocol with {@code fp + pk_0 + ... + pk_n},
 * encoding the whole primary-key tuple unambiguously on the wire via
 * {@link CompositePkCodec}.</p>
 */
public final class CompositeIbltSidecarServer {
    /** Bound rebucket fan-out; scan concurrency may be higher without multiplying M-sized arrays. */
    private static final int MAX_REBUCKET_WORKERS = 16;
    private final String dialect;
    private final String jdbcUrl;
    private final String table;
    private final List<String> pkColumns;
    private final List<PkType> pkTypes;
    private final List<String> valueColumns;
    private final List<ValueCanonType> valueTypes;
    private final String spillDir;
    private final int oracleParallelDegree;
    private final String scanMode;
    private final int scanShards;
    private final int scanWorkers;
    private final String scanShardColumn;
    private final String mysqlFence;
    private final PkType[] pkTypeArray;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** Spill unlink must not occupy an HTTP handler or the controller's e2e clock. */
    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spill-cleanup");
        t.setDaemon(true);
        return t;
    });
    private ExecutorService httpExecutor;
    private long sequence;

    /** Probe count for {@code --mysql-fence sample}: each probe becomes a constant-literal index dive. */
    private static final int SAMPLE_FENCE_PROBE_COUNT = 2000;
    /** Probes per snap statement: constant-literal scalar subqueries batched to bound SQL text size. */
    private static final int SAMPLE_FENCE_SNAP_BATCH = 500;

    private CompositeIbltSidecarServer(String dialect, String jdbcUrl, String table,
                                       List<String> pkColumns, List<PkType> pkTypes,
                                       List<String> valueColumns, List<ValueCanonType> valueTypes,
                                       String spillDir, int oracleParallelDegree,
                                       String scanMode, int scanShards, int scanWorkers,
                                       String scanShardColumn, String mysqlFence) throws Exception {
        this.dialect = dialect;
        this.jdbcUrl = jdbcUrl;
        this.table = table;
        this.spillDir = spillDir;
        this.pkColumns = List.copyOf(pkColumns);
        this.pkTypes = List.copyOf(pkTypes);
        this.valueColumns = List.copyOf(valueColumns);
        this.valueTypes = List.copyOf(valueTypes);
        this.oracleParallelDegree = oracleParallelDegree;
        this.scanMode = scanMode;
        this.scanShards = scanShards;
        this.scanWorkers = scanWorkers;
        this.scanShardColumn = scanShardColumn;
        this.mysqlFence = mysqlFence;
        this.pkTypeArray = this.pkTypes.toArray(PkType[]::new);
        if (this.pkColumns.isEmpty() || this.pkColumns.size() != this.pkTypes.size()) {
            throw new IllegalArgumentException("pk columns/types mismatch");
        }
        if (this.valueColumns.size() != this.valueTypes.size()) {
            throw new IllegalArgumentException("value columns/types mismatch");
        }
        if ("sample".equals(mysqlFence) && !"mysql".equalsIgnoreCase(dialect)) {
            throw new IllegalArgumentException("--mysql-fence sample requires --dialect mysql");
        }
        try (Connection ignored = DriverManager.getConnection(jdbcUrl)) {
            // startup connectivity gate
        }
    }

    /** One-side session: the whole table's fingerprints spill to disk (fp,id -> .fpid; id,pk -> .pk), O(1) heap, avoids OOM on very large tables. */
    private static final class Session {
        final FingerprintStore store;

        Session(FingerprintStore store) {
            this.store = store;
        }
    }

    /** A logical store backed by independent per-shard files. Readers walk every file in order. */
    private static final class ShardedFingerprintStore implements FingerprintStore {
        private final List<FingerprintStore> shards;
        private final long size;
        private final int resolveWorkers;

        ShardedFingerprintStore(List<FingerprintStore> shards, int resolveWorkers) {
            this.shards = List.copyOf(shards);
            this.resolveWorkers = Math.max(1, Math.min(resolveWorkers, shards.size()));
            long total = 0;
            for (FingerprintStore shard : shards) total += shard.size();
            this.size = total;
        }

        @Override public void add(long fp, long id, String pk) {
            throw new UnsupportedOperationException("add to an individual shard store");
        }
        @Override public void finishIngest() { }
        @Override public long size() { return size; }
        @Override public void forEachFpId(FpIdConsumer consumer) throws Exception {
            for (FingerprintStore shard : shards) shard.forEachFpId(consumer);
        }
        @Override public void forEachIdPk(IdPkConsumer consumer) throws Exception {
            for (FingerprintStore shard : shards) shard.forEachIdPk(consumer);
        }
        @Override public void close() {
            for (FingerprintStore shard : shards) shard.close();
        }

        Map<Long, String> resolveIds(Set<Long> wanted) throws Exception {
            Map<Long, String> found = new HashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(resolveWorkers);
            List<Future<Map<Long, String>>> futures = new ArrayList<>(shards.size());
            try {
                for (FingerprintStore shard : shards) {
                    if (!(shard instanceof CandidateResolvingStore selective)) {
                        throw new IllegalStateException("shard does not support selective resolve");
                    }
                    futures.add(pool.submit(() -> selective.resolveIds(wanted)));
                }
                for (Future<Map<Long, String>> future : futures) {
                    for (Map.Entry<Long, String> entry : future.get().entrySet()) {
                        found.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            return found;
        }

        void resolveIdsStream(Set<Long> wanted, StreamingCandidateResolvingStore.PkConsumer consumer)
                throws Exception {
            if (wanted.isEmpty()) return;
            ExecutorService pool = Executors.newFixedThreadPool(resolveWorkers);
            ExecutorCompletionService<Map<Long, String>> completed =
                    new ExecutorCompletionService<>(pool);
            Set<Long> emitted = new HashSet<>();
            try {
                int submitted = 0;
                for (FingerprintStore shard : shards) {
                    if (!(shard instanceof CandidateResolvingStore selective)) {
                        throw new IllegalStateException("shard does not support selective resolve");
                    }
                    completed.submit(() -> selective.resolveIds(wanted));
                    submitted++;
                }
                for (int i = 0; i < submitted; i++) {
                    for (Map.Entry<Long, String> entry : completed.take().get().entrySet()) {
                        if (emitted.add(entry.getKey())) consumer.accept(entry.getValue());
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }

        /**
         * Build an IBLT by reducing independent per-shard sketches.  Each worker owns its local
         * bucket arrays; only the controller thread calls mergeFrom, so there are no concurrent
         * writes to a shared sketch.  This is used for both the first build and rebucket.
         */
        IbltSketch buildSketch(int bucketCount, long seed, int buildWorkers) throws Exception {
            int parallelism = Math.max(1, Math.min(Math.min(buildWorkers, MAX_REBUCKET_WORKERS), shards.size()));
            if (parallelism == 1) return IbltSketch.build(this, bucketCount, seed);
            ExecutorService pool = Executors.newFixedThreadPool(parallelism);
            try {
                IbltSketch merged = new IbltSketch(bucketCount, seed);
                // Process bounded batches.  At most `parallelism` M-sized local sketches exist
                // at once; after each batch is merged, its futures and arrays become collectible.
                // This keeps memory proportional to MAX_REBUCKET_WORKERS rather than all shards.
                for (int start = 0; start < shards.size(); start += parallelism) {
                    int end = Math.min(shards.size(), start + parallelism);
                    ExecutorCompletionService<IbltSketch> completed =
                            new ExecutorCompletionService<>(pool);
                    for (int i = start; i < end; i++) {
                        FingerprintStore shard = shards.get(i);
                        completed.submit(() -> buildShardSketch(shard, bucketCount, seed));
                    }
                    for (int i = start; i < end; i++) {
                        merged.mergeFrom(completed.take().get());
                    }
                }
                return merged;
            } finally {
                pool.shutdownNow();
            }
        }

        private static IbltSketch buildShardSketch(FingerprintStore shard, int bucketCount,
                long seed) throws Exception {
            IbltSketch local = new IbltSketch(bucketCount, seed);
            shard.forEachFpId(local::insert);
            return local;
        }
    }

    /** Removes the session from the active table; the actual file deletion is done by a background cleanup thread. */
    private void clearSessionsAsync() {
        List<Session> detached = new ArrayList<>(sessions.values());
        sessions.clear();
        if (detached.isEmpty()) return;
        cleanupExecutor.execute(() -> {
            for (Session s : detached) {
                try { s.store.close(); } catch (RuntimeException ignored) { }
            }
        });
    }

    private void buildSketch(HttpExchange ex) throws Exception {
        String[] p = readBody(ex).trim().split("\\s+");
        int m = Integer.parseInt(p[0]);
        long seed = p.length > 1 ? Long.parseLong(p[1]) : 0L;
        // single-session model: remove the old session first; the old spill files are unlinked asynchronously and do not block new scan requests.
        clearSessionsAsync();
        String session = Long.toHexString(++sequence) + "-" + Long.toHexString(System.nanoTime());
        String base = new File(spillDir, "iblt-" + dialect + "-" + session).getAbsolutePath();
        long scanStart = System.nanoTime();
        // spill all fingerprints instead of keeping them in memory: a 600M-row table does not OOM even on a 4 GiB sidecar (.fpid is fixed-length for the sketch build, .pk for resolve).
        FingerprintStore store = null;
        boolean kept = false;
        try {
            ScanResult scan = scanFingerprints(base, m, seed);
            store = scan.store();
            long scanMs = Math.round((System.nanoTime() - scanStart) / 1e6);
            // The first-round sketch is built by each JDBC scan worker while rows stream in;
            // only the small per-shard sketches are merged here.  Rebucket still calls the
            // separate buildSketch(store, ...) path below because it must use a new M/seed.
            IbltSketch sketch = scan.initialSketch();
            long buildMs = scan.mergeMillis();
            sessions.put(session, new Session(store));
            kept = true;
            long rows = store.size();
            // Keep the original metrics shape and append the time spent deriving shard
            // predicates/fences.  With pipelined first-build ingest, scanMs includes the JDBC
            // stream, spill writes, and per-row local sketch inserts; buildMs is only the final
            // small merge of local sketches (rebucket retains the old full .fpid build path).
            String body = session + "\n" + rows + " " + scanMs + " " + buildMs + " "
                    + m + " " + SketchCodec.wireBytes(m) + " " + scan.partitionMs()
                    + "\n" + SketchCodec.encodeBase64(sketch) + "\n";
            send(ex, 200, body);
        } finally {
            if (!kept && store != null) store.close(); // failure path deletes the temp files to avoid leaking them
        }
    }

    private ScanResult scanFingerprints(String base, int bucketCount, long seed) throws Exception {
        if ("single".equals(scanMode)) {
            String sql = CompositeIbltSql.fingerprintSql(dialect, table, pkColumns, pkTypes,
                    valueColumns, valueTypes, oracleParallelDegree);
            ScannedShard scanned = scanOne(base, sql, bucketCount, seed);
            return new ScanResult(scanned.store(), scanned.sketch(), 0L, 0L);
        }
        long partitionStart = System.nanoTime();
        List<String> predicates;
        if ("rowid".equals(scanMode)) {
            predicates = oracleRowidRangePredicates();
        } else if ("hashmod".equals(scanMode)) {
            predicates = new ArrayList<>(scanShards);
            for (int shard = 0; shard < scanShards; shard++) {
                predicates.add(CompositeIbltSql.hashmodShardPredicate(
                        dialect, scanShardColumn, scanShards, shard));
            }
        } else if ("keyrange".equals(scanMode)) {
            predicates = "sample".equals(mysqlFence)
                    ? sampleFenceKeyRangePredicates()
                    : numericSuffixKeyRangePredicates();
        } else {
            throw new IllegalStateException("unknown scan mode: " + scanMode);
        }
        long partitionMs = Math.round((System.nanoTime() - partitionStart) / 1e6);
        int workers = Math.min(scanWorkers, predicates.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<ScannedShard>> futures = new ArrayList<>(predicates.size());
        List<FingerprintStore> completed = new ArrayList<>(predicates.size());
        List<IbltSketch> localSketches = new ArrayList<>(predicates.size());
        List<FingerprintStore> opened = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int shard = 0; shard < predicates.size(); shard++) {
                final int shardId = shard;
                final String predicate = predicates.get(shard);
                futures.add(pool.submit(() -> {
                    ScannedShard scanned = scanOne(base + "-shard-" + shardId,
                            CompositeIbltSql.fingerprintShardSql(dialect, table, pkColumns, pkTypes,
                                    valueColumns, valueTypes, predicate), bucketCount, seed);
                    opened.add(scanned.store());
                    return scanned;
                }));
            }
            for (Future<ScannedShard> future : futures) {
                ScannedShard scanned = future.get();
                completed.add(scanned.store());
                localSketches.add(scanned.sketch());
            }
            long mergeStart = System.nanoTime();
            IbltSketch merged = new IbltSketch(bucketCount, seed);
            for (IbltSketch local : localSketches) merged.mergeFrom(local);
            long mergeMs = Math.round((System.nanoTime() - mergeStart) / 1e6);
            return new ScanResult(new ShardedFingerprintStore(completed, scanWorkers), merged,
                    mergeMs, partitionMs);
        } catch (Exception e) {
            for (Future<ScannedShard> future : futures) future.cancel(true);
            // Workers can finish scanOne and append while another worker's failure is being
            // observed here. Iterating a synchronizedList still requires external locking;
            // otherwise cleanup can mask the original database/JDBC exception with CME.
            List<FingerprintStore> cleanup;
            synchronized (opened) {
                cleanup = new ArrayList<>(opened);
            }
            for (FingerprintStore store : cleanup) store.close();
            throw e;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Fingerprint store plus time spent deriving the disjoint shard predicates. */
    private record ScannedShard(FingerprintStore store, IbltSketch sketch) { }

    /** Scan result with the first-round sketch already built from the streaming JDBC rows. */
    private record ScanResult(FingerprintStore store, IbltSketch initialSketch, long mergeMillis,
                              long partitionMs) {
    }

    /**
     * Split a fixed-width string key such as IT0000000123 into disjoint indexed ranges. MIN/MAX
     * are index-friendly on the leading PK column; the shard scans themselves remain unordered.
     */
    private List<String> numericSuffixKeyRangePredicates() throws Exception {
        String min;
        String max;
        String sql = CompositeIbltSql.minMaxSql(dialect, table, scanShardColumn);
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("MIN/MAX returned no row");
            min = rs.getString(1);
            max = rs.getString(2);
        }
        if (min == null || max == null) throw new IllegalStateException("keyrange table is empty");
        int minDigits = numericSuffixStart(min);
        int maxDigits = numericSuffixStart(max);
        String prefix = min.substring(0, minDigits);
        int width = min.length() - minDigits;
        if (!prefix.equals(max.substring(0, maxDigits)) || width != max.length() - maxDigits) {
            throw new IllegalArgumentException("keyrange requires a shared fixed-width numeric suffix: "
                    + min + " / " + max);
        }
        BigInteger first = new BigInteger(min.substring(minDigits));
        BigInteger last = new BigInteger(max.substring(maxDigits));
        BigInteger span = last.subtract(first).add(BigInteger.ONE);
        int shards = span.min(BigInteger.valueOf(scanShards)).intValueExact();
        List<String> boundaries = new ArrayList<>(Math.max(0, shards - 1));
        BigInteger divisor = BigInteger.valueOf(shards);
        for (int i = 1; i < shards; i++) {
            BigInteger numerator = span.multiply(BigInteger.valueOf(i)).add(divisor).subtract(BigInteger.ONE);
            BigInteger boundary = first.add(numerator.divide(divisor)).subtract(BigInteger.ONE);
            boundaries.add(prefix + leftPad(boundary.toString(), width));
        }
        List<String> predicates = new ArrayList<>(shards);
        for (int i = 0; i < shards; i++) {
            predicates.add(CompositeIbltSql.stringRangePredicate(dialect, scanShardColumn,
                    i == 0 ? null : boundaries.get(i - 1),
                    i == shards - 1 ? null : boundaries.get(i)));
        }
        return predicates;
    }

    private static int numericSuffixStart(String value) {
        int index = value.length();
        while (index > 0 && Character.isDigit(value.charAt(index - 1))) index--;
        if (index == value.length()) {
            throw new IllegalArgumentException("keyrange key has no numeric suffix: " + value);
        }
        return index;
    }

    private static String leftPad(String value, int width) {
        if (value.length() > width) throw new IllegalArgumentException("numeric suffix overflow: " + value);
        return "0".repeat(width - value.length()) + value;
    }

    /**
     * MySQL-only, distribution-agnostic alternative to {@link #numericSuffixKeyRangePredicates()}:
     * fences are quantiles of the actual key set (read-only sampling, no DDL/ANALYZE/histograms),
     * so shards end up with roughly equal row counts on arbitrary key formats such as the base-36
     * jump keys ({@code IT008KN9}) that don't have a fixed-width numeric suffix.
     *
     * <ol>
     *   <li>{@code MIN/MAX} on {@link #scanShardColumn} — two index dives.</li>
     *   <li>Generate {@link #SAMPLE_FENCE_PROBE_COUNT} probe keys evenly spread across the observed
     *       key domain (charset/width only — see {@link CompositeIbltSql#base36FenceProbes}).</li>
     *   <li>Snap every probe to a real key in a single round trip via
     *       {@link CompositeIbltSql#mysqlFenceSnapLateralSql} (MySQL 8.0+ {@code JOIN LATERAL}).</li>
     *   <li>Sort + dedup the snapped keys, take every {@code (count / scanShards)}-th as a fence.</li>
     *   <li>Build disjoint predicates with {@link CompositeIbltSql#stringRangePredicate}.</li>
     * </ol>
     */
    private List<String> sampleFenceKeyRangePredicates() throws Exception {
        if (!"mysql".equalsIgnoreCase(dialect)) {
            throw new IllegalArgumentException("--mysql-fence sample requires --dialect mysql");
        }
        String min;
        String max;
        String minMaxSql = CompositeIbltSql.minMaxSql(dialect, table, scanShardColumn);
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(minMaxSql)) {
            if (!rs.next()) throw new IllegalStateException("MIN/MAX returned no row");
            min = rs.getString(1);
            max = rs.getString(2);
        }
        if (min == null || max == null) throw new IllegalStateException("sample fence table is empty");
        if (min.equals(max)) {
            return List.of("1=1");
        }
        if (numericScanShardColumn()) {
            return numericSampleFenceKeyRangePredicates(min, max);
        }
        List<String> probes = CompositeIbltSql.base36FenceProbes(min, max, SAMPLE_FENCE_PROBE_COUNT);
        // Constant-literal scalar subqueries, batched. Each probe is an index range dive
        // ("... item >= 'CONST' ORDER BY item LIMIT 1"); a correlated LATERAL on s.probe instead
        // degrades to a full index scan + filter per probe (verified via EXPLAIN), so avoid it.
        List<String> snapStatements = CompositeIbltSql.mysqlFenceSnapUnionAllSql(
                table, scanShardColumn, probes, SAMPLE_FENCE_SNAP_BATCH);
        // Sorted + deduped: a TreeSet both orders and removes probes that snap to the same real key
        // (expected near the domain edges / on sparse regions).
        TreeSet<String> snappedDistinct = new TreeSet<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            for (String snapSql : snapStatements) {
                try (ResultSet rs = st.executeQuery(snapSql)) {
                    while (rs.next()) {
                        String snapped = rs.getString(1);
                        if (snapped != null) snappedDistinct.add(snapped);
                    }
                }
            }
        }
        if (snappedDistinct.isEmpty()) {
            throw new IllegalStateException("sample fence snap returned no rows");
        }
        List<String> sorted = new ArrayList<>(snappedDistinct);
        int n = sorted.size();
        int shards = Math.min(scanShards, n);
        // LinkedHashSet: keeps ordering while collapsing any duplicate index picks that can occur
        // when scanShards is close to n (few distinct snapped keys, e.g. tiny/near-constant tables).
        Set<String> boundaries = new LinkedHashSet<>();
        for (int i = 1; i < shards; i++) {
            int idx = Math.min((int) ((long) n * i / shards), n - 1);
            boundaries.add(sorted.get(idx));
        }
        List<String> boundaryList = new ArrayList<>(boundaries);
        int effectiveShards = boundaryList.size() + 1;
        List<String> predicates = new ArrayList<>(effectiveShards);
        for (int i = 0; i < effectiveShards; i++) {
            predicates.add(CompositeIbltSql.stringRangePredicate(dialect, scanShardColumn,
                    i == 0 ? null : boundaryList.get(i - 1),
                    i == effectiveShards - 1 ? null : boundaryList.get(i)));
        }
        return predicates;
    }

    /** Numeric sample-fence path for sparse/variable-width integer keys such as P1 TRAN_ID. */
    private List<String> numericSampleFenceKeyRangePredicates(String min, String max) throws Exception {
        BigInteger first = new BigInteger(min);
        BigInteger last = new BigInteger(max);
        if (last.compareTo(first) < 0) {
            BigInteger swap = first;
            first = last;
            last = swap;
        }
        BigInteger span = last.subtract(first);
        BigInteger steps = BigInteger.valueOf(Math.max(1, SAMPLE_FENCE_PROBE_COUNT - 1));
        List<BigInteger> probes = new ArrayList<>(SAMPLE_FENCE_PROBE_COUNT);
        for (int i = 0; i < SAMPLE_FENCE_PROBE_COUNT; i++) {
            BigInteger offset = (SAMPLE_FENCE_PROBE_COUNT == 1)
                    ? BigInteger.ZERO
                    : span.multiply(BigInteger.valueOf(i)).divide(steps);
            probes.add(first.add(offset));
        }
        List<String> snapStatements = CompositeIbltSql.mysqlNumericFenceSnapUnionAllSql(
                table, scanShardColumn, probes, SAMPLE_FENCE_SNAP_BATCH);
        TreeSet<BigInteger> snappedDistinct = new TreeSet<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            for (String snapSql : snapStatements) {
                try (ResultSet rs = st.executeQuery(snapSql)) {
                    while (rs.next()) {
                        String snapped = rs.getString(1);
                        if (snapped != null) snappedDistinct.add(new BigInteger(snapped));
                    }
                }
            }
        }
        if (snappedDistinct.isEmpty()) {
            throw new IllegalStateException("numeric sample fence snap returned no rows");
        }
        List<BigInteger> sorted = new ArrayList<>(snappedDistinct);
        int n = sorted.size();
        int shards = Math.min(scanShards, n);
        Set<BigInteger> boundaries = new LinkedHashSet<>();
        for (int i = 1; i < shards; i++) {
            int idx = Math.min((int) ((long) n * i / shards), n - 1);
            boundaries.add(sorted.get(idx));
        }
        List<BigInteger> boundaryList = new ArrayList<>(boundaries);
        int effectiveShards = boundaryList.size() + 1;
        List<String> predicates = new ArrayList<>(effectiveShards);
        for (int i = 0; i < effectiveShards; i++) {
            predicates.add(CompositeIbltSql.numericRangePredicate("mysql", scanShardColumn,
                    i == 0 ? null : boundaryList.get(i - 1).toString(),
                    i == effectiveShards - 1 ? null : boundaryList.get(i).toString()));
        }
        return predicates;
    }

    private boolean numericScanShardColumn() {
        for (int i = 0; i < pkColumns.size(); i++) {
            if (pkColumns.get(i).equalsIgnoreCase(scanShardColumn)) {
                // The numeric probe path emits integer literals. Decimal/string keys retain the
                // existing Base36/string path unless a dedicated decimal sampler is added.
                return pkTypes.get(i) == PkType.INT64;
            }
        }
        return false;
    }

    /** Execute one unordered, forward-only query and append each row immediately to its shard file. */
    private ScannedShard scanOne(String base, String sql, int bucketCount, long seed)
            throws Exception {
        FingerprintStore store = new SelectiveFileFingerprintStore(base);
        IbltSketch localSketch = new IbltSketch(bucketCount, seed);
        boolean kept = false;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // Oracle fetches in bounded row batches. Connector/J's MIN_VALUE mode is its explicit
            // forward-only streaming protocol and does not materialize the complete ResultSet.
            st.setFetchSize("mysql".equalsIgnoreCase(dialect) ? Integer.MIN_VALUE : 10000);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Object[] objects = new Object[pkColumns.size()];
                    List<String> raw = new ArrayList<>(pkColumns.size());
                    for (int i = 0; i < pkColumns.size(); i++) {
                        objects[i] = rs.getObject(i + 2);
                        raw.add(rs.getString(i + 2));
                    }
                    String wirePk = CompositePkCodec.encode(raw);
                    // cheap8 is the cross-engine PK proxy stored in idXor, not the shard hash.
                    long id = IbltHash.cheap8(PkTupleCanonicalizer.encode(pkTypeArray, objects, true));
                    long fp = rs.getLong(1);
                    store.add(fp, id, wirePk);
                    // Pipeline the first-round IBLT build with the streaming JDBC ingest.  This
                    // avoids a second full .fpid pass after all shards have finished scanning.
                    localSketch.insert(fp, id);
                }
            }
            store.finishIngest();
            kept = true;
            return new ScannedShard(store, localSketch);
        } finally {
            if (!kept) store.close();
        }
    }

    /**
     * Build true physical ROWID ranges from Oracle extent metadata. Unlike ORA_HASH(ROWID), these
     * predicates let all workers together visit each table extent once rather than once per shard.
     */
    private List<String> oracleRowidRangePredicates() throws Exception {
        if (!"oracle".equalsIgnoreCase(dialect)) {
            throw new IllegalArgumentException("rowid scan mode is Oracle-only");
        }
        List<String> predicates = new ArrayList<>();
        for (OracleRowidChunker.Chunk chunk : OracleRowidChunker.create(jdbcUrl, table, scanShards)) {
            predicates.add(chunk.predicate());
        }
        return predicates;
    }

    private void rebucket(HttpExchange ex) throws Exception {
        String[] p = readBody(ex).trim().split("\\s+");
        Session session = sessions.get(p[0]);
        if (session == null) {
            send(ex, 404, "no such session\n");
            return;
        }
        IbltSketch sketch = buildSketch(session.store,
                Integer.parseInt(p[1]), p.length > 2 ? Long.parseLong(p[2]) : 0L);
        send(ex, 200, SketchCodec.encodeBase64(sketch) + "\n");
    }

    private IbltSketch buildSketch(FingerprintStore store, int bucketCount, long seed)
            throws Exception {
        if (store instanceof ShardedFingerprintStore sharded) {
            return sharded.buildSketch(bucketCount, seed, scanWorkers);
        }
        return IbltSketch.build(store, bucketCount, seed);
    }

    private void resolveIds(HttpExchange ex) throws Exception {
        String[] lines = readBody(ex).split("\\R");
        Session session = lines.length == 0 ? null : sessions.get(lines[0].trim());
        if (session == null) {
            send(ex, 404, "no such session\n");
            return;
        }
        Set<Long> wanted = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            long id = Long.parseLong(lines[i].trim());
            wanted.add(id);
        }
        // Chunked response: resolve can take seconds on large spill files, so let the controller
        // consume PKs while shards finish instead of materializing one giant HTTP body.
        ex.sendResponseHeaders(200, 0);
        try (BufferedOutputStream out = new BufferedOutputStream(ex.getResponseBody())) {
            int[] bufferedBytes = {0};
            StreamingCandidateResolvingStore.PkConsumer emit = pk -> {
                if (pk != null) {
                    byte[] bytes = pk.getBytes(StandardCharsets.UTF_8);
                    out.write(bytes);
                    out.write('\n');
                    bufferedBytes[0] += bytes.length + 1;
                    if (bufferedBytes[0] >= 64 * 1024) {
                        out.flush();
                        bufferedBytes[0] = 0;
                    }
                }
            };
            try {
                if (wanted.isEmpty()) return;
                if (session.store instanceof ShardedFingerprintStore sharded) {
                    sharded.resolveIdsStream(wanted, emit);
                } else if (session.store instanceof StreamingCandidateResolvingStore streaming) {
                    streaming.resolveIdsStream(wanted, emit);
                } else if (session.store instanceof CandidateResolvingStore selective) {
                    for (String pk : selective.resolveIds(wanted).values()) emit.accept(pk);
                } else {
                    Map<Long, String> found = new HashMap<>();
                    session.store.forEachIdPk((id, pk) -> {
                        if (wanted.contains(id) && pk != null) found.putIfAbsent(id, pk);
                    });
                    for (String pk : found.values()) emit.accept(pk);
                }
            } catch (Exception failure) {
                // The status line is already committed for a chunked response, so route() cannot
                // replace it with HTTP 500. Emit an unambiguous protocol line before closing the
                // stream; the client turns it into an IOException with the sidecar-side cause.
                String detail = failure.getClass().getSimpleName() + ": "
                        + String.valueOf(failure.getMessage()).replace('\n', ' ').replace('\r', ' ')
                        .replace('\t', ' ');
                try {
                    byte[] error = (StreamingResolveClient.ERROR_PREFIX + detail + "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    out.write(error);
                    out.flush();
                } catch (IOException writeFailure) {
                    failure.addSuppressed(writeFailure);
                }
                System.err.println("resolve-ids stream failed session=" + session + ": " + detail);
                throw failure;
            }
        }
    }

    private void recheck(HttpExchange ex) throws Exception {
        List<String> pks = new ArrayList<>();
        for (String line : readBody(ex).split("\\R")) {
            if (!line.isBlank()) pks.add(line.trim());
        }
        if (pks.isEmpty()) {
            send(ex, 200, "0\n\n");
            return;
        }
        String sql = CompositeIbltSql.recheckSql(dialect, table, pkColumns, pkTypes, valueColumns, pks);
        long start = System.nanoTime();
        StringBuilder out = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            out.append(Math.round((System.nanoTime() - start) / 1e6)).append('\n');
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if (i > 1) out.append('\t');
                out.append(md.getColumnLabel(i));
            }
            out.append('\n');
            while (rs.next()) {
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (i > 1) out.append('\t');
                    String value = rs.getString(i);
                    if (value != null) out.append(value.replace("\t", " ").replace("\n", " "));
                }
                out.append('\n');
            }
        }
        send(ex, 200, out.toString());
    }

    private void health(HttpExchange ex) throws IOException {
        send(ex, 200, "ok " + dialect + " table=" + table + " pk=" + String.join(",", pkColumns)
                + " oracleParallelDegree=" + oracleParallelDegree
                + " scanMode=" + scanMode + " scanShards=" + scanShards
                + " scanWorkers=" + scanWorkers + " scanShardColumn=" + scanShardColumn
                + " rebucketWorkersMax=" + MAX_REBUCKET_WORKERS
                + " mysqlFence=" + mysqlFence + "\n");
    }

    private HttpServer start(String bind, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        // The controller may issue one recheck request per batch concurrently. The JDK HTTP
        // server's implicit executor is implementation-dependent (and can serialize handlers),
        // so make the endpoint concurrency explicit and align it with scan workers.
        // Two resolve handlers can remain occupied while they stream spill-file results.  Keep
        // extra handler slots available so the controller's early recheck batches can actually
        // overlap resolve (especially when scanWorkers/controllerWorkers is small).
        httpExecutor = Executors.newFixedThreadPool(Math.max(4, scanWorkers + 2));
        server.setExecutor(httpExecutor);
        server.createContext("/health", ex -> route(ex, this::health));
        server.createContext("/build-sketch", ex -> route(ex, this::buildSketch));
        server.createContext("/rebucket", ex -> route(ex, this::rebucket));
        server.createContext("/resolve-ids", ex -> route(ex, this::resolveIds));
        server.createContext("/recheck", ex -> route(ex, this::recheck));
        server.createContext("/close-sessions", ex -> route(ex, ex2 -> {
            clearSessionsAsync();
            send(ex2, 200, "cleanup-queued\n");
        }));
        server.start();
        return server;
    }

    private void route(HttpExchange ex, Handler handler) {
        try {
            handler.handle(ex);
        } catch (Exception e) {
            try {
                send(ex, 500, "error: " + e + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        String dialect = required(a, "--dialect");
        List<String> pkColumns = csv(required(a, "--pk-columns"));
        List<PkType> pkTypes = types(required(a, "--pk-types"));
        List<String> valueColumns = csv(a.getOrDefault("--value-columns", ""));
        List<ValueCanonType> valueTypes = ValueCanonType.parseCsv(a.getOrDefault("--value-types", ""));
        // default spill directory is a shared NFS mount: one place large enough for every table's fingerprint spill, including P1's 600M rows,
        // and on a different disk from the database data files so space and IO do not contend. Override with --spill-dir.
        String spillDir = a.getOrDefault(
                "--spill-dir", Path.of(System.getProperty("java.io.tmpdir"), "e29-spill").toString());
        int oracleParallelDegree = Integer.parseInt(a.getOrDefault("--oracle-parallel-degree", "1"));
        if (oracleParallelDegree < 1) throw new IllegalArgumentException("oracle parallel degree must be positive");
        String scanMode = a.getOrDefault("--scan-mode", "single").toLowerCase(Locale.ROOT);
        if (!Set.of("single", "rowid", "hashmod", "keyrange").contains(scanMode)) {
            throw new IllegalArgumentException(
                    "scan mode must be single, rowid, hashmod, or keyrange: " + scanMode);
        }
        if ("rowid".equals(scanMode) && !"oracle".equalsIgnoreCase(dialect)) {
            throw new IllegalArgumentException("rowid scan mode is Oracle-only");
        }
        int scanWorkers = positiveInt(a.getOrDefault("--scan-workers",
                "single".equals(scanMode) ? "1" : "2"), "--scan-workers");
        String defaultShards = "rowid".equals(scanMode) ? "8" : Integer.toString(scanWorkers);
        int scanShards = positiveInt(a.getOrDefault("--scan-shards", defaultShards), "--scan-shards");
        String scanShardColumn = a.getOrDefault("--scan-shard-column", pkColumns.get(0));
        // Additive, keyrange-only modifier: default "numeric" preserves the existing fixed-width
        // numeric-suffix boundary math untouched; "sample" switches keyrange to read-only quantile
        // sampling (MySQL only) for arbitrary key formats such as base-36 jump keys.
        String mysqlFence = a.getOrDefault("--mysql-fence", "numeric").toLowerCase(Locale.ROOT);
        if (!Set.of("numeric", "sample").contains(mysqlFence)) {
            throw new IllegalArgumentException("--mysql-fence must be numeric or sample: " + mysqlFence);
        }
        String jdbcUrl = a.containsKey("--jdbc-env")
                ? requiredEnv(a.get("--jdbc-env")) : required(a, "--jdbc");
        new File(spillDir).mkdirs();
        CompositeIbltSidecarServer app = new CompositeIbltSidecarServer(dialect,
                jdbcUrl, required(a, "--table"), pkColumns, pkTypes,
                valueColumns, valueTypes, spillDir, oracleParallelDegree,
                scanMode, scanShards, scanWorkers, scanShardColumn, mysqlFence);
        HttpServer server = app.start(a.getOrDefault("--bind", "127.0.0.1"),
                Integer.parseInt(a.getOrDefault("--port", "19100")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            if (app.httpExecutor != null) app.httpExecutor.shutdownNow();
            app.cleanupExecutor.shutdownNow();
        }));
        Thread.currentThread().join();
    }

    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing environment: " + key);
        return value;
    }

    private static int positiveInt(String raw, String option) {
        try {
            int value = Integer.parseInt(raw);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(option + " must be a positive integer: " + raw);
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String item : value.split(",")) out.add(item.trim());
        return out;
    }

    private static List<PkType> types(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<PkType> out = new ArrayList<>();
        for (String item : value.split(",")) {
            String type = item.trim().toLowerCase();
            if (type.contains("int") || type.contains("serial")) {
                out.add(PkType.INT64);
            } else if (type.contains("decimal") || type.contains("numeric")) {
                out.add(PkType.DECIMAL);
            } else {
                out.add(PkType.STRING);
            }
        }
        return out;
    }
}

package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Standalone JDBC Merkle sidecar migrated from the single-column benchmark path.
 * The HTTP protocol deliberately carries encoded tuple boundaries, never raw row data.
 */
public final class CompositeMerkleSidecarServer {
    /** Keeps the recheck IN-list under Oracle's 1000-expression limit. */
    private static final int RECHECK_BATCH = 500;
    /** Keep ROWID probe SQLs small enough to parse quickly while exposing literals to Oracle CBO. */
    private static final int ROWID_PROBE_BATCH = 32;
    private final String dialect;
    private final String jdbcUrl;
    private final String table;
    private final List<String> pkColumns;
    private final List<PkType> pkTypes;
    private final List<String> orderColumns;
    private final List<PkType> orderTypes;
    private final List<String> valueColumns;
    private final List<ValueCanonType> valueTypes;
    private final int merkleWorkers;
    private final String boundaryMode;
    private final int boundaryWorkers;
    private final ExecutorService merkleExecutor;
    private ExecutorService httpExecutor;

    private CompositeMerkleSidecarServer(String dialect, String jdbcUrl, String table,
                                         List<String> pkColumns, List<PkType> pkTypes,
                                         List<String> valueColumns, List<ValueCanonType> valueTypes,
                                         List<String> orderColumns, List<PkType> orderTypes,
                                         boolean orderKeyUnique, int merkleWorkers,
                                         String boundaryMode, int boundaryWorkers)
            throws Exception {
        this.dialect = dialect;
        this.jdbcUrl = jdbcUrl;
        this.table = table;
        this.pkColumns = List.copyOf(pkColumns);
        this.pkTypes = List.copyOf(pkTypes);
        this.orderColumns = List.copyOf(orderColumns);
        this.orderTypes = List.copyOf(orderTypes);
        this.valueColumns = List.copyOf(valueColumns);
        this.valueTypes = List.copyOf(valueTypes);
        this.merkleWorkers = Math.max(1, merkleWorkers);
        this.boundaryMode = boundaryMode;
        this.boundaryWorkers = Math.max(1, boundaryWorkers);
        this.merkleExecutor = Executors.newFixedThreadPool(this.merkleWorkers);
        if (pkColumns.isEmpty() || pkColumns.size() != pkTypes.size()) {
            throw new IllegalArgumentException("pk columns/types mismatch");
        }
        if (valueColumns.size() != valueTypes.size()) {
            throw new IllegalArgumentException("value columns/types mismatch");
        }
        if (orderColumns.isEmpty() || orderColumns.size() != orderTypes.size()) {
            throw new IllegalArgumentException("Merkle order columns/types mismatch");
        }
        if (!orderColumns.equals(pkColumns) && !orderKeyUnique) {
            throw new IllegalArgumentException("custom Merkle order key requires --merkle-order-key-unique true");
        }
        if (!List.of("ordered", "ordered-stream", "rowid-sample").contains(boundaryMode)) {
            throw new IllegalArgumentException("Merkle boundary mode must be ordered, ordered-stream or rowid-sample");
        }
        if ("rowid-sample".equals(boundaryMode) && !"oracle".equalsIgnoreCase(dialect)) {
            throw new IllegalArgumentException("rowid-sample Merkle boundaries are Oracle-only");
        }
        try (Connection ignored = DriverManager.getConnection(jdbcUrl)) {
            // Connectivity is a startup gate, so a sidecar cannot appear healthy while JDBC is down.
        }
    }

    private void health(HttpExchange ex) throws IOException {
        send(ex, 200, "ok " + dialect + " table=" + table
                + " pk=" + String.join(",", pkColumns) + " values="
                + String.join(",", valueColumns)
                + " merkleWorkers=" + merkleWorkers
                + " merkleOrder=" + String.join(",", orderColumns)
                + " merkleBoundaryMode=" + boundaryMode
                + " merkleBoundaryWorkers=" + boundaryWorkers
                + " merkleOrderUnique=true\n");
    }

    /** Source-side boundary scan. The final null line opens the tail range. */
    private void boundaries(HttpExchange ex) throws Exception {
        int chunkSize = Integer.parseInt(readBody(ex).trim());
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        if ("rowid-sample".equals(boundaryMode)) {
            rowidSampleBoundaries(ex, chunkSize);
            return;
        }
        if ("ordered-stream".equals(boundaryMode)) {
            orderedStreamingBoundaries(ex, chunkSize);
            return;
        }
        StringBuilder out = new StringBuilder();
        List<String> lower = null;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            while (true) {
                String sql = CompositeMerkleSql.boundarySql(dialect, table, orderColumns, orderTypes,
                        lower, chunkSize);
                List<String> last = null;
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        List<String> tuple = new ArrayList<>(orderColumns.size());
                        for (int i = 0; i < orderColumns.size(); i++) tuple.add(rs.getString(i + 1));
                        last = tuple;
                    }
                }
                if (last == null) {
                    out.append("null\n");
                    break;
                }
                out.append(CompositePkCodec.encode(last)).append('\n');
                lower = last;
            }
        }
        send(ex, 200, out.toString());
    }

    /** One ordered, forward-only scan; retain every chunkSize-th logical key as an exact fence. */
    private void orderedStreamingBoundaries(HttpExchange ex, int chunkSize) throws Exception {
        long started = System.nanoTime();
        String sql = CompositeMerkleSql.orderedStreamingBoundarySql(dialect, table, orderColumns);
        StringBuilder out = new StringBuilder();
        long count = 0;
        long samples = 0;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize("mysql".equalsIgnoreCase(dialect) ? Integer.MIN_VALUE : 10000);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    count++;
                    if (count % chunkSize != 0) continue;
                    List<String> tuple = new ArrayList<>(orderColumns.size());
                    for (int i = 0; i < orderColumns.size(); i++) {
                        String value = rs.getString(i + 1);
                        if (value == null) throw new IllegalStateException("Merkle order key contains NULL");
                        tuple.add(value);
                    }
                    out.append(CompositePkCodec.encode(tuple)).append('\n');
                    samples++;
                }
            }
        }
        // The controller appends an open tail range.  If the final row is not exactly on a
        // boundary, that tail contains fewer than chunkSize rows and needs no extra query.
        out.append("null\n");
        ex.getResponseHeaders().set("X-Merkle-Boundary-Mode", boundaryMode);
        ex.getResponseHeaders().set("X-Merkle-Boundary-Estimated-Rows", Long.toString(count));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Samples", Long.toString(samples));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Scan-Rows", Long.toString(count));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Scan-Millis", Long.toString(elapsedMs(started)));
        send(ex, 200, out.toString());
    }

    /**
     * Approximate logical fences from physical chunks. Fence accuracy only affects balance, not
     * correctness: the controller sorts these source values and still appends an open tail range.
     */
    private void rowidSampleBoundaries(HttpExchange ex, int chunkSize) throws Exception {
        long chunkStarted = System.nanoTime();
        long estimatedRows = estimatedOracleRows();
        int desiredSamples = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                (estimatedRows + chunkSize - 1) / chunkSize));
        List<OracleRowidChunker.Chunk> chunks = OracleRowidChunker.create(
                jdbcUrl, table, desiredSamples);
        long chunkMs = elapsedMs(chunkStarted);
        @SuppressWarnings("unchecked")
        List<String>[] samples = new List[chunks.size()];
        int workers = Math.min(boundaryWorkers, chunks.size());
        List<Future<?>> futures = new ArrayList<>();
        long probesStarted = System.nanoTime();
        // Do not bind ROWID values here.  CHARTOROWID(?) is opaque to Oracle's optimizer and
        // was observed to become INDEX FAST FULL SCAN for every probe.  Literal ROWIDs let the
        // optimizer choose a true ROWID range access.  Batch 32 probes per statement to amortize
        // HTTP/JDBC overhead without recreating the old single giant UNION ALL statement.
        for (int first = 0; first < chunks.size(); first += ROWID_PROBE_BATCH) {
            final int from = first;
            final int to = Math.min(chunks.size(), first + ROWID_PROBE_BATCH);
            futures.add(merkleExecutor.submit(() -> {
                List<String> predicates = new ArrayList<>(to - from);
                List<Integer> indexes = new ArrayList<>(to - from);
                for (int i = from; i < to; i++) {
                    predicates.add(chunks.get(i).predicate());
                    indexes.add(i);
                }
                String sql = CompositeMerkleSql.rowidBoundarySampleBatchSql(
                        table, orderColumns, predicates, indexes);
                try (Connection conn = DriverManager.getConnection(jdbcUrl);
                     Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                             ResultSet.CONCUR_READ_ONLY)) {
                    // Oracle 11g may choose serial direct reads for this large heap.  That path
                    // makes concurrent ROWID probes contend on the KO fast-object-checkpoint
                    // enqueue.  Force buffered single-block reads for this probe-only session.
                    try (Statement session = conn.createStatement()) {
                        session.execute("ALTER SESSION SET \"_serial_direct_read\" = NEVER");
                    }
                    st.setFetchSize(Math.max(1, indexes.size()));
                    try (ResultSet rs = st.executeQuery(sql)) {
                        while (rs.next()) {
                            int sampleIndex = rs.getInt(1);
                            List<String> tuple = new ArrayList<>(orderColumns.size());
                            for (int column = 0; column < orderColumns.size(); column++) {
                                String value = rs.getString(column + 2);
                                if (value == null) {
                                    throw new IllegalStateException("Merkle order key contains NULL");
                                }
                                tuple.add(value);
                            }
                            samples[sampleIndex] = tuple;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        awaitAll(futures);
        long probeMs = elapsedMs(probesStarted);
        long sortStarted = System.nanoTime();
        List<List<String>> sorted = new ArrayList<>();
        for (List<String> sample : samples) if (sample != null) sorted.add(sample);
        sorted.sort(boundaryComparator());
        if (sorted.size() > desiredSamples) {
            List<List<String>> limited = new ArrayList<>(desiredSamples);
            for (int i = 1; i <= desiredSamples; i++) {
                int index = (int) Math.ceil((double) i * sorted.size() / desiredSamples) - 1;
                limited.add(sorted.get(index));
            }
            sorted = limited;
        }
        long sortMs = elapsedMs(sortStarted);
        StringBuilder out = new StringBuilder();
        List<String> previous = null;
        for (List<String> sample : sorted) {
            if (!sample.equals(previous)) out.append(CompositePkCodec.encode(sample)).append('\n');
            previous = sample;
        }
        out.append("null\n");
        ex.getResponseHeaders().set("X-Merkle-Boundary-Mode", boundaryMode);
        ex.getResponseHeaders().set("X-Merkle-Boundary-Workers", Integer.toString(workers));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Samples", Integer.toString(sorted.size()));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Chunk-Millis", Long.toString(chunkMs));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Probe-Millis", Long.toString(probeMs));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Sort-Millis", Long.toString(sortMs));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Probe-Statements", Integer.toString(futures.size()));
        ex.getResponseHeaders().set("X-Merkle-Boundary-Probe-Batch-Size", Integer.toString(ROWID_PROBE_BATCH));
        // This is deliberately the catalog estimate, not COUNT(*).  It only determines how many
        // physical ROWID samples we ask for; checksum equality remains the correctness gate.
        ex.getResponseHeaders().set("X-Merkle-Boundary-Estimated-Rows", Long.toString(estimatedRows));
        send(ex, 200, out.toString());
    }

    /** Body: chunkSize newline encodedLower<TAB>encodedUpper. */
    private void splitBoundaries(HttpExchange ex) throws Exception {
        String[] lines = readBody(ex).split("\\R");
        if (lines.length < 2) throw new IllegalArgumentException("missing split range");
        int chunkSize = Integer.parseInt(lines[0].trim());
        List<Range> ranges = parseRanges(lines[1]);
        if (chunkSize < 1 || ranges.size() != 1) throw new IllegalArgumentException("bad split request");
        Range range = ranges.get(0);
        String sql = CompositeMerkleSql.splitBoundarySql(dialect, table, orderColumns, orderTypes,
                range.lower, range.upper);
        List<List<String>> boundaries = new ArrayList<>();
        long count = 0;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize("mysql".equalsIgnoreCase(dialect) ? Integer.MIN_VALUE : 10000);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    count++;
                    if (count % chunkSize == 0) {
                        List<String> tuple = new ArrayList<>(orderColumns.size());
                        for (int i = 0; i < orderColumns.size(); i++) tuple.add(rs.getString(i + 1));
                        boundaries.add(tuple);
                    }
                }
            }
        }
        if (count % chunkSize == 0 && !boundaries.isEmpty()) boundaries.remove(boundaries.size() - 1);
        StringBuilder out = new StringBuilder();
        for (List<String> boundary : boundaries) out.append(CompositePkCodec.encode(boundary)).append('\n');
        ex.getResponseHeaders().set("X-Merkle-Split-Rows", Long.toString(count));
        send(ex, 200, out.toString());
    }

    private long estimatedOracleRows() throws Exception {
        String tableName = table.substring(table.lastIndexOf('.') + 1).toUpperCase();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             java.sql.PreparedStatement st = conn.prepareStatement(
                     "SELECT num_rows FROM user_tables WHERE table_name=?")) {
            st.setString(1, tableName);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next() || rs.getLong(1) <= 0) {
                    throw new IllegalStateException("missing positive NUM_ROWS statistics for " + table);
                }
                return rs.getLong(1);
            }
        }
    }

    private Comparator<List<String>> boundaryComparator() {
        return (left, right) -> {
            for (int i = 0; i < orderTypes.size(); i++) {
                int compared;
                if (orderTypes.get(i) == PkType.STRING) {
                    compared = left.get(i).compareTo(right.get(i));
                } else {
                    compared = new BigDecimal(left.get(i)).compareTo(new BigDecimal(right.get(i)));
                }
                if (compared != 0) return compared;
            }
            return 0;
        };
    }

    /** Body: one range per line, encodedLower<TAB>encodedUpper; null means open bound. */
    private void checksums(HttpExchange ex) throws Exception {
        long started = System.nanoTime();
        List<Range> ranges = parseRanges(readBody(ex));
        int workers = Math.min(merkleWorkers, ranges.size());
        ChecksumResult[] results = new ChecksumResult[ranges.size()];
        List<Future<?>> futures = new ArrayList<>(workers);
        for (int workerId = 0; workerId < workers; workerId++) {
            final int id = workerId;
            futures.add(merkleExecutor.submit(() -> {
                try {
                    try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                        for (int i = id; i < ranges.size(); i += workers) {
                            Range range = ranges.get(i);
                            String sql = CompositeMerkleSql.checksumSql(dialect, table, pkColumns, pkTypes,
                                    valueColumns, valueTypes, orderColumns, orderTypes,
                                    range.lower, range.upper);
                            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                                if (!rs.next()) throw new IllegalStateException("checksum returned no row");
                                results[i] = new ChecksumResult(
                                        rs.getString(1) == null ? "0" : rs.getString(1), rs.getLong(2));
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        awaitAll(futures);
        StringBuilder out = new StringBuilder();
        for (ChecksumResult result : results) {
            out.append(result.sum).append('\t').append(result.count).append('\n');
        }
        ex.getResponseHeaders().set("X-Merkle-Workers", Integer.toString(workers));
        ex.getResponseHeaders().set("X-Merkle-Checksum-Millis", Long.toString(elapsedMs(started)));
        send(ex, 200, out.toString());
    }

    /**
     * Oracle-only one-pass localisation of a single dirty range. Body: line0 = fanout, line1 =
     * encodedLower<TAB>encodedUpper. Response: one line per bucket, encodedUpperKey<TAB>sumFp<TAB>count.
     */
    private void decileChecksums(HttpExchange ex) throws Exception {
        long started = System.nanoTime();
        if (!"oracle".equalsIgnoreCase(dialect)) {
            throw new IllegalArgumentException("decile-checksums is Oracle-only (fence source)");
        }
        String[] in = readBody(ex).split("\\R");
        if (in.length < 2) throw new IllegalArgumentException("decile request needs fanout and range");
        int fanout = Integer.parseInt(in[0].trim());
        List<Range> ranges = parseRanges(in[1]);
        if (ranges.size() != 1) throw new IllegalArgumentException("decile requires exactly one range");
        Range range = ranges.get(0);
        String sql = CompositeMerkleSql.oracleDecileChecksumSql(table, pkColumns, pkTypes,
                valueColumns, valueTypes, orderColumns, orderTypes, range.lower, range.upper, fanout);
        StringBuilder out = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.append(CompositePkCodec.encode(List.of(rs.getString(2)))).append('\t')
                        .append(rs.getString(3) == null ? "0" : rs.getString(3)).append('\t')
                        .append(rs.getLong(4)).append('\n');
            }
        }
        ex.getResponseHeaders().set("X-Merkle-Decile-Millis", Long.toString(elapsedMs(started)));
        send(ex, 200, out.toString());
    }

    /**
     * Value-level recheck. Body: one encoded PK tuple per line. Response: encodedPk<TAB>canonicalMd5.
     * Independent point queries over the actual rows, batched to stay within the IN-list limit.
     */
    private void recheck(HttpExchange ex) throws Exception {
        List<List<String>> pks = new ArrayList<>();
        for (String line : readBody(ex).split("\\R")) {
            if (line.isBlank()) continue;
            pks.add(CompositePkCodec.decode(line.trim(), pkColumns.size()));
        }
        if (pks.isEmpty()) { send(ex, 200, ""); return; }
        StringBuilder out = new StringBuilder();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            for (int off = 0; off < pks.size(); off += RECHECK_BATCH) {
                List<List<String>> slice = pks.subList(off, Math.min(pks.size(), off + RECHECK_BATCH));
                String sql = CompositeMerkleSql.recheckMd5Sql(dialect, table, pkColumns, pkTypes,
                        valueColumns, valueTypes, slice);
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        List<String> pk = new ArrayList<>(pkColumns.size());
                        for (int j = 0; j < pkColumns.size(); j++) pk.add(rs.getString(j + 1));
                        out.append(CompositePkCodec.encode(pk)).append('\t')
                                .append(rs.getString(pkColumns.size() + 1)).append('\n');
                    }
                }
            }
        }
        send(ex, 200, out.toString());
    }

    /** Streaming response: encodedOrder<TAB>encodedPk<TAB>fingerprint. */
    private void rows(HttpExchange ex) throws Exception {
        List<Range> ranges = parseRanges(readBody(ex));
        if (ranges.size() != 1) throw new IllegalArgumentException("streaming rows requires one range");
        Range range = ranges.get(0);
        String sql = CompositeMerkleSql.rowsSql(dialect, table, pkColumns, pkTypes,
                valueColumns, valueTypes, orderColumns, orderTypes, range.lower, range.upper);
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize("mysql".equalsIgnoreCase(dialect) ? Integer.MIN_VALUE : 10000);
            try (ResultSet rs = st.executeQuery(sql)) {
                ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                ex.getResponseHeaders().set("X-Merkle-Workers", "1");
                ex.sendResponseHeaders(200, 0);
                try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        ex.getResponseBody(), StandardCharsets.UTF_8), 1 << 20)) {
                    while (rs.next()) {
                        List<String> pk = new ArrayList<>(pkColumns.size());
                        for (int j = 0; j < pkColumns.size(); j++) pk.add(rs.getString(j + 2));
                        List<String> order = new ArrayList<>(orderColumns.size());
                        for (int j = 0; j < orderColumns.size(); j++) {
                            order.add(rs.getString(2 + pkColumns.size() + j));
                        }
                        out.write(CompositePkCodec.encode(order));
                        out.write('\t');
                        out.write(CompositePkCodec.encode(pk));
                        out.write('\t');
                        out.write(rs.getString(1));
                        out.newLine();
                    }
                }
            }
        }
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> future : futures) future.get();
    }

    private static final class ChecksumResult {
        final String sum;
        final long count;
        ChecksumResult(String sum, long count) {
            this.sum = sum;
            this.count = count;
        }
    }

    private List<Range> parseRanges(String body) {
        List<Range> out = new ArrayList<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\t", -1);
            if (p.length != 2) throw new IllegalArgumentException("bad range: " + line);
            out.add(new Range(bound(p[0]), bound(p[1])));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no ranges");
        return out;
    }

    private List<String> bound(String value) {
        String s = value.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return CompositePkCodec.decode(s, orderColumns.size());
    }

    private static final class Range {
        final List<String> lower;
        final List<String> upper;
        Range(List<String> lower, List<String> upper) { this.lower = lower; this.upper = upper; }
    }

    private HttpServer start(String bind, int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        httpExecutor = Executors.newFixedThreadPool(Math.max(4, merkleWorkers + 2));
        http.setExecutor(httpExecutor);
        http.createContext("/health", ex -> route(ex, this::health));
        http.createContext("/merkle/boundaries", ex -> route(ex, this::boundaries));
        http.createContext("/merkle/checksums", ex -> route(ex, this::checksums));
        http.createContext("/merkle/split-boundaries", ex -> route(ex, this::splitBoundaries));
        http.createContext("/merkle/decile-checksums", ex -> route(ex, this::decileChecksums));
        http.createContext("/merkle/recheck", ex -> route(ex, this::recheck));
        http.createContext("/merkle/rows", ex -> route(ex, this::rows));
        http.createContext("/close", ex -> route(ex, ex2 -> send(ex2, 200, "closed\n")));
        http.start();
        return http;
    }

    private void shutdown() {
        merkleExecutor.shutdownNow();
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

    private static void route(HttpExchange ex, Handler handler) {
        try {
            handler.handle(ex);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            try { send(ex, 500, "error: " + e + "\n"); } catch (IOException ignored) { }
        }
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static long elapsedMs(long started) {
        return Math.round((System.nanoTime() - started) / 1e6);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        String dialect = required(a, "--dialect");
        List<String> pks = csv(required(a, "--pk-columns"));
        List<PkType> pkTypes = types(required(a, "--pk-types"));
        List<String> orderColumns = csv(a.getOrDefault("--merkle-order-columns",
                String.join(",", pks)));
        List<PkType> orderTypes = types(a.getOrDefault("--merkle-order-types",
                required(a, "--pk-types")));
        boolean orderKeyUnique = Boolean.parseBoolean(a.getOrDefault(
                "--merkle-order-key-unique", "false"));
        List<String> values = csv(a.getOrDefault("--value-columns", ""));
        List<ValueCanonType> valueTypes = ValueCanonType.parseCsv(a.getOrDefault("--value-types", ""));
        int merkleWorkers = positiveInt(a.getOrDefault("--merkle-workers", "5"), "--merkle-workers");
        String boundaryMode = a.getOrDefault("--merkle-boundary-mode", "ordered");
        int boundaryWorkers = positiveInt(a.getOrDefault("--merkle-boundary-workers", "2"),
                "--merkle-boundary-workers");
        CompositeMerkleSidecarServer app = new CompositeMerkleSidecarServer(dialect,
                required(a, "--jdbc"), required(a, "--table"), pks, pkTypes, values, valueTypes,
                orderColumns, orderTypes, orderKeyUnique, merkleWorkers,
                boundaryMode, boundaryWorkers);
        HttpServer http = app.start(a.getOrDefault("--bind", "127.0.0.1"),
                Integer.parseInt(a.getOrDefault("--port", "19200")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.shutdown();
            http.stop(1);
        }));
        Thread.currentThread().join();
    }

    private static int positiveInt(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException(name + " must be positive");
        return parsed;
    }

    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String item : value.split(",")) out.add(item.trim());
        return out;
    }

    private static List<PkType> types(String value) {
        List<PkType> out = new ArrayList<>();
        if (value == null || value.isBlank()) return out;
        for (String item : value.split(",")) {
            String type = item.trim().toLowerCase();
            if (type.contains("int") || type.contains("serial")) out.add(PkType.INT64);
            else if (type.contains("decimal") || type.contains("numeric")) out.add(PkType.DECIMAL);
            else out.add(PkType.STRING);
        }
        return out;
    }
}

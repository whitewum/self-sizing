package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.core.FingerprintStore;
import io.github.selfsizing.iblt.core.IbltConstants;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.core.SketchCodec;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import io.github.selfsizing.iblt.sql.CanonType;
import io.github.selfsizing.iblt.sql.ClickHouseIbltDialect;
import io.github.selfsizing.iblt.sql.ColumnSpec;
import io.github.selfsizing.iblt.sql.FingerprintQuery;
import io.github.selfsizing.iblt.sql.IbltDialect;
import io.github.selfsizing.iblt.sql.MysqlIbltDialect;
import io.github.selfsizing.iblt.sql.OracleIbltDialect;
import io.github.selfsizing.iblt.sql.PostgresIbltDialect;
import io.github.selfsizing.iblt.sql.RedisIbltDialect;
import io.github.selfsizing.iblt.sql.RecheckQuery;
import io.github.selfsizing.iblt.sql.SqlServerIbltDialect;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Long-running sidecar HTTP service (pure JDK {@code com.sun.net.httpserver}, zero third-party dependencies).
 *
 * <p>One per host, bound to {@code 127.0.0.1:<port>} (<b>not exposed to the
 * public internet</b>; across machines the controller reaches it through an
 * {@code ssh -L} tunnel). Reads the <b>local</b> database, builds a sketch,
 * rechecks by candidate.</p>
 *
 * <p>Text-line protocol (UTF-8):
 * <ul>
 *   <li>{@code GET  /health} &rarr; {@code ok ... mapperVersion=2}</li>
 *   <li>{@code POST /build-sketch}  body={@code <M>} &rarr; L1 sessionId; L2 {@code scanRows scanMillis buildMillis M wireBytes}; L3 base64(sketch)</li>
 *   <li>{@code POST /rebucket}      body={@code <sessionId> <M>} &rarr; L1 base64(sketch) (rebuilt from the retained store, no re-scan)</li>
 *   <li>{@code POST /resolve-ids}   body=L1 sessionId, then one id per line &rarr; one pk per line (only those matched in this side's store)</li>
 *   <li>{@code POST /recheck}       body=one pk per line &rarr; L1 column names(\t); then one matched row per line(\t)</li>
 * </ul>
 * Orchestration of decode / re-bucket retry / resolve-ids is on the controller side.</p>
 */
public final class IbltSidecarServer {

    private static final Logger LOG = Logger.getLogger(IbltSidecarServer.class.getName());

    // the experiment table schema is fixed (items); to generalize, carry a column description in the request.
    private static final List<String> VALUE_COLUMNS =
            List.of("value_text", "amount", "category", "updated_at");
    private static final List<ColumnSpec> VALUE_SPECS = List.of(
            ColumnSpec.of("value_text", CanonType.STRING),
            ColumnSpec.of("amount", CanonType.MONEY2),
            ColumnSpec.of("category", CanonType.INT),
            ColumnSpec.of("updated_at", CanonType.DATETIME_SEC));
    private static final int FETCH = 4096;

    private final IbltSidecarService service;
    private final IbltDialect dialect;
    private final String table;
    private final FingerprintQuery fpQuery;
    private final RecheckQuery recheckQuery;
    private final String jdbcUrl; // null for non-JDBC dialects; the Merkle benchmark endpoints currently support only JDBC engines
    private final int scanWorkers;
    private final int merkleWorkers;
    private final BlockingQueue<Connection> merkleConnectionPool;
    private final Object merkleConnectionLock = new Object();
    private int merkleConnectionsCreated;
    private final Object dbLock = new Object(); // on one sidecar, prevents two full compare phases from interfering
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, RedisMerkleSession> redisMerkleSessions = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private IbltSidecarServer(IbltSidecarService service, IbltDialect dialect, String table,
                              String jdbcUrl, int scanWorkers, int merkleWorkers) {
        this.service = service;
        this.dialect = dialect;
        this.table = table;
        this.fpQuery = new FingerprintQuery(
                table, ColumnSpec.of("id", CanonType.INT), PkType.INT64, VALUE_SPECS, true);
        this.recheckQuery = new RecheckQuery(table, "id", PkType.INT64, VALUE_COLUMNS);
        this.jdbcUrl = jdbcUrl;
        this.scanWorkers = Math.max(1, scanWorkers);
        this.merkleWorkers = Math.max(1, merkleWorkers);
        this.merkleConnectionPool = new ArrayBlockingQueue<>(this.merkleWorkers);
    }

    private static final class Session {
        final FingerprintStore store;

        Session(FingerprintStore store) {
            this.store = store;
        }
    }

    private static final class RedisMerkleSession {
        final RedisMerkleCache cache;

        RedisMerkleSession(RedisMerkleCache cache) {
            this.cache = cache;
        }
    }

    // ---- endpoints ----

    private void handleHealth(HttpExchange ex) throws IOException {
        send(ex, 200, "ok " + dialect.id() + " mapperVersion=" + IbltConstants.MAPPER_VERSION
                + " table=" + table
                + " scanWorkers=" + scanWorkers + " merkleWorkers=" + merkleWorkers
                + " merkleConnections=" + merkleConnectionsCreated + "\n");
    }

    private void handleBuildSketch(HttpExchange ex) throws Exception {
        String[] parts = readBody(ex).trim().split("\\s+");
        int m = Integer.parseInt(parts[0]);
        long hashSeed = parts.length >= 2 ? Long.parseLong(parts[1]) : 0L;
        BuildSketchResponse resp;
        synchronized (dbLock) {
            // the experiment sidecar runs only one compare at a time; release the previous round's fingerprint store
            // before a new round starts, so warm-up + measured repetitions do not accumulate whole-table memory per round.
            for (Session session : sessions.values()) {
                session.store.close();
            }
            sessions.clear();
            closeRedisMerkleSessionsLocked();
            resp = service.buildSketch(new BuildSketchRequest(fpQuery, m, FETCH, hashSeed));
        }
        String sessionId = Long.toHexString(seq.incrementAndGet()) + "-"
                + Long.toHexString(System.nanoTime());
        sessions.put(sessionId, new Session(resp.store()));
        SketchMetrics mt = resp.metrics();
        String head = sessionId + "\n"
                + mt.scanRows() + " " + mt.scanMillis() + " " + mt.buildMillis() + " "
                + mt.bucketCount() + " " + SketchCodec.wireBytes(mt.bucketCount()) + "\n"
                + SketchCodec.encodeBase64(resp.sketch()) + "\n";
        send(ex, 200, head);
    }

    private void handleRebucket(HttpExchange ex) throws Exception {
        String[] parts = readBody(ex).trim().split("\\s+");
        Session s = sessions.get(parts[0]);
        if (s == null) {
            send(ex, 404, "no such session\n");
            return;
        }
        int m = Integer.parseInt(parts[1]);
        long hashSeed = parts.length >= 3 ? Long.parseLong(parts[2]) : 0L;
        IbltSketch sketch = IbltSketch.build(s.store, m, hashSeed); // no re-scan: fresh rehash
        send(ex, 200, SketchCodec.encodeBase64(sketch) + "\n");
    }

    private void handleResolveIds(HttpExchange ex) throws Exception {
        String[] lines = readBody(ex).split("\n");
        Session s = sessions.get(lines[0].trim());
        if (s == null) {
            send(ex, 404, "no such session\n");
            return;
        }
        // only a few candidate ids need their pk: pass the wanted set into the scan, match while reading, keep only hits (O(N) one sequential pass, O(d) heap).
        // no longer pulls the whole store back into RAM to build a million-entry HashMap just to resolve ~d ids -- that would replay the OOM the file backend exists to prevent.
        Set<Long> wanted = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                wanted.add(Long.parseLong(line));
            }
        }
        Map<Long, String> found = new HashMap<>();
        s.store.forEachIdPk((id, pk) -> {
            if (wanted.contains(id)) {
                found.put(id, pk);
            }
        });
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String pk = found.get(Long.parseLong(line));
            if (pk != null) {
                out.append(pk).append('\n');
            }
        }
        send(ex, 200, out.toString());
    }

    private void handleRecheck(HttpExchange ex) throws Exception {
        List<String> pks = new ArrayList<>();
        for (String line : readBody(ex).split("\n")) {
            String p = line.trim();
            if (!p.isEmpty()) {
                pks.add(p);
            }
        }
        RecheckResponse rc;
        synchronized (dbLock) {
            rc = service.recheck(new RecheckRequest(recheckQuery, pks));
        }
        // wire: line0 = server IN() point-lookup time (ms), line1 = column names(\t), then one matched row per line(\t).
        StringBuilder out = new StringBuilder().append(rc.queryMillis()).append('\n')
                .append(String.join("\t", rc.columns())).append('\n');
        for (String[] row : rc.rows()) {
            out.append(String.join("\t", row)).append('\n');
        }
        send(ex, 200, out.toString());
    }

    private void handleCloseSessions(HttpExchange ex) throws IOException {
        closeSessions();
        send(ex, 200, "closed\n");
    }

    private void closeSessions() {
        synchronized (dbLock) {
            for (Session session : sessions.values()) {
                session.store.close();
            }
            sessions.clear();
            closeRedisMerkleSessionsLocked();
        }
    }

    private void closeRedisMerkleSessionsLocked() {
        for (RedisMerkleSession session : redisMerkleSessions.values()) {
            session.cache.close();
        }
        redisMerkleSessions.clear();
    }

    // ---- Redis optimized-Merkle benchmark endpoints ----

    /**
     * POST /redis/merkle/checksums, body=bucketCount. Each side scans its local Redis and returns
     * the same stable key-hash checksum vector; no Redis rows cross the container link.
     */
    private void handleRedisMerkleChecksums(HttpExchange ex) throws Exception {
        RedisIbltSidecarService redis = requireRedisService();
        int bucketCount = Integer.parseInt(readBody(ex).trim());
        RedisIbltSidecarService.RedisMerkleVector vector;
        String sessionId;
        synchronized (dbLock) {
            closeRedisMerkleSessionsLocked();
            vector = redis.buildMerkleVector(bucketCount);
            sessionId = "merkle-" + Long.toHexString(seq.incrementAndGet()) + "-"
                    + Long.toHexString(System.nanoTime());
            redisMerkleSessions.put(
                    sessionId, new RedisMerkleSession(vector.cache));
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < vector.checksums.length; i++) {
            out.append(i).append('\t').append(vector.checksums[i].wire()).append('\n');
        }
        ex.getResponseHeaders().set("X-Redis-Compute-Millis", Long.toString(vector.millis));
        ex.getResponseHeaders().set("X-Redis-Scan-Rows", Long.toString(vector.rows));
        ex.getResponseHeaders().set("X-Redis-Buckets", Integer.toString(bucketCount));
        ex.getResponseHeaders().set("X-Redis-Merkle-Session", sessionId);
        ex.getResponseHeaders().set(
                "X-Redis-Merkle-Cache-Shards", Integer.toString(vector.cache.shardCount()));
        send(ex, 200, out.toString());
    }

    /**
     * POST /redis/merkle/rows. Body line 1 is sessionId; remaining lines are dirty bucket ids.
     * Response rows are key<TAB>logical-value-md5, sorted by key.
     */
    private void handleRedisMerkleRows(HttpExchange ex) throws Exception {
        RedisIbltSidecarService redis = requireRedisService();
        String[] lines = readBody(ex).split("\\R");
        if (lines.length < 1 || lines[0].isBlank()) {
            throw new IllegalArgumentException("missing Redis Merkle sessionId");
        }
        String sessionId = lines[0].trim();
        Set<Integer> dirty = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                dirty.add(Integer.parseInt(lines[i].trim()));
            }
        }
        RedisIbltSidecarService.RedisMerkleRows result;
        synchronized (dbLock) {
            RedisMerkleSession session = redisMerkleSessions.get(sessionId);
            if (session == null) {
                send(ex, 404, "no such Redis Merkle session\n");
                return;
            }
            result = redis.readMerkleRows(session.cache, dirty);
        }
        StringBuilder out = new StringBuilder();
        for (RedisIbltSidecarService.RedisMerkleRow row : result.rows) {
            out.append(row.key).append('\t').append(row.digest).append('\n');
        }
        ex.getResponseHeaders().set("X-Redis-Compute-Millis", Long.toString(result.millis));
        ex.getResponseHeaders().set("X-Redis-Drill-Rows", Integer.toString(result.rows.size()));
        ex.getResponseHeaders().set(
                "X-Redis-Cache-Scan-Rows", Long.toString(result.cacheRowsRead));
        send(ex, 200, out.toString());
    }

    private void handleRedisMerkleClose(HttpExchange ex) throws IOException {
        String sessionId = readBody(ex).trim();
        RedisMerkleSession session;
        synchronized (dbLock) {
            session = redisMerkleSessions.remove(sessionId);
            if (session != null) {
                session.cache.close();
            }
        }
        send(ex, 200, session == null ? "already closed\n" : "closed\n");
    }

    private RedisIbltSidecarService requireRedisService() {
        if (!(service instanceof RedisIbltSidecarService)) {
            throw new IllegalStateException("Redis Merkle endpoints require a Redis sidecar");
        }
        return (RedisIbltSidecarService) service;
    }

    // ---- Merkle benchmark endpoints (co-located with IBLT in the same near-data sidecar) ----

    /**
     * POST /merkle/boundaries, body=chunkSize. Called only on the source sidecar; returns each block's
     * hiInclusive, with a final null line marking the open tail interval, so rows on the target beyond
     * the source's max primary key can still be found.
     */
    private void handleMerkleBoundaries(HttpExchange ex) throws Exception {
        requireJdbcMerkle();
        int chunkSize = Integer.parseInt(readBody(ex).trim());
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        StringBuilder out = new StringBuilder();
        Long lo = null;
        Connection conn = borrowMerkleConnection();
        boolean reusable = false;
        try {
            while (true) {
                Long hi = nextBoundary(conn, lo, chunkSize);
                out.append(hi == null ? "null" : hi).append('\n');
                if (hi == null) {
                    break;
                }
                lo = hi;
            }
            reusable = true;
        } finally {
            returnMerkleConnection(conn, reusable);
        }
        send(ex, 200, out.toString());
    }

    /** POST /merkle/checksums, body: one line per interval loExclusive<TAB>hiInclusive (null means unbounded). */
    private void handleMerkleChecksums(HttpExchange ex) throws Exception {
        requireJdbcMerkle();
        List<MerkleRange> ranges = parseRanges(readBody(ex));
        int workers = Math.min(merkleWorkers, ranges.size());
        ChecksumResult[] results = new ChecksumResult[ranges.size()];
        StringBuilder out = new StringBuilder();
        long t0 = System.nanoTime();
        if (workers <= 1) {
            Connection conn = borrowMerkleConnection();
            boolean reusable = false;
            try {
                for (int i = 0; i < ranges.size(); i++) {
                    results[i] = checksum(conn, ranges.get(i));
                }
                reusable = true;
            } finally {
                returnMerkleConnection(conn, reusable);
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                List<Future<Void>> futures = new ArrayList<>(workers);
                for (int workerId = 0; workerId < workers; workerId++) {
                    final int wid = workerId;
                    futures.add(pool.submit(() -> {
                        Connection conn = borrowMerkleConnection();
                        boolean reusable = false;
                        try {
                            for (int i = wid; i < ranges.size(); i += workers) {
                                results[i] = checksum(conn, ranges.get(i));
                            }
                            reusable = true;
                            return null;
                        } finally {
                            returnMerkleConnection(conn, reusable);
                        }
                    }));
                }
                for (Future<Void> future : futures) future.get();
            } finally {
                pool.shutdownNow();
            }
        }
        for (ChecksumResult result : results) {
            out.append(result.sum.stripTrailingZeros().toPlainString())
                    .append('\t').append(result.count).append('\n');
        }
        long ms = Math.round((System.nanoTime() - t0) / 1e6);
        ex.getResponseHeaders().set("X-Merkle-Compute-Millis", Long.toString(ms));
        ex.getResponseHeaders().set("X-Merkle-Blocks", Integer.toString(ranges.size()));
        ex.getResponseHeaders().set("X-Merkle-Workers", Integer.toString(workers));
        send(ex, 200, out.toString());
    }

    private ChecksumResult checksum(Connection conn, MerkleRange range) throws Exception {
        String fpExpr = dialect.fingerprintExpression(fpQuery);
        String sql = "SELECT COALESCE(SUM(CAST(" + fpExpr
                + " AS DECIMAL(38,0))), 0), COUNT(*) FROM " + table
                + " WHERE " + rangeCondition(range);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("checksum returned no row");
            }
            BigDecimal sum = rs.getBigDecimal(1);
            return new ChecksumResult(sum == null ? BigDecimal.ZERO : sum, rs.getLong(2));
        }
    }

    /**
     * POST /merkle/rows, body: the merged dirty intervals. The response streams binary records until EOF:
     * [id:int64][signatureLength:int32][canonicalSignature:utf8]. Does not buffer the whole dirty table.
     */
    private void handleMerkleRows(HttpExchange ex) throws Exception {
        requireJdbcMerkle();
        List<MerkleRange> ranges = parseRanges(readBody(ex));
        String pk = dialect.quoteIdentifier("id");
        String sql = "SELECT " + pk + ", "
                + dialect.quoteIdentifier("value_text") + ", "
                + dialect.quoteIdentifier("amount") + ", "
                + dialect.quoteIdentifier("category") + ", "
                + dialect.quoteIdentifier("updated_at")
                + " FROM " + table + " WHERE " + rangesCondition(ranges)
                + " ORDER BY " + pk + " ASC";
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(200, 0); // chunked, lets a full-table scattered drill stream on a small-memory machine
        Connection conn = borrowMerkleConnection();
        boolean reusable = false;
        try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             DataOutputStream out = new DataOutputStream(ex.getResponseBody())) {
            st.setFetchSize(FETCH);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    byte[] signature = canonicalSignature(rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)).getBytes(StandardCharsets.UTF_8);
                    out.writeLong(id);
                    out.writeInt(signature.length);
                    out.write(signature);
                }
            }
            reusable = true;
        } finally {
            returnMerkleConnection(conn, reusable);
        }
    }

    /** Up to merkleWorkers persistent JDBC connections; while checked out, each is used by exactly one worker. */
    private Connection borrowMerkleConnection() throws Exception {
        Connection pooled = merkleConnectionPool.poll();
        if (pooled != null) return pooled;
        boolean create = false;
        synchronized (merkleConnectionLock) {
            pooled = merkleConnectionPool.poll();
            if (pooled != null) return pooled;
            if (merkleConnectionsCreated < merkleWorkers) {
                merkleConnectionsCreated++;
                create = true;
            }
        }
        if (create) {
            try {
                return DriverManager.getConnection(jdbcUrl);
            } catch (Exception e) {
                synchronized (merkleConnectionLock) { merkleConnectionsCreated--; }
                throw e;
            }
        }
        return merkleConnectionPool.take();
    }

    private void returnMerkleConnection(Connection conn, boolean reusable) {
        if (conn == null) return;
        if (reusable && merkleConnectionPool.offer(conn)) return;
        try { conn.close(); } catch (Exception ignore) { }
        synchronized (merkleConnectionLock) { merkleConnectionsCreated--; }
    }

    private void requireJdbcMerkle() {
        if (jdbcUrl == null) {
            throw new IllegalStateException("Merkle benchmark endpoints currently require a JDBC sidecar");
        }
    }

    private Long nextBoundary(Connection conn, Long lo, int chunkSize) throws Exception {
        String pk = dialect.quoteIdentifier("id");
        String where = lo == null ? "1=1" : pk + " > " + lo;
        final String sql;
        if ("sqlserver".equals(dialect.id())) {
            sql = "SELECT MAX(iblt_pk) FROM (SELECT TOP (" + chunkSize + ") " + pk
                    + " AS iblt_pk FROM " + table + " WHERE " + where
                    + " ORDER BY " + pk + " ASC) merkle_boundary";
        } else {
            sql = "SELECT iblt_pk FROM (SELECT " + pk + " AS iblt_pk FROM " + table
                    + " WHERE " + where + " ORDER BY " + pk + " ASC LIMIT " + chunkSize
                    + ") merkle_boundary ORDER BY iblt_pk DESC LIMIT 1";
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next() || rs.getObject(1) == null) {
                return null;
            }
            return rs.getLong(1);
        }
    }

    private static final class MerkleRange {
        final Long lo;
        final Long hi;
        MerkleRange(Long lo, Long hi) { this.lo = lo; this.hi = hi; }
    }

    private static final class ChecksumResult {
        final BigDecimal sum;
        final long count;
        ChecksumResult(BigDecimal sum, long count) { this.sum = sum; this.count = count; }
    }

    private static List<MerkleRange> parseRanges(String body) {
        List<MerkleRange> out = new ArrayList<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\t", -1);
            if (p.length != 2) throw new IllegalArgumentException("bad range: " + line);
            out.add(new MerkleRange(parseBound(p[0]), parseBound(p[1])));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no ranges");
        return out;
    }

    private static Long parseBound(String s) {
        String v = s.trim();
        return v.isEmpty() || "null".equalsIgnoreCase(v) ? null : Long.parseLong(v);
    }

    private String rangeCondition(MerkleRange r) {
        String pk = dialect.quoteIdentifier("id");
        if (r.lo == null && r.hi == null) return "1=1";
        if (r.lo == null) return pk + " <= " + r.hi;
        if (r.hi == null) return pk + " > " + r.lo;
        return pk + " > " + r.lo + " AND " + pk + " <= " + r.hi;
    }

    private String rangesCondition(List<MerkleRange> ranges) {
        StringBuilder out = new StringBuilder();
        for (MerkleRange r : ranges) {
            if (out.length() > 0) out.append(" OR ");
            out.append('(').append(rangeCondition(r)).append(')');
        }
        return out.toString();
    }

    private static String canonicalSignature(String valueText, String amount, String category, String updatedAt) {
        String v = valueText == null ? "" : valueText.toLowerCase(java.util.Locale.ROOT);
        String a = "";
        if (amount != null && !amount.isEmpty()) {
            a = new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        String c = category == null ? "" : category;
        String t = updatedAt == null ? "" : updatedAt.trim();
        int dot = t.indexOf('.');
        if (dot >= 0) t = t.substring(0, dot);
        if (t.length() > 19) t = t.substring(0, 19);
        return v + '\u001f' + a + '\u001f' + c + '\u001f' + t;
    }

    // ---- HTTP helpers ----

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void route(HttpExchange ex, ThrowingHandler h) {
        try {
            h.handle(ex);
        } catch (Exception e) {
            LOG.warning("[sidecar] " + ex.getRequestURI() + " failed: " + e);
            try {
                send(ex, 500, "error: " + e + "\n");
            } catch (IOException ignore) {
                // client already disconnected
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange ex) throws Exception;
    }

    // ---- startup ----

    private HttpServer start(String bindHost, int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        http.createContext("/health", ex -> route(ex, this::handleHealth));
        http.createContext("/build-sketch", ex -> route(ex, this::handleBuildSketch));
        http.createContext("/rebucket", ex -> route(ex, this::handleRebucket));
        http.createContext("/resolve-ids", ex -> route(ex, this::handleResolveIds));
        http.createContext("/recheck", ex -> route(ex, this::handleRecheck));
        http.createContext("/close-sessions", ex -> route(ex, this::handleCloseSessions));
        http.createContext("/redis/merkle/checksums", ex -> route(ex, this::handleRedisMerkleChecksums));
        http.createContext("/redis/merkle/rows", ex -> route(ex, this::handleRedisMerkleRows));
        http.createContext("/redis/merkle/close", ex -> route(ex, this::handleRedisMerkleClose));
        http.createContext("/merkle/boundaries", ex -> route(ex, this::handleMerkleBoundaries));
        http.createContext("/merkle/checksums", ex -> route(ex, this::handleMerkleChecksums));
        http.createContext("/merkle/rows", ex -> route(ex, this::handleMerkleRows));
        http.setExecutor(Executors.newFixedThreadPool(Math.max(4, merkleWorkers)));
        http.start();
        LOG.info(() -> "[sidecar] listening on " + bindHost + ":" + port
                + " dialect=" + dialect.id() + " table=" + table
                + " scanWorkers=" + scanWorkers + " merkleWorkers=" + merkleWorkers);
        return http;
    }

    /**
     * Startup arguments:
     * <pre>
     *   --port 9090 --bind 127.0.0.1 --dialect mysql|postgres|sqlserver|clickhouse|redis --table items
     *   JDBC engine: --jdbc "jdbc:...."
     *                 [--scan-workers 1] [--merkle-workers 1]
     *   ClickHouse: --ch-url http://localhost:8123 --database iblt_sidecar_a
     *   Redis: --redis-host 127.0.0.1 [--redis-port 6379] [--redis-db 0]
     *          [--redis-password secret] [--redis-scan-count 1000]
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        String bind = a.getOrDefault("--bind", "127.0.0.1");
        int port = Integer.parseInt(a.getOrDefault("--port", "9090"));
        String dialectName = req(a, "--dialect");
        String table = "redis".equalsIgnoreCase(dialectName)
                ? a.getOrDefault("--table", "db" + a.getOrDefault("--redis-db", "0"))
                : req(a, "--table");
        int scanWorkers = positiveInt(a.getOrDefault("--scan-workers", "1"), "--scan-workers");
        int merkleWorkers = positiveInt(a.getOrDefault("--merkle-workers", "1"), "--merkle-workers");

        IbltDialect dialect;
        IbltSidecarService service;
        String jdbcUrl = null;
        switch (dialectName.toLowerCase()) {
            case "clickhouse":
                dialect = new ClickHouseIbltDialect();
                service = new ClickHouseHttpSidecarService(
                        req(a, "--ch-url"), req(a, "--database"), dialect);
                break;
            case "mysql":
                dialect = new MysqlIbltDialect();
                jdbcUrl = req(a, "--jdbc");
                service = jdbc(jdbcUrl, dialect, scanWorkers);
                break;
            case "postgres":
            case "postgresql":
                dialect = new PostgresIbltDialect();
                jdbcUrl = req(a, "--jdbc");
                service = jdbc(jdbcUrl, dialect, scanWorkers);
                break;
            case "oracle":
                dialect = new OracleIbltDialect();
                jdbcUrl = req(a, "--jdbc");
                service = jdbc(jdbcUrl, dialect, scanWorkers);
                break;
            case "sqlserver":
            case "mssql":
                dialect = new SqlServerIbltDialect();
                jdbcUrl = req(a, "--jdbc");
                service = jdbc(jdbcUrl, dialect, scanWorkers);
                break;
            case "redis":
                dialect = new RedisIbltDialect();
                RedisIbltSidecarService redisService = new RedisIbltSidecarService(
                        a.getOrDefault("--redis-host", "127.0.0.1"),
                        positiveInt(a.getOrDefault("--redis-port", "6379"), "--redis-port"),
                        a.getOrDefault("--redis-password", ""),
                        nonNegativeInt(a.getOrDefault("--redis-db", "0"), "--redis-db"),
                        positiveInt(a.getOrDefault("--redis-scan-count", "1000"), "--redis-scan-count"));
                redisService.validateConnection();
                service = redisService;
                break;
            default:
                throw new IllegalArgumentException("unknown --dialect: " + dialectName);
        }
        IbltSidecarServer server =
                new IbltSidecarServer(service, dialect, table, jdbcUrl, scanWorkers, merkleWorkers);
        HttpServer http = server.start(bind, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.closeSessions();
            http.stop(1);
        }, "iblt-sidecar-shutdown"));
        // long-running: park the main thread; process lifecycle is managed by the deployment layer / experiment scripts.
        Thread.currentThread().join();
    }

    private static IbltSidecarService jdbc(String url, IbltDialect dialect, int scanWorkers) throws Exception {
        // at startup, verify the driver, credentials and local DB reachability; actual requests open per-worker connections.
        try (Connection ignored = DriverManager.getConnection(url)) {
            // validation only
        }
        return new LocalIbltSidecarService(url, dialect,
                new FingerprintReadOptions(scanWorkers, FingerprintReadOptions.PgReadMode.JDBC));
    }

    private static int positiveInt(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive");
        return parsed;
    }

    private static int nonNegativeInt(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return parsed;
    }

    private static String req(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required arg: " + key);
        }
        return v;
    }
}

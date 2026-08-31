package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.core.FileFingerprintStore;
import io.github.selfsizing.iblt.core.FingerprintStore;
import io.github.selfsizing.iblt.core.IbltHash;
import io.github.selfsizing.iblt.core.IbltSketch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static io.github.selfsizing.iblt.sidecar.RedisRespClient.bytes;

/**
 * Redis implementation of the independent IBLT sidecar.
 *
 * <p>Each Redis key is one logical row. The key bytes produce the IBLT id; the fingerprint covers
 * the Redis type plus a canonical logical value. TTL is intentionally excluded to match the
 * comparison semantics used in production. Supported types are string, list, set, hash, zset
 * and stream. Keys must be valid UTF-8 without CR/LF/TAB so they remain lossless in the existing
 * line-oriented controller protocol.</p>
 *
 * <p>SCAN is not a transactional snapshot. Formal runs must keep both datasets quiescent while the
 * two sidecars build their sketches.</p>
 */
public final class RedisIbltSidecarService implements IbltSidecarService {

    private static final Logger LOG = Logger.getLogger(RedisIbltSidecarService.class.getName());
    private static final byte[] ZERO = bytes("0");
    private static final Comparator<byte[]> BYTE_ORDER = RedisIbltSidecarService::compareBytes;

    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int scanCount;

    public RedisIbltSidecarService(String host, int port, String password, int database, int scanCount) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.scanCount = Math.max(10, scanCount);
    }

    /** Startup gate: runs only AUTH (if configured), SELECT (if non-zero) and PING; does not read or modify any data key. */
    public void validateConnection() throws IOException {
        try (RedisRespClient redis = new RedisRespClient(host, port, password, database)) {
            Object reply = redis.command(bytes("PING"));
            String pong = new String(bulk(reply, "PING"), StandardCharsets.US_ASCII);
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IOException("unexpected Redis PING reply: " + pong);
            }
        }
    }

    @Override
    public BuildSketchResponse buildSketch(BuildSketchRequest request) throws Exception {
        long t0 = System.nanoTime();
        FingerprintStore store = newStore();
        IbltSketch sketch = new IbltSketch(request.bucketCount(), request.hashSeed());
        long rows = 0;
        try (RedisRespClient redis = new RedisRespClient(host, port, password, database)) {
            byte[] cursor = ZERO;
            do {
                Object reply = redis.command(bytes("SCAN"), cursor, bytes("COUNT"), bytes(Integer.toString(scanCount)));
                List<Object> scan = array(reply, "SCAN");
                cursor = bulk(scan.get(0), "SCAN cursor");
                List<Object> rawKeys = array(scan.get(1), "SCAN keys");
                List<byte[]> keys = new ArrayList<>(rawKeys.size());
                for (Object rawKey : rawKeys) {
                    keys.add(bulk(rawKey, "SCAN key"));
                }
                if (!keys.isEmpty()) {
                    rows += ingestBatch(redis, keys, store, sketch);
                }
            } while (!Arrays.equals(cursor, ZERO));
            store.finishIngest();
        } catch (Exception e) {
            store.close();
            throw e;
        }
        long scanMillis = Math.round((System.nanoTime() - t0) / 1e6);
        SketchMetrics metrics = new SketchMetrics(rows, scanMillis, 0L, request.bucketCount());
        LOG.info(() -> "[iblt] Redis scan/build done: " + metrics);
        return new BuildSketchResponse(sketch, store, metrics);
    }

    private long ingestBatch(RedisRespClient redis, List<byte[]> keys,
                             FingerprintStore store, IbltSketch sketch) throws Exception {
        List<RedisEntry> entries = fetchBatch(redis, keys);
        for (RedisEntry entry : entries) {
            store.add(entry.fp, entry.id, entry.keyText);
            sketch.insert(entry.fp, entry.id);
        }
        return entries.size();
    }

    private List<RedisEntry> fetchBatch(RedisRespClient redis, List<byte[]> keys) throws Exception {
        List<byte[][]> typeCommands = new ArrayList<>(keys.size());
        for (byte[] key : keys) {
            typeCommands.add(new byte[][]{bytes("TYPE"), key});
        }
        List<Object> typeReplies = redis.pipeline(typeCommands);

        List<byte[][]> valueCommands = new ArrayList<>();
        List<Integer> presentIndexes = new ArrayList<>();
        List<String> types = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String type = utf8(bulk(typeReplies.get(i), "TYPE")).toLowerCase(Locale.ROOT);
            types.add(type);
            if (!"none".equals(type)) {
                valueCommands.add(valueCommand(type, keys.get(i)));
                presentIndexes.add(i);
            }
        }
        List<Object> values = redis.pipeline(valueCommands);

        List<RedisEntry> entries = new ArrayList<>(presentIndexes.size());
        for (int j = 0; j < presentIndexes.size(); j++) {
            int i = presentIndexes.get(j);
            byte[] key = keys.get(i);
            String type = types.get(i);
            String keyText = keyText(key);
            byte[] canonical = canonicalValue(type, values.get(j));
            byte[] digest = valueDigest(type, canonical);
            long fp = fingerprint(digest);
            long id = IbltHash.cheap8(key);
            entries.add(new RedisEntry(keyText, digest, fp, id));
        }
        return entries;
    }

    /**
     * Build a near-data Redis Merkle checksum vector. Buckets are stable hash partitions of key
     * bytes, so both sides can build the same vector without sorting or exchanging boundaries.
     */
    RedisMerkleVector buildMerkleVector(int bucketCount) throws Exception {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        RedisMerkleChecksum[] checksums = new RedisMerkleChecksum[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            checksums[i] = new RedisMerkleChecksum();
        }
        RedisMerkleCache cache = newMerkleCache(bucketCount);
        long t0 = System.nanoTime();
        try {
            long rows = scanEntries(entry -> {
                int bucket = bucket(entry.id, bucketCount);
                RedisMerkleChecksum checksum = checksums[bucket];
                checksum.count++;
                checksum.fpXor ^= entry.fp;
                checksum.fpSum += entry.fp;
                checksum.idXor ^= entry.id;
                cache.add(bucket, entry.keyText, entry.digest);
            });
            cache.finishIngest();
            long millis = Math.round((System.nanoTime() - t0) / 1e6);
            LOG.info("[redis-merkle] scan/cache done: rows=" + rows
                    + " buckets=" + bucketCount
                    + " cacheShards=" + cache.shardCount()
                    + " millis=" + millis);
            return new RedisMerkleVector(checksums, rows, millis, cache);
        } catch (Exception e) {
            cache.close();
            throw e;
        }
    }

    /** Read dirty buckets from the materialized first scan; Redis is not touched again. */
    RedisMerkleRows readMerkleRows(RedisMerkleCache cache, Set<Integer> dirtyBuckets)
            throws Exception {
        long t0 = System.nanoTime();
        RedisMerkleCache.ReadResult cached = cache.readBuckets(dirtyBuckets);
        List<RedisMerkleRow> rows = new ArrayList<>();
        for (RedisMerkleCache.Row row : cached.rows) {
            rows.add(new RedisMerkleRow(row.key, row.digest));
        }
        rows.sort((a, b) -> a.key.compareTo(b.key));
        long millis = Math.round((System.nanoTime() - t0) / 1e6);
        LOG.info("[redis-merkle] cache drill done: returnedRows=" + rows.size()
                + " cacheRowsRead=" + cached.rowsRead
                + " millis=" + millis);
        return new RedisMerkleRows(rows, millis, cached.rowsRead);
    }

    private long scanEntries(EntryConsumer consumer) throws Exception {
        long rows = 0;
        try (RedisRespClient redis = new RedisRespClient(host, port, password, database)) {
            byte[] cursor = ZERO;
            do {
                Object reply = redis.command(bytes("SCAN"), cursor, bytes("COUNT"), bytes(Integer.toString(scanCount)));
                List<Object> scan = array(reply, "SCAN");
                cursor = bulk(scan.get(0), "SCAN cursor");
                List<Object> rawKeys = array(scan.get(1), "SCAN keys");
                List<byte[]> keys = new ArrayList<>(rawKeys.size());
                for (Object rawKey : rawKeys) {
                    keys.add(bulk(rawKey, "SCAN key"));
                }
                for (RedisEntry entry : fetchBatch(redis, keys)) {
                    consumer.accept(entry);
                    rows++;
                }
            } while (!Arrays.equals(cursor, ZERO));
        }
        return rows;
    }

    private static int bucket(long id, int bucketCount) {
        return (int) Long.remainderUnsigned(IbltHash.sm64(id), bucketCount);
    }

    @Override
    public RecheckResponse recheck(RecheckRequest request) throws Exception {
        long t0 = System.nanoTime();
        List<String> candidates = request.candidatePks();
        List<String[]> rows = new ArrayList<>();
        if (!candidates.isEmpty()) {
            List<byte[]> keys = new ArrayList<>(candidates.size());
            for (String candidate : candidates) {
                keys.add(candidate.getBytes(StandardCharsets.UTF_8));
            }
            try (RedisRespClient redis = new RedisRespClient(host, port, password, database)) {
                List<byte[][]> typeCommands = new ArrayList<>(keys.size());
                for (byte[] key : keys) {
                    typeCommands.add(new byte[][]{bytes("TYPE"), key});
                }
                List<Object> typeReplies = redis.pipeline(typeCommands);
                List<byte[][]> valueCommands = new ArrayList<>();
                List<Integer> presentIndexes = new ArrayList<>();
                List<String> types = new ArrayList<>(keys.size());
                for (int i = 0; i < keys.size(); i++) {
                    String type = utf8(bulk(typeReplies.get(i), "TYPE")).toLowerCase(Locale.ROOT);
                    types.add(type);
                    if (!"none".equals(type)) {
                        valueCommands.add(valueCommand(type, keys.get(i)));
                        presentIndexes.add(i);
                    }
                }
                List<Object> values = redis.pipeline(valueCommands);
                for (int j = 0; j < presentIndexes.size(); j++) {
                    int i = presentIndexes.get(j);
                    byte[] canonical = canonicalValue(types.get(i), values.get(j));
                    rows.add(new String[]{
                            candidates.get(i),
                            types.get(i),
                            hexMd5(canonical)
                    });
                }
            }
        }
        long queryMillis = Math.round((System.nanoTime() - t0) / 1e6);
        return new RecheckResponse(List.of("iblt_pk", "redis_type", "value_md5"), rows, queryMillis);
    }

    private static byte[][] valueCommand(String type, byte[] key) {
        switch (type) {
            case "string":
                return new byte[][]{bytes("GET"), key};
            case "list":
                return new byte[][]{bytes("LRANGE"), key, ZERO, bytes("-1")};
            case "set":
                return new byte[][]{bytes("SMEMBERS"), key};
            case "hash":
                return new byte[][]{bytes("HGETALL"), key};
            case "zset":
                return new byte[][]{bytes("ZRANGE"), key, ZERO, bytes("-1"), bytes("WITHSCORES")};
            case "stream":
                return new byte[][]{bytes("XRANGE"), key, bytes("-"), bytes("+")};
            default:
                throw new IllegalArgumentException("unsupported Redis type: " + type);
        }
    }

    private static byte[] canonicalValue(String type, Object reply) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeBytes(out, type.getBytes(StandardCharsets.US_ASCII));
            switch (type) {
                case "string":
                    writeObject(out, reply);
                    break;
                case "list":
                case "zset":
                case "stream":
                    writeObject(out, reply);
                    break;
                case "set": {
                    List<byte[]> members = flatBulkArray(reply, "SMEMBERS");
                    members.sort(BYTE_ORDER);
                    writeByteList(out, members);
                    break;
                }
                case "hash": {
                    List<byte[]> flat = flatBulkArray(reply, "HGETALL");
                    if ((flat.size() & 1) != 0) {
                        throw new IOException("HGETALL returned odd element count");
                    }
                    List<byte[][]> pairs = new ArrayList<>(flat.size() / 2);
                    for (int i = 0; i < flat.size(); i += 2) {
                        pairs.add(new byte[][]{flat.get(i), flat.get(i + 1)});
                    }
                    pairs.sort((a, b) -> {
                        int c = compareBytes(a[0], b[0]);
                        return c != 0 ? c : compareBytes(a[1], b[1]);
                    });
                    out.writeInt(pairs.size());
                    for (byte[][] pair : pairs) {
                        writeBytes(out, pair[0]);
                        writeBytes(out, pair[1]);
                    }
                    break;
                }
                default:
                    throw new IOException("unsupported Redis type: " + type);
            }
        }
        return bytes.toByteArray();
    }

    private static void writeObject(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(0);
        } else if (value instanceof byte[]) {
            out.writeByte(1);
            writeBytes(out, (byte[]) value);
        } else if (value instanceof Long) {
            out.writeByte(2);
            out.writeLong((Long) value);
        } else if (value instanceof List) {
            out.writeByte(3);
            List<?> list = (List<?>) value;
            out.writeInt(list.size());
            for (Object item : list) {
                writeObject(out, item);
            }
        } else {
            throw new IOException("unsupported RESP value: " + value.getClass());
        }
    }

    private static void writeByteList(DataOutputStream out, List<byte[]> values) throws IOException {
        out.writeInt(values.size());
        for (byte[] value : values) {
            writeBytes(out, value);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static long fingerprint(byte[] digest) {
        long fp = 0;
        for (int i = 0; i < 7; i++) {
            fp = (fp << 8) | (digest[i] & 0xffL);
        }
        return fp;
    }

    private static String hexMd5(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value);
        StringBuilder out = new StringBuilder(32);
        for (byte b : digest) {
            out.append(Character.forDigit((b >>> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static byte[] valueDigest(String type, byte[] canonical) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(type.getBytes(StandardCharsets.US_ASCII));
        md5.update((byte) 0);
        return md5.digest(canonical);
    }

    private static FingerprintStore newStore() throws IOException {
        String mode = System.getenv().getOrDefault("FP_STORE", "file");
        if ("memory".equalsIgnoreCase(mode)) {
            return new io.github.selfsizing.iblt.core.MemoryFingerprintStore();
        }
        Path directory = Path.of(System.getenv().getOrDefault("FP_STORE_DIR", "/tmp/iblt-fpstore"));
        Files.createDirectories(directory);
        Path base = directory.resolve("redis-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        int bufferMb = Integer.parseInt(System.getenv().getOrDefault("FP_STORE_BUF_MB", "16"));
        return new FileFingerprintStore(base.toString(), Math.max(1, bufferMb) << 20);
    }

    private static RedisMerkleCache newMerkleCache(int bucketCount) throws IOException {
        Path directory = Path.of(System.getenv().getOrDefault(
                "MERKLE_CACHE_DIR",
                System.getenv().getOrDefault("FP_STORE_DIR", "/tmp/iblt-fpstore")));
        int shards = Integer.parseInt(
                System.getenv().getOrDefault("MERKLE_CACHE_SHARDS", "64"));
        int bufferMb = Integer.parseInt(System.getenv().getOrDefault(
                "MERKLE_CACHE_BUF_MB",
                System.getenv().getOrDefault("FP_STORE_BUF_MB", "16")));
        String prefix = "redis-merkle-" + ProcessHandle.current().pid() + "-"
                + System.nanoTime();
        return new RedisMerkleCache(
                directory, prefix, bucketCount, Math.max(1, shards),
                Math.max(1, bufferMb) << 20);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String context) throws IOException {
        if (!(value instanceof List)) {
            throw new IOException(context + " did not return an array");
        }
        return (List<Object>) value;
    }

    private static byte[] bulk(Object value, String context) throws IOException {
        if (!(value instanceof byte[])) {
            throw new IOException(context + " did not return a bulk string");
        }
        return (byte[]) value;
    }

    private static List<byte[]> flatBulkArray(Object value, String context) throws IOException {
        List<Object> raw = array(value, context);
        List<byte[]> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            result.add(bulk(item, context + " element"));
        }
        return result;
    }

    private static String keyText(byte[] key) throws CharacterCodingException {
        String value = utf8(key);
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("Redis keys containing CR/LF/TAB are not supported by the line protocol");
        }
        return value;
    }

    private static String utf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(value)).toString();
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(a[i] & 0xff, b[i] & 0xff);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static final class RedisEntry {
        final String keyText;
        final byte[] digest;
        final long fp;
        final long id;

        RedisEntry(String keyText, byte[] digest, long fp, long id) {
            this.keyText = keyText;
            this.digest = digest;
            this.fp = fp;
            this.id = id;
        }
    }

    @FunctionalInterface
    private interface EntryConsumer {
        void accept(RedisEntry entry) throws Exception;
    }

    static final class RedisMerkleChecksum {
        long count;
        long fpXor;
        long fpSum;
        long idXor;

        String wire() {
            return count + "\t" + Long.toUnsignedString(fpXor) + "\t"
                    + Long.toUnsignedString(fpSum) + "\t" + Long.toUnsignedString(idXor);
        }
    }

    static final class RedisMerkleVector {
        final RedisMerkleChecksum[] checksums;
        final long rows;
        final long millis;
        final RedisMerkleCache cache;

        RedisMerkleVector(RedisMerkleChecksum[] checksums, long rows, long millis,
                          RedisMerkleCache cache) {
            this.checksums = checksums;
            this.rows = rows;
            this.millis = millis;
            this.cache = cache;
        }
    }

    static final class RedisMerkleRow {
        final String key;
        final String digest;

        RedisMerkleRow(String key, String digest) {
            this.key = key;
            this.digest = digest;
        }
    }

    static final class RedisMerkleRows {
        final List<RedisMerkleRow> rows;
        final long millis;
        final long cacheRowsRead;

        RedisMerkleRows(List<RedisMerkleRow> rows, long millis, long cacheRowsRead) {
            this.rows = rows;
            this.millis = millis;
            this.cacheRowsRead = cacheRowsRead;
        }
    }
}

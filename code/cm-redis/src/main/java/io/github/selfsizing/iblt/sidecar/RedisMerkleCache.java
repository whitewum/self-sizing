package io.github.selfsizing.iblt.sidecar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Disk-backed materialization of one Redis Merkle scan.
 *
 * <p>Rows are split across a bounded number of shard files by Merkle bucket. A dirty-bucket drill
 * therefore reads only the relevant shard files and never rescans Redis. Each record preserves the
 * full logical-value MD5 used by the original row protocol.</p>
 */
final class RedisMerkleCache implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RedisMerkleCache.class.getName());
    private static final int DIGEST_BYTES = 16;
    private static final int READ_BUFFER = 4 << 20;
    private static final int MIN_WRITE_BUFFER = 64 << 10;
    private static final int MAX_KEY_BYTES = 64 << 20;

    private final Path[] shardPaths;
    private final DataOutputStream[] shardOutputs;
    private final int bucketCount;
    private final int shardCount;
    private final int writeBufferBytes;
    private long rows;
    private boolean finished;
    private boolean closed;

    RedisMerkleCache(Path directory, String prefix, int bucketCount,
                     int requestedShards, int totalWriteBufferBytes) throws IOException {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        Files.createDirectories(directory);
        this.bucketCount = bucketCount;
        this.shardCount = Math.max(1, Math.min(bucketCount, requestedShards));
        this.writeBufferBytes = Math.max(
                MIN_WRITE_BUFFER, totalWriteBufferBytes / this.shardCount);
        this.shardPaths = new Path[this.shardCount];
        this.shardOutputs = new DataOutputStream[this.shardCount];
        for (int i = 0; i < this.shardCount; i++) {
            shardPaths[i] = directory.resolve(prefix + ".merkle-" + i + ".bin");
        }
    }

    void add(int bucket, String key, byte[] digest) throws IOException {
        ensureWritable();
        validateBucket(bucket);
        if (digest.length != DIGEST_BYTES) {
            throw new IllegalArgumentException("Merkle digest must be 16 bytes");
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        DataOutputStream out = outputFor(shard(bucket));
        out.writeInt(bucket);
        out.writeInt(keyBytes.length);
        out.write(keyBytes);
        out.write(digest);
        rows++;
    }

    void finishIngest() throws IOException {
        if (closed || finished) {
            return;
        }
        IOException failure = null;
        for (int i = 0; i < shardOutputs.length; i++) {
            DataOutputStream out = shardOutputs[i];
            if (out == null) {
                continue;
            }
            try {
                out.flush();
                out.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            } finally {
                shardOutputs[i] = null;
            }
        }
        finished = true;
        if (failure != null) {
            throw failure;
        }
    }

    ReadResult readBuckets(Set<Integer> dirtyBuckets) throws IOException {
        ensureReadable();
        Set<Integer> wanted = new HashSet<>(dirtyBuckets);
        Set<Integer> wantedShards = new HashSet<>();
        for (int bucket : wanted) {
            validateBucket(bucket);
            wantedShards.add(shard(bucket));
        }

        List<Row> matched = new ArrayList<>();
        long rowsRead = 0;
        for (int shard : wantedShards) {
            Path path = shardPaths[shard];
            if (!Files.exists(path)) {
                continue;
            }
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(path), READ_BUFFER))) {
                while (true) {
                    int bucket;
                    try {
                        bucket = in.readInt();
                    } catch (EOFException eof) {
                        break;
                    }
                    int keyLength = in.readInt();
                    if (keyLength < 0 || keyLength > MAX_KEY_BYTES) {
                        throw new IOException("invalid Redis Merkle cache key length: " + keyLength);
                    }
                    rowsRead++;
                    if (wanted.contains(bucket)) {
                        byte[] key = new byte[keyLength];
                        in.readFully(key);
                        byte[] digest = new byte[DIGEST_BYTES];
                        in.readFully(digest);
                        matched.add(new Row(
                                new String(key, StandardCharsets.UTF_8), hex(digest)));
                    } else {
                        in.skipNBytes((long) keyLength + DIGEST_BYTES);
                    }
                }
            }
        }
        return new ReadResult(matched, rowsRead);
    }

    int bucketCount() {
        return bucketCount;
    }

    long size() {
        return rows;
    }

    int shardCount() {
        return shardCount;
    }

    private DataOutputStream outputFor(int shard) throws IOException {
        DataOutputStream out = shardOutputs[shard];
        if (out == null) {
            out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(shardPaths[shard],
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE),
                    writeBufferBytes));
            shardOutputs[shard] = out;
        }
        return out;
    }

    private int shard(int bucket) {
        return bucket % shardCount;
    }

    private void validateBucket(int bucket) {
        if (bucket < 0 || bucket >= bucketCount) {
            throw new IllegalArgumentException("dirty bucket out of range: " + bucket);
        }
    }

    private void ensureWritable() {
        if (closed || finished) {
            throw new IllegalStateException("Redis Merkle cache is not writable");
        }
    }

    private void ensureReadable() {
        if (closed) {
            throw new IllegalStateException("Redis Merkle cache is closed");
        }
        if (!finished) {
            throw new IllegalStateException("Redis Merkle cache ingest is not finished");
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >>> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            finishIngest();
        } catch (IOException e) {
            LOG.warning("[redis-merkle] cache flush error: " + e.getMessage());
        }
        closed = true;
        for (Path path : shardPaths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warning("[redis-merkle] delete temp file " + path + " failed: " + e.getMessage());
            }
        }
    }

    static final class Row {
        final String key;
        final String digest;

        Row(String key, String digest) {
            this.key = key;
            this.digest = digest;
        }
    }

    static final class ReadResult {
        final List<Row> rows;
        final long rowsRead;

        ReadResult(List<Row> rows, long rowsRead) {
            this.rows = rows;
            this.rowsRead = rowsRead;
        }
    }
}

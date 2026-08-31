package io.github.selfsizing.iblt.sidecar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Dependency-free regression test for the disk-backed Redis Merkle cache. */
public final class RedisMerkleCacheTest {

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("redis-merkle-cache-test-");
        RedisMerkleCache cache =
                new RedisMerkleCache(directory, "case", 16, 4, 256 << 10);
        try {
            cache.add(1, "key-a", digest(0x11));
            cache.add(5, "same-shard-but-clean", digest(0x22));
            cache.add(9, "key-b", digest(0x33));
            cache.add(2, "other-shard", digest(0x44));
            cache.finishIngest();

            RedisMerkleCache.ReadResult result = cache.readBuckets(Set.of(1, 9));
            Map<String, String> rows = result.rows.stream()
                    .collect(Collectors.toMap(row -> row.key, row -> row.digest));

            check(cache.size() == 4, "cache size");
            check(cache.shardCount() == 4, "shard count");
            check(result.rowsRead == 3, "only the selected shard should be scanned");
            check(rows.size() == 2, "only dirty buckets should be returned");
            check(rows.get("key-a").equals("11111111111111111111111111111111"),
                    "key-a digest");
            check(rows.get("key-b").equals("33333333333333333333333333333333"),
                    "key-b digest");
            check(!rows.containsKey("same-shard-but-clean"),
                    "clean bucket in selected shard must be filtered");
        } finally {
            cache.close();
        }

        try (var files = Files.list(directory)) {
            check(files.findAny().isEmpty(), "close should delete cache shard files");
        }
        Files.delete(directory);
        System.out.println("RedisMerkleCacheTest: PASS");
    }

    private static byte[] digest(int value) {
        byte[] digest = new byte[16];
        java.util.Arrays.fill(digest, (byte) value);
        return digest;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private RedisMerkleCacheTest() {
    }
}

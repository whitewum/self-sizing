package io.github.selfsizing.iblt.sidecar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.selfsizing.iblt.sidecar.RedisRespClient.bytes;

/**
 * Deterministic two-sided Redis benchmark generator.
 *
 * <p>Each invocation prepares one side. Run source and target with the same rows/diff values.
 * The source invocation also writes the exact truth key list. Besides scalable string keys, both
 * sides receive equal logical values of every supported Redis type; set/hash/zset insertion order
 * is deliberately reversed on the target to gate canonicalization rather than physical encoding.</p>
 */
public final class RedisBenchDataGen {

    private static final int PIPELINE_BATCH = 2_000;

    private RedisBenchDataGen() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8 || !"--confirm-flushdb".equals(args[7])) {
            System.err.println("usage: RedisBenchDataGen <host> <port> <db> <rows> <diff> "
                    + "<source|target> <truthFile> --confirm-flushdb");
            System.err.println("refusing to run: this test-data generator executes FLUSHDB");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int db = Integer.parseInt(args[2]);
        long rows = Long.parseLong(args[3]);
        long diff = Long.parseLong(args[4]);
        boolean source = "source".equalsIgnoreCase(args[5]);
        if (!source && !"target".equalsIgnoreCase(args[5])) {
            throw new IllegalArgumentException("role must be source or target");
        }
        if (rows <= 0 || diff < 0 || diff >= rows) {
            throw new IllegalArgumentException("require rows>0 and 0<=diff<rows");
        }

        long modified = diff / 2;
        long sourceOnly = (diff - modified) / 2;
        long targetOnly = diff - modified - sourceOnly;
        long sourceOnlyEnd = modified + sourceOnly;
        long targetOnlyEnd = sourceOnlyEnd + targetOnly;

        List<String> truth = source ? new ArrayList<>((int) Math.min(diff, Integer.MAX_VALUE)) : null;
        long inserted = 0;
        try (RedisRespClient redis = new RedisRespClient(host, port, "", db)) {
            redis.command(bytes("FLUSHDB"));
            List<byte[][]> batch = new ArrayList<>(PIPELINE_BATCH);
            for (long i = 0; i < rows; i++) {
                String key = key(i);
                if (source && i >= sourceOnlyEnd && i < targetOnlyEnd) {
                    continue;
                }
                if (!source && i >= modified && i < sourceOnlyEnd) {
                    continue;
                }
                String value;
                if (i < modified) {
                    value = source ? "modified-source:" + i : "modified-target:" + i;
                } else {
                    value = "same:" + i;
                }
                batch.add(new byte[][]{bytes("SET"), bytes(key), bytes(value)});
                inserted++;
                if (batch.size() == PIPELINE_BATCH) {
                    redis.pipeline(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redis.pipeline(batch);
            }
            addCanonicalizationGate(redis, source);
        }

        if (source) {
            for (long i = 0; i < diff; i++) {
                truth.add(key(i));
            }
            Path truthPath = Path.of(args[6]);
            Files.createDirectories(truthPath.getParent());
            Files.write(truthPath, truth, StandardCharsets.UTF_8);
        }
        System.out.printf("role=%s rowsRequested=%d insertedStrings=%d commonMixed=6 diff=%d%n",
                source ? "source" : "target", rows, inserted, diff);
    }

    private static void addCanonicalizationGate(RedisRespClient redis, boolean source) throws Exception {
        redis.command(bytes("SET"), bytes("mixed:string"), bytes("hello"));
        redis.command(bytes("RPUSH"), bytes("mixed:list"), bytes("a"), bytes("b"), bytes("c"));
        if (source) {
            redis.command(bytes("SADD"), bytes("mixed:set"), bytes("a"), bytes("b"), bytes("c"));
            redis.command(bytes("HSET"), bytes("mixed:hash"),
                    bytes("a"), bytes("1"), bytes("b"), bytes("2"), bytes("c"), bytes("3"));
            redis.command(bytes("ZADD"), bytes("mixed:zset"),
                    bytes("1"), bytes("a"), bytes("2"), bytes("b"), bytes("3"), bytes("c"));
        } else {
            redis.command(bytes("SADD"), bytes("mixed:set"), bytes("c"), bytes("b"), bytes("a"));
            redis.command(bytes("HSET"), bytes("mixed:hash"),
                    bytes("c"), bytes("3"), bytes("b"), bytes("2"), bytes("a"), bytes("1"));
            redis.command(bytes("ZADD"), bytes("mixed:zset"),
                    bytes("3"), bytes("c"), bytes("2"), bytes("b"), bytes("1"), bytes("a"));
        }
        redis.command(bytes("XADD"), bytes("mixed:stream"), bytes("1000-0"),
                bytes("field"), bytes("value"));
    }

    private static String key(long i) {
        return String.format("k:%012d", i);
    }
}

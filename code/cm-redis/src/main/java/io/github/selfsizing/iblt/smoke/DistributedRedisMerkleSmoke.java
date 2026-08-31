package io.github.selfsizing.iblt.smoke;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Controller for B=optimized-merkle-sidecar on Redis.
 *
 * <p>Both sidecars build a stable key-hash checksum vector near their local Redis. The controller
 * compares vectors, requests rows only for dirty buckets, then exactly joins key/value digests and
 * checks the resulting keys against ground truth.</p>
 */
public final class DistributedRedisMerkleSmoke {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: DistributedRedisMerkleSmoke <urlA> <urlB> <bucketCount> "
                    + "[truthKeys] [--truth <file>] [--expect-equal]");
            System.exit(2);
        }
        Path truthPath = null;
        boolean expectEqual = false;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--truth":
                    if (++i >= args.length) throw new IllegalArgumentException("--truth requires a file");
                    truthPath = Path.of(args[i]);
                    break;
                case "--expect-equal":
                    expectEqual = true;
                    break;
                default:
                    if (truthPath == null) {
                        truthPath = Path.of(args[i]);
                    } else {
                        throw new IllegalArgumentException("unknown argument: " + args[i]);
                    }
            }
        }
        int exit = new DistributedRedisMerkleSmoke().run(
                args[0], args[1], Integer.parseInt(args[2]), truthPath, expectEqual);
        System.exit(exit);
    }

    private int run(String urlA, String urlB, int bucketCount, Path truthPath,
                    boolean expectEqual) throws Exception {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        long totalStart = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String sessionA = null;
        String sessionB = null;
        try {
            long checksumStart = System.nanoTime();
            Future<Response> vectorAF = pool.submit(() -> post(urlA, "/redis/merkle/checksums",
                    Integer.toString(bucketCount)));
            Future<Response> vectorBF = pool.submit(() -> post(urlB, "/redis/merkle/checksums",
                    Integer.toString(bucketCount)));
            Response vectorA = vectorAF.get();
            sessionA = vectorA.headerRequired("X-Redis-Merkle-Session");
            Response vectorB = vectorBF.get();
            sessionB = vectorB.headerRequired("X-Redis-Merkle-Session");
            long checksumWall = millisSince(checksumStart);

            Map<Integer, String> checksumsA = parseVector(vectorA.body);
            Map<Integer, String> checksumsB = parseVector(vectorB.body);
            List<Integer> dirty = new ArrayList<>();
            for (int i = 0; i < bucketCount; i++) {
                if (!checksumsA.get(i).equals(checksumsB.get(i))) {
                    dirty.add(i);
                }
            }

            long drillWall = 0;
            Response rowsA = Response.empty();
            Response rowsB = Response.empty();
            if (!dirty.isEmpty()) {
                StringBuilder dirtyBody = new StringBuilder();
                for (int bucket : dirty) {
                    dirtyBody.append(bucket).append('\n');
                }
                long drillStart = System.nanoTime();
                String requestBodyA = sessionA + "\n" + dirtyBody;
                String requestBodyB = sessionB + "\n" + dirtyBody;
                Future<Response> rowsAF =
                        pool.submit(() -> post(urlA, "/redis/merkle/rows", requestBodyA));
                Future<Response> rowsBF =
                        pool.submit(() -> post(urlB, "/redis/merkle/rows", requestBodyB));
                rowsA = rowsAF.get();
                rowsB = rowsBF.get();
                drillWall = millisSince(drillStart);
            }

            Map<String, String> mapA = parseRows(rowsA.body);
            Map<String, String> mapB = parseRows(rowsB.body);
            Set<String> keys = new HashSet<>(mapA.keySet());
            keys.addAll(mapB.keySet());
            TreeSet<String> differences = new TreeSet<>();
            for (String key : keys) {
                if (!java.util.Objects.equals(mapA.get(key), mapB.get(key))) {
                    differences.add(key);
                }
            }

            TreeSet<String> truth = null;
            boolean match = true;
            if (truthPath != null) {
                truth = new TreeSet<>();
                for (String line : Files.readAllLines(truthPath, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        truth.add(line.trim());
                    }
                }
                match = differences.equals(truth);
            } else if (expectEqual) {
                match = differences.isEmpty();
            }
            long total = millisSince(totalStart);
            long payloadB = vectorB.body.getBytes(StandardCharsets.UTF_8).length
                    + rowsB.body.getBytes(StandardCharsets.UTF_8).length;

            System.out.printf("[redis-merkle] buckets=%d dirtyBuckets=%d checksum wall=%dms "
                            + "serverA=%dms serverB=%dms scanRowsA=%d scanRowsB=%d%n",
                    bucketCount, dirty.size(), checksumWall,
                    vectorA.headerLong("X-Redis-Compute-Millis"),
                    vectorB.headerLong("X-Redis-Compute-Millis"),
                    vectorA.headerLong("X-Redis-Scan-Rows"),
                    vectorB.headerLong("X-Redis-Scan-Rows"));
            System.out.printf("[redis-merkle] drill wall=%dms rowsA=%d rowsB=%d "
                            + "serverA=%dms serverB=%dms cacheScanA=%d cacheScanB=%d%n",
                    drillWall, mapA.size(), mapB.size(),
                    rowsA.headerLong("X-Redis-Compute-Millis"),
                    rowsB.headerLong("X-Redis-Compute-Millis"),
                    rowsA.headerLong("X-Redis-Cache-Scan-Rows"),
                    rowsB.headerLong("X-Redis-Cache-Scan-Rows"));
            if (truth != null) {
                System.out.printf("[redis-merkle] diffKeys=%d truth=%d match=%b%n",
                        differences.size(), truth.size(), match);
            } else if (expectEqual) {
                System.out.printf("[redis-merkle] diffKeys=%d expectEqual=true match=%b%n",
                        differences.size(), match);
            } else {
                System.out.printf("[redis-merkle] diffKeys=%d%n", differences.size());
            }
            System.out.println("[redis-merkle] differences=" + differences);
            System.out.printf("[redis-merkle] remotePayloadB=%dB controllerEndToEnd=%dms%n",
                    payloadB, total);
            System.out.println(match ? "[redis-merkle] RESULT: PASS" : "[redis-merkle] RESULT: FAIL");
            if (!match) {
                if (truth != null) {
                    System.out.println("[redis-merkle] expected=" + truth);
                }
            }
            return match ? 0 : 1;
        } finally {
            closeMerkleSessionQuietly(urlA, sessionA);
            closeMerkleSessionQuietly(urlB, sessionB);
            pool.shutdownNow();
        }
    }

    private void closeMerkleSessionQuietly(String baseUrl, String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            post(baseUrl, "/redis/merkle/close", sessionId);
        } catch (Exception e) {
            System.err.println("[redis-merkle] close session failed for " + baseUrl + ": " + e);
        }
    }

    private Response post(String baseUrl, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(path + " HTTP " + response.statusCode() + ": " + response.body());
        }
        return new Response(response.body(), response);
    }

    private static Map<Integer, String> parseVector(String body) {
        Map<Integer, String> result = new HashMap<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", 2);
            result.put(Integer.parseInt(parts[0]), parts[1]);
        }
        return result;
    }

    private static Map<String, String> parseRows(String body) {
        Map<String, String> result = new HashMap<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", 2);
            result.put(parts[0], parts[1]);
        }
        return result;
    }

    private static long millisSince(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1e6);
    }

    private static final class Response {
        final String body;
        final HttpResponse<String> response;

        Response(String body, HttpResponse<String> response) {
            this.body = body;
            this.response = response;
        }

        static Response empty() {
            return new Response("", null);
        }

        long headerLong(String name) {
            if (response == null) {
                return 0;
            }
            return response.headers().firstValue(name).map(Long::parseLong).orElse(0L);
        }

        String headerRequired(String name) {
            if (response == null) {
                throw new IllegalStateException("missing HTTP response for header " + name);
            }
            return response.headers().firstValue(name)
                    .orElseThrow(() -> new IllegalStateException(
                            "sidecar response missing required header " + name));
        }
    }

    private DistributedRedisMerkleSmoke() {
    }
}

package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.controller.SidecarClient;
import io.github.selfsizing.iblt.controller.SidecarClient.BuildSketchRemote;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.core.SketchCodec;
import io.github.selfsizing.iblt.sidecar.RecheckResponse;
import io.github.selfsizing.iblt.sidecar.SketchMetrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** HTTP sidecar client overlay with a configurable request timeout. */
public final class HttpSidecarClient implements SidecarClient, StreamingResolveClient {
    public static final String TIMEOUT_PROPERTY = "e29.http.request.timeout.seconds";
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    private final String label;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration requestTimeout;
    private volatile long lastPartitionMs;

    public HttpSidecarClient(String label, String baseUrl) {
        this(label, baseUrl, configuredRequestTimeout());
    }

    HttpSidecarClient(String label, String baseUrl, Duration requestTimeout) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("request timeout must be positive: " + requestTimeout);
        }
        this.label = label;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public static Duration configuredRequestTimeout() {
        String raw = System.getProperty(TIMEOUT_PROPERTY, Long.toString(DEFAULT_TIMEOUT_SECONDS));
        try {
            long seconds = Long.parseLong(raw);
            if (seconds <= 0) throw new NumberFormatException("not positive");
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(TIMEOUT_PROPERTY + " must be a positive integer: " + raw, e);
        }
    }

    @Override
    public String label() { return label; }

    @Override
    public String health() throws Exception { return post("/health", "").trim(); }

    @Override
    public BuildSketchRemote buildSketch(int bucketCount, long hashSeed) throws Exception {
        long t0 = System.nanoTime();
        String body = post("/build-sketch", bucketCount + " " + hashSeed);
        long rtt = Math.round((System.nanoTime() - t0) / 1e6);
        String[] lines = body.split("\n", 3);
        String sessionId = lines[0].trim();
        String[] m = lines[1].trim().split("\\s+");
        SketchMetrics metrics = new SketchMetrics(
                Long.parseLong(m[0]), Long.parseLong(m[1]), Long.parseLong(m[2]), Integer.parseInt(m[3]));
        int wireBytes = Integer.parseInt(m[4]);
        // Newer composite sidecars append shard/fence preparation time. Older sidecars
        // legitimately omit it, so retain a zero-compatible fallback.
        lastPartitionMs = m.length > 5 ? Long.parseLong(m[5]) : 0L;
        IbltSketch sketch = SketchCodec.decodeBase64(lines[2].trim());
        return new BuildSketchRemote(sessionId, sketch, metrics, wireBytes, rtt);
    }

    @Override
    public IbltSketch rebucket(String sessionId, int bucketCount, long hashSeed) throws Exception {
        return SketchCodec.decodeBase64(post("/rebucket", sessionId + " " + bucketCount + " " + hashSeed).trim());
    }

    @Override
    public List<String> resolveIds(String sessionId, long[] ids) throws Exception {
        List<String> pks = new ArrayList<>();
        resolveIdsStream(sessionId, ids, pks::add);
        return pks;
    }

    @Override
    public void resolveIdsStream(String sessionId, long[] ids, StreamingResolveClient.PkConsumer pkConsumer)
            throws Exception {
        StringBuilder req = new StringBuilder(sessionId).append('\n');
        for (long id : ids) req.append(id).append('\n');
        HttpResponse<InputStream> response = postStream("/resolve-ids", req.toString());
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(StreamingResolveClient.ERROR_PREFIX)) {
                    throw new IOException(label + " /resolve-ids failed: "
                            + line.substring(StreamingResolveClient.ERROR_PREFIX.length()));
                }
                String pk = line.trim();
                if (!pk.isEmpty()) pkConsumer.accept(pk);
            }
        }
    }

    @Override
    public RecheckResponse recheck(List<String> pks) throws Exception {
        long t0 = System.nanoTime();
        if (pks.isEmpty()) return new RecheckResponse(List.of(), List.of(), 0L);
        String[] lines = post("/recheck", String.join("\n", pks)).split("\n");
        long rtt = Math.round((System.nanoTime() - t0) / 1e6);
        long serverMs = rtt;
        if (lines.length > 0) {
            try { serverMs = Long.parseLong(lines[0].trim()); }
            catch (NumberFormatException ignored) { }
        }
        List<String> columns = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        if (lines.length > 1 && !lines[1].isEmpty()) {
            for (String column : lines[1].split("\t", -1)) columns.add(column);
        }
        for (int i = 2; i < lines.length; i++) {
            if (!lines[i].isEmpty()) rows.add(lines[i].split("\t", -1));
        }
        return new RecheckResponse(columns, rows, serverMs);
    }

    @Override
    public void closeSessions() throws Exception { post("/close-sessions", ""); }

    /** Server-side time used to derive ROWID/key-range shard predicates for the last build. */
    public long lastPartitionMs() { return lastPartitionMs; }

    private String post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(label + " " + path + " http " + response.statusCode()
                    + ": " + response.body());
        }
        return response.body();
    }

    private HttpResponse<InputStream> postStream(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = http.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream in = response.body()) {
                String error = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException(label + " " + path + " http "
                        + response.statusCode() + ": " + error);
            }
        }
        return response;
    }
}

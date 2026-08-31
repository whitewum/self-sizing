package io.github.selfsizing.iblt.controller;

import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.core.SketchCodec;
import io.github.selfsizing.iblt.sidecar.RecheckResponse;
import io.github.selfsizing.iblt.sidecar.SketchMetrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP implementation of {@link SidecarClient} (pure JDK {@code java.net.http}, zero third-party dependencies).
 *
 * <p>{@code baseUrl} points at that side's sidecar; across machines it points at
 * a local {@code ssh -L} tunnel port (e.g. {@code http://127.0.0.1:19011}), with
 * a sidecar bound to {@code 127.0.0.1} on the far end of the tunnel. Uses the
 * text-line protocol of {@link IbltSidecarServer}.</p>
 */
public final class HttpSidecarClient implements SidecarClient {

    private final String label;
    private final String baseUrl;
    private final HttpClient http;

    public HttpSidecarClient(String label, String baseUrl) {
        this.label = label;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String health() throws Exception {
        return post("/health", "").trim();
    }

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
        IbltSketch sketch = SketchCodec.decodeBase64(lines[2].trim());
        return new BuildSketchRemote(sessionId, sketch, metrics, wireBytes, rtt);
    }

    @Override
    public IbltSketch rebucket(String sessionId, int bucketCount, long hashSeed) throws Exception {
        String body = post("/rebucket", sessionId + " " + bucketCount + " " + hashSeed);
        return SketchCodec.decodeBase64(body.trim());
    }

    @Override
    public List<String> resolveIds(String sessionId, long[] ids) throws Exception {
        StringBuilder req = new StringBuilder(sessionId).append('\n');
        for (long id : ids) {
            req.append(id).append('\n');
        }
        String body = post("/resolve-ids", req.toString());
        List<String> pks = new ArrayList<>();
        for (String line : body.split("\n")) {
            String p = line.trim();
            if (!p.isEmpty()) {
                pks.add(p);
            }
        }
        return pks;
    }

    @Override
    public RecheckResponse recheck(List<String> pks) throws Exception {
        long t0 = System.nanoTime();
        if (pks.isEmpty()) {
            return new RecheckResponse(List.of(), List.of(), 0L);
        }
        String body = post("/recheck", String.join("\n", pks));
        long rtt = Math.round((System.nanoTime() - t0) / 1e6);
        String[] lines = body.split("\n");
        // wire: line0 = server IN() point-lookup time (ms), line1 = column names, each following line is a matched row.
        long serverMs = rtt;
        if (lines.length > 0) {
            try {
                serverMs = Long.parseLong(lines[0].trim());
            } catch (NumberFormatException ignore) {
                // old-protocol compatibility: no timing header, fall back to RTT
            }
        }
        List<String> columns = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        if (lines.length > 1 && !lines[1].isEmpty()) {
            for (String c : lines[1].split("\t", -1)) {
                columns.add(c);
            }
        }
        for (int i = 2; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                rows.add(lines[i].split("\t", -1));
            }
        }
        return new RecheckResponse(columns, rows, serverMs);
    }

    @Override
    public void closeSessions() throws Exception {
        post("/close-sessions", "");
    }

    private String post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(label + " " + path + " http " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }
}

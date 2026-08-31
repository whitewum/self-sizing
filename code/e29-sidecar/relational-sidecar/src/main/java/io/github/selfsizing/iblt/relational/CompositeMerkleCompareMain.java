package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.relational.HttpSidecarClient;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Count-bounded, streaming controller for logical-range Merkle comparison. */
public final class CompositeMerkleCompareMain {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final int DIFFERENCE_SAMPLE_LIMIT = 10_000;

    private CompositeMerkleCompareMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = args(args);
        String source = required(a, "--source");
        String target = required(a, "--target");
        int chunkSize = positive(a.getOrDefault("--chunk-size", "10000"), "--chunk-size");
        int expectedWorkers = positive(a.getOrDefault("--merkle-workers", "5"), "--merkle-workers");
        int drillWorkers = positive(a.getOrDefault("--merkle-drill-workers",
                Integer.toString(expectedWorkers)), "--merkle-drill-workers");
        List<PkType> orderTypes = types(a.getOrDefault("--merkle-order-types", "string"));
        String descentMode = a.getOrDefault("--merkle-descent", "dirty");
        if (!List.of("dirty", "size").contains(descentMode)) {
            throw new IllegalArgumentException("--merkle-descent must be dirty or size");
        }
        int refineFanout = positive(a.getOrDefault("--merkle-refine-fanout", "10"), "--merkle-refine-fanout");
        if (refineFanout < 2) throw new IllegalArgumentException("--merkle-refine-fanout must be >= 2");
        int drillCap = positive(a.getOrDefault("--merkle-drill-cap", "100000"), "--merkle-drill-cap");

        String sourceHealth = get(source + "/health");
        String targetHealth = get(target + "/health");
        int sourceWorkers = healthInt(sourceHealth, "merkleWorkers");
        int targetWorkers = healthInt(targetHealth, "merkleWorkers");
        String sourceOrder = healthValue(sourceHealth, "merkleOrder");
        String targetOrder = healthValue(targetHealth, "merkleOrder");
        if (sourceWorkers != expectedWorkers || targetWorkers != expectedWorkers) {
            throw new IllegalStateException("Merkle worker mismatch: expected=" + expectedWorkers
                    + " source=" + sourceWorkers + " target=" + targetWorkers);
        }
        verifyExpected(a, "--source-order-columns", sourceOrder);
        verifyExpected(a, "--target-order-columns", targetOrder);
        int orderArity = sourceOrder.split(",").length;
        if (orderTypes.size() != orderArity) throw new IllegalArgumentException("Merkle order type mismatch");
        if ("dirty".equals(descentMode) && orderArity != 1) {
            throw new IllegalArgumentException("--merkle-descent dirty needs a single order column"
                    + " (NTILE fence); use --merkle-descent size for composite order keys");
        }
        System.out.println("source=" + sourceHealth.trim());
        System.out.println("target=" + targetHealth.trim());

        long started = System.nanoTime();
        long boundariesStarted = System.nanoTime();
        Response boundaryResponse = requestResponse("POST", source + "/merkle/boundaries",
                Integer.toString(chunkSize));
        List<Range> initialRanges = rangesFromBoundaries(lines(boundaryResponse.body));
        long boundariesMs = elapsedMs(boundariesStarted);
        long boundaryChunkMs = headerLong(boundaryResponse, "X-Merkle-Boundary-Chunk-Millis", -1);
        long boundaryProbeMs = headerLong(boundaryResponse, "X-Merkle-Boundary-Probe-Millis", -1);
        long boundarySortMs = headerLong(boundaryResponse, "X-Merkle-Boundary-Sort-Millis", -1);
        long boundaryStatements = headerLong(boundaryResponse, "X-Merkle-Boundary-Probe-Statements", -1);
        long boundaryEstimatedRows = headerLong(boundaryResponse,
                "X-Merkle-Boundary-Estimated-Rows", -1);

        long checksumsStarted = System.nanoTime();
        ChecksumBatch initial = checksums(source, target, initialRanges);
        List<CheckedRange> leaves = checked(initialRanges, initial.left, initial.right);
        int checksumWorkersA = initial.workersA;
        int checksumWorkersB = initial.workersB;
        // Coarse-to-fine localisation. In the default "dirty" descent a range that matches on both
        // engines is pruned immediately, whatever its size -- it is never drilled, so its row count
        // is irrelevant. Only DIRTY ranges are refined, and only until they are small enough to
        // drill (<= drillCap), which both narrows the streamed drill and lets it parallelise. The
        // fences come from Oracle NTILE (one pass -> boundary keys + Oracle checksums); MySQL 5.7
        // only re-applies those key fences as range checksums. The old size-driven refine (which
        // subdivided AND re-checksummed every range, clean or not -- a near second full-table scan)
        // is retained behind --merkle-descent size for A/B comparison only.
        int refineRounds = 0;
        int refinedParents = 0;
        long refineStarted = System.nanoTime();
        List<Range> drillTargets = new ArrayList<>();
        if ("size".equals(descentMode)) {
            while (true) {
                List<CheckedRange> oversized = new ArrayList<>();
                for (CheckedRange leaf : leaves) if (leaf.maxCount() > chunkSize) oversized.add(leaf);
                if (oversized.isEmpty()) break;
                refineRounds++;
                refinedParents += oversized.size();
                List<List<Range>> replacements = splitAll(source, target, oversized, chunkSize,
                        Math.max(sourceWorkers, targetWorkers));
                List<Range> children = new ArrayList<>();
                for (List<Range> ranges : replacements) children.addAll(ranges);
                ChecksumBatch childChecksums = checksums(source, target, children);
                checksumWorkersA = Math.max(checksumWorkersA, childChecksums.workersA);
                checksumWorkersB = Math.max(checksumWorkersB, childChecksums.workersB);
                List<CheckedRange> childLeaves = checked(children, childChecksums.left, childChecksums.right);
                List<CheckedRange> next = new ArrayList<>();
                int childOffset = 0;
                int replaceOffset = 0;
                for (CheckedRange leaf : leaves) {
                    if (leaf.maxCount() <= chunkSize) {
                        next.add(leaf);
                    } else {
                        int childCount = replacements.get(replaceOffset++).size();
                        next.addAll(childLeaves.subList(childOffset, childOffset + childCount));
                        childOffset += childCount;
                    }
                }
                leaves = next;
                if (refineRounds > 32) throw new IllegalStateException("Merkle refinement did not converge");
            }
            for (CheckedRange leaf : leaves) if (!leaf.left.equals(leaf.right)) drillTargets.add(leaf.range);
        } else {
            List<CheckedRange> frontier = new ArrayList<>();
            for (CheckedRange leaf : leaves) if (!leaf.left.equals(leaf.right)) frontier.add(leaf);
            while (!frontier.isEmpty()) {
                List<CheckedRange> tooBig = new ArrayList<>();
                for (CheckedRange r : frontier) {
                    if (r.maxCount() <= drillCap) drillTargets.add(r.range);
                    else tooBig.add(r);
                }
                if (tooBig.isEmpty()) break;
                refineRounds++;
                refinedParents += tooBig.size();
                frontier = splitDirtyLevel(source, target, tooBig, refineFanout,
                        Math.max(sourceWorkers, targetWorkers));
                if (refineRounds > 40) throw new IllegalStateException("Merkle descent did not converge");
            }
            // Replace the initial dirty parents in the reporting frontier with their final dirty
            // leaves.  Clean initial ranges remain as-is and were already pruned.  Drill behavior
            // does not depend on this list, but maxLeafRows/ranges must describe the actual final
            // partition rather than the coarse ROWID-sampled parents.
            List<CheckedRange> finalLeaves = new ArrayList<>();
            for (CheckedRange leaf : leaves) if (leaf.left.equals(leaf.right)) finalLeaves.add(leaf);
            finalLeaves.addAll(frontier);
            leaves = finalLeaves;
        }
        long refineMs = elapsedMs(refineStarted);
        long checksumsMs = elapsedMs(checksumsStarted);
        long maxLeafA = 0, maxLeafB = 0;
        for (CheckedRange leaf : leaves) {
            maxLeafA = Math.max(maxLeafA, leaf.left.count);
            maxLeafB = Math.max(maxLeafB, leaf.right.count);
        }

        long drillStarted = System.nanoTime();
        int plus = 0, minus = 0, changed = 0;
        List<String> differences = new ArrayList<>();
        List<String> changedPks = new ArrayList<>();
        ExecutorService drillPool = Executors.newFixedThreadPool(Math.min(drillWorkers,
                Math.max(1, drillTargets.size())));
        List<Future<DrillResult>> drillFutures = new ArrayList<>();
        try {
            for (Range range : drillTargets) {
                drillFutures.add(drillPool.submit(() -> drillRange(source, target, range,
                        orderTypes, orderArity)));
            }
            for (Future<DrillResult> future : drillFutures) {
                DrillResult result = get(future);
                plus += result.plus;
                minus += result.minus;
                changed += result.changed;
                changedPks.addAll(result.changedPks);
                for (String sample : result.samples) {
                    if (differences.size() < DIFFERENCE_SAMPLE_LIMIT) differences.add(sample);
                }
            }
        } finally {
            for (Future<DrillResult> future : drillFutures) future.cancel(true);
            drillPool.shutdownNow();
        }
        long drillMs = elapsedMs(drillStarted);

        // Value-level recheck of the fp-flagged "changed" PKs, mirroring IBLT's candidate recheck.
        // Merkle's drill is an exact streaming merge (source-only/target-only are decided by key
        // presence, never fingerprints), so this is independent-point-query confirmation on the
        // re-read rows -- not the artifact filtering that IBLT's probabilistic peeling needs.
        long recheckStarted = System.nanoTime();
        int rechecked = changedPks.size();
        int confirmedChanged = 0;
        if (!changedPks.isEmpty()) {
            String body = String.join("\n", changedPks) + "\n";
            ResponsePair pair = requestPair("POST", source + "/merkle/recheck",
                    target + "/merkle/recheck", body);
            Map<String, String> sourceMd5 = parseRecheck(pair.left.body);
            Map<String, String> targetMd5 = parseRecheck(pair.right.body);
            for (String pk : changedPks) {
                String s = sourceMd5.get(pk);
                String t = targetMd5.get(pk);
                if (s != null && t != null && !s.equals(t)) confirmedChanged++;
            }
        }
        long recheckMs = elapsedMs(recheckStarted);

        long ms = elapsedMs(started);
        int dirtyRanges = drillTargets.size();
        int actualDrillWorkers = Math.min(drillWorkers, Math.max(1, dirtyRanges));
        int effectiveDrillCap = "size".equals(descentMode) ? chunkSize : drillCap;
        System.out.println("chunkSize=" + chunkSize + " ranges=" + leaves.size()
                + " dirtyRanges=" + dirtyRanges + " drillWorkers=" + actualDrillWorkers
                + " plus=" + plus + " minus=" + minus + " changed=" + changed
                + " confirmedChanged=" + confirmedChanged + " elapsedMs=" + ms);
        System.out.println("E29_RESULT algorithm=MERKLE e2e_ms=" + ms
                + " status=" + ((plus + minus + changed) == 0 ? "EQUAL" : "DIFF")
                + " descent=" + descentMode + " chunkSize=" + chunkSize
                + " drillCap=" + effectiveDrillCap + " refineFanout=" + refineFanout
                + " ranges=" + leaves.size()
                + " dirtyRanges=" + dirtyRanges + " plus=" + plus + " minus=" + minus
                + " changed=" + changed + " rechecked=" + rechecked
                + " confirmedChanged=" + confirmedChanged + " boundariesMs=" + boundariesMs
                + " boundaryChunkMs=" + boundaryChunkMs + " boundaryProbeMs=" + boundaryProbeMs
                + " boundarySortMs=" + boundarySortMs + " boundaryProbeStatements=" + boundaryStatements
                + " boundaryEstimatedRows=" + boundaryEstimatedRows
                + " checksumsMs=" + checksumsMs + " refineMs=" + refineMs
                + " refineRounds=" + refineRounds + " refinedParents=" + refinedParents
                + " maxLeafRowsA=" + maxLeafA + " maxLeafRowsB=" + maxLeafB
                + " drillMs=" + drillMs + " recheckMs=" + recheckMs
                + " merkleWorkersA=" + sourceWorkers
                + " merkleWorkersB=" + targetWorkers + " checksumWorkersA=" + checksumWorkersA
                + " checksumWorkersB=" + checksumWorkersB + " drillWorkersA=" + actualDrillWorkers
                + " drillWorkersB=" + actualDrillWorkers + " orderColumnsA=" + sourceOrder
                + " orderColumnsB=" + targetOrder);
        for (String difference : differences) System.out.println(difference);
        System.out.println("status=" + ((plus + minus + changed) == 0 ? "PASS" : "DIFF"));
        if (a.containsKey("--expect-equal") && (plus + minus + changed) != 0) {
            throw new IllegalStateException("Merkle comparison expected equality but found differences");
        }
    }

    private static List<List<Range>> splitAll(String source, String target,
            List<CheckedRange> oversized, int chunkSize, int splitWorkers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(Math.max(1, splitWorkers), oversized.size()));
        List<Future<List<Range>>> futures = new ArrayList<>(oversized.size());
        try {
            for (CheckedRange leaf : oversized) {
                String endpoint = leaf.left.count >= leaf.right.count ? source : target;
                futures.add(pool.submit(() -> {
                    List<String> boundaries = lines(post(endpoint + "/merkle/split-boundaries",
                            chunkSize + "\n" + encodeRange(leaf.range)));
                    if (boundaries.isEmpty()) throw new IllegalStateException(
                            "oversized range produced no split boundary: " + leaf);
                    return splitRange(leaf.range, boundaries);
                }));
            }
            List<List<Range>> out = new ArrayList<>(oversized.size());
            for (Future<List<Range>> future : futures) out.add(future.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Range> splitRange(Range parent, List<String> boundaries) {
        List<Range> out = new ArrayList<>();
        String lower = parent.lower;
        for (String upper : boundaries) {
            if (upper.equals(lower) || upper.equals(parent.upper)) continue;
            out.add(new Range(lower, upper));
            lower = upper;
        }
        out.add(new Range(lower, parent.upper));
        return out;
    }

    private static ChecksumBatch checksums(String source, String target, List<Range> ranges)
            throws Exception {
        ResponsePair pair = requestPair("POST", source + "/merkle/checksums",
                target + "/merkle/checksums", encodeRanges(ranges));
        List<Checksum> left = parseChecksums(pair.left.body);
        List<Checksum> right = parseChecksums(pair.right.body);
        if (left.size() != ranges.size() || right.size() != ranges.size()) {
            throw new IllegalStateException("checksum range count mismatch");
        }
        return new ChecksumBatch(left, right, headerInt(pair.left), headerInt(pair.right));
    }

    /** Checksums against a single endpoint only (the other side's values come from the NTILE pass). */
    private static List<Checksum> checksumsSingle(String endpoint, List<Range> ranges) throws Exception {
        List<Checksum> out = parseChecksums(post(endpoint + "/merkle/checksums", encodeRanges(ranges)));
        if (out.size() != ranges.size()) {
            throw new IllegalStateException("single-endpoint checksum count mismatch");
        }
        return out;
    }

    /** Refine one descent level: split every still-too-big dirty parent, keep only dirty children. */
    private static List<CheckedRange> splitDirtyLevel(String source, String target,
            List<CheckedRange> parents, int fanout, int workers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(Math.max(1, workers), parents.size()));
        List<Future<List<CheckedRange>>> futures = new ArrayList<>(parents.size());
        try {
            for (CheckedRange parent : parents) {
                futures.add(pool.submit(() -> splitOneDirty(source, target, parent, fanout)));
            }
            List<CheckedRange> out = new ArrayList<>();
            for (Future<List<CheckedRange>> future : futures) out.addAll(get(future));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * One Oracle NTILE pass carves {@code fanout} equi-row buckets of one dirty parent, returning
     * each bucket's upper boundary key AND Oracle checksum together; MySQL then re-checksums the
     * same key fences. Buckets are reconstructed 1:1 with the returned boundaries (the last bucket
     * inherits the parent's -- possibly open -- upper bound). Only differing children survive.
     */
    private static List<CheckedRange> splitOneDirty(String source, String target,
            CheckedRange parent, int fanout) throws Exception {
        List<String> resp = lines(post(source + "/merkle/decile-checksums",
                fanout + "\n" + encodeRange(parent.range)));
        if (resp.isEmpty()) throw new IllegalStateException("decile returned no buckets: " + parent.range);
        List<String> hiKeys = new ArrayList<>(resp.size());
        List<Checksum> oracle = new ArrayList<>(resp.size());
        for (String line : resp) {
            String[] p = line.split("\\t", -1);
            if (p.length != 3) throw new IllegalArgumentException("bad decile row: " + line);
            hiKeys.add(p[0]);
            oracle.add(new Checksum(p[1], Long.parseLong(p[2])));
        }
        List<Range> subRanges = new ArrayList<>(hiKeys.size());
        String lower = parent.range.lower;
        for (int i = 0; i < hiKeys.size(); i++) {
            String upper = (i == hiKeys.size() - 1) ? parent.range.upper : hiKeys.get(i);
            subRanges.add(new Range(lower, upper));
            lower = hiKeys.get(i);
        }
        List<Checksum> mysql = checksumsSingle(target, subRanges);
        List<CheckedRange> dirty = new ArrayList<>();
        for (int i = 0; i < subRanges.size(); i++) {
            CheckedRange child = new CheckedRange(subRanges.get(i), oracle.get(i), mysql.get(i));
            if (!child.left.equals(child.right)) dirty.add(child);
        }
        return dirty;
    }

    private static Map<String, String> parseRecheck(String body) {
        Map<String, String> out = new HashMap<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\t", -1);
            if (p.length != 2) throw new IllegalArgumentException("bad recheck row: " + line);
            out.put(p[0], p[1]);
        }
        return out;
    }

    private static DrillResult drillRange(String source, String target, Range range,
            List<PkType> orderTypes, int orderArity) throws Exception {
        String body = encodeRange(range);
        CompletableFuture<HttpResponse<InputStream>> leftFuture = HTTP.sendAsync(
                buildRequest("POST", source + "/merkle/rows", body), HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<HttpResponse<InputStream>> rightFuture = HTTP.sendAsync(
                buildRequest("POST", target + "/merkle/rows", body), HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture.allOf(leftFuture, rightFuture).get();
        HttpResponse<InputStream> leftResponse = leftFuture.get();
        HttpResponse<InputStream> rightResponse = rightFuture.get();
        if (leftResponse.statusCode() != 200 || rightResponse.statusCode() != 200) {
            String leftError = leftResponse.statusCode() == 200 ? "" : new String(
                    leftResponse.body().readAllBytes(), StandardCharsets.UTF_8);
            String rightError = rightResponse.statusCode() == 200 ? "" : new String(
                    rightResponse.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("Merkle streaming rows HTTP failure: "
                    + leftResponse.statusCode() + "/" + rightResponse.statusCode()
                    + " left=" + leftError + " right=" + rightError);
        }
        try (RowCursor left = new RowCursor(leftResponse.body());
             RowCursor right = new RowCursor(rightResponse.body())) {
            int plus = 0, minus = 0, changed = 0;
            List<String> samples = new ArrayList<>();
            List<String> changedPks = new ArrayList<>();
            StreamRow a = left.next();
            StreamRow b = right.next();
            while (a != null || b != null) {
                if (a == null) {
                    plus++; sample(samples, "target-only " + b.pk); b = right.next(); continue;
                }
                if (b == null) {
                    minus++; sample(samples, "source-only " + a.pk); a = left.next(); continue;
                }
                int compared = compareOrder(a.order, b.order, orderTypes, orderArity);
                if (compared < 0) {
                    minus++; sample(samples, "source-only " + a.pk); a = left.next();
                } else if (compared > 0) {
                    plus++; sample(samples, "target-only " + b.pk); b = right.next();
                } else if (!a.pk.equals(b.pk)) {
                    minus++; plus++;
                    sample(samples, "source-only " + a.pk);
                    sample(samples, "target-only " + b.pk);
                    a = left.next(); b = right.next();
                } else {
                    if (!a.fp.equals(b.fp)) { changed++; sample(samples, "changed " + a.pk); changedPks.add(a.pk); }
                    a = left.next(); b = right.next();
                }
            }
            return new DrillResult(plus, minus, changed, samples, changedPks);
        }
    }

    private static int compareOrder(String left, String right, List<PkType> types, int arity) {
        List<String> a = CompositePkCodec.decode(left, arity);
        List<String> b = CompositePkCodec.decode(right, arity);
        for (int i = 0; i < arity; i++) {
            int compared = types.get(i) == PkType.STRING ? a.get(i).compareTo(b.get(i))
                    : new BigDecimal(a.get(i)).compareTo(new BigDecimal(b.get(i)));
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static void sample(List<String> samples, String value) {
        if (samples.size() < DIFFERENCE_SAMPLE_LIMIT) samples.add(value);
    }

    private static List<CheckedRange> checked(List<Range> ranges, List<Checksum> left,
            List<Checksum> right) {
        List<CheckedRange> out = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) out.add(new CheckedRange(ranges.get(i), left.get(i), right.get(i)));
        return out;
    }

    private static List<Range> rangesFromBoundaries(List<String> boundaries) {
        List<Range> ranges = new ArrayList<>();
        String lower = null;
        for (String upper : boundaries) {
            String value = "null".equalsIgnoreCase(upper) ? null : upper;
            ranges.add(new Range(lower, value));
            if (value == null) break;
            lower = value;
        }
        if (ranges.isEmpty()) throw new IllegalStateException("source returned no Merkle ranges");
        return ranges;
    }

    private static List<Checksum> parseChecksums(String body) {
        List<Checksum> out = new ArrayList<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\t", -1);
            if (p.length != 2) throw new IllegalArgumentException("bad checksum: " + line);
            out.add(new Checksum(p[0], Long.parseLong(p[1])));
        }
        return out;
    }

    private static String encodeRange(Range range) {
        return (range.lower == null ? "null" : range.lower) + '\t'
                + (range.upper == null ? "null" : range.upper) + '\n';
    }

    private static String encodeRanges(List<Range> ranges) {
        StringBuilder out = new StringBuilder();
        for (Range range : ranges) out.append(encodeRange(range));
        return out.toString();
    }

    private static List<String> lines(String body) {
        List<String> out = new ArrayList<>();
        for (String line : body.split("\\R")) if (!line.isBlank()) out.add(line.trim());
        return out;
    }

    private static String get(String url) throws Exception { return request("GET", url, ""); }
    private static String post(String url, String body) throws Exception { return request("POST", url, body); }
    private static String request(String method, String url, String body) throws Exception {
        return requestResponse(method, url, body).body;
    }

    private static Response requestResponse(String method, String url, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(buildRequest(method, url, body), HttpResponse.BodyHandlers.ofString());
        return checkedResponse(url, response);
    }

    static ResponsePair requestPair(String method, String leftUrl, String rightUrl, String body)
            throws Exception {
        CompletableFuture<HttpResponse<String>> left = HTTP.sendAsync(
                buildRequest(method, leftUrl, body), HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> right = HTTP.sendAsync(
                buildRequest(method, rightUrl, body), HttpResponse.BodyHandlers.ofString());
        try {
            CompletableFuture.allOf(left, right).get();
            return new ResponsePair(checkedResponse(leftUrl, left.get()), checkedResponse(rightUrl, right.get()));
        } catch (Exception e) {
            left.cancel(true); right.cancel(true); throw e;
        }
    }

    static HttpRequest buildRequest(String method, String url, String body) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(HttpSidecarClient.configuredRequestTimeout())
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static Response checkedResponse(String url, HttpResponse<String> response) {
        if (response.statusCode() != 200) throw new IllegalStateException(url + " -> HTTP "
                + response.statusCode() + ": " + response.body());
        return new Response(response.body(), response.headers().firstValue("X-Merkle-Workers").orElse(""),
                response.headers());
    }

    private static int headerInt(Response response) {
        if (response.workerHeader.isBlank()) throw new IllegalStateException("response missing X-Merkle-Workers");
        return Integer.parseInt(response.workerHeader);
    }

    private static long headerLong(Response response, String name, long fallback) {
        return response.headers.firstValue(name).map(Long::parseLong).orElse(fallback);
    }

    private static Map<String, String> args(String[] raw) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < raw.length; i += 2) out.put(raw[i], raw[i + 1]);
        return out;
    }

    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static int positive(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException(name + " must be positive");
        return parsed;
    }

    private static int healthInt(String health, String key) { return Integer.parseInt(healthValue(health, key)); }
    private static String healthValue(String health, String key) {
        for (String token : health.trim().split("\\s+")) {
            if (token.startsWith(key + "=")) return token.substring(key.length() + 1);
        }
        throw new IllegalStateException("health response missing " + key + ": " + health);
    }

    private static void verifyExpected(Map<String, String> args, String key, String actual) {
        String expected = args.get(key);
        if (expected != null && !expected.equals(actual)) {
            throw new IllegalStateException(key + " mismatch: expected=" + expected + " actual=" + actual);
        }
    }

    private static List<PkType> types(String value) {
        List<PkType> out = new ArrayList<>();
        for (String raw : value.split(",")) {
            String type = raw.trim().toLowerCase();
            if (type.contains("int") || type.contains("serial")) out.add(PkType.INT64);
            else if (type.contains("decimal") || type.contains("numeric")) out.add(PkType.DECIMAL);
            else out.add(PkType.STRING);
        }
        return out;
    }

    private static long elapsedMs(long started) { return Math.round((System.nanoTime() - started) / 1e6); }

    private static <T> T get(Future<T> future) throws Exception {
        try { return future.get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new RuntimeException(e.getCause());
        }
    }

    private static final class RowCursor implements AutoCloseable {
        private final BufferedReader reader;
        RowCursor(InputStream in) { reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20); }
        StreamRow next() throws Exception {
            String line = reader.readLine();
            if (line == null) return null;
            String[] p = line.split("\\t", -1);
            if (p.length != 3) throw new IllegalArgumentException("bad streaming Merkle row");
            return new StreamRow(p[0], p[1], p[2]);
        }
        @Override public void close() throws Exception { reader.close(); }
    }

    private record Range(String lower, String upper) { }
    private record Checksum(String sum, long count) { }
    private record CheckedRange(Range range, Checksum left, Checksum right) {
        long maxCount() { return Math.max(left.count, right.count); }
    }
    private record ChecksumBatch(List<Checksum> left, List<Checksum> right, int workersA, int workersB) { }
    private record StreamRow(String order, String pk, String fp) { }
    private record DrillResult(int plus, int minus, int changed, List<String> samples,
            List<String> changedPks) { }
    static record Response(String body, String workerHeader, HttpHeaders headers) { }
    static record ResponsePair(Response left, Response right) { }
}

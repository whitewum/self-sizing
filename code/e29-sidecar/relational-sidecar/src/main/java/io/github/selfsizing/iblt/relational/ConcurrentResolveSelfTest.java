package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.controller.SidecarClient;
import io.github.selfsizing.iblt.controller.SidecarClient.BuildSketchRemote;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.sidecar.RecheckResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Verifies dual-endpoint resolve overlap and selective spill lookup semantics. */
public final class ConcurrentResolveSelfTest {
    private ConcurrentResolveSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("e29-selective-resolve-");
        SelectiveFileFingerprintStore store = new SelectiveFileFingerprintStore(dir.resolve("s").toString());
        try {
            store.add(11, 101, "pk-a");
            store.add(12, 102, "pk-b");
            store.add(13, 103, "pk-c");
            store.finishIngest();
            Map<Long, String> found = store.resolveIds(Set.of(102L, 103L));
            if (!found.equals(Map.of(102L, "pk-b", 103L, "pk-c"))) {
                throw new AssertionError("selective resolve mismatch: " + found);
            }
            List<String> streamed = new ArrayList<>();
            store.resolveIdsStream(Set.of(101L, 103L), streamed::add);
            if (!streamed.equals(List.of("pk-a", "pk-c"))) {
                throw new AssertionError("streaming selective resolve mismatch: " + streamed);
            }
        } finally {
            store.close();
            Files.deleteIfExists(dir);
        }

        CyclicBarrier overlap = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SidecarClient left = resolver("left-pk", overlap);
            SidecarClient right = resolver("right-pk", overlap);
            List<String> pks = ConcurrentResolveIbltCompareController.resolveCandidatePks(
                    pool, left, right, "a", "b", new long[]{1}, new long[]{2});
            if (!pks.equals(List.of("left-pk", "right-pk"))) {
                throw new AssertionError("concurrent resolve union mismatch: " + pks);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static SidecarClient resolver(String pk, CyclicBarrier overlap) {
        return new SidecarClient() {
            @Override public String label() { return pk; }
            @Override public String health() { return "ok"; }
            @Override public BuildSketchRemote buildSketch(int m, long seed) { throw unsupported(); }
            @Override public IbltSketch rebucket(String session, int m, long seed) { throw unsupported(); }
            @Override public List<String> resolveIds(String session, long[] ids) throws Exception {
                overlap.await(2, TimeUnit.SECONDS);
                return List.of(pk);
            }
            @Override public RecheckResponse recheck(List<String> pks) { throw unsupported(); }
            @Override public void closeSessions() { }
            private UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
        };
    }
}

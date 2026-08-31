package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.IbltSketch;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies that independent shard sketches merge exactly into the sequential sketch. */
public final class ParallelSketchMergeSelfTest {
    private ParallelSketchMergeSelfTest() { }

    public static void main(String[] args) throws Exception {
        int buckets = 257;
        long seed = 0xE29L;
        IbltSketch sequential = new IbltSketch(buckets, seed);
        IbltSketch left = new IbltSketch(buckets, seed);
        IbltSketch right = new IbltSketch(buckets, seed);
        for (long i = 0; i < 10_000; i++) {
            long fp = 0x1000_0000L + i * 17;
            long id = 0x2000_0000L + i * 31;
            sequential.insert(fp, id);
            (i % 2 == 0 ? left : right).insert(fp, id);
        }
        left.mergeFrom(right);
        for (int i = 0; i < buckets; i++) {
            if (left.count(i) != sequential.count(i)
                    || left.fpXor(i) != sequential.fpXor(i)
                    || left.idXor(i) != sequential.idXor(i)
                    || left.chkXor(i) != sequential.chkXor(i)) {
                throw new AssertionError("merged sketch differs at bucket " + i);
            }
        }

        // The first-build pipeline inserts the same (fp,id) pair while the row is streamed into
        // the spill store.  Replay the persisted .fpid file and require byte-for-byte sketch
        // equality; this guards against accidentally changing the row order or one of the two
        // values used by the online insert.
        Path dir;
        try {
            dir = Files.createTempDirectory("e29-pipelined-sketch-");
        } catch (Exception e) {
            throw new AssertionError("cannot create pipeline self-test directory", e);
        }
        SelectiveFileFingerprintStore store = new SelectiveFileFingerprintStore(
                dir.resolve("rows").toString());
        try {
            IbltSketch online = new IbltSketch(buckets, seed);
            for (long i = 0; i < 10_000; i++) {
                long fp = 0x3000_0000L + i * 19;
                long id = 0x4000_0000L + i * 23;
                store.add(fp, id, "pk-" + i);
                online.insert(fp, id);
            }
            store.finishIngest();
            IbltSketch replay = IbltSketch.build(store, buckets, seed);
            for (int i = 0; i < buckets; i++) {
                if (online.count(i) != replay.count(i)
                        || online.fpXor(i) != replay.fpXor(i)
                        || online.idXor(i) != replay.idXor(i)
                        || online.chkXor(i) != replay.chkXor(i)) {
                    throw new AssertionError("pipelined sketch differs at bucket " + i);
                }
            }
        } finally {
            store.close();
            try {
                Files.deleteIfExists(dir);
            } catch (Exception ignored) {
                // store.close() removes the two files; directory cleanup is best effort.
            }
        }
        System.out.println("PARALLEL_SKETCH_MERGE_SELF_TEST PASS");
    }
}

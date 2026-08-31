package io.github.selfsizing.iblt.core;

import java.util.Arrays;

/**
 * In-memory array implementation (default): one {@code long[]} each for (fp, id),
 * one {@code String[]} for pk.
 *
 * <p>Moved in from the main project. About 16 bytes/row plus the pk string;
 * 10M rows &asymp; 160MB+. For very large tables, add a spill-to-disk backend to
 * avoid OOM.</p>
 */
public final class MemoryFingerprintStore implements FingerprintStore {

    private long[] fps;
    private long[] ids;
    private String[] pks;
    private int n;

    public MemoryFingerprintStore() {
        this(1 << 16);
    }

    public MemoryFingerprintStore(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        this.fps = new long[cap];
        this.ids = new long[cap];
        this.pks = new String[cap];
        this.n = 0;
    }

    @Override
    public void add(long fp, long id, String pk) {
        if (n == fps.length) {
            int newCap = n + (n >> 1) + 1;
            fps = Arrays.copyOf(fps, newCap);
            ids = Arrays.copyOf(ids, newCap);
            pks = Arrays.copyOf(pks, newCap);
        }
        fps[n] = fp;
        ids[n] = id;
        pks[n] = pk;
        n++;
    }

    @Override
    public void finishIngest() {
        // the in-memory implementation needs no finalization
    }

    @Override
    public long size() {
        return n;
    }

    @Override
    public void forEachFpId(FpIdConsumer consumer) {
        for (int i = 0; i < n; i++) {
            consumer.accept(fps[i], ids[i]);
        }
    }

    @Override
    public void forEachIdPk(IdPkConsumer consumer) {
        for (int i = 0; i < n; i++) {
            consumer.accept(ids[i], pks[i]);
        }
    }

    @Override
    public void close() {
        fps = null;
        ids = null;
        pks = null;
        n = 0;
    }
}

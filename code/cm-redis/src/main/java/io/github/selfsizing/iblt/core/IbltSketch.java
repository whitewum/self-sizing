package io.github.selfsizing.iblt.core;

import java.util.logging.Logger;

/**
 * IBLT sketch (a compact summary of a fixed M cells).
 *
 * <p>cell = {count, fpXor, idXor, chkXor} (the id-only IBLT-b variant, interop
 * spec &sect;6). insert = XOR a row into its 3 cell positions from
 * {@link IbltHash#positions}. Moved in from the main project.</p>
 */
public final class IbltSketch {

    private static final Logger LOG = Logger.getLogger(IbltSketch.class.getName());

    private final int m;
    private final long hashSeed;
    private final long[] count;
    private final long[] fpXor;
    private final long[] idXor;
    private final long[] chkXor;

    public IbltSketch(int m) {
        this(m, 0L);
    }

    public IbltSketch(int m, long hashSeed) {
        if (m <= 0) {
            throw new IllegalArgumentException("bucket count must be > 0, got " + m);
        }
        this.m = m;
        this.hashSeed = hashSeed;
        this.count = new long[m];
        this.fpXor = new long[m];
        this.idXor = new long[m];
        this.chkXor = new long[m];
    }

    /** Rebuilds directly from the four cell channel arrays (for a sketch deserialized by {@link SketchCodec}, no store rebuild needed). */
    IbltSketch(int m, long hashSeed, long[] count, long[] fpXor, long[] idXor, long[] chkXor) {
        if (m <= 0) {
            throw new IllegalArgumentException("bucket count must be > 0, got " + m);
        }
        if (count.length != m || fpXor.length != m || idXor.length != m || chkXor.length != m) {
            throw new IllegalArgumentException("channel length != M=" + m);
        }
        this.m = m;
        this.hashSeed = hashSeed;
        this.count = count;
        this.fpXor = fpXor;
        this.idXor = idXor;
        this.chkXor = chkXor;
    }

    // The 4 package-private accessors below let the same-package SketchCodec read channels in bulk (avoids per-cell getter overhead).
    long[] countChannel() {
        return count;
    }

    long[] fpXorChannel() {
        return fpXor;
    }

    long[] idXorChannel() {
        return idXor;
    }

    long[] chkXorChannel() {
        return chkXor;
    }

    /** Inserts one row (fp, id). */
    public void insert(long fp, long id) {
        long cs = IbltHash.checksum(fp);
        for (int p : IbltHash.positions(fp, m, hashSeed)) {
            count[p] += 1;
            fpXor[p] ^= fp;
            idXor[p] ^= id;
            chkXor[p] ^= cs;
        }
    }

    /**
     * XOR-merges another sketch of the <b>same M</b> into this one (in place).
     * When scanning shards in parallel, each worker builds its own partial
     * sketch locally and they are merged into the whole afterwards: {@code count}
     * added, {@code fpXor/idXor/chkXor} XORed per cell.
     *
     * <p>insert is commutative and associative (addition + XOR), so build-per-shard
     * then merge is <b>bit-identical</b> to a single-stream full insert, regardless
     * of scan order (this underpins the correctness of fused fetch+build).</p>
     */
    public void mergeFrom(IbltSketch other) {
        if (other.m != this.m || other.hashSeed != this.hashSeed) {
            throw new IllegalArgumentException("merge mapping mismatch: M/seed="
                    + this.m + "/" + this.hashSeed + " vs " + other.m + "/" + other.hashSeed);
        }
        for (int i = 0; i < m; i++) {
            count[i] += other.count[i];
            fpXor[i] ^= other.fpXor[i];
            idXor[i] ^= other.idXor[i];
            chkXor[i] ^= other.chkXor[i];
        }
    }

    /** Builds a sketch in one pass from the fingerprint cache at bucket count M (re-bucketing). */
    public static IbltSketch build(FingerprintStore store, int m) throws Exception {
        return build(store, m, 0L);
    }

    public static IbltSketch build(FingerprintStore store, int m, long hashSeed) throws Exception {
        // performance instrumentation: building a sketch happens once per side / per re-bucket, so record size and time (not inside the forEachFpId row loop).
        long t0 = System.nanoTime();
        IbltSketch sketch = new IbltSketch(m, hashSeed);
        store.forEachFpId(sketch::insert);
        long rows = store.size();
        LOG.info(() -> String.format("[iblt] build sketch: rows=%d, M=%d, seed=%d, elapsedMs=%.1f",
                rows, m, hashSeed, (System.nanoTime() - t0) / 1e6));
        return sketch;
    }

    public int bucketCount() {
        return m;
    }

    public long hashSeed() {
        return hashSeed;
    }

    public long count(int i) {
        return count[i];
    }

    public long fpXor(int i) {
        return fpXor[i];
    }

    public long idXor(int i) {
        return idXor[i];
    }

    public long chkXor(int i) {
        return chkXor[i];
    }
}

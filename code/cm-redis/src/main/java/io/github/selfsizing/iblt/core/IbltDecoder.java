package io.github.selfsizing.iblt.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * IBLT decoder: difference sketch = A &minus; B &rarr; F2 estimate d_hat &rarr;
 * peel &rarr; emit +/&minus; ids.
 *
 * <p>Mirrors the reference implementation (measured bit-for-bit and
 * result-for-result identical to the C++). Moved in from the main project.</p>
 *
 * <p><b>Joint peeling (residual-core reuse):</b> {@link #decodeJoint} takes
 * multi-round (A_i, B_i) sketches, builds one {@link DiffView} per round, and
 * ping-pong peels across the different Ms together &mdash; a symbol peeled from a
 * pure cell in any view is subtracted from <b>all</b> views (each using its own
 * M / cell positions), so information still encoded in a failed round-1 residual
 * core is reused and round 2 can be smaller and still decode. {@link #decode} is
 * the single-round special case.</p>
 */
public final class IbltDecoder {

    private static final Logger LOG = Logger.getLogger(IbltDecoder.class.getName());

    private IbltDecoder() {
    }

    /**
     * Decodes two sketches of the same M (A = source, B = target). Single round,
     * behavior unchanged from before.
     */
    public static IbltDecodeResult decode(IbltSketch a, IbltSketch b) {
        DiffView view = new DiffView(a, b);
        double dHat = view.f2DHat();
        List<DiffView> views = new ArrayList<>(1);
        views.add(view);
        return peel(views, dHat);
    }

    /**
     * Jointly decodes multi-round sketches (residual-core reuse). {@code as[i]} /
     * {@code bs[i]} are the source/target sketches of round i; each round's M may
     * differ (a round-1 M1 probe plus a larger M chosen in one step from d&#770;).
     * d&#770; is taken from the <b>first</b> round (M1) as the sizing signal.
     *
     * @param as per-round source sketches (the first is the round-1 M1 probe)
     * @param bs per-round target sketches, matching {@code as} one-to-one
     */
    public static IbltDecodeResult decodeJoint(List<IbltSketch> as, List<IbltSketch> bs) {
        if (as.isEmpty() || as.size() != bs.size()) {
            throw new IllegalArgumentException("joint decode needs matching non-empty sketch rounds: "
                    + as.size() + " vs " + bs.size());
        }
        List<DiffView> views = new ArrayList<>(as.size());
        for (int r = 0; r < as.size(); r++) {
            views.add(new DiffView(as.get(r), bs.get(r)));
        }
        double dHat = views.get(0).f2DHat();
        return peel(views, dHat);
    }

    /**
     * Joint peeling across multiple difference views. When a pure cell in any
     * view yields a symbol, it is subtracted from all views and their pure cells
     * are re-checked. Success = every view's residual core is cleared. dHat is
     * computed by the caller before peeling (while the arrays are untouched) and
     * passed in.
     */
    private static IbltDecodeResult peel(List<DiffView> views, double dHat) {
        long t0 = System.nanoTime();

        LongList plus = new LongList();
        LongList minus = new LongList();

        // queue elements encode (viewIdx << 32 | cellIdx).
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (int v = 0; v < views.size(); v++) {
            DiffView dv = views.get(v);
            for (int i = 0; i < dv.m; i++) {
                if (dv.isPure(i)) {
                    queue.add(((long) v << 32) | (i & 0xffffffffL));
                }
            }
        }

        while (!queue.isEmpty()) {
            long enc = queue.poll();
            int v = (int) (enc >>> 32);
            int i = (int) (enc & 0xffffffffL);
            DiffView src = views.get(v);
            if (!src.isPure(i)) {
                continue;
            }
            long fp = src.fpx[i];
            long id = src.idx[i];
            int side = (int) src.cnt[i];
            if (side == 1) {
                plus.add(id);
            } else {
                minus.add(id);
            }
            long cs = IbltHash.checksum(fp);
            // subtract this symbol from all views (each using its own M / cell positions).
            for (int w = 0; w < views.size(); w++) {
                DiffView dv = views.get(w);
                for (int p : IbltHash.positions(fp, dv.m, dv.hashSeed)) {
                    dv.cnt[p] -= side;
                    dv.fpx[p] ^= fp;
                    dv.idx[p] ^= id;
                    dv.chkx[p] ^= cs;
                    if (dv.isPure(p)) {
                        queue.add(((long) w << 32) | (p & 0xffffffffL));
                    }
                }
            }
        }

        int residual = 0;
        for (DiffView dv : views) {
            residual += dv.residual();
        }
        boolean success = residual == 0;
        int plusCount = plus.size();
        int minusCount = minus.size();
        int residualFinal = residual;
        int rounds = views.size();
        LOG.info(() -> String.format(
                "[iblt] decode: rounds=%d, M=%s, success=%b, plus=%d, minus=%d, residual=%d, dHat=%.1f, elapsedMs=%.1f",
                rounds, mList(views), success, plusCount, minusCount, residualFinal, dHat,
                (System.nanoTime() - t0) / 1e6));
        return new IbltDecodeResult(success, plus.toArray(), minus.toArray(), residual, dHat);
    }

    private static String mList(List<DiffView> views) {
        if (views.size() == 1) {
            return Integer.toString(views.get(0).m);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < views.size(); i++) {
            if (i > 0) {
                sb.append('+');
            }
            sb.append(views.get(i).m);
        }
        return sb.append(']').toString();
    }

    /** A peelable view of one difference sketch (A&minus;B): four channel arrays plus its own M. */
    private static final class DiffView {
        final int m;
        final long hashSeed;
        final long[] cnt;
        final long[] fpx;
        final long[] idx;
        final long[] chkx;

        DiffView(IbltSketch a, IbltSketch b) {
            int mm = a.bucketCount();
            if (mm != b.bucketCount()) {
                throw new IllegalArgumentException("bucket count mismatch: " + mm + " vs " + b.bucketCount());
            }
            if (a.hashSeed() != b.hashSeed()) {
                throw new IllegalArgumentException("hash seed mismatch: "
                        + a.hashSeed() + " vs " + b.hashSeed());
            }
            this.m = mm;
            this.hashSeed = a.hashSeed();
            this.cnt = new long[mm];
            this.fpx = new long[mm];
            this.idx = new long[mm];
            this.chkx = new long[mm];
            for (int i = 0; i < mm; i++) {
                cnt[i] = a.count(i) - b.count(i);
                fpx[i] = a.fpXor(i) ^ b.fpXor(i);
                idx[i] = a.idXor(i) ^ b.idXor(i);
                chkx[i] = a.chkXor(i) ^ b.chkXor(i);
            }
        }

        /** Pure cell: |count|==1 and the checksum matches. */
        boolean isPure(int i) {
            return (cnt[i] == 1 || cnt[i] == -1) && chkx[i] == IbltHash.checksum(fpx[i]);
        }

        /** F2 estimate d_hat (before peeling, on the count array). Interop spec &sect;9. */
        double f2DHat() {
            long sumC = 0;
            double sumC2 = 0;
            for (int i = 0; i < m; i++) {
                sumC += cnt[i];
                sumC2 += (double) cnt[i] * cnt[i];
            }
            double e = sumC2 - (double) sumC * sumC / m;
            double c = 1 - (double) IbltConstants.K / m;
            return e / (IbltConstants.K * c);
        }

        int residual() {
            int r = 0;
            for (int i = 0; i < m; i++) {
                if (cnt[i] != 0 || fpx[i] != 0 || idx[i] != 0 || chkx[i] != 0) {
                    r++;
                }
            }
            return r;
        }
    }

    /** Lightweight growable long array (avoids boxing). */
    private static final class LongList {
        private long[] data = new long[16];
        private int size;

        void add(long v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size + (size >> 1) + 1);
            }
            data[size++] = v;
        }

        long[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }

        int size() {
            return size;
        }
    }
}

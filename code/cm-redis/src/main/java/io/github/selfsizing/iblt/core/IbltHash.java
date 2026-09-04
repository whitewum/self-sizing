package io.github.selfsizing.iblt.core;

import static io.github.selfsizing.iblt.core.IbltConstants.BUCKET_SEED_A;
import static io.github.selfsizing.iblt.core.IbltConstants.BUCKET_SEED_B;
import static io.github.selfsizing.iblt.core.IbltConstants.BUCKET_SEED_C;
import static io.github.selfsizing.iblt.core.IbltConstants.CHECKSUM_SEED;
import static io.github.selfsizing.iblt.core.IbltConstants.MUL1;
import static io.github.selfsizing.iblt.core.IbltConstants.MUL2;

/**
 * IBLT interoperability hash primitives (splitmix64 / cell positions / checksum / fp parsing).
 *
 * <p>Mirrors the reference implementations. Moved in verbatim from the main
 * project; only the package name changed.</p>
 *
 * <p><b>Java unsigned pitfalls (the number-one interop killer):</b>
 * <ul>
 *   <li>splitmix64 right shift uses {@code >>>} (unsigned), never {@code >>}.</li>
 *   <li>cell-position modulo uses {@link Long#remainderUnsigned}, never {@code %}.</li>
 *   <li>fp parsing uses {@link Long#parseUnsignedLong}, never {@link Long#parseLong}.</li>
 *   <li>multiply/add/XOR: a Java {@code long} already wraps mod 2^64, matching
 *       uint64, so these can be used directly.</li>
 * </ul>
 */
public final class IbltHash {

    private IbltHash() {
    }

    /** Bare splitmix64 mix (no pre-increment); splitmix64(0)=0. */
    public static long sm64(long x) {
        x ^= (x >>> 30);
        x *= MUL1;
        x ^= (x >>> 27);
        x *= MUL2;
        x ^= (x >>> 31);
        return x;
    }

    /**
     * Cell positions positions(fp, M): k=3 distinct cells sampled without
     * replacement, matching the paper's uniform k-subset model. Three
     * independent splitmix64 streams feed rejection sampling: on a collision
     * with an earlier cell, the stream advances by {@link IbltConstants#RETRY_STEP}.
     *
     * @param fp 56-bit fingerprint
     * @param m  number of cells (bucket_count)
     * @return 3 distinct cell indices
     */
    public static int[] positions(long fp, long m) {
        return positions(fp, m, 0L);
    }

    /**
     * Cell-position mapping with a per-round salt. Both sides use the same salt
     * in a given round; changing the salt in round two is a fresh rehash.
     * salt=0 keeps bit-compatibility with the historical test vectors.
     */
    public static int[] positions(long fp, long m, long hashSeed) {
        long seedA = hashSeed == 0 ? BUCKET_SEED_A : sm64(BUCKET_SEED_A ^ hashSeed);
        long seedB = hashSeed == 0 ? BUCKET_SEED_B : sm64(BUCKET_SEED_B ^ hashSeed);
        long seedC = hashSeed == 0 ? BUCKET_SEED_C : sm64(BUCKET_SEED_C ^ hashSeed);
        int a = (int) Long.remainderUnsigned(sm64(seedA + fp), m);
        int b = distinctPosition(fp, seedB, m, a);
        int c = distinctPosition(fp, seedC, m, a, b);
        return new int[]{a, b, c};
    }

    private static int distinctPosition(long fp, long base, long m, int... avoid) {
        for (long k = 0; ; k++) {
            int p = (int) Long.remainderUnsigned(sm64(base + fp + k * IbltConstants.RETRY_STEP), m);
            boolean duplicate = false;
            for (int q : avoid) {
                if (p == q) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                return p;
            }
        }
    }

    /** checksum(fp) = splitmix64(CHECKSUM_SEED + fp); must be non-linear. */
    public static long checksum(long fp) {
        return sm64(CHECKSUM_SEED + fp);
    }

    /**
     * Parses the first 14 hex characters of an md5 into a 56-bit fp (big-endian, unsigned).
     *
     * @param md5Hex an md5 hex string, lower or upper case, at least 14 chars
     * @return the 56-bit fp (a positive long in [0, 2^56))
     */
    public static long parseFp(String md5Hex) {
        return Long.parseUnsignedLong(md5Hex.substring(0, 14), 16);
    }

    /**
     * A cheap 56-bit proxy id for a primary key: splitmix over the
     * {@link PkTupleCanonicalizer canonically encoded} primary-key bytes.
     *
     * <p>It is only the {@code idXor} payload of an IBLT cell (used to "identify
     * a row" &mdash; to bind the +side old fingerprint and the &minus;side new
     * fingerprint of the same primary key to one id); it does not take part in
     * cell placement or cancellation, does <b>not</b> need md5, and does not need
     * to be bit-identical across SQL engines &mdash; it only needs <b>the same
     * Java on both sides</b> to produce the same id for the same logical primary
     * key. Cross-database consistency is guaranteed by
     * {@link PkTupleCanonicalizer}'s per-type normalization (e.g. {@code 100} vs
     * {@code 100.00}, trailing-space CHAR).</p>
     *
     * <p><b>Interop note:</b> now that id is cheap8, this Java sketch can no
     * longer decode against a native sidecar CSV that uses md5 ids (the fp is
     * unchanged and cancellation still works, only idXor will not match). Both
     * sides of this project run Java, so there is no impact.</p>
     *
     * <p>8-byte chunks feed {@link #sm64}, the tail is mixed once separately;
     * {@code & 0x00FFFFFFFFFFFFFF} folds back to 56-bit, same domain as fp, stays positive.</p>
     *
     * @param b the canonically encoded primary-key bytes ({@code PkTupleCanonicalizer.encode})
     * @return the 56-bit proxy id (a positive long)
     */
    public static long cheap8(byte[] b) {
        long h = 0x9E3779B97F4A7C15L;
        int i = 0, n = b.length;
        for (; i + 8 <= n; i += 8) {
            long w = 0;
            for (int j = 0; j < 8; j++) {
                w = (w << 8) | (b[i + j] & 0xffL);
            }
            h = sm64(h ^ w);
        }
        long t = 0;
        for (; i < n; i++) {
            t = (t << 8) | (b[i] & 0xffL);
        }
        return sm64(h ^ t) & 0x00FFFFFFFFFFFFFFL;
    }

    /**
     * Interop self-check: returns true if it hits the spec test vectors.
     * Worth running at startup / in CI as a gate.
     */
    public static boolean selfTest() {
        boolean ok = true;
        ok &= sm64(0L) == 0x0L;
        ok &= sm64(1L) == 0x5692161D100B05E5L;
        ok &= sm64(0x123456789ABCDEF0L) == 0x9629F58E8EC5B906L;

        long fp1 = 0x206D7A4C58CD2CL;
        ok &= checksum(fp1) == 0xF5AA3C0C47B695A8L;
        ok &= java.util.Arrays.equals(positions(fp1, 6000), new int[]{3301, 359, 5223});
        ok &= java.util.Arrays.equals(positions(fp1, 600), new int[]{301, 359, 423});

        long fp2 = 0xBC6A786948D774L;
        ok &= checksum(fp2) == 0x2AE6BC9BD7B884D7L;
        ok &= java.util.Arrays.equals(positions(fp2, 6000), new int[]{1431, 5820, 2338});

        // Rejection sampling: fp=0x20d at m=6000 collides on the raw stream-B
        // candidate, so positions must re-sample to three distinct cells.
        ok &= java.util.Arrays.equals(positions(0x20dL, 6000), new int[]{5167, 4921, 4024});
        return ok;
    }
}

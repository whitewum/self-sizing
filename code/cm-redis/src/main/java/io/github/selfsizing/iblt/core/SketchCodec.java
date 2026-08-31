package io.github.selfsizing.iblt.core;

import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * Wire codec for an IBLT sketch (used to exchange sketches sidecar &rarr; controller).
 *
 * <p>Format (big-endian): {@code [magic 'IBS2'(4B)][M int(4B)][hashSeed long(8B)]
 * [count M*long][fpXor M*long][idXor M*long][chkXor M*long]}.
 * Size = 16 + 32*M bytes. The sketch is the "small control payload" &mdash; it,
 * not the whole-table fingerprint set, is what crosses the wire.</p>
 *
 * <p>Pure JDK ({@link ByteBuffer} + {@link Base64}), no third-party dependencies.
 * {@link #encodeBase64} / {@link #decodeBase64} are convenient for a text HTTP body.</p>
 */
public final class SketchCodec {

    private static final int MAGIC = 0x49425332; // "IBS2"
    private static final int HEADER = 16;        // magic + M + hashSeed
    private static final int BYTES_PER_CELL = 32; // 4 channels * 8B

    private SketchCodec() {
    }

    /** Serializes to compact bytes. */
    public static byte[] encode(IbltSketch sketch) {
        int m = sketch.bucketCount();
        ByteBuffer buf = ByteBuffer.allocate(HEADER + BYTES_PER_CELL * m);
        buf.putInt(MAGIC);
        buf.putInt(m);
        buf.putLong(sketch.hashSeed());
        buf.asLongBuffer().put(sketch.countChannel()).put(sketch.fpXorChannel())
                .put(sketch.idXorChannel()).put(sketch.chkXorChannel());
        return buf.array();
    }

    /** Rebuilds a sketch from bytes. */
    public static IbltSketch decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("bad sketch magic: 0x" + Integer.toHexString(magic));
        }
        int m = buf.getInt();
        if (m <= 0 || bytes.length != HEADER + BYTES_PER_CELL * m) {
            throw new IllegalArgumentException("bad sketch length: M=" + m + ", bytes=" + bytes.length);
        }
        long hashSeed = buf.getLong();
        long[] count = new long[m];
        long[] fpXor = new long[m];
        long[] idXor = new long[m];
        long[] chkXor = new long[m];
        buf.asLongBuffer().get(count).get(fpXor).get(idXor).get(chkXor);
        return new IbltSketch(m, hashSeed, count, fpXor, idXor, chkXor);
    }

    /** Base64 of the encoding (for a text HTTP body). */
    public static String encodeBase64(IbltSketch sketch) {
        return Base64.getEncoder().encodeToString(encode(sketch));
    }

    /** Decodes from Base64. */
    public static IbltSketch decodeBase64(String b64) {
        return decode(Base64.getDecoder().decode(b64));
    }

    /** Wire size in bytes (for metrics / logs). */
    public static int wireBytes(int bucketCount) {
        return HEADER + BYTES_PER_CELL * bucketCount;
    }
}

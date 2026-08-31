package io.github.selfsizing.iblt.core;

/**
 * IBLT interoperability constants (from the interop spec &sect;3).
 *
 * <p>These constants are what makes cross-implementation / cross-language /
 * cross-engine decoding work; if any one of them differs, the sketches will not
 * decode against each other. Do not change the values. They match the reference
 * C++ implementation.</p>
 *
 * <p>This class was moved verbatim into the standalone sidecar module from the
 * main project; only the package name changed, and the values must stay
 * bit-identical to the main project.</p>
 */
public final class IbltConstants {

    private IbltConstants() {
    }

    /** splitmix64 multiplication constants. */
    public static final long MUL1 = 0xBF58476D1CE4E5B9L;
    public static final long MUL2 = 0x94D049BB133111EBL;

    /** Seeds for the 3 cell positions (k=3). */
    public static final long BUCKET_SEED_A = 0xA1B2C3D4E5F60718L;
    public static final long BUCKET_SEED_B = 0x1234567890ABCDEFL;
    public static final long BUCKET_SEED_C = 0xFEDCBA9876543210L;

    /** Checksum seed (must be non-linear; uses splitmix64). */
    public static final long CHECKSUM_SEED = 0x9E3779B97F4A7C15L;

    /** Each element goes into k=3 cells (may be &lt;3 after de-duplication). */
    public static final int K = 3;

    /** The fingerprint occupies 56 bits, in [0, 2^56). */
    public static final long FP_MASK_56 = (1L << 56) - 1L;
}

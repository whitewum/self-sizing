package io.github.selfsizing.iblt.relational;

/** IBLT capacity rule for the experiment: first round fixed at 512, second round computed from dHat only, with no rounding to a power of two. */
public final class IbltSizing {
    public static final int FIRST_ROUND_BUCKETS = 512;
    public static final double SECOND_ROUND_ALPHA = 1.52;
    public static final double ENGINEERING_MARGIN = 1.20;

    private IbltSizing() {
    }

    public static int secondRoundBuckets(double dHat, int cap) {
        double raw = Math.ceil(SECOND_ROUND_ALPHA * ENGINEERING_MARGIN * dHat);
        if (!Double.isFinite(raw) || raw <= 0 || raw > cap) {
            throw new IllegalArgumentException("second-round bucket count out of range: dHat="
                    + dHat + ", raw=" + raw + ", cap=" + cap);
        }
        // Important: do not round m upward to a power of two. If the formula itself
        // naturally produces a power of two, keep that exact result; the prohibition
        // is about an extra hidden round-up, not about the numeric value 2^k itself.
        return (int) raw;
    }
}

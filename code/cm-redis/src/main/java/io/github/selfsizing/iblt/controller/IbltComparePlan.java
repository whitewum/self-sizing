package io.github.selfsizing.iblt.controller;

/**
 * Controller parameters for one comparison task. No connection info &mdash; that
 * lives in {@link SidecarClient}.
 *
 * <ul>
 *   <li>{@code bucketCount0}: initial bucket count M0 (formal default 512).</li>
 *   <li>{@code bucketCap}: safety upper bound for the round-two target M.</li>
 *   <li>{@code dHatJump}: <b>deprecated field</b>. The controller now always does
 *       the residual-core d&#770; one-step jump:
 *       {@code M2=ceil(alpha*engineeringMargin*dHat)}; the formal default is
 *       {@code ceil(1.52*1.2*dHat)}. Round two does a fresh rehash and joint
 *       peeling with round one; if it still fails it goes straight to FALLBACK,
 *       no more per-round doubling. This field is kept only for constructor compatibility.</li>
 *   <li>{@code valueSampleLimit}: <b>the cap on how many rows have their values
 *       fetched</b>. The difference pk set is always returned in full (pks are
 *       small); pulling each row's real column values back to the controller over
 *       the network is the expensive part and is pointless when there are many
 *       differences. <b>{@code <=0} = full (default)</b>; a positive number =
 *       fetch values only for the first N candidate pks (e.g. 100).
 *       Engine-agnostic: it truncates the pk list directly, avoiding per-database
 *       LIMIT/TOP dialect divergence.</li>
 * </ul>
 */
public final class IbltComparePlan {

    private final int bucketCount0;
    private final int bucketCap;
    private final int valueSampleLimit;
    private final boolean dHatJump;
    private final boolean jointPeel;
    private final double secondRoundAlpha;
    private final double engineeringMargin;

    public IbltComparePlan(int bucketCount0, int bucketCap, int valueSampleLimit) {
        this(bucketCount0, bucketCap, valueSampleLimit, false, true);
    }

    public IbltComparePlan(int bucketCount0, int bucketCap, int valueSampleLimit, boolean dHatJump) {
        this(bucketCount0, bucketCap, valueSampleLimit, dHatJump, true);
    }

    public IbltComparePlan(int bucketCount0, int bucketCap, int valueSampleLimit, boolean dHatJump,
                           boolean jointPeel) {
        this(bucketCount0, bucketCap, valueSampleLimit, dHatJump, jointPeel, 1.52);
    }

    public IbltComparePlan(int bucketCount0, int bucketCap, int valueSampleLimit, boolean dHatJump,
                           boolean jointPeel, double secondRoundAlpha) {
        this(bucketCount0, bucketCap, valueSampleLimit, dHatJump, jointPeel,
                secondRoundAlpha, 1.2);
    }

    public IbltComparePlan(int bucketCount0, int bucketCap, int valueSampleLimit, boolean dHatJump,
                           boolean jointPeel, double secondRoundAlpha, double engineeringMargin) {
        this.bucketCount0 = bucketCount0;
        this.bucketCap = bucketCap;
        this.valueSampleLimit = valueSampleLimit;
        this.dHatJump = dHatJump;
        this.jointPeel = jointPeel;
        this.secondRoundAlpha = secondRoundAlpha;
        this.engineeringMargin = engineeringMargin;
    }

    /** Default precision profile: M0=512, M2=ceil(1.52*1.2*dHat), fresh rehash + joint peeling. */
    public static IbltComparePlan defaults() {
        return new IbltComparePlan(512, 1 << 20, 0, true, true, 1.52, 1.2);
    }

    /** Same as the default, but the row-value sampling cap is set to {@code limit} ({@code <=0} means full). */
    public IbltComparePlan withValueSampleLimit(int limit) {
        return new IbltComparePlan(bucketCount0, bucketCap, limit, dHatJump, jointPeel,
                secondRoundAlpha, engineeringMargin);
    }

    /** @deprecated no-op: the controller always does the d&#770; one-step jump; this switch no longer changes any behavior. */
    @Deprecated
    public IbltComparePlan withDhatJump(boolean enabled) {
        return new IbltComparePlan(bucketCount0, bucketCap, valueSampleLimit, enabled, jointPeel,
                secondRoundAlpha, engineeringMargin);
    }

    /** Enable/disable "residual-core reuse joint peeling": round 2+ ping-pong decodes together with the earlier rounds' residual cores. On by default. */
    public IbltComparePlan withJointPeel(boolean enabled) {
        return new IbltComparePlan(bucketCount0, bucketCap, valueSampleLimit, dHatJump, enabled,
                secondRoundAlpha, engineeringMargin);
    }

    /** Whether round 2+ reuses the earlier rounds' failed residual cores for joint peeling (default true). */
    public boolean jointPeel() {
        return jointPeel;
    }

    public int bucketCount0() {
        return bucketCount0;
    }

    public int bucketCap() {
        return bucketCap;
    }

    public int valueSampleLimit() {
        return valueSampleLimit;
    }

    /** Whether to fetch all row values. */
    public boolean fullValueFetch() {
        return valueSampleLimit <= 0;
    }

    /** Deprecated compatibility value; the controller ignores it and always does the residual-core d&#770; one-step jump. */
    public boolean dHatJump() {
        return dHatJump;
    }

    public double secondRoundAlpha() {
        return secondRoundAlpha;
    }

    public double engineeringMargin() {
        return engineeringMargin;
    }

    public double effectiveSecondRoundAlpha() {
        return secondRoundAlpha * engineeringMargin;
    }
}

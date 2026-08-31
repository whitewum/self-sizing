package io.github.selfsizing.iblt.controller;

import io.github.selfsizing.iblt.sidecar.RecheckResponse;

import java.util.List;

/**
 * The output of one comparison.
 *
 * <p>The core is {@link #candidatePks} (the <b>full</b> list of difference
 * primary keys); row values are only fetched for the sampled candidates (see
 * {@link IbltComparePlan#valueSampleLimit()}). When decode fails,
 * {@link #success}=false, but {@link #dHat} is still a usable output (an estimate
 * of the symmetric-difference size; a failed residual core still carries value).</p>
 */
public final class IbltCompareResult {

    private final boolean success;
    private final int bucketCountAtDecode;
    private final int plusCount;
    private final int minusCount;
    private final int residualBuckets;
    private final double dHat;
    private final List<String> candidatePks;
    private final int sampledPkCount;
    private final RecheckResponse recheckA;
    private final RecheckResponse recheckB;
    private final long sketchWireBytesA;
    private final long sketchWireBytesB;

    public IbltCompareResult(boolean success, int bucketCountAtDecode, int plusCount, int minusCount,
                             int residualBuckets, double dHat, List<String> candidatePks,
                             int sampledPkCount, RecheckResponse recheckA, RecheckResponse recheckB,
                             long sketchWireBytesA, long sketchWireBytesB) {
        this.success = success;
        this.bucketCountAtDecode = bucketCountAtDecode;
        this.plusCount = plusCount;
        this.minusCount = minusCount;
        this.residualBuckets = residualBuckets;
        this.dHat = dHat;
        this.candidatePks = List.copyOf(candidatePks);
        this.sampledPkCount = sampledPkCount;
        this.recheckA = recheckA;
        this.recheckB = recheckB;
        this.sketchWireBytesA = sketchWireBytesA;
        this.sketchWireBytesB = sketchWireBytesB;
    }

    /** Whether decode succeeded (residual==0). */
    public boolean isSuccess() {
        return success;
    }

    public int bucketCountAtDecode() {
        return bucketCountAtDecode;
    }

    public int plusCount() {
        return plusCount;
    }

    public int minusCount() {
        return minusCount;
    }

    public int residualBuckets() {
        return residualBuckets;
    }

    public double dHat() {
        return dHat;
    }

    /** Full list of difference primary keys (de-duplicated). */
    public List<String> candidatePks() {
        return candidatePks;
    }

    /** How many candidates actually had their values fetched (= min(full, valueSampleLimit)). */
    public int sampledPkCount() {
        return sampledPkCount;
    }

    public RecheckResponse recheckA() {
        return recheckA;
    }

    public RecheckResponse recheckB() {
        return recheckB;
    }

    /** Sketch wire bytes for each side (the amount exchanged over the network). */
    public long sketchWireBytesA() {
        return sketchWireBytesA;
    }

    public long sketchWireBytesB() {
        return sketchWireBytesB;
    }

    @Override
    public String toString() {
        return "IbltCompareResult{success=" + success + ", M=" + bucketCountAtDecode
                + ", plus=" + plusCount + ", minus=" + minusCount + ", residual=" + residualBuckets
                + ", dHat=" + dHat + ", diffPks=" + candidatePks.size()
                + ", sampledValues=" + sampledPkCount
                + ", recheckHitA=" + (recheckA == null ? 0 : recheckA.rowCount())
                + ", recheckHitB=" + (recheckB == null ? 0 : recheckB.rowCount())
                + ", sketchBytesA=" + sketchWireBytesA + ", sketchBytesB=" + sketchWireBytesB + '}';
    }
}

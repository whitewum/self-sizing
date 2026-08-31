package io.github.selfsizing.iblt.core;

/**
 * IBLT decode result. Moved in from the main project.
 *
 * <ul>
 *   <li>{@code plusIds}: side=+1, the ids carried by fingerprints that appear
 *       only on the source (A) &mdash; source-only rows, or the source side of a
 *       value-changed row.</li>
 *   <li>{@code minusIds}: side=-1, the ids carried by fingerprints that appear
 *       only on the target (B) &mdash; target-only rows, or the target side of a
 *       value-changed row.</li>
 *   <li>a value-changed row = the same id appears in both plusIds and minusIds.</li>
 *   <li>{@code success}: true if peeling leaves no residual (residual==0);
 *       otherwise this M does not fit and a re-bucket or fallback is needed.</li>
 *   <li>{@code dHat}: the F2 estimate of the symmetric difference |A&triangle;B|
 *       (number of distinct fingerprints, not changed rows; a changed row
 *       contributes 2), computed on the difference sketch before peeling.</li>
 * </ul>
 */
public final class IbltDecodeResult {

    private final boolean success;
    private final long[] plusIds;
    private final long[] minusIds;
    private final int residualBuckets;
    private final double dHat;

    public IbltDecodeResult(boolean success,
                            long[] plusIds,
                            long[] minusIds,
                            int residualBuckets,
                            double dHat) {
        this.success = success;
        this.plusIds = plusIds;
        this.minusIds = minusIds;
        this.residualBuckets = residualBuckets;
        this.dHat = dHat;
    }

    public boolean isSuccess() {
        return success;
    }

    public long[] getPlusIds() {
        return plusIds;
    }

    public long[] getMinusIds() {
        return minusIds;
    }

    public int getResidualBuckets() {
        return residualBuckets;
    }

    public double getdHat() {
        return dHat;
    }

    public int diffCandidateCount() {
        return plusIds.length + minusIds.length;
    }

    @Override
    public String toString() {
        return "IbltDecodeResult{success=" + success
                + ", plus=" + plusIds.length
                + ", minus=" + minusIds.length
                + ", residual=" + residualBuckets
                + ", dHat=" + dHat + '}';
    }
}

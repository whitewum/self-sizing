package io.github.selfsizing.iblt.controller;

/**
 * Controller orchestration interface: coordinates the two sidecars through one
 * IBLT comparison.
 */
public interface IbltCompareController {

    /**
     * Runs the full pipeline over both sides: buildSketch on each side &rarr;
     * local decode (with re-bucket retry) &rarr; resolve candidate pks &rarr;
     * recheck (row-value sampling) &rarr; summarize.
     *
     * @param a    source sidecar
     * @param b    target sidecar
     * @param plan controller parameters (M0, caps, row-value sampling cap)
     */
    IbltCompareResult compare(SidecarClient a, SidecarClient b, IbltComparePlan plan) throws Exception;
}

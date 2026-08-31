package io.github.selfsizing.iblt.sidecar;

/**
 * The sidecar's outward capability: connect to the local database and build a
 * sketch / recheck.
 *
 * <p>The local implementation {@link LocalIbltSidecarService} calls straight
 * through a held connection; an HTTP implementation exposes the same two methods
 * as a remote API (controller A POSTs to sidecar B). The controller programs
 * against this interface and does not care whether the sidecar is in-process or
 * remote.</p>
 */
public interface IbltSidecarService {

    /** Scans this side's fingerprints, builds a sketch, and keeps the fingerprint cache for re-bucketing without re-scanning. */
    BuildSketchResponse buildSketch(BuildSketchRequest request) throws Exception;

    /** Fetches real row values from this side's table by candidate primary key for a value-by-value comparison. */
    RecheckResponse recheck(RecheckRequest request) throws Exception;
}

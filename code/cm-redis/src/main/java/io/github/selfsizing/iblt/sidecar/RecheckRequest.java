package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.sql.RecheckQuery;
import java.util.List;

/**
 * Recheck request: which table ({@link RecheckQuery}) and which batch of candidate primary keys to fetch real row values for.
 */
public final class RecheckRequest {

    private final RecheckQuery query;
    private final List<String> candidatePks;

    public RecheckRequest(RecheckQuery query, List<String> candidatePks) {
        this.query = query;
        this.candidatePks = List.copyOf(candidatePks);
    }

    public RecheckQuery query() {
        return query;
    }

    /** Raw candidate primary-key values (the difference candidates from IBLT decode, mapped back to real pks). */
    public List<String> candidatePks() {
        return candidatePks;
    }
}

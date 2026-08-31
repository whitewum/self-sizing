package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.FingerprintStore;
import java.util.Map;
import java.util.Set;

/** Spill store with a low-allocation candidate lookup path. */
interface CandidateResolvingStore extends FingerprintStore {
    Map<Long, String> resolveIds(Set<Long> wanted) throws Exception;
}

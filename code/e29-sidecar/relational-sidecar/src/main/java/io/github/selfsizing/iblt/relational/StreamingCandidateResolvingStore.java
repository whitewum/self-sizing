package io.github.selfsizing.iblt.relational;

import java.util.Set;

/** Candidate resolver that can emit resolved PKs without materializing the whole response. */
interface StreamingCandidateResolvingStore extends CandidateResolvingStore {
    @FunctionalInterface
    interface PkConsumer {
        void accept(String pk) throws Exception;
    }

    void resolveIdsStream(Set<Long> candidates, PkConsumer consumer) throws Exception;
}

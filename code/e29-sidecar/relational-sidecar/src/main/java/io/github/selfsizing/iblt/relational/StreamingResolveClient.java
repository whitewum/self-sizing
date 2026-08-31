package io.github.selfsizing.iblt.relational;

/** Optional sidecar client capability for consuming resolve results as they arrive. */
public interface StreamingResolveClient {
    /** Reserved line prefix used to report a resolve failure after chunked headers are committed. */
    String ERROR_PREFIX = "\u0000E29_RESOLVE_ERROR\t";

    @FunctionalInterface
    interface PkConsumer {
        void accept(String pk) throws Exception;
    }

    void resolveIdsStream(String sessionId, long[] ids, PkConsumer pkConsumer) throws Exception;
}

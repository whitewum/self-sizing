package io.github.selfsizing.iblt.core;

import java.io.Closeable;

/**
 * Fingerprint-cache abstraction: after ingesting {@code (fp, id, pk)} rows, it
 * serves both sketch construction (sequential scan of fp, id) and the recheck
 * mapping (sequential scan of id, pk).
 *
 * <p>Moved in from the main project. The main project also has file-backed
 * spill backends; this standalone module ships only
 * {@link MemoryFingerprintStore} for now (pure JDK, zero dependencies). A
 * spill-to-disk backend for large tables can be added later as needed.</p>
 *
 * <p>The access pattern is always "write once, then scan the whole thing
 * sequentially several times", so row order does not matter. Not thread-safe;
 * use it single-threaded per side (one instance per side, ingesting in
 * parallel).</p>
 */
public interface FingerprintStore extends Closeable {

    /** Appends a row. fp is 56-bit; id is the 64-bit cheap8 of the primary key; pk is the raw primary-key value (as-is). */
    void add(long fp, long id, String pk) throws Exception;

    /** End of ingest: flush / commit, switch to readable state. */
    void finishIngest() throws Exception;

    /** Number of cached rows. */
    long size();

    /** Sequentially scans each row's (fp, id), for building the IBLT sketch. */
    void forEachFpId(FpIdConsumer consumer) throws Exception;

    /** Sequentially scans each row's (id, pk), for the recheck id&rarr;pk mapping. */
    void forEachIdPk(IdPkConsumer consumer) throws Exception;

    /** Releases resources; file-backed implementations delete their temp files. */
    @Override
    void close();

    @FunctionalInterface
    interface FpIdConsumer {
        void accept(long fp, long id);
    }

    @FunctionalInterface
    interface IdPkConsumer {
        void accept(long id, String pk);
    }
}

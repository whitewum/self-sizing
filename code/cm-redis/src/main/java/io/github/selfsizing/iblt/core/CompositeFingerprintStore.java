package io.github.selfsizing.iblt.core;

import java.util.List;

/**
 * Read-only logical merge of per-worker fingerprint stores.
 *
 * <p>Workers ingest independently, then sketch build / id resolution scans stores in sequence.
 * No physical merge is needed.</p>
 */
public final class CompositeFingerprintStore implements FingerprintStore {

    private final List<FingerprintStore> stores;

    public CompositeFingerprintStore(List<FingerprintStore> stores) {
        this.stores = List.copyOf(stores);
    }

    @Override
    public void add(long fp, long id, String pk) {
        throw new UnsupportedOperationException("composite store is read-only");
    }

    @Override
    public void finishIngest() {
        // Worker stores are already finished.
    }

    @Override
    public long size() {
        long n = 0;
        for (FingerprintStore store : stores) {
            n += store.size();
        }
        return n;
    }

    @Override
    public void forEachFpId(FpIdConsumer consumer) throws Exception {
        for (FingerprintStore store : stores) {
            store.forEachFpId(consumer);
        }
    }

    @Override
    public void forEachIdPk(IdPkConsumer consumer) throws Exception {
        for (FingerprintStore store : stores) {
            store.forEachIdPk(consumer);
        }
    }

    @Override
    public void close() {
        for (FingerprintStore store : stores) {
            store.close();
        }
    }
}

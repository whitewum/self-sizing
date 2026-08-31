package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.FingerprintStore;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** File spill store that skips decoding non-candidate PK payloads during resolve. */
final class SelectiveFileFingerprintStore implements StreamingCandidateResolvingStore {
    private static final int WRITE_BUFFER = 1 << 20;
    private static final int READ_BUFFER = 4 << 20;
    private final Path fpidPath;
    private final Path pkPath;
    private DataOutputStream fpidOut;
    private DataOutputStream pkOut;
    private long size;

    SelectiveFileFingerprintStore(String base) throws Exception {
        fpidPath = Path.of(base + ".fpid");
        pkPath = Path.of(base + ".pk");
        fpidOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fpidPath.toFile()), WRITE_BUFFER));
        pkOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(pkPath.toFile()), WRITE_BUFFER));
    }

    @Override public void add(long fp, long id, String pk) throws Exception {
        fpidOut.writeLong(fp);
        fpidOut.writeLong(id);
        if (pk == null) {
            pkOut.writeInt(-1);
        } else {
            byte[] bytes = pk.getBytes(StandardCharsets.UTF_8);
            pkOut.writeInt(bytes.length);
            pkOut.write(bytes);
        }
        size++;
    }

    @Override public void finishIngest() throws Exception { closeWriters(); }
    @Override public long size() { return size; }

    @Override public void forEachFpId(FingerprintStore.FpIdConsumer consumer) throws Exception {
        try (DataInputStream in = input(fpidPath)) {
            for (long i = 0; i < size; i++) consumer.accept(in.readLong(), in.readLong());
        }
    }

    @Override public void forEachIdPk(FingerprintStore.IdPkConsumer consumer) throws Exception {
        try (DataInputStream fpid = input(fpidPath); DataInputStream pk = input(pkPath)) {
            for (long i = 0; i < size; i++) {
                fpid.readLong();
                long id = fpid.readLong();
                int length = pk.readInt();
                consumer.accept(id, length < 0 ? null : readString(pk, length));
            }
        }
    }

    @Override public Map<Long, String> resolveIds(Set<Long> candidates) throws Exception {
        Set<Long> remaining = new HashSet<>(candidates);
        Map<Long, String> found = new HashMap<>();
        if (remaining.isEmpty()) return found;
        try (DataInputStream fpid = input(fpidPath); DataInputStream pk = input(pkPath)) {
            for (long i = 0; i < size && !remaining.isEmpty(); i++) {
                fpid.readLong();
                long id = fpid.readLong();
                int length = pk.readInt();
                if (length < 0) continue;
                if (remaining.contains(id)) {
                    found.putIfAbsent(id, readString(pk, length));
                    remaining.remove(id);
                } else {
                    pk.skipNBytes(length);
                }
            }
        }
        return found;
    }

    @Override public void resolveIdsStream(Set<Long> candidates, PkConsumer consumer) throws Exception {
        Set<Long> remaining = new HashSet<>(candidates);
        if (remaining.isEmpty()) return;
        try (DataInputStream fpid = input(fpidPath); DataInputStream pk = input(pkPath)) {
            for (long i = 0; i < size && !remaining.isEmpty(); i++) {
                fpid.readLong();
                long id = fpid.readLong();
                int length = pk.readInt();
                if (length < 0) continue;
                if (remaining.remove(id)) {
                    consumer.accept(readString(pk, length));
                } else {
                    pk.skipNBytes(length);
                }
            }
        }
    }

    private static String readString(DataInputStream in, int length) throws Exception {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static DataInputStream input(Path path) throws Exception {
        return new DataInputStream(new BufferedInputStream(new FileInputStream(path.toFile()), READ_BUFFER));
    }

    private void closeWriters() throws Exception {
        if (fpidOut != null) { fpidOut.close(); fpidOut = null; }
        if (pkOut != null) { pkOut.close(); pkOut = null; }
    }

    @Override public void close() {
        try { closeWriters(); } catch (Exception ignored) { }
        try { Files.deleteIfExists(fpidPath); } catch (Exception ignored) { }
        try { Files.deleteIfExists(pkPath); } catch (Exception ignored) { }
    }
}

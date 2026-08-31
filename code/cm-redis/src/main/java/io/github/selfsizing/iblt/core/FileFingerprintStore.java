package io.github.selfsizing.iblt.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Local binary-file backend: the full fingerprint set spills to disk, heap use
 * is O(1) (read/write buffers only), for very large tables to avoid OOM.
 *
 * <p>Moved in from the main project to this zero-dependency module (the lombok
 * logger is replaced with java.util.logging). The two files are separate so the
 * hottest scan (sketch build) only touches a compact fixed-length stream:</p>
 * <ul>
 *   <li>{@code .fpid}: fixed 16B/row = fp(8B) + id(8B), big-endian. Row count = file length / 16.</li>
 *   <li>{@code .pk}: variable length, each row is {@code int32 length} (-1 = null) + that many
 *       UTF-8 bytes, in the same order as {@code .fpid}.</li>
 * </ul>
 *
 * <p>Pure sequential IO, no parsing / SQL cost: {@link #forEachFpId} reads only
 * {@code .fpid}; {@link #forEachIdPk} reads both streams in lockstep.
 * {@link #close()} deletes both files. The write buffer size is configurable (a
 * large buffer amortizes syscalls).</p>
 */
public final class FileFingerprintStore implements FingerprintStore {

    private static final Logger LOG = Logger.getLogger(FileFingerprintStore.class.getName());

    /** Read buffer fixed at 4MB (sequential read; no need for 500MB-scale). */
    private static final int READ_BUFFER = 4 << 20;

    private final String fpidPath;
    private final String pkPath;

    private DataOutputStream fpidOut;
    private DataOutputStream pkOut;
    private long n;

    public FileFingerprintStore(String basePath) throws IOException {
        this(basePath, 1 << 20);
    }

    /**
     * @param basePath    temp-file base name ({@code .fpid} / {@code .pk} appended)
     * @param bufferBytes buffer bytes per write stream (one each for the two streams)
     */
    public FileFingerprintStore(String basePath, int bufferBytes) throws IOException {
        int buf = Math.max(1 << 16, bufferBytes);
        this.fpidPath = basePath + ".fpid";
        this.pkPath = basePath + ".pk";
        this.fpidOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fpidPath), buf));
        this.pkOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(pkPath), buf));
    }

    @Override
    public void add(long fp, long id, String pk) throws IOException {
        fpidOut.writeLong(fp);
        fpidOut.writeLong(id);
        if (pk == null) {
            pkOut.writeInt(-1);
        } else {
            byte[] b = pk.getBytes(StandardCharsets.UTF_8);
            pkOut.writeInt(b.length);
            pkOut.write(b);
        }
        n++;
    }

    @Override
    public void finishIngest() throws IOException {
        closeWriters();
    }

    private void closeWriters() throws IOException {
        if (fpidOut != null) {
            fpidOut.flush();
            fpidOut.close();
            fpidOut = null;
        }
        if (pkOut != null) {
            pkOut.flush();
            pkOut.close();
            pkOut = null;
        }
    }

    @Override
    public long size() {
        return n;
    }

    @Override
    public void forEachFpId(FpIdConsumer consumer) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(fpidPath), READ_BUFFER))) {
            for (long i = 0; i < n; i++) {
                long fp = in.readLong();
                long id = in.readLong();
                consumer.accept(fp, id);
            }
        }
    }

    @Override
    public void forEachIdPk(IdPkConsumer consumer) throws IOException {
        try (DataInputStream fpidIn = new DataInputStream(
                new BufferedInputStream(new FileInputStream(fpidPath), READ_BUFFER));
             DataInputStream pkIn = new DataInputStream(
                     new BufferedInputStream(new FileInputStream(pkPath), READ_BUFFER))) {
            for (long i = 0; i < n; i++) {
                fpidIn.readLong();          // skip fp
                long id = fpidIn.readLong();
                int len = pkIn.readInt();
                String pk = null;
                if (len >= 0) {
                    byte[] b = new byte[len];
                    pkIn.readFully(b);
                    pk = new String(b, StandardCharsets.UTF_8);
                }
                consumer.accept(id, pk);
            }
        }
    }

    @Override
    public void close() {
        try {
            closeWriters();
        } catch (IOException e) {
            LOG.warning("[iblt] file store flush error: " + e.getMessage());
        }
        deleteQuietly(fpidPath);
        deleteQuietly(pkPath);
    }

    private void deleteQuietly(String path) {
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            LOG.warning("[iblt] delete temp file " + path + " failed: " + e.getMessage());
        }
    }
}

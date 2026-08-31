package io.github.selfsizing.iblt.relational;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Creates transient Oracle physical ROWID chunks without requiring catalog-view grants. */
public final class OracleRowidChunker {
    private OracleRowidChunker() { }

    public record Chunk(String startRowid, String endRowid) {
        public String predicate() { return rowidRange(startRowid, endRowid); }
    }

    public static List<Chunk> create(String jdbcUrl, String qualifiedTable, int desiredChunks)
            throws Exception {
        if (desiredChunks < 1) throw new IllegalArgumentException("desiredChunks must be positive");
        String[] name = ownerAndTable(qualifiedTable);
        String task = "E29R" + Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        List<Chunk> chunks = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            long blocks;
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT blocks FROM user_segments WHERE segment_name=? AND segment_type='TABLE'")) {
                st.setString(1, name[1]);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("table segment not found in current schema: "
                                + qualifiedTable);
                    }
                    blocks = rs.getLong(1);
                }
            }
            long chunkBlocks = Math.max(1, (blocks + desiredChunks - 1) / desiredChunks);
            try (CallableStatement st = conn.prepareCall(
                    "BEGIN DBMS_PARALLEL_EXECUTE.CREATE_TASK(?); END;")) {
                st.setString(1, task);
                st.execute();
            }
            try (CallableStatement st = conn.prepareCall(
                    "BEGIN DBMS_PARALLEL_EXECUTE.CREATE_CHUNKS_BY_ROWID(?,?,?,FALSE,?); END;")) {
                st.setString(1, task);
                st.setString(2, name[0]);
                st.setString(3, name[1]);
                st.setLong(4, chunkBlocks);
                st.execute();
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT start_rowid,end_rowid FROM user_parallel_execute_chunks "
                            + "WHERE task_name=? ORDER BY chunk_id")) {
                st.setString(1, task);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) chunks.add(new Chunk(rs.getString(1), rs.getString(2)));
                }
            }
        } finally {
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 CallableStatement st = conn.prepareCall(
                         "BEGIN DBMS_PARALLEL_EXECUTE.DROP_TASK(?); END;")) {
                st.setString(1, task);
                st.execute();
            } catch (Exception ignored) { }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Oracle produced no ROWID chunks for " + qualifiedTable);
        }
        return chunks;
    }

    static String rowidRange(String startRowid, String endRowid) {
        return "ROWID BETWEEN CHARTOROWID('" + startRowid.replace("'", "''")
                + "') AND CHARTOROWID('" + endRowid.replace("'", "''") + "')";
    }

    private static String[] ownerAndTable(String qualified) {
        String normalized = qualified.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_$#]*\\.[A-Z][A-Z0-9_$#]*")) {
            throw new IllegalArgumentException(
                    "ROWID mode requires unquoted OWNER.TABLE in the current schema: " + qualified);
        }
        int dot = normalized.indexOf('.');
        return new String[]{normalized.substring(0, dot), normalized.substring(dot + 1)};
    }
}

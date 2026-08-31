package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;

import java.util.List;

/**
 * Database-free gate for the Merkle "dirty" descent: checks the shape and
 * constraints of the Oracle NTILE decile-checksum SQL and the changed-PK
 * raw-value recheck SQL. The dirty descent only refines the dirty subtree -- a
 * matching interval at any granularity is pruned and never drilled -- with
 * Oracle producing "bucket upper-bound key + Oracle checksum" in one NTILE pass,
 * while MySQL 5.7 does only a key-fenced range checksum (no window functions);
 * the changed decision is then confirmed on the real rows by a full 128-bit
 * canonical MD5.
 */
public final class MerkleDirtyDescentSqlSelfTest {
    public static void main(String[] args) {
        List<String> pk = List.of("ITEM", "SUPPLIER", "ORIGIN_COUNTRY_ID");
        List<PkType> pkTypes = List.of(PkType.STRING, PkType.INT64, PkType.STRING);
        List<String> values = List.of("UNIT_COST", "PACKING_METHOD");
        List<ValueCanonType> valueTypes = ValueCanonType.parseCsv("decimal4,string");
        List<String> order = List.of("ITEM");
        List<PkType> orderTypes = List.of(PkType.STRING);

        String orderedStream = CompositeMerkleSql.orderedStreamingBoundarySql(
                "oracle", "ARTIFACT_REPLAY.T", order);
        if (!orderedStream.equals("SELECT \"ITEM\" FROM ARTIFACT_REPLAY.T ORDER BY \"ITEM\"")
                || orderedStream.contains("LIMIT")) {
            throw new AssertionError("ordered streaming boundary SQL shape wrong: " + orderedStream);
        }

        // 1) Oracle NTILE decile: one pass carves N equi-row buckets by the single order column and
        // returns per bucket the upper boundary key (MAX), SUM(fp) and COUNT together.
        String decile = CompositeMerkleSql.oracleDecileChecksumSql("ARTIFACT_REPLAY.PROFILE_P2_JUMP",
                pk, pkTypes, values, valueTypes, order, orderTypes, null, List.of("IT099999"), 10);
        if (!decile.contains("NTILE(10) OVER (ORDER BY \"ITEM\")")
                || !decile.contains("MAX(\"ITEM\") AS hi")
                || !decile.contains("SUM(CAST(fpv AS DECIMAL(38,0)))")
                || !decile.contains("COUNT(*) AS cnt")
                || !decile.contains("GROUP BY bucket ORDER BY bucket")) {
            throw new AssertionError("oracle decile SQL shape wrong: " + decile);
        }

        // 2) A composite order key cannot use the NTILE fence -- it must fall back to size descent.
        boolean rejectedComposite = false;
        try {
            CompositeMerkleSql.oracleDecileChecksumSql("t", pk, pkTypes, values, valueTypes,
                    List.of("ITEM", "SUPPLIER"), List.of(PkType.STRING, PkType.INT64), null, null, 10);
        } catch (IllegalArgumentException expected) {
            rejectedComposite = true;
        }
        if (!rejectedComposite) throw new AssertionError("composite order key accepted for NTILE fence");

        // 3) fanout < 2 is meaningless (no split) and must be rejected.
        boolean rejectedFanout = false;
        try {
            CompositeMerkleSql.oracleDecileChecksumSql("t", pk, pkTypes, values, valueTypes,
                    order, orderTypes, null, null, 1);
        } catch (IllegalArgumentException expected) {
            rejectedFanout = true;
        }
        if (!rejectedFanout) throw new AssertionError("fanout=1 accepted");

        // 4) Recheck SQL: tuple IN over the composite PK, projecting the full canonical MD5 as hex.
        String recheck = CompositeMerkleSql.recheckMd5Sql("mysql", "artifact_replay.profile_p2_jump",
                List.of("item", "supplier", "origin_country_id"), pkTypes,
                List.of("unit_cost", "packing_method"), valueTypes,
                List.of(List.of("IT000010", "5", "US"), List.of("IT000020", "7", "CN")));
        if (!recheck.contains("MD5(")
                || !recheck.contains("(`item`, `supplier`, `origin_country_id`) IN (")
                || !recheck.contains("('IT000010', 5, 'US')")
                || !recheck.contains("('IT000020', 7, 'CN')")) {
            throw new AssertionError("recheck SQL shape wrong: " + recheck);
        }

        // 5) Oracle recheck uses DBMS_CRYPTO MD5 hex and unquotes the integer PK component.
        String recheckOra = CompositeMerkleSql.recheckMd5Sql("oracle", "ARTIFACT_REPLAY.PROFILE_P2_JUMP",
                pk, pkTypes, values, valueTypes, List.of(List.of("IT000010", "5", "US")));
        if (!recheckOra.contains("DBMS_CRYPTO.HASH")
                || !recheckOra.contains("(\"ITEM\", \"SUPPLIER\", \"ORIGIN_COUNTRY_ID\") IN (")
                || !recheckOra.contains("('IT000010', 5, 'US')")) {
            throw new AssertionError("oracle recheck SQL shape wrong: " + recheckOra);
        }

        System.out.println("PASS merkle dirty descent: NTILE decile checksum SQL + changed-PK MD5 recheck SQL");
    }
}

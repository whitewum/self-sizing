package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/** Database-free composite-key / capacity gate. */
public final class CompositeIbltPatchSelfTest {
    public static void main(String[] args) {
        String wire = CompositePkCodec.encode(List.of("SKU-001", "1001", "CN"));
        if (!List.of("SKU-001", "1001", "CN").equals(CompositePkCodec.decode(wire, 3))) {
            throw new AssertionError("composite pk codec round-trip failed");
        }
        int naturalPowerOfTwo = IbltSizing.secondRoundBuckets(1024.0 / (1.52 * 1.2), 1 << 20);
        if (IbltSizing.FIRST_ROUND_BUCKETS != 512
                || IbltSizing.secondRoundBuckets(10, 1 << 20) != 19
                || naturalPowerOfTwo != 1024) {
            throw new AssertionError("IBLT sizing rule failed");
        }
        String sql = CompositeIbltSql.recheckSql("mysql", "artifact_replay.profile_p2",
                List.of("item", "supplier", "origin_country_id"),
                List.of(PkType.STRING, PkType.INT64, PkType.STRING),
                List.of("unit_cost"), List.of(wire));
        if (!sql.contains("(`item`, `supplier`, `origin_country_id`) IN ((")) {
            throw new AssertionError("composite row-value recheck SQL missing: " + sql);
        }
        List<String> values = List.of("unit_cost", "lead_time", "create_datetime", "packing_method");
        List<ValueCanonType> types = List.of(ValueCanonType.DECIMAL4, ValueCanonType.INT64,
                ValueCanonType.DATETIME_SEC, ValueCanonType.STRING);
        String mysqlFp = CompositeIbltSql.fingerprintExpression("mysql",
                List.of("item"), List.of(PkType.STRING), values, types);
        String oracleFp = CompositeIbltSql.fingerprintExpression("oracle",
                List.of("ITEM"), List.of(PkType.STRING), values, types);
        if (!mysqlFp.contains("ROUND(`unit_cost`,4)*10000")
                || !mysqlFp.contains("AS DECIMAL(38,0)")
                || !mysqlFp.contains("DATE_FORMAT(`create_datetime`, '%Y-%m-%d %H:%i:%s')")
                || !oracleFp.contains("ROUND(\"UNIT_COST\"*10000)")
                || !oracleFp.contains("'YYYY-MM-DD HH24:MI:SS'")) {
            throw new AssertionError("full-column canonical SQL missing: " + mysqlFp + " / " + oracleFp);
        }
        String parallelSql = CompositeIbltSql.fingerprintSql("oracle", "ARTIFACT_REPLAY.T",
                List.of("ITEM"), List.of(PkType.STRING), values, types, 16);
        if (!parallelSql.startsWith("SELECT /*+ PARALLEL(t,16) */ ")
                || !parallelSql.endsWith(" FROM ARTIFACT_REPLAY.T t")) {
            throw new AssertionError("Oracle parallel hint/alias missing: " + parallelSql);
        }
        String oracleHash = CompositeIbltSql.hashmodShardPredicate("oracle", "ITEM", 2, 1);
        String mysqlHash = CompositeIbltSql.hashmodShardPredicate("mysql", "item", 2, 1);
        String mysqlRange = CompositeIbltSql.stringRangePredicate(
                "mysql", "item", "IT0000009999", "IT0000019999");
        String shardSql = CompositeIbltSql.fingerprintShardSql("oracle", "ARTIFACT_REPLAY.T",
                List.of("ITEM"), List.of(PkType.STRING), values, types, oracleHash);
        String rowidRange = OracleRowidChunker.rowidRange("AAABBB", "AAACCC");
        String rowidBoundarySql = CompositeMerkleSql.rowidBoundarySampleSql(
                "ARTIFACT_REPLAY.T", List.of("ITEM"), rowidRange);
        if (!"ORA_HASH(\"ITEM\", 1) = 1".equals(oracleHash)
                || !"MOD(CRC32(CAST(`item` AS CHAR)), 2) = 1".equals(mysqlHash)
                || !"`item` > 'IT0000009999' AND `item` <= 'IT0000019999'".equals(mysqlRange)
                || !shardSql.contains(" WHERE ORA_HASH(\"ITEM\", 1) = 1")
                || shardSql.contains("ORDER BY")
                || !rowidRange.equals("ROWID BETWEEN CHARTOROWID('AAABBB') AND CHARTOROWID('AAACCC')")
                || !rowidBoundarySql.endsWith(rowidRange + " AND ROWNUM = 1")
                || rowidBoundarySql.contains("ORDER BY")) {
            throw new AssertionError("streaming shard SQL contract missing: "
                    + oracleHash + " / " + mysqlHash + " / " + shardSql + " / " + rowidRange);
        }
        String rowidBatchSql = CompositeMerkleSql.rowidBoundarySampleBatchSql(
                "ARTIFACT_REPLAY.T", List.of("ITEM"), List.of(rowidRange), List.of(7));
        if (!rowidBatchSql.contains("7 AS merkle_chunk_idx")
                || !rowidBatchSql.contains(rowidRange)
                || !rowidBatchSql.contains("ROWNUM = 1")
                || rowidBatchSql.contains("CHARTOROWID(?)")) {
            throw new AssertionError("literal rowid boundary batch SQL missing: " + rowidBatchSql);
        }
        List<String> fullPk = List.of("ITEM", "SUPPLIER", "ORIGIN_COUNTRY_ID");
        List<PkType> fullPkTypes = List.of(PkType.STRING, PkType.INT64, PkType.STRING);
        List<String> itemOrder = List.of("ITEM");
        List<PkType> itemOrderType = List.of(PkType.STRING);
        String boundarySql = CompositeMerkleSql.boundarySql("oracle", "ARTIFACT_REPLAY.T",
                itemOrder, itemOrderType, List.of("IT0000000100"), 100000);
        String checksumSql = CompositeMerkleSql.checksumSql("oracle", "ARTIFACT_REPLAY.T",
                fullPk, fullPkTypes, values, types, itemOrder, itemOrderType,
                List.of("IT0000000100"), List.of("IT0000100100"));
        String rowsSql = CompositeMerkleSql.rowsSql("oracle", "ARTIFACT_REPLAY.T",
                fullPk, fullPkTypes, values, types, itemOrder, itemOrderType,
                List.of("IT0000000100"), List.of("IT0000100100"));
        if (!boundarySql.contains("\"ITEM\" > 'IT0000000100'")
                || boundarySql.contains("SUPPLIER") || boundarySql.contains(" OR ")
                || !checksumSql.contains("\"SUPPLIER\"")
                || checksumSql.contains("\"SUPPLIER\" >")
                || !checksumSql.contains("\"ITEM\" > 'IT0000000100'")
                || !checksumSql.contains("\"ITEM\" <= 'IT0000100100'")
                || checksumSql.contains(" OR ")
                || !rowsSql.endsWith("ORDER BY \"ITEM\"")) {
            throw new AssertionError("single unique Merkle order-key fast path missing: "
                    + boundarySql + " / " + checksumSql + " / " + rowsSql);
        }
        boolean rejectedAmbiguousDecimal = false;
        try {
            ValueCanonType.parse("decimal");
        } catch (IllegalArgumentException expected) {
            rejectedAmbiguousDecimal = true;
        }
        if (!rejectedAmbiguousDecimal) throw new AssertionError("ambiguous decimal type accepted");
        System.out.println("PASS composite codec + row-value SQL + canonical value types + M sizing");
        System.out.println(sql);
    }
}

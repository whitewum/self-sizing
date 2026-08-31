package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;

import java.util.ArrayList;
import java.util.List;

/** SQL generation for the migrated Merkle path with a lexicographically ordered tuple key. */
public final class CompositeMerkleSql {
    private CompositeMerkleSql() {
    }

    public static String boundarySql(String dialect, String table, List<String> orderColumns,
                                     List<PkType> orderTypes, List<String> lowerExclusive,
                                     int chunkSize) {
        String projection = String.join(", ", quotedColumns(dialect, orderColumns));
        String where = lowerExclusive == null ? "1=1"
                : lexCompare(dialect, orderColumns, orderTypes, lowerExclusive, false);
        if ("oracle".equalsIgnoreCase(dialect)) {
            return "SELECT " + projection + " FROM (SELECT " + projection + " FROM " + table
                    + " WHERE " + where + " ORDER BY " + projection + ") merkle_boundary"
                    + " WHERE ROWNUM <= " + chunkSize;
        }
        return "SELECT " + projection + " FROM " + table + " WHERE " + where
                + " ORDER BY " + projection + " LIMIT " + chunkSize;
    }

    /**
     * One-pass ordered boundary scan.  The caller streams this result and keeps every Nth key;
     * unlike boundarySql(), it never re-issues a query for each logical range.
     */
    public static String orderedStreamingBoundarySql(String dialect, String table,
                                                      List<String> orderColumns) {
        String projection = String.join(", ", quotedColumns(dialect, orderColumns));
        return "SELECT " + projection + " FROM " + table + " ORDER BY " + projection;
    }

    /** One unordered logical-key sample from a true Oracle physical ROWID chunk. */
    public static String rowidBoundarySampleSql(String table, List<String> orderColumns,
                                                String rowidPredicate) {
        String projection = String.join(", ", quotedColumns("oracle", orderColumns));
        return "SELECT /*+ FIRST_ROWS(1) ROWID(" + oracleHintTable(table) + ") */ "
                + projection + " FROM " + table + " WHERE " + rowidPredicate
                + " AND ROWNUM = 1";
    }

    /** One statement containing several independent ROWID dives; column 1 is the caller index. */
    public static String rowidBoundarySampleBatchSql(String table, List<String> orderColumns,
                                                     List<String> predicates, List<Integer> indexes) {
        if (predicates.size() != indexes.size() || predicates.isEmpty()) {
            throw new IllegalArgumentException("ROWID probe batch mismatch/empty");
        }
        String projection = String.join(", ", quotedColumns("oracle", orderColumns));
        List<String> branches = new ArrayList<>(predicates.size());
        for (int i = 0; i < predicates.size(); i++) {
            branches.add("SELECT " + indexes.get(i) + " AS merkle_chunk_idx, " + projection
                    + " FROM (SELECT /*+ FIRST_ROWS(1) ROWID(" + oracleHintTable(table) + ") */ "
                    + projection + " FROM " + table + " WHERE "
                    + predicates.get(i) + " AND ROWNUM = 1)");
        }
        return String.join(" UNION ALL ", branches);
    }

    private static String oracleHintTable(String qualifiedTable) {
        int dot = qualifiedTable.lastIndexOf('.');
        String name = dot < 0 ? qualifiedTable : qualifiedTable.substring(dot + 1);
        return name.replace("\"", "");
    }

    /** Indexed ordered scan used only to split a count-oversized logical range. */
    public static String splitBoundarySql(String dialect, String table, List<String> orderColumns,
                                          List<PkType> orderTypes, List<String> lowerExclusive,
                                          List<String> upperInclusive) {
        String projection = String.join(", ", quotedColumns(dialect, orderColumns));
        return "SELECT " + projection + " FROM " + table + " WHERE "
                + rangePredicate(dialect, orderColumns, orderTypes, lowerExclusive, upperInclusive)
                + " ORDER BY " + projection;
    }

    /**
     * Oracle-only one-pass NTILE localisation. Splits [lowerExclusive, upperInclusive] into
     * {@code buckets} equi-row buckets by the single order column and, in the SAME scan, returns
     * per bucket: the inclusive upper boundary key ({@code MAX(order)}), the checksum {@code SUM(fp)}
     * and the row {@code COUNT}. Oracle is always the fence source (the source-of-truth engine);
     * MySQL 5.7 has no window functions and only re-applies the returned key fences as range
     * checksums. Requires a single, unique order column -- a composite order key must fall back to
     * the size-driven descent.
     */
    public static String oracleDecileChecksumSql(String table, List<String> pkColumns,
                                                 List<PkType> pkTypes, List<String> valueColumns,
                                                 List<ValueCanonType> valueTypes,
                                                 List<String> orderColumns, List<PkType> orderTypes,
                                                 List<String> lowerExclusive,
                                                 List<String> upperInclusive, int buckets) {
        if (orderColumns.size() != 1) {
            throw new IllegalArgumentException("oracleDecileChecksumSql requires a single order column");
        }
        if (buckets < 2) throw new IllegalArgumentException("buckets must be >= 2");
        String fp = CompositeIbltSql.fingerprintExpression("oracle", pkColumns, pkTypes,
                valueColumns, valueTypes);
        String orderCol = quote("oracle", orderColumns.get(0));
        String where = rangePredicate("oracle", orderColumns, orderTypes, lowerExclusive, upperInclusive);
        return "SELECT bucket, MAX(" + orderCol + ") AS hi,"
                + " SUM(CAST(fpv AS DECIMAL(38,0))) AS sumfp, COUNT(*) AS cnt FROM ("
                + "SELECT " + orderCol + ", " + fp + " AS fpv,"
                + " NTILE(" + buckets + ") OVER (ORDER BY " + orderCol + ") AS bucket"
                + " FROM " + table + " WHERE " + where + ") GROUP BY bucket ORDER BY bucket";
    }

    /**
     * Value-level recheck: for a batch of PK tuples, return each row's full (128-bit) canonical MD5
     * as safe lowercase hex. Cross-engine consistent (MySQL {@code MD5} vs Oracle
     * {@code DBMS_CRYPTO.HASH} type 2 over the identical canonical string), so a per-PK hex mismatch
     * confirms the drilled "changed" verdict on the actual re-read rows.
     */
    public static String recheckMd5Sql(String dialect, String table, List<String> pkColumns,
                                       List<PkType> pkTypes, List<String> valueColumns,
                                       List<ValueCanonType> valueTypes, List<List<String>> pkTuples) {
        if (pkTuples.isEmpty()) throw new IllegalArgumentException("recheck requires at least one PK");
        String canonical = CompositeIbltSql.canonicalExpression(dialect, pkColumns, pkTypes,
                valueColumns, valueTypes);
        String md5 = CompositeIbltSql.fullMd5Expression(dialect, canonical);
        String pkProjection = String.join(", ", quotedColumns(dialect, pkColumns));
        List<String> tupleLiterals = new ArrayList<>(pkTuples.size());
        for (List<String> tuple : pkTuples) tupleLiterals.add(tupleLiteral(tuple, pkTypes));
        return "SELECT " + pkProjection + ", " + md5 + " AS merkle_md5 FROM " + table
                + " WHERE (" + pkProjection + ") IN (" + String.join(", ", tupleLiterals) + ")";
    }

    public static String checksumSql(String dialect, String table, List<String> pkColumns,
                                     List<PkType> pkTypes, List<String> valueColumns,
                                     List<ValueCanonType> valueTypes, List<String> orderColumns,
                                     List<PkType> orderTypes, List<String> lowerExclusive,
                                     List<String> upperInclusive) {
        String fp = CompositeIbltSql.fingerprintExpression(dialect, pkColumns, pkTypes,
                valueColumns, valueTypes);
        String where = rangePredicate(dialect, orderColumns, orderTypes, lowerExclusive, upperInclusive);
        return "SELECT COALESCE(SUM(CAST(" + fp + " AS DECIMAL(38,0))), 0), COUNT(*) FROM "
                + table + " WHERE " + where;
    }

    /** Returns fingerprint first, followed by the tuple key columns. */
    public static String rowsSql(String dialect, String table, List<String> pkColumns,
                                 List<PkType> pkTypes, List<String> valueColumns,
                                 List<ValueCanonType> valueTypes, List<String> orderColumns,
                                 List<PkType> orderTypes, List<String> lowerExclusive,
                                 List<String> upperInclusive) {
        String fp = CompositeIbltSql.fingerprintExpression(dialect, pkColumns, pkTypes,
                valueColumns, valueTypes);
        String projection = fp + " AS merkle_fp";
        for (String pk : pkColumns) projection += ", " + quote(dialect, pk);
        for (int i = 0; i < orderColumns.size(); i++) {
            projection += ", " + quote(dialect, orderColumns.get(i)) + " AS merkle_order_" + i;
        }
        String where = rangePredicate(dialect, orderColumns, orderTypes, lowerExclusive, upperInclusive);
        return "SELECT " + projection + " FROM " + table + " WHERE " + where
                + " ORDER BY " + String.join(", ", quotedColumns(dialect, orderColumns));
    }

    public static String rangePredicate(String dialect, List<String> pkColumns,
                                        List<PkType> pkTypes, List<String> lowerExclusive,
                                        List<String> upperInclusive) {
        List<String> predicates = new ArrayList<>();
        if (lowerExclusive != null) {
            predicates.add(lexCompare(dialect, pkColumns, pkTypes, lowerExclusive, false));
        }
        if (upperInclusive != null) {
            predicates.add(lexCompare(dialect, pkColumns, pkTypes, upperInclusive, true));
        }
        return predicates.isEmpty() ? "1=1" : String.join(" AND ", predicates);
    }

    private static String tuple(String dialect, List<String> columns) {
        return "(" + String.join(", ", quotedColumns(dialect, columns)) + ")";
    }

    /**
     * Portable lexicographic tuple comparison. Oracle 11g rejects row-value `>`/`<=`;
     * the expanded form is also valid on MySQL and keeps the boundary semantics identical.
     * inclusive=true means <=, otherwise >.
     */
    private static String lexCompare(String dialect, List<String> columns, List<PkType> types,
                                     List<String> values, boolean inclusive) {
        if (columns.size() != values.size() || columns.size() != types.size()) {
            throw new IllegalArgumentException("tuple arity mismatch");
        }
        if (columns.size() == 1) {
            return "(" + quote(dialect, columns.get(0)) + (inclusive ? " <= " : " > ")
                    + literal(values.get(0), types.get(0)) + ")";
        }
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            List<String> and = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                and.add(quote(dialect, columns.get(j)) + " = " + literal(values.get(j), types.get(j)));
            }
            String op = inclusive ? (i == columns.size() - 1 ? " <= " : " < ") : " > ";
            and.add(quote(dialect, columns.get(i)) + op + literal(values.get(i), types.get(i)));
            terms.add("(" + String.join(" AND ", and) + ")");
        }
        return "(" + String.join(" OR ", terms) + ")";
    }

    private static String tupleLiteral(List<String> values, List<PkType> types) {
        if (values.size() != types.size()) throw new IllegalArgumentException("tuple/type arity mismatch");
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(literal(values.get(i), types.get(i)));
        }
        return out.append(')').toString();
    }

    private static List<String> quotedColumns(String dialect, List<String> columns) {
        List<String> out = new ArrayList<>(columns.size());
        for (String column : columns) out.add(quote(dialect, column));
        return out;
    }

    private static String literal(String value, PkType type) {
        if (type == PkType.INT64 || type == PkType.DECIMAL) return value;
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quote(String dialect, String identifier) {
        if ("mysql".equalsIgnoreCase(dialect)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.toUpperCase().replace("\"", "\"\"") + "\"";
    }
}

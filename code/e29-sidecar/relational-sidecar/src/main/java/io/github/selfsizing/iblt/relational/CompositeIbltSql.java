package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal cross-database SQL adapter layer for Oracle/MySQL composite keys. */
public final class CompositeIbltSql {
    private CompositeIbltSql() {
    }

    public static String fingerprintSql(String dialect, String table, List<String> pkColumns,
                                        List<PkType> pkTypes, List<String> valueColumns,
                                        List<ValueCanonType> valueTypes) {
        return fingerprintSql(dialect, table, pkColumns, pkTypes, valueColumns, valueTypes, 1);
    }

    public static String fingerprintSql(String dialect, String table, List<String> pkColumns,
                                        List<PkType> pkTypes, List<String> valueColumns,
                                        List<ValueCanonType> valueTypes, int oracleParallelDegree) {
        if (oracleParallelDegree < 1) {
            throw new IllegalArgumentException("oracle parallel degree must be positive");
        }
        String fp = fingerprintExpression(dialect, pkColumns, pkTypes, valueColumns, valueTypes);
        StringBuilder sql = new StringBuilder("SELECT ");
        if ("oracle".equalsIgnoreCase(dialect) && oracleParallelDegree > 1) {
            sql.append("/*+ PARALLEL(t,").append(oracleParallelDegree).append(") */ ");
        }
        sql.append(fp).append(" AS iblt_fp");
        for (String pk : pkColumns) {
            sql.append(", ").append(quote(dialect, pk));
        }
        sql.append(" FROM ").append(table);
        if ("oracle".equalsIgnoreCase(dialect) && oracleParallelDegree > 1) sql.append(" t");
        return sql.toString();
    }

    /**
     * Fingerprint scan SQL for one shard. Appends the shard predicate and
     * <b>never adds ORDER BY</b>: the IBLT is a set structure, order-independent,
     * and sorting would only materialize the whole shard and slow it down. The
     * fingerprint column is still computed on the database side (symmetric across
     * engines); the client only does the cheap id hash and the spill to disk.
     */
    public static String fingerprintShardSql(String dialect, String table, List<String> pkColumns,
                                             List<PkType> pkTypes, List<String> valueColumns,
                                             List<ValueCanonType> valueTypes, String shardPredicate) {
        String fp = fingerprintExpression(dialect, pkColumns, pkTypes, valueColumns, valueTypes);
        StringBuilder sql = new StringBuilder("SELECT ").append(fp).append(" AS iblt_fp");
        for (String pk : pkColumns) {
            sql.append(", ").append(quote(dialect, pk));
        }
        sql.append(" FROM ").append(table);
        if (shardPredicate != null && !shardPredicate.isBlank()) {
            sql.append(" WHERE ").append(shardPredicate);
        }
        return sql.toString();
    }

    /**
     * Cheap database-side hash partition. This deliberately hashes one configured column rather
     * than serializing the whole composite key. Each side may use a different hash function because
     * IBLT ingestion is order/partition independent; every local row only needs to occur once.
     *
     * <p>This predicate is an experimental CPU/parallelism trade-off, not a physical range scan:
     * without a matching function-based index each shard query may scan the full table.</p>
     */
    public static String hashmodShardPredicate(String dialect, String shardColumn,
                                               int shards, int shard) {
        if (shards < 1 || shard < 0 || shard >= shards) {
            throw new IllegalArgumentException("bad shard " + shard + "/" + shards);
        }
        String column = quote(dialect, shardColumn);
        if ("oracle".equalsIgnoreCase(dialect)) {
            return "ORA_HASH(" + column + ", " + (shards - 1) + ") = " + shard;
        }
        if ("mysql".equalsIgnoreCase(dialect)) {
            return "MOD(CRC32(CAST(" + column + " AS CHAR)), " + shards + ") = " + shard;
        }
        throw new IllegalArgumentException("hashmod scan mode unsupported for dialect: " + dialect);
    }

    public static String minMaxSql(String dialect, String table, String column) {
        String quoted = quote(dialect, column);
        return "SELECT MIN(" + quoted + "), MAX(" + quoted + ") FROM " + table;
    }

    /** Disjoint string-key range; open ends preserve coverage beyond observed MIN/MAX. */
    public static String stringRangePredicate(String dialect, String column,
                                              String lowerExclusive, String upperInclusive) {
        String quoted = quote(dialect, column);
        List<String> terms = new ArrayList<>();
        if (lowerExclusive != null) terms.add(quoted + " > '" + escape(lowerExclusive) + "'");
        if (upperInclusive != null) terms.add(quoted + " <= '" + escape(upperInclusive) + "'");
        return terms.isEmpty() ? "1=1" : String.join(" AND ", terms);
    }

    /** Disjoint numeric range for an integer/decimal key. Literals are generated internally. */
    public static String numericRangePredicate(String dialect, String column,
                                               String lowerExclusive, String upperInclusive) {
        String quoted = quote(dialect, column);
        List<String> terms = new ArrayList<>();
        if (lowerExclusive != null) terms.add(quoted + " > " + numericLiteral(lowerExclusive));
        if (upperInclusive != null) terms.add(quoted + " <= " + numericLiteral(upperInclusive));
        return terms.isEmpty() ? "1=1" : String.join(" AND ", terms);
    }

    private static String numericLiteral(String value) {
        // BigInteger validation also prevents an accidental SQL fragment from reaching a query.
        return new BigInteger(value).toString();
    }

    private static String escape(String value) { return value.replace("'", "''"); }

    /**
     * Distribution-agnostic quantile-fence probes for MySQL {@code --mysql-fence sample}. This
     * looks only at the observed MIN/MAX key's shared literal prefix, fixed body width, and base-36
     * charset ([0-9A-Z]) — never at the row distribution itself — so it works on arbitrary keys such
     * as {@code IT008KN9} where {@link #stringRangePredicate} boundaries computed from a fixed-width
     * numeric suffix (see {@code numericSuffixKeyRangePredicates}) do not apply. The returned probes
     * are evenly spaced across the observed body's numeric span; callers snap each probe to a real
     * key (see {@link #mysqlFenceSnapLateralSql}) and thin the sorted, deduped results down to
     * {@code scanShards - 1} fences.
     */
    public static List<String> base36FenceProbes(String min, String max, int count) {
        if (count < 1) throw new IllegalArgumentException("probe count must be positive");
        if (min == null || max == null) throw new IllegalArgumentException("min/max must not be null");
        int prefixLen = commonPrefixLength(min, max);
        String prefix = min.substring(0, prefixLen);
        String minBody = min.substring(prefixLen);
        String maxBody = max.substring(prefixLen);
        if (minBody.isEmpty() || maxBody.isEmpty() || minBody.length() != maxBody.length()) {
            throw new IllegalArgumentException(
                    "sample fence requires a shared fixed-width key body: " + min + " / " + max);
        }
        int width = minBody.length();
        BigInteger first = decodeBase36(minBody);
        BigInteger last = decodeBase36(maxBody);
        if (last.compareTo(first) < 0) {
            BigInteger swap = first;
            first = last;
            last = swap;
        }
        BigInteger span = last.subtract(first);
        BigInteger steps = BigInteger.valueOf(Math.max(1, count - 1));
        List<String> probes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigInteger offset = (count == 1)
                    ? BigInteger.ZERO
                    : span.multiply(BigInteger.valueOf(i)).divide(steps);
            probes.add(prefix + encodeBase36(first.add(offset), width));
        }
        return probes;
    }

    private static int commonPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static BigInteger decodeBase36(String body) {
        BigInteger base = BigInteger.valueOf(36);
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < body.length(); i++) {
            int digit = Character.digit(body.charAt(i), 36);
            if (digit < 0) {
                throw new IllegalArgumentException("not a base-36 key body character: " + body);
            }
            value = value.multiply(base).add(BigInteger.valueOf(digit));
        }
        return value;
    }

    private static String encodeBase36(BigInteger value, int width) {
        String raw = value.toString(36).toUpperCase(Locale.ROOT);
        if (raw.length() > width) {
            throw new IllegalArgumentException("base-36 key body overflow: " + raw);
        }
        return "0".repeat(width - raw.length()) + raw;
    }

    /**
     * @deprecated Do not use. The correlated {@code >= s.probe} inside the {@code JOIN LATERAL}
     * cannot drive an index range seek (the bound is a column, not a constant), so MySQL executes
     * each probe as a full {@code Index scan using PRIMARY} + filter -- EXPLAIN-verified, and
     * observed to run 900s+ on the 31M-row P2 table. Use {@link #mysqlFenceSnapUnionAllSql}
     * instead: its constant literals do become index range dives. Kept only to document the trap.
     */
    @Deprecated
    public static String mysqlFenceSnapLateralSql(String table, String column, List<String> probes) {
        if (probes.isEmpty()) throw new IllegalArgumentException("no probes to snap");
        String quoted = quote("mysql", column);
        StringBuilder probeRows = new StringBuilder();
        for (int i = 0; i < probes.size(); i++) {
            if (i > 0) probeRows.append(" UNION ALL ");
            probeRows.append("SELECT '").append(escape(probes.get(i))).append("' AS probe");
        }
        return "SELECT s.probe AS probe, x.snapped AS snapped FROM (" + probeRows + ") s "
                + "JOIN LATERAL (SELECT " + quoted + " AS snapped FROM " + table
                + " WHERE " + quoted + " >= s.probe ORDER BY " + quoted + " LIMIT 1) x ON TRUE";
    }

    /**
     * MySQL 5.7-safe fallback for {@link #mysqlFenceSnapLateralSql}: a batch of probes becomes a
     * {@code UNION ALL} of independent scalar subqueries, each still a {@code >= probe ORDER BY
     * col LIMIT 1} range dive. One statement per returned list entry; callers should keep
     * {@code batchSize} around 500 to avoid oversized SQL text / excessive subquery fan-out.
     */
    public static List<String> mysqlFenceSnapUnionAllSql(String table, String column,
                                                          List<String> probes, int batchSize) {
        if (probes.isEmpty()) throw new IllegalArgumentException("no probes to snap");
        if (batchSize < 1) throw new IllegalArgumentException("batch size must be positive");
        String quoted = quote("mysql", column);
        List<String> statements = new ArrayList<>();
        for (int start = 0; start < probes.size(); start += batchSize) {
            int end = Math.min(start + batchSize, probes.size());
            StringBuilder sql = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(" UNION ALL ");
                sql.append("SELECT (SELECT ").append(quoted).append(" FROM ").append(table)
                        .append(" WHERE ").append(quoted).append(" >= '")
                        .append(escape(probes.get(i))).append("' ORDER BY ").append(quoted)
                        .append(" LIMIT 1) AS snapped");
            }
            statements.add(sql.toString());
        }
        return statements;
    }

    /**
     * Numeric counterpart of {@link #mysqlFenceSnapUnionAllSql}. Each literal remains a constant
     * so MySQL can perform an indexed range dive; quoting the probe would invite implicit string
     * comparison on some numeric column types, therefore the validated decimal literal is emitted
     * without quotes.
     */
    public static List<String> mysqlNumericFenceSnapUnionAllSql(String table, String column,
                                                                  List<BigInteger> probes,
                                                                  int batchSize) {
        if (probes.isEmpty()) throw new IllegalArgumentException("no probes to snap");
        if (batchSize < 1) throw new IllegalArgumentException("batch size must be positive");
        String quoted = quote("mysql", column);
        List<String> statements = new ArrayList<>();
        for (int start = 0; start < probes.size(); start += batchSize) {
            int end = Math.min(start + batchSize, probes.size());
            StringBuilder sql = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(" UNION ALL ");
                String probe = probes.get(i).toString();
                sql.append("SELECT (SELECT ").append(quoted).append(" FROM ").append(table)
                        .append(" WHERE ").append(quoted).append(" >= ").append(probe)
                        .append(" ORDER BY ").append(quoted).append(" LIMIT 1) AS snapped");
            }
            statements.add(sql.toString());
        }
        return statements;
    }

    public static String recheckSql(String dialect, String table, List<String> pkColumns,
                                     List<PkType> pkTypes, List<String> valueColumns,
                                     List<String> encodedPks) {
        if (encodedPks.isEmpty()) {
            throw new IllegalArgumentException("empty candidate pk list");
        }
        List<List<String>> tuples = new ArrayList<>();
        for (String encoded : encodedPks) {
            tuples.add(CompositePkCodec.decode(encoded, pkColumns.size()));
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < pkColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(quote(dialect, pkColumns.get(i)));
        }
        for (String value : valueColumns) {
            sql.append(", ").append(quote(dialect, value));
        }
        sql.append(" FROM ").append(table).append(" WHERE ");
        if (pkColumns.size() == 1) {
            sql.append(quote(dialect, pkColumns.get(0))).append(" IN (");
            for (int i = 0; i < tuples.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(literal(tuples.get(i).get(0), pkTypes.get(0)));
            }
            return sql.append(')').toString();
        }
        sql.append('(');
        for (int i = 0; i < pkColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(quote(dialect, pkColumns.get(i)));
        }
        sql.append(") IN (");
        for (int row = 0; row < tuples.size(); row++) {
            if (row > 0) sql.append(", ");
            sql.append('(');
            for (int col = 0; col < pkColumns.size(); col++) {
                if (col > 0) sql.append(", ");
                sql.append(literal(tuples.get(row).get(col), pkTypes.get(col)));
            }
            sql.append(')');
        }
        return sql.append(")").toString();
    }

    /** Stable cross-database row fingerprint shared by the IBLT and Merkle overlays. */
    public static String fingerprintExpression(String dialect, List<String> pkColumns,
                                               List<PkType> pkTypes, List<String> valueColumns,
                                               List<ValueCanonType> valueTypes) {
        String canonical = canonicalExpression(dialect, pkColumns, pkTypes, valueColumns, valueTypes);
        if ("mysql".equalsIgnoreCase(dialect)) {
            return "CONV(SUBSTRING(MD5(" + canonical + "),1,14),16,10)";
        }
        return "TO_NUMBER(RAWTOHEX(UTL_RAW.SUBSTR(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW("
                + canonical + "), 2), 1, 7)), 'XXXXXXXXXXXXXX')";
    }

    static String canonicalExpression(String dialect, List<String> pkColumns,
                                      List<PkType> pkTypes, List<String> valueColumns,
                                      List<ValueCanonType> valueTypes) {
        if (pkColumns.size() != pkTypes.size()) {
            throw new IllegalArgumentException("pk columns/types mismatch");
        }
        if (valueColumns.size() != valueTypes.size()) {
            throw new IllegalArgumentException("value columns/types mismatch");
        }
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < pkColumns.size(); i++) {
            pieces.add(canonPk(dialect, pkColumns.get(i), pkTypes.get(i)));
        }
        for (int i = 0; i < valueColumns.size(); i++) {
            pieces.add(canonValue(dialect, valueColumns.get(i), valueTypes.get(i)));
        }
        String joined;
        if ("mysql".equalsIgnoreCase(dialect)) {
            joined = "CONCAT(" + String.join(", CHAR(31), ", pieces) + ")";
            return "LOWER(" + joined + ")";
        }
        joined = String.join(" || CHR(31) || ", pieces);
        return "LOWER(" + joined + ")";
    }

    static String fullMd5Expression(String dialect, String canonical) {
        if ("mysql".equalsIgnoreCase(dialect)) return "MD5(" + canonical + ")";
        return "LOWER(RAWTOHEX(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW(" + canonical + "), 2)))";
    }

    private static String canonPk(String dialect, String column, PkType type) {
        String q = quote(dialect, column);
        String expr;
        if (type == PkType.INT64) {
            expr = integerText(dialect, q);
        } else if (type == PkType.DECIMAL) {
            // Existing key contract: numeric keys use their normalized decimal text.
            expr = "mysql".equalsIgnoreCase(dialect)
                    ? "CAST(" + q + " AS CHAR)"
                    : "TO_CHAR(" + q + ",'TM9','NLS_NUMERIC_CHARACTERS=''.,''')";
        } else {
            expr = "mysql".equalsIgnoreCase(dialect) ? "CAST(" + q + " AS CHAR)" : q;
        }
        return nullSafe(dialect, expr);
    }

    static String canonValue(String dialect, String column, ValueCanonType type) {
        String q = quote(dialect, column);
        String expr;
        switch (type) {
            case INT64:
                expr = integerText(dialect, q);
                break;
            case DECIMAL4:
                // Both P2 schemas declare scale=4. Scaling to an integer removes
                // 1.2 versus 1.2000 representation differences without locale-sensitive dots.
                expr = "mysql".equalsIgnoreCase(dialect)
                        ? "CAST(CAST(ROUND(" + q + ",4)*10000 AS DECIMAL(38,0)) AS CHAR)"
                        : "TO_CHAR(ROUND(" + q + "*10000),'FM999999999999999999999999999999999999990',"
                        + "'NLS_NUMERIC_CHARACTERS=''.,''')";
                break;
            case DATETIME_SEC:
                expr = "mysql".equalsIgnoreCase(dialect)
                        ? "DATE_FORMAT(" + q + ", '%Y-%m-%d %H:%i:%s')"
                        : "TO_CHAR(" + q + ",'YYYY-MM-DD HH24:MI:SS','NLS_DATE_LANGUAGE=American')";
                break;
            case STRING:
                expr = "mysql".equalsIgnoreCase(dialect) ? "CAST(" + q + " AS CHAR)" : q;
                break;
            default:
                throw new IllegalArgumentException("unsupported value type: " + type);
        }
        return nullSafe(dialect, expr);
    }

    private static String integerText(String dialect, String quotedColumn) {
        return "mysql".equalsIgnoreCase(dialect)
                ? "CAST(" + quotedColumn + " AS CHAR)"
                : "TO_CHAR(" + quotedColumn + ",'FM999999999999999999999999999999999999990',"
                + "'NLS_NUMERIC_CHARACTERS=''.,''')";
    }

    private static String nullSafe(String dialect, String expression) {
        return ("mysql".equalsIgnoreCase(dialect) ? "COALESCE(" : "NVL(")
                + expression + ", '<NULL>')";
    }

    private static String literal(String value, PkType type) {
        if (type == PkType.INT64 || type == PkType.DECIMAL) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quote(String dialect, String identifier) {
        if ("mysql".equalsIgnoreCase(dialect)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.toUpperCase().replace("\"", "\"\"") + "\"";
    }
}

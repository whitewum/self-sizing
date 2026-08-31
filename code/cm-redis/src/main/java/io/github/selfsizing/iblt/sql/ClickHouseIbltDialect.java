package io.github.selfsizing.iblt.sql;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/**
 * ClickHouse dialect (cross-engine alignment recipe, byte-aligned with
 * {@link SqlServerIbltDialect}).
 *
 * <p>A full-table check gave CH.a vs SS.a = 0 diff. Key points:
 * <ul>
 *   <li>per-column normalization: INT&rarr;{@code toString}; MONEY2&rarr;integer cents
 *       {@code toString(toInt64(round(c*100)))} (avoids the Float64 precision trap);
 *       DATETIME_SEC&rarr;{@code formatDateTime(c,'%Y-%m-%d %H:%M:%S')}; STRING&rarr;as-is.</li>
 *   <li>{@code unhex('1f')} between columns (ClickHouse 18.16 has no {@code char()} function);
 *       {@code lower} over the whole thing.</li>
 *   <li>fp = {@code toInt64(reinterpretAsUInt64(reverse(substring(MD5(...),1,7))))}:
 *       the first 7 raw MD5 bytes, big-endian.</li>
 * </ul>
 */
public final class ClickHouseIbltDialect implements IbltDialect {

    private static final String SEP = "unhex('1f')";

    @Override
    public String id() {
        return "clickhouse";
    }

    @Override
    public String fingerprintExpression(FingerprintQuery q) {
        String concat = concatExpr(q.pk(), q.valueColumns());
        String hashInput = q.lowerForString() ? "lower(" + concat + ")" : concat;
        return "toInt64(reinterpretAsUInt64(reverse(substring(MD5(" + hashInput + "), 1, 7))))";
    }

    @Override
    public String fingerprintSql(FingerprintQuery q) {
        return "SELECT " + fingerprintExpression(q) + " AS iblt_fp, "
                + quoteIdentifier(q.pk().name()) + " AS iblt_pk "
                + "FROM " + q.fromClause();
    }

    @Override
    public String recheckSql(RecheckQuery q, List<String> candidatePks) {
        boolean pkIsString = q.pkType() == PkType.STRING;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < candidatePks.size(); i++) {
            if (i > 0) {
                in.append(", ");
            }
            in.append(pkLiteral(candidatePks.get(i), pkIsString));
        }
        StringBuilder cols = new StringBuilder(quoteIdentifier(q.pkColumn())).append(" AS iblt_pk");
        for (String c : q.valueColumns()) {
            cols.append(", ").append(quoteIdentifier(c));
        }
        return "SELECT " + cols + " FROM " + q.fromClause()
                + " WHERE " + quoteIdentifier(q.pkColumn()) + " IN (" + in + ")";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        // ClickHouse backtick quoting; a backtick is escaped by doubling it
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String pkLiteral(String value, boolean pkIsString) {
        if (value == null) {
            return "NULL";
        }
        if (!pkIsString) {
            return value;
        }
        // ClickHouse string literals use backslash escaping for \ and '
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private String concatExpr(ColumnSpec pk, List<ColumnSpec> valueColumns) {
        StringBuilder sb = new StringBuilder("concat(").append(canonText(pk));
        for (ColumnSpec col : valueColumns) {
            sb.append(", ").append(SEP).append(", ").append(canonText(col));
        }
        sb.append(")");
        return sb.toString();
    }

    private String canonText(ColumnSpec col) {
        String q = quoteIdentifier(col.name());
        switch (col.type()) {
            case INT:
                return "toString(" + q + ")";
            case MONEY2:
                return "toString(toInt64(round(" + q + " * 100)))";
            case DATETIME_SEC:
                // version-independent: toString on a DateTime renders "yyyy-MM-dd HH:mm:ss" (second precision)
                // on both 18.16 and modern ClickHouse, byte-aligned with the other 4 engines (full-table md5 check).
                // formatDateTime is avoided: its %i/%M minute specifiers have opposite meanings across CH versions
                // (18.16: %M = minute; modern: %i = minute, %M = month name), a hidden cross-version divergence.
                return "toString(" + q + ")";
            case STRING:
            default:
                return q;
        }
    }
}

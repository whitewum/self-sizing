package io.github.selfsizing.iblt.sql;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/**
 * MySQL dialect (cross-engine alignment recipe, byte-aligned with
 * {@link SqlServerIbltDialect} / {@link ClickHouseIbltDialect} /
 * {@link PostgresIbltDialect}). All four dialects produce the same 56-bit
 * fingerprint, so any heterogeneous pair is comparable.
 *
 * <p><b>Recipe:</b>
 * <ul>
 *   <li>per-column normalization: INT&rarr;{@code CAST(c AS CHAR)};
 *       MONEY2&rarr;integer cents {@code CAST(CAST(ROUND(c,2)*100 AS SIGNED) AS CHAR)}
 *       (avoids the Decimal precision trap, aligns with the other engines);
 *       DATETIME_SEC&rarr;{@code DATE_FORMAT(c,'%Y-%m-%d %H:%i:%s')} (second precision);
 *       STRING&rarr;as-is.</li>
 *   <li>{@code CHAR(31)} (unit separator 0x1F) between columns; {@code LOWER} over the whole thing.</li>
 *   <li>fp = {@code CONV(SUBSTRING(MD5(...),1,14),16,10)}: the first 14 hex chars
 *       (= first 7 bytes) of the MD5, big-endian, to decimal &mdash; the same value as
 *       SQL Server's {@code CONVERT(BIGINT,SUBSTRING(HASHBYTES(...),1,7))} and CH's
 *       7-byte big-endian (&lt;2^56, stays positive).</li>
 * </ul>
 * concat uses {@code CONCAT} (any NULL column makes the whole thing NULL, matching CH's {@code concat}).
 */
public final class MysqlIbltDialect implements IbltDialect {

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String fingerprintExpression(FingerprintQuery q) {
        String concat = concatExpr(q.pk(), q.valueColumns());
        String hashInput = q.lowerForString() ? "LOWER(" + concat + ")" : concat;
        return "CONV(SUBSTRING(MD5(" + hashInput + "), 1, 14), 16, 10)";
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
        // MySQL backtick quoting; a backtick is escaped by doubling it
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
        // MySQL treats backslash as an escape character by default, so escape both \ and '
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private String concatExpr(ColumnSpec pk, List<ColumnSpec> valueColumns) {
        StringBuilder sb = new StringBuilder("CONCAT(").append(canonText(pk));
        for (ColumnSpec col : valueColumns) {
            sb.append(", CHAR(31), ").append(canonText(col));
        }
        sb.append(")");
        return sb.toString();
    }

    private String canonText(ColumnSpec col) {
        String q = quoteIdentifier(col.name());
        switch (col.type()) {
            case INT:
                return "CAST(" + q + " AS CHAR)";
            case MONEY2:
                // integer cents: ROUND then *100 as SIGNED, avoids the precision trap, aligns with the other engines
                return "CAST(CAST(ROUND(" + q + ", 2) * 100 AS SIGNED) AS CHAR)";
            case DATETIME_SEC:
                return "DATE_FORMAT(" + q + ", '%Y-%m-%d %H:%i:%s')";
            case STRING:
            default:
                return "CAST(" + q + " AS CHAR)";
        }
    }
}

package io.github.selfsizing.iblt.sql;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/**
 * SQL Server dialect (cross-engine alignment recipe). The fingerprint is
 * byte-aligned with ClickHouse; a full-table check gave CH.a vs SS.a = 0 diff
 * and CH.a vs SS.b = 22 diff.
 *
 * <p><b>Recipe:</b>
 * <ul>
 *   <li>concat uses {@code VARCHAR} (<b>single-byte, ASCII = UTF-8</b>; never
 *       NVARCHAR/UTF-16, which would diverge from ClickHouse's UTF-8 MD5).</li>
 *   <li>per-column normalization: INT&rarr;decimal string; MONEY2&rarr;integer cents
 *       {@code CAST(ROUND(c,2)*100 AS BIGINT)}; DATETIME_SEC&rarr;{@code CONVERT(VARCHAR(19),c,120)};
 *       STRING&rarr;as-is.</li>
 *   <li>{@code CHAR(31)} (unit separator 0x1F) between columns; {@code LOWER} over the whole thing.</li>
 *   <li>fp = {@code CONVERT(BIGINT, SUBSTRING(HASHBYTES('MD5',...),1,7))}: the first 7 bytes
 *       of the MD5, big-endian (&lt;2^56, stays positive).</li>
 * </ul>
 */
public final class SqlServerIbltDialect implements IbltDialect {

    @Override
    public String id() {
        return "sqlserver";
    }

    @Override
    public String fingerprintExpression(FingerprintQuery q) {
        String concat = concatExpr(q.pk(), q.valueColumns());
        String hashInput = q.lowerForString() ? "LOWER(" + concat + ")" : concat;
        return "CONVERT(BIGINT, SUBSTRING(HASHBYTES('MD5', " + hashInput + "), 1, 7))";
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
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    public String pkLiteral(String value, boolean pkIsString) {
        if (value == null) {
            return "NULL";
        }
        if (!pkIsString) {
            return value;
        }
        return "N'" + value.replace("'", "''") + "'";
    }

    private String concatExpr(ColumnSpec pk, List<ColumnSpec> valueColumns) {
        StringBuilder sb = new StringBuilder("CONCAT(").append(canonText(pk));
        for (ColumnSpec col : valueColumns) {
            sb.append(", CHAR(31), ").append(canonText(col));
        }
        sb.append(")");
        return sb.toString();
    }

    /** Cross-engine canonical text for a single column (VARCHAR, single-byte). */
    private String canonText(ColumnSpec col) {
        String q = quoteIdentifier(col.name());
        switch (col.type()) {
            case INT:
                return "CAST(" + q + " AS VARCHAR(40))";
            case MONEY2:
                // integer cents: ROUND then *100 as BIGINT, avoids the Float64 precision trap, aligns with ClickHouse
                return "CAST(CAST(ROUND(" + q + ", 2) * 100 AS BIGINT) AS VARCHAR(40))";
            case DATETIME_SEC:
                return "CONVERT(VARCHAR(19), " + q + ", 120)";
            case STRING:
            default:
                return "CAST(" + q + " AS VARCHAR(4000))";
        }
    }
}

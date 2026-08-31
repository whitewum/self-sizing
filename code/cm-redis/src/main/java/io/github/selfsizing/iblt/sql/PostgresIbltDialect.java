package io.github.selfsizing.iblt.sql;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/**
 * PostgreSQL dialect (cross-engine alignment recipe, byte-aligned with
 * {@link MysqlIbltDialect} / {@link SqlServerIbltDialect} /
 * {@link ClickHouseIbltDialect}). All four dialects produce the same 56-bit
 * fingerprint, so any heterogeneous pair is comparable.
 *
 * <p><b>Recipe:</b>
 * <ul>
 *   <li>per-column normalization: INT&rarr;{@code (c)::text}; MONEY2&rarr;integer cents
 *       {@code round(c*100)::bigint::text} (avoids the numeric precision trap, aligns
 *       with the other engines); DATETIME_SEC&rarr;{@code to_char(c,'YYYY-MM-DD HH24:MI:SS')}
 *       (second precision); STRING&rarr;{@code (c)::text}.</li>
 *   <li>{@code chr(31)} (unit separator 0x1F) between columns; {@code lower} over the whole thing.</li>
 *   <li>fp = {@code (('x'||substring(md5(...),1,14))::bit(56)::bigint)}: the first 14 hex
 *       chars (= first 7 bytes) of the MD5, big-endian, to bigint &mdash; the same value as
 *       MySQL's {@code CONV(...,16,10)} and SQL Server's 7-byte big-endian (&lt;2^56, stays positive).</li>
 * </ul>
 * concat uses {@code concat} (a NULL column is treated as an empty string and skipped;
 * the sample data here has no NULLs, and cross-engine NULL semantics are not yet unified).
 */
public final class PostgresIbltDialect implements IbltDialect {

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public String fingerprintExpression(FingerprintQuery q) {
        String concat = concatExpr(q.pk(), q.valueColumns());
        String hashInput = q.lowerForString() ? "lower(" + concat + ")" : concat;
        return "(('x' || substring(md5(" + hashInput + "), 1, 14))::bit(56)::bigint)";
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
        // PostgreSQL double-quote quoting; a double quote is escaped by doubling it
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String pkLiteral(String value, boolean pkIsString) {
        if (value == null) {
            return "NULL";
        }
        if (!pkIsString) {
            return value;
        }
        // PostgreSQL standard_conforming_strings is on by default: backslash is literal, only ' is escaped
        return "'" + value.replace("'", "''") + "'";
    }

    private String concatExpr(ColumnSpec pk, List<ColumnSpec> valueColumns) {
        StringBuilder sb = new StringBuilder("concat(").append(canonText(pk));
        for (ColumnSpec col : valueColumns) {
            sb.append(", chr(31), ").append(canonText(col));
        }
        sb.append(")");
        return sb.toString();
    }

    private String canonText(ColumnSpec col) {
        String q = quoteIdentifier(col.name());
        switch (col.type()) {
            case INT:
                return "(" + q + ")::text";
            case MONEY2:
                // integer cents: *100 rounded to bigint, avoids the precision trap, aligns with the other engines
                return "round(" + q + " * 100)::bigint::text";
            case DATETIME_SEC:
                return "to_char(" + q + ", 'YYYY-MM-DD HH24:MI:SS')";
            case STRING:
            default:
                return "(" + q + ")::text";
        }
    }
}

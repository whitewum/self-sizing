package io.github.selfsizing.iblt.sql;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/**
 * Oracle dialect (cross-engine alignment recipe, byte-aligned with
 * MySQL/PostgreSQL/SQL Server/ClickHouse). All five dialects produce the same
 * 56-bit fingerprint, so any heterogeneous pair is comparable. Verified on
 * Oracle Free 23ai.
 *
 * <p><b>Recipe:</b>
 * <ul>
 *   <li>per-column normalization: INT&rarr;{@code TO_CHAR(c)}; MONEY2&rarr;integer cents
 *       {@code TO_CHAR(ROUND(c*100))}; DATETIME_SEC&rarr;{@code TO_CHAR(c,'YYYY-MM-DD HH24:MI:SS')}
 *       (second precision); STRING&rarr;column as-is.</li>
 *   <li>{@code CHR(31)} between columns (unit separator 0x1F; single-byte under the
 *       AL32UTF8 database character set, matching the other engines' UTF-8);
 *       {@code LOWER} over the whole thing; concatenation with {@code ||}.</li>
 *   <li>fp = {@code TO_NUMBER(RAWTOHEX(UTL_RAW.SUBSTR(STANDARD_HASH(...,'MD5'),1,7)),'XXXXXXXXXXXXXX')}:
 *       the first 7 raw MD5 bytes, big-endian, to decimal &mdash; the same value as
 *       SQL Server/CH/MySQL/PG (&lt;2^56, stays positive).</li>
 * </ul>
 *
 * <p><b>Identifier case:</b> Oracle folds an unquoted DDL identifier to
 * <b>upper case</b> for storage. The upper layer always passes lower-case column
 * names ({@code id}/{@code value_text}...), so {@link #quoteIdentifier} upper-cases
 * before adding double quotes ({@code "ID"}) to match columns created without
 * quotes. Table names (schema.TABLE) are passed through {@code fromClause} as-is
 * and do not go through this method.</p>
 */
public final class OracleIbltDialect implements IbltDialect {

    @Override
    public String id() {
        return "oracle";
    }

    @Override
    public String fingerprintExpression(FingerprintQuery q) {
        String concat = concatExpr(q.pk(), q.valueColumns());
        String hashInput = q.lowerForString() ? "LOWER(" + concat + ")" : concat;
        return "TO_NUMBER(RAWTOHEX(UTL_RAW.SUBSTR(STANDARD_HASH(" + hashInput
                + ", 'MD5'), 1, 7)), 'XXXXXXXXXXXXXX')";
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
        // upper-case first (to match columns created without quotes), then double-quote; a quote is escaped by doubling it.
        return "\"" + identifier.toUpperCase().replace("\"", "\"\"") + "\"";
    }

    @Override
    public String pkLiteral(String value, boolean pkIsString) {
        if (value == null) {
            return "NULL";
        }
        if (!pkIsString) {
            return value;
        }
        // Oracle treats backslash as literal; only the single quote is escaped.
        return "'" + value.replace("'", "''") + "'";
    }

    private String concatExpr(ColumnSpec pk, List<ColumnSpec> valueColumns) {
        StringBuilder sb = new StringBuilder(canonText(pk));
        for (ColumnSpec col : valueColumns) {
            sb.append(" || CHR(31) || ").append(canonText(col));
        }
        return sb.toString();
    }

    private String canonText(ColumnSpec col) {
        String q = quoteIdentifier(col.name());
        switch (col.type()) {
            case INT:
                return "TO_CHAR(" + q + ")";
            case MONEY2:
                // integer cents: *100 rounded to an integer, avoids the precision trap, aligns with the other engines
                return "TO_CHAR(ROUND(" + q + " * 100))";
            case DATETIME_SEC:
                return "TO_CHAR(" + q + ", 'YYYY-MM-DD HH24:MI:SS')";
            case STRING:
            default:
                return q;
        }
    }
}

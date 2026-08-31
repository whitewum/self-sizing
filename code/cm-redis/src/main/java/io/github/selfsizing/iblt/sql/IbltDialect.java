package io.github.selfsizing.iblt.sql;

import java.util.List;

/**
 * Database dialect: translates a neutral query description into engine-specific SQL.
 * Switching databases only touches this layer.
 *
 * <p><b>Fingerprint invariant:</b> the {@code iblt_fp} produced by
 * {@link #fingerprintSql} must be
 * {@code first 56 bits( md5( [lower]( concat_ws(chr31, pk, values...) ) ) )} &mdash;
 * this determines IBLT cell placement and cancellation, so the same logical row
 * must be bit-identical across every engine taking part in a comparison (for
 * cross-database comparison). The single-engine combination used here
 * (SQL Server&harr;SQL Server) is consistent by construction as long as both
 * sides run the same implementation.</p>
 */
public interface IbltDialect {

    /** Dialect id (e.g. {@code sqlserver}), used for logging and selection. */
    String id();

    /**
     * Builds the "fingerprint stream" SQL, returning two columns:
     * {@code iblt_fp} (bigint) and {@code iblt_pk} (the original primary key).
     */
    String fingerprintSql(FingerprintQuery query);

    /**
     * Returns the bare single-row fingerprint expression &mdash; the expression
     * used as {@code iblt_fp} inside {@link #fingerprintSql}, without the
     * {@code SELECT}/{@code FROM} wrapper. Byte-for-byte the same as
     * {@link #fingerprintSql}.
     *
     * <p>Lets the Merkle baseline aggregate inline within a block via
     * {@code SELECT SUM(<expr>), COUNT(*) FROM tbl WHERE range}, avoiding an
     * extra {@code (SELECT * FROM ...)} derived table for the block checksum
     * (that layer would pull all columns and block range pushdown, which would
     * be unfair to the baseline).</p>
     */
    String fingerprintExpression(FingerprintQuery query);

    /**
     * Builds the recheck SQL:
     * {@code SELECT pk, values... FROM <from> WHERE pk IN (<candidatePks literals>)}.
     *
     * @param candidatePks raw candidate primary-key values (rendered as literals
     *                     by the dialect according to {@link RecheckQuery#pkType()})
     */
    String recheckSql(RecheckQuery query, List<String> candidatePks);

    /** Engine-specific quoted form of a primary-key column name (used for recheck lookups). */
    String quoteIdentifier(String identifier);

    /**
     * Renders a raw primary-key value as a SQL literal.
     *
     * @param pkIsString true &rarr; quoted string (escaped); false &rarr; numeric as-is
     */
    String pkLiteral(String value, boolean pkIsString);
}

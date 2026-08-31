package io.github.selfsizing.iblt.sql;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/**
 * Neutral description of a "fingerprint stream" query: which table/subquery to
 * read from, the primary-key column, the value columns (each with its
 * engine-independent canonical type), and whether to lower-case.
 *
 * <p>Consumed by {@link IbltDialect#fingerprintSql} to generate two-column SQL
 * ({@code iblt_fp}, {@code iblt_pk}). Each column's {@link ColumnSpec#type()}
 * determines the cross-engine normalized text, so different engines produce a
 * byte-identical fingerprint.</p>
 *
 * <p>{@code pkType} ({@link PkType}) is also used on the Java side for the
 * {@code cheap8} primary-key canonical encoding and for rendering recheck
 * literals. It is a separate layer from the {@link CanonType} of the fingerprint
 * concat, and the caller must keep the two consistent (e.g. an integer pk:
 * CanonType.INT + PkType.INT64).</p>
 */
public final class FingerprintQuery {

    private final String fromClause;
    private final ColumnSpec pk;
    private final PkType pkType;
    private final List<ColumnSpec> valueColumns;
    private final boolean lowerForString;

    public FingerprintQuery(String fromClause,
                            ColumnSpec pk,
                            PkType pkType,
                            List<ColumnSpec> valueColumns,
                            boolean lowerForString) {
        this.fromClause = fromClause;
        this.pk = pk;
        this.pkType = pkType;
        this.valueColumns = List.copyOf(valueColumns);
        this.lowerForString = lowerForString;
    }

    /** The fragment used directly after FROM: a table name ({@code dbo.items} / {@code items}) or {@code ( <subquery> ) alias}. */
    public String fromClause() {
        return fromClause;
    }

    public ColumnSpec pk() {
        return pk;
    }

    public PkType pkType() {
        return pkType;
    }

    public List<ColumnSpec> valueColumns() {
        return valueColumns;
    }

    public boolean lowerForString() {
        return lowerForString;
    }
}

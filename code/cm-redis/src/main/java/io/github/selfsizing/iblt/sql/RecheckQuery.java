package io.github.selfsizing.iblt.sql;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;
import java.util.List;

/**
 * Neutral description of a recheck query: given candidate primary keys,
 * {@code WHERE pk IN(...)} pulls back the real row values for a value-by-value
 * comparison.
 *
 * <p>Consumed by {@link IbltDialect#recheckSql}. The candidate primary-key
 * values are passed to the dialect separately (together with {@code pkType},
 * which decides whether the literal is quoted); they are not held on this
 * object, so one query can be reused across multiple candidate batches.</p>
 */
public final class RecheckQuery {

    private final String fromClause;
    private final String pkColumn;
    private final PkType pkType;
    private final List<String> valueColumns;

    public RecheckQuery(String fromClause,
                        String pkColumn,
                        PkType pkType,
                        List<String> valueColumns) {
        this.fromClause = fromClause;
        this.pkColumn = pkColumn;
        this.pkType = pkType;
        this.valueColumns = List.copyOf(valueColumns);
    }

    public String fromClause() {
        return fromClause;
    }

    public String pkColumn() {
        return pkColumn;
    }

    public PkType pkType() {
        return pkType;
    }

    public List<String> valueColumns() {
        return valueColumns;
    }
}

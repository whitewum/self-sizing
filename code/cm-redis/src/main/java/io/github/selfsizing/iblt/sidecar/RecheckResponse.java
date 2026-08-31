package io.github.selfsizing.iblt.sidecar;

import java.util.List;

/**
 * Recheck response: the candidate rows' real column values (column names + rows, all as strings for engine-neutral comparison) + query time.
 */
public final class RecheckResponse {

    private final List<String> columns;
    private final List<String[]> rows;
    private final long queryMillis;

    public RecheckResponse(List<String> columns, List<String[]> rows, long queryMillis) {
        this.columns = List.copyOf(columns);
        this.rows = List.copyOf(rows);
        this.queryMillis = queryMillis;
    }

    /** Column names; the first column is {@code iblt_pk}. */
    public List<String> columns() {
        return columns;
    }

    /** The matched candidate rows; each row aligns with {@link #columns()}. */
    public List<String[]> rows() {
        return rows;
    }

    public long queryMillis() {
        return queryMillis;
    }

    public int rowCount() {
        return rows.size();
    }
}

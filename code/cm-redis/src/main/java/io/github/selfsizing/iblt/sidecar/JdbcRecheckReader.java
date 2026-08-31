package io.github.selfsizing.iblt.sidecar;

import io.github.selfsizing.iblt.sql.IbltDialect;
import io.github.selfsizing.iblt.sql.RecheckQuery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * JDBC recheck reader: fetches real row values by candidate primary key with {@code WHERE pk IN(...)}.
 *
 * <p>IBLT decode gives "candidate difference primary keys" (a superset from
 * fingerprint divergence, never missing a real diff but possibly with false
 * candidates); the recheck fetches these candidates' real values so the upper
 * layer can compare value-by-value and clear false candidates caused by
 * cross-database format differences.</p>
 *
 * <p><b>TODO:</b> when there are many candidates {@code IN(...)} gets too long
 * and needs batching (e.g. 1000 per batch). At the current experiment scale
 * (tens of candidates) a single batch is enough.</p>
 */
public final class JdbcRecheckReader {

    private static final Logger LOG = Logger.getLogger(JdbcRecheckReader.class.getName());

    /**
     * SQL log switch (for experiment sanity checks; turn off for measured runs):
     * prints the actual recheck {@code WHERE pk IN(...)} SQL + thread name +
     * candidate count, to verify recheck SQL correctness and two-side concurrency.
     * Enable: {@code -Dbench.sqlLog=true} or the environment variable {@code BENCH_SQL_LOG=true}.
     */
    private static final boolean SQL_LOG = Boolean.parseBoolean(
            System.getProperty("bench.sqlLog",
                    System.getenv().getOrDefault("BENCH_SQL_LOG", "false")));

    public RecheckResponse recheck(Connection conn, IbltDialect dialect, RecheckQuery query,
                                   List<String> candidatePks) throws Exception {
        if (candidatePks.isEmpty()) {
            return new RecheckResponse(Collections.emptyList(), Collections.emptyList(), 0L);
        }
        String sql = dialect.recheckSql(query, candidatePks);
        if (SQL_LOG) {
            System.out.printf("[bench][iblt][sql] thread=%s phase=recheck db=%s candidates=%d sql=%s%n",
                    Thread.currentThread().getName(), dialect.id(), candidatePks.size(), sql);
        }
        long t0 = System.nanoTime();
        List<String> columns = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int c = 1; c <= n; c++) {
                columns.add(md.getColumnLabel(c));
            }
            while (rs.next()) {
                String[] r = new String[n];
                for (int c = 1; c <= n; c++) {
                    r[c - 1] = rs.getString(c);
                }
                rows.add(r);
            }
        }
        long queryMillis = Math.round((System.nanoTime() - t0) / 1e6);
        final int hit = rows.size();
        final int asked = candidatePks.size();
        // performance instrumentation: the recheck is a significant once-per-round step.
        LOG.info(() -> String.format("[iblt] recheck: dialect=%s, candidates=%d, hitRows=%d, elapsedMs=%d",
                dialect.id(), asked, hit, queryMillis));
        return new RecheckResponse(columns, rows, queryMillis);
    }
}

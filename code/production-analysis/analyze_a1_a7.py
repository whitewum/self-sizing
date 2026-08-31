#!/usr/bin/env python3
"""Aggregate analysis A1-A7 of the production comparison logs.

This is the script that produced the reviewed aggregate CSVs shipped in
``data/paper/`` (production summary, d / duration quantiles, rank buckets, size
buckets). The raw production comparison logs it reads are not distributed; see
``DATA_AVAILABILITY.md``.

Input (three read-only CSV exports, not part of the repository). The column
names below are a neutral schema; point the script at CSVs whose headers match
it, or add a renaming view over your own export.

  compare_tasks_*.csv     one row per comparison task
      id, ...                                        (task definition; only id is used)

  compare_execs_*.csv     one row per task execution
      id, task_id, account_id, create_time, exec_type, status, sample_percent

  compare_progress_*.csv  one row per table per execution
      exec_id, source_db, source_table, pk_type, status, start_time, end_time,
      source_rows, target_rows, changed_rows, source_only_rows, target_only_rows

Output: aggregate CSVs under out/ (no database, table, or account identifiers)
plus a report on stdout. Table names and task IDs appear only in
out/_name_map.local.csv, which is excluded by .gitignore.

Definitions (consistent with the paper, Section 3):
  d_row = changed + source-only + target-only              (row count)
  d_ms  = 2*changed + source-only + target-only            (unit-weight multiset count, the paper's convention)
  N     = max(source_rows, target_rows)

Usage: python3 analyze_a1_a7.py [--raw-dir .] [--out out]
"""

import argparse
import hashlib
import pathlib
import sys

import duckdb

def bucket_expr(col="N"):
    """Bucket by table size. col can be an aggregate expression (e.g. max(N))."""
    return (f"CASE WHEN {col} < 1000 THEN '1_<1K' WHEN {col} < 1000000 THEN '2_1K-1M' "
            f"WHEN {col} < 10000000 THEN '3_1-10M' WHEN {col} < 100000000 THEN '4_10-100M' "
            f"ELSE '5_>100M' END")


BUCKETS = bucket_expr("N")

# Deployment configuration (paper Section 5.4): M1=512 (16 KB) -> RSD 6.3%, alpha~=1.52; 32 B/cell
M1_CELLS = 512
ALPHA = 1.52
BETA = 1.3          # decoder capacity margin
BYTES_PER_CELL = 32
PRIOR_ORACLE_RHO = ALPHA / BETA
# Periodic-series test: lower bound on run count within the window
PERIODIC_MIN_RUNS = 30


def anon(s) -> str:
    if not isinstance(s, str):
        return "T_unknown"
    return "T" + hashlib.blake2s(s.encode(), digest_size=4).hexdigest()


def build(con, raw: pathlib.Path):
    prog = sorted(raw.glob("compare_progress_*.csv"))
    execs = sorted(raw.glob("compare_execs_*.csv"))
    tasks = sorted(raw.glob("compare_tasks_*.csv"))
    if not (prog and execs):
        sys.exit(f"missing input CSVs (raw-dir={raw})")

    con.execute(f"""
    CREATE VIEW prog_raw AS
      SELECT * FROM read_csv_auto({[str(p) for p in prog]}, header=true, sample_size=-1);
    CREATE VIEW exec_raw AS
      SELECT * FROM read_csv_auto({[str(p) for p in execs]}, header=true,
                                  sample_size=-1, union_by_name=true);
    """)
    if tasks:
        con.execute(f"CREATE VIEW task_raw AS SELECT * FROM read_csv_auto("
                    f"{[str(p) for p in tasks]}, header=true, sample_size=-1);")

    # Per-table-per-execution wide table. Sampled executions (sample_percent not null)
    # are dropped entirely: their d is not a full-scan d.
    con.execute(f"""
    CREATE TABLE rec AS
    SELECT
      p.exec_id,
      e.task_id,
      e.account_id,
      e.create_time                                   AS exec_time,
      e.exec_type,
      p.source_db || '.' || p.source_table            AS tbl,
      e.task_id || '|' || p.source_db || '.' || p.source_table AS series,
      p.pk_type,
      p.source_rows                                   AS n_src,
      p.target_rows                                   AS n_tgt,
      greatest(p.source_rows, p.target_rows)          AS N,
      p.source_rows + p.target_rows                   AS rows_both_sides,
      p.changed_rows                                  AS n_diff,
      p.source_only_rows                              AS n_only_src,
      p.target_only_rows                              AS n_only_tgt,
      p.changed_rows + p.source_only_rows + p.target_only_rows       AS d_row,
      2*p.changed_rows + p.source_only_rows + p.target_only_rows     AS d_ms,
      date_diff('second', p.start_time, p.end_time)   AS dur_s,
      (p.source_rows = 0 AND p.target_rows = 0)                      AS is_empty,
      (p.pk_type IS NULL)                             AS no_pk
    FROM prog_raw p
    JOIN exec_raw e ON p.exec_id = e.id
    WHERE p.status = 'success'
      AND e.status = 'success'
      AND e.sample_percent IS NULL;          -- drop sampled executions

    -- idle-run flag: d=0 and duration under 10% of the series' normal duration (the sync did not really scan)
    CREATE TABLE med AS
      SELECT series, median(dur_s) AS med_dur FROM rec WHERE d_row > 0 GROUP BY 1;
    CREATE TABLE r AS
      SELECT rec.*, {BUCKETS} AS bucket,
             coalesce(rec.d_row = 0 AND m.med_dur > 10 AND rec.dur_s < 0.1*m.med_dur, false) AS idle
      FROM rec LEFT JOIN med m USING (series);

    -- descriptive main population: drop empty tables. idle and no_pk keep their flags, filtered explicitly per analysis.
    CREATE VIEW ne AS SELECT * FROM r WHERE NOT is_empty;
    -- IBLT-deployable subset: further drop idle runs and tables without a primary key.
    CREATE VIEW eligible AS SELECT * FROM ne WHERE NOT idle AND NOT no_pk;
    """)


def q(con, sql):
    return con.execute(sql).df()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw-dir", default=".")
    ap.add_argument("--out", default="out")
    a = ap.parse_args()
    raw, out = pathlib.Path(a.raw_dir), pathlib.Path(a.out)
    out.mkdir(exist_ok=True)

    con = duckdb.connect()
    build(con, raw)

    def emit(name, df, show=True):
        df.to_csv(out / f"{name}.csv", index=False)
        if show:
            print(f"### {name}\n{df.to_string(index=False)}\n")

    # ---------- data boundary ----------
    emit("00_scope", q(con, """
      SELECT count(*) AS recs, count(*) FILTER (WHERE is_empty) AS empty_recs,
             count(*) FILTER (WHERE no_pk) AS no_pk_recs,
             count(*) FILTER (WHERE idle)  AS idle_recs,
             count(*) FILTER (WHERE NOT is_empty AND NOT idle AND NOT no_pk) AS eligible_recs,
             count(DISTINCT exec_id) AS execs, count(DISTINCT task_id) AS tasks,
             count(DISTINCT series)  AS series,
             min(exec_time) AS t0, max(exec_time) AS t1 FROM r"""))

    emit("00_data_quality", q(con, """
      SELECT
        (SELECT count(*) FROM prog_raw) AS raw_progress_recs,
        (SELECT count(DISTINCT exec_id) FROM prog_raw) AS raw_progress_execs,
        (SELECT count(*) FROM exec_raw) AS raw_execs,
        (SELECT count(DISTINCT id) FROM exec_raw
          WHERE sample_percent IS NOT NULL) AS sampled_execs,
        (SELECT count(*) FROM prog_raw p LEFT JOIN exec_raw e ON p.exec_id=e.id
          WHERE e.id IS NULL) AS unmatched_progress_recs,
        (SELECT count(DISTINCT p.exec_id) FROM prog_raw p
          LEFT JOIN exec_raw e ON p.exec_id=e.id WHERE e.id IS NULL) AS unmatched_progress_execs,
        (SELECT count(*) FROM rec WHERE dur_s IS NULL) AS matched_null_duration_recs,
        (SELECT count(*) FROM ne WHERE no_pk) AS nonempty_no_pk_recs,
        round(100.0*(SELECT sum(dur_s) FROM ne WHERE no_pk)
              /nullif((SELECT sum(dur_s) FROM ne),0),3) AS pct_table_time_no_pk
      """))

    # ---------- A1 definition ladder ----------
    emit("a1_zero_diff_by_scope", q(con, """
      SELECT '1_all records (incl. empty tables)' AS scope, count(*) AS recs,
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) AS pct_d0 FROM r
      UNION ALL SELECT '2_drop empty tables', count(*),
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) FROM ne
      UNION ALL SELECT '3_drop empty tables + idle runs', count(*),
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) FROM ne WHERE NOT idle
      UNION ALL SELECT '4_IBLT-deployable subset (default)', count(*),
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) FROM eligible
      UNION ALL SELECT '5_deployable subset N>=1e3', count(*),
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) FROM eligible WHERE N>=1000
      UNION ALL SELECT '6_deployable subset N>=1e6', count(*),
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) FROM eligible WHERE N>=1000000
      ORDER BY 1"""))

    # ---------- A1/A3/A4 layered by table size ----------
    emit("a1_a3_a4_by_size", q(con, f"""
      SELECT bucket, count(*) AS recs,
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) AS pct_d0,
             median(N)::BIGINT AS med_N,
             median(dur_s)::BIGINT AS med_dur_s,
             quantile_cont(dur_s,0.9)::BIGINT AS p90_dur_s,
             round(sum(dur_s)/3600.0,1) AS table_hours,
             round(100.0*sum(dur_s)/(SELECT sum(dur_s) FROM eligible),1) AS pct_table_time,
             round(100.0*sum(N)/(SELECT sum(N) FROM eligible),1) AS pct_max_side_rows
      FROM eligible GROUP BY 1 ORDER BY 1"""))

    # ---------- A4b time concentration: the median table is fast, the long tail eats all the time ----------
    con.execute("""
    CREATE TABLE dur AS
      SELECT dur_s, d_row, N, bucket, row_number() OVER (ORDER BY dur_s DESC) AS rn
      FROM eligible WHERE dur_s IS NOT NULL;
    """)
    tot, nrec = con.execute("SELECT sum(dur_s), count(*) FROM dur").fetchone()
    rows = []
    for lab, frac in [("1_top 0.1%", 0.001), ("2_top 1%", 0.01), ("3_top 5%", 0.05), ("4_top 10%", 0.10)]:
        k = max(1, int(frac * nrec))
        rows.append((lab,) + con.execute(f"""
          SELECT count(*), round(100.0*sum(dur_s)/{tot},1),
                 round(100.0*count(*) FILTER (WHERE d_row>0)/count(*),1),
                 round(median(dur_s)/3600.0,2), round(median(N)/1e6,1)
          FROM dur WHERE rn<={k}""").fetchone())
    rows.append(("5_bottom 50%",) + con.execute(f"""
      SELECT count(*), round(100.0*sum(dur_s)/{tot},1),
             round(100.0*count(*) FILTER (WHERE d_row>0)/count(*),1),
             round(median(dur_s)/3600.0,4), round(median(N)/1e6,4)
      FROM dur WHERE rn>{nrec//2}""").fetchone())
    import pandas as pd
    emit("a4b_time_concentration", pd.DataFrame(
        rows, columns=["slice", "recs", "pct_of_total_time", "pct_d_gt0", "med_hours", "med_N_millions"]))

    # Duration quantile ladder over the deployable subset -- p50->p90 spans two orders of magnitude, given step by step.
    # cum_pct_time: the share of cumulative table-level time from records whose duration is at most this quantile.
    qs = [0.10, 0.25, 0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 0.99, 0.999]
    qsel = ", ".join(f"quantile_cont(dur_s,{x}) AS q{int(x*1000)}" for x in qs)
    qv = con.execute(f"SELECT {qsel}, max(dur_s), sum(dur_s), count(*) "
                     "FROM eligible WHERE dur_s IS NOT NULL").fetchone()
    tot_s, n_s = qv[-2], qv[-1]
    rows = []
    for lab, v in list(zip([f"p{x*100:g}" for x in qs], qv[:-3])) + [("max", qv[-3])]:
        below = con.execute("SELECT sum(dur_s) FROM eligible "
                            f"WHERE dur_s IS NOT NULL AND dur_s<={v}").fetchone()[0] or 0
        rows.append((lab, round(v, 1), round(v/3600.0, 3), round(100.0*below/tot_s, 2)))
    emit("a4e_duration_quantiles", pd.DataFrame(
        rows, columns=["quantile", "dur_s", "dur_hours", "cum_pct_table_time"]))
    print(f"(duration quantiles over {n_s} deployable records with start/end times)")

    # A3b table-size quantile ladder, same steps as the duration ladder, for cross-reading.
    qsel_n = ", ".join(f"quantile_cont(N,{x}) AS q{int(x*1000)}" for x in qs)
    nv = con.execute(f"SELECT {qsel_n}, max(N), sum(N) FROM eligible").fetchone()
    tot_n = nv[-1]
    rows = []
    for lab, v in list(zip([f"p{x*100:g}" for x in qs], nv[:-2])) + [("max", nv[-2])]:
        below = con.execute(f"SELECT sum(N) FROM eligible WHERE N<={v}").fetchone()[0] or 0
        rows.append((lab, int(v), round(100.0*below/tot_n, 2)))
    emit("a3b_size_quantiles", pd.DataFrame(
        rows, columns=["quantile", "N_rows", "cum_pct_max_side_rows"]))

    # Duration decile x table size: is shifting gears driven by scale or by throughput?
    emit("a4f_size_by_duration_decile", q(con, """
      WITH d AS (SELECT *, ntile(10) OVER (ORDER BY dur_s) AS dec FROM eligible)
      SELECT dec AS duration_decile,
             count(*) AS recs,
             median(dur_s)::BIGINT AS med_dur_s,
             quantile_cont(N,0.10)::BIGINT AS N_p10,
             median(N)::BIGINT              AS N_p50,
             quantile_cont(N,0.90)::BIGINT  AS N_p90,
             round(median(N/nullif(dur_s,0)),0) AS med_rows_per_s,
             round(100.0*count(*) FILTER (WHERE d_row=0)/count(*),1) AS pct_d0,
             round(100.0*sum(N)/(SELECT sum(N) FROM eligible),2) AS pct_max_side_rows,
             round(100.0*sum(dur_s)/(SELECT sum(dur_s) FROM eligible),2) AS pct_table_time
      FROM d GROUP BY 1 ORDER BY 1"""))

    emit("a4c_batch_tail", q(con, """
      WITH e AS (SELECT exec_id, count(*) AS ntab, median(dur_s) AS med,
                        max(dur_s) AS mx, sum(dur_s) AS tot
                 FROM eligible WHERE dur_s IS NOT NULL
                 GROUP BY 1 HAVING count(*)>=10)
      SELECT count(*) AS execs, median(ntab)::INT AS med_tables_per_batch,
             median(med)::INT AS med_table_s, round(median(mx)/3600.0,2) AS med_slowest_table_h,
             round(median(mx/nullif(med,0)),0) AS ratio_slowest_over_median,
             round(100.0*median(mx/nullif(tot,0)),1) AS pct_batch_table_time_in_slowest
      FROM e"""))

    # The absolute cost of the workload is highly concentrated; emit only ratios, no account identifier.
    emit("a4d_workload_concentration", q(con, """
      WITH a AS (
        SELECT account_id, sum(dur_s) AS sec, sum(N) AS max_side_rows, count(*) AS recs
        FROM eligible GROUP BY 1
      ), topa AS (
        SELECT account_id FROM a ORDER BY sec DESC LIMIT 1
      )
      SELECT
        (SELECT round(100.0*sec/(SELECT sum(sec) FROM a),1) FROM a
          WHERE account_id=(SELECT account_id FROM topa)) AS top_account_pct_table_time,
        (SELECT round(100.0*max_side_rows/(SELECT sum(max_side_rows) FROM a),1) FROM a
          WHERE account_id=(SELECT account_id FROM topa))
          AS top_account_pct_max_side_rows,
        (SELECT round(100.0*recs/(SELECT sum(recs) FROM a),1) FROM a
          WHERE account_id=(SELECT account_id FROM topa)) AS top_account_pct_recs,
        round(100.0*count(*) FILTER (
          WHERE account_id=(SELECT account_id FROM topa) AND N>=100000000)
          /nullif(count(*) FILTER (WHERE N>=100000000),0),1)
          AS top_account_pct_gt100m_recs,
        round(100.0*count(*) FILTER (
          WHERE account_id=(SELECT account_id FROM topa) AND N>=100000000 AND d_row>0)
          /nullif(count(*) FILTER (WHERE N>=100000000 AND d_row>0),0),1)
          AS top_account_pct_gt100m_dpos_recs,
        round(100.0*count(*) FILTER (
          WHERE account_id<>(SELECT account_id FROM topa) AND d_row=0)
          /nullif(count(*) FILTER (
            WHERE account_id<>(SELECT account_id FROM topa)),0),1)
          AS other_accounts_pct_d0
      FROM eligible
      """))

    # ---------- A1b series level ----------
    emit("a1b_series_level", q(con, f"""
      WITH s AS (
        SELECT series, count(*) AS runs, max(N) AS N, sum(d_row) AS sd,
               {bucket_expr('max(N)')} AS bucket
        FROM eligible GROUP BY series)
      SELECT CASE WHEN runs>={PERIODIC_MIN_RUNS} THEN 'periodic series (>=30 runs)' ELSE 'low-frequency series (<30 runs)' END AS kind,
             bucket, count(*) AS series,
             round(100.0*count(*) FILTER (WHERE sd=0)/count(*),1) AS pct_never_diff
      FROM s GROUP BY 1,2
      UNION ALL
      SELECT CASE WHEN runs>={PERIODIC_MIN_RUNS} THEN 'periodic series (>=30 runs)' ELSE 'low-frequency series (<30 runs)' END,
             '_total', count(*), round(100.0*count(*) FILTER (WHERE sd=0)/count(*),1)
      FROM s GROUP BY 1 ORDER BY 1,2"""))

    # ---------- A2 d and d/N distribution ----------
    # A2b difference-magnitude quantile ladder (d>0 records), same steps as the duration / size ladders;
    # cum_pct_diffs: the share of total difference magnitude from records whose d is at most this quantile.
    # round1_ok: the first round M1=512 cells decodes directly when M>=beta*d (d <= 512/1.3 = 393).
    qsel_d = ", ".join(f"quantile_cont(d_ms,{x}) AS q{int(x*1000)}" for x in qs)
    dv = con.execute(f"SELECT {qsel_d}, max(d_ms), sum(d_ms) "
                     "FROM eligible WHERE d_row>0").fetchone()
    tot_d = dv[-1]
    rows = []
    for lab, v in list(zip([f"p{x*100:g}" for x in qs], dv[:-2])) + [("max", dv[-2])]:
        below = con.execute("SELECT sum(d_ms) FROM eligible "
                            f"WHERE d_row>0 AND d_ms<={v}").fetchone()[0] or 0
        rows.append((lab, int(v), round(100.0*below/tot_d, 3),
                     "yes" if v <= M1_CELLS/BETA else "no"))
    emit("a2b_d_quantiles", pd.DataFrame(
        rows, columns=["quantile", "d_ms", "cum_pct_diffs", "round1_ok_at_M1"]))

    emit("a2c_capacity_read", q(con, f"""
      SELECT count(*) AS d_pos_recs,
             round(100.0*count(*) FILTER (WHERE d_ms<={M1_CELLS/BETA})/count(*),1)   AS pct_round1_decodes,
             round(100.0*count(*) FILTER (WHERE d_ms<={4096/BETA})/count(*),1) AS pct_fits_4096_cells,
             round(100.0*count(*) FILTER (WHERE d_ms>1e6)/count(*),2)          AS pct_d_over_1e6
      FROM eligible WHERE d_row>0"""))

    # A2d within-table effect: on the same table, does more difference mean slower?
    # Per series, compute the Spearman rank correlation of d_ms and dur_s, then look at the distribution of those coefficients.
    con.execute("""
    CREATE TABLE ser_corr AS
    WITH base AS (
      SELECT series, d_ms, dur_s,
             rank() OVER (PARTITION BY series ORDER BY d_ms)  AS r_d,
             rank() OVER (PARTITION BY series ORDER BY dur_s) AS r_t,
             rank() OVER (PARTITION BY series ORDER BY N)     AS r_n
      FROM eligible WHERE dur_s IS NOT NULL
    )
    SELECT series, count(*) AS runs,
           corr(r_d, r_t) AS rho,          -- difference magnitude ~ duration
           corr(r_n, r_t) AS rho_n_t,      -- table size ~ duration
           corr(r_d, r_n) AS rho_d_n,      -- difference magnitude ~ table size (confounding channel)
           count(DISTINCT d_ms) AS distinct_d
    FROM base GROUP BY series
    HAVING count(*)>=20 AND count(DISTINCT d_ms)>=5 AND stddev_pop(dur_s)>0;
    """)
    emit("a2d_within_table_d_vs_time", q(con, """
      SELECT count(*) AS series, median(runs)::INT AS med_runs,
             round(quantile_cont(rho,0.25),3) AS rho_p25,
             round(median(rho),3)             AS rho_p50,
             round(quantile_cont(rho,0.75),3) AS rho_p75,
             round(100.0*count(*) FILTER (WHERE rho>0)/count(*),1)    AS pct_positive,
             round(100.0*count(*) FILTER (WHERE rho>0.3)/count(*),1)  AS pct_rho_gt_0_3,
             round(100.0*count(*) FILTER (WHERE rho<-0.3)/count(*),1) AS pct_rho_lt_neg_0_3,
             round(median(rho_n_t),3) AS rho_N_time_p50,
             round(median(rho_d_n),3) AS rho_d_N_p50
      FROM ser_corr"""))

    # Within the same series: ratio of median duration for d=0 runs vs d>0 runs.
    emit("a2e_within_table_zero_vs_pos", q(con, """
      WITH s AS (
        SELECT series,
               median(dur_s) FILTER (WHERE d_row=0) AS med_zero,
               median(dur_s) FILTER (WHERE d_row>0) AS med_pos,
               count(*) FILTER (WHERE d_row=0) AS n_zero,
               count(*) FILTER (WHERE d_row>0) AS n_pos,
               median(N) AS med_N
        FROM eligible WHERE dur_s IS NOT NULL GROUP BY 1
      ), t AS (SELECT *, med_pos/nullif(med_zero,0) AS ratio FROM s
               WHERE n_zero>=3 AND n_pos>=3 AND med_zero>0)
      SELECT 'all' AS scope, count(*) AS series,
             round(quantile_cont(ratio,0.25),2) AS p25,
             round(median(ratio),2)             AS p50,
             round(quantile_cont(ratio,0.75),2) AS p75,
             round(100.0*count(*) FILTER (WHERE ratio>1.1)/count(*),1) AS pct_pos_slower_10
      FROM t
      UNION ALL
      SELECT 'large tables N>=1e6', count(*), round(quantile_cont(ratio,0.25),2),
             round(median(ratio),2), round(quantile_cont(ratio,0.75),2),
             round(100.0*count(*) FILTER (WHERE ratio>1.1)/count(*),1)
      FROM t WHERE med_N>=1000000"""))

    emit("a2_d_distribution", q(con, """
      SELECT 'row count d_row' AS metric, count(*) AS n,
             quantile_cont(d_row,0.5)::BIGINT AS p50, quantile_cont(d_row,0.9)::BIGINT AS p90,
             quantile_cont(d_row,0.99)::BIGINT AS p99, max(d_row)::BIGINT AS mx FROM eligible WHERE d_row>0
      UNION ALL SELECT 'multiset count d_ms', count(*),
             quantile_cont(d_ms,0.5)::BIGINT, quantile_cont(d_ms,0.9)::BIGINT,
             quantile_cont(d_ms,0.99)::BIGINT, max(d_ms)::BIGINT FROM eligible WHERE d_row>0"""))

    emit("a2_dn_by_size", q(con, """
      SELECT bucket, count(*) AS n,
             quantile_cont(d_ms::DOUBLE/N,0.01) AS p01,
             quantile_cont(d_ms::DOUBLE/N,0.25) AS p25,
             quantile_cont(d_ms::DOUBLE/N,0.5)  AS p50,
             quantile_cont(d_ms::DOUBLE/N,0.9)  AS p90
      FROM eligible WHERE d_row>0 AND N>=1000 GROUP BY 1 ORDER BY 1"""))

    # ---------- A5 difference composition ----------
    emit("a5_composition", q(con, """
      SELECT 'magnitude-weighted (share of total d_row)' AS view,
             round(100.0*sum(n_diff)/sum(d_row),1) AS pct_different,
             round(100.0*sum(n_only_src)/sum(d_row),1) AS pct_only_source,
             round(100.0*sum(n_only_tgt)/sum(d_row),1) AS pct_only_target FROM eligible WHERE d_row>0
      UNION ALL SELECT 'occurrence rate (share of table-runs with a difference)',
             round(100.0*count(*) FILTER (WHERE n_diff>0)/count(*),1),
             round(100.0*count(*) FILTER (WHERE n_only_src>0)/count(*),1),
             round(100.0*count(*) FILTER (WHERE n_only_tgt>0)/count(*),1) FROM eligible WHERE d_row>0"""))

    # ---------- A6 length of consecutive d>0 runs (window-boundary censoring reported separately) ----------
    con.execute(f"""
    CREATE TABLE seq AS
      SELECT * FROM (
        SELECT *, count(*) OVER (PARTITION BY series) AS runs,
               row_number() OVER (PARTITION BY series ORDER BY exec_time) AS rn
        FROM eligible)
      WHERE runs >= {PERIODIC_MIN_RUNS};
    CREATE TABLE runs AS
      WITH g AS (SELECT series, rn, d_row, exec_time, dur_s,
                        rn - row_number() OVER (PARTITION BY series, (d_row>0) ORDER BY rn) AS grp
                 FROM seq)
      SELECT series, grp, count(*) AS len, sum(dur_s) AS sec
      FROM g WHERE d_row>0 GROUP BY 1,2;
    """)
    emit("a1c_schedule_cadence", q(con, """
      WITH day_stats AS (
        SELECT count(*) AS table_runs,
               count(DISTINCT series || '|' || cast(exec_time AS DATE)) AS series_days
        FROM eligible
      ), gaps AS (
        SELECT date_diff('second',
                 lag(exec_time) OVER (PARTITION BY series ORDER BY exec_time),
                 exec_time)/3600.0 AS gap_h
        FROM seq
      ), periodic AS (
        SELECT count(*) AS recs, sum(dur_s) AS sec, sum(N) AS max_side_rows
        FROM seq
      )
      SELECT table_runs, series_days, table_runs-series_days AS extra_same_day_runs,
             round(100.0*(table_runs-series_days)/table_runs,2) AS pct_extra_same_day_runs,
             round((SELECT quantile_cont(gap_h,0.5) FROM gaps WHERE gap_h IS NOT NULL),2)
               AS periodic_gap_p50_h,
             round((SELECT quantile_cont(gap_h,0.95) FROM gaps WHERE gap_h IS NOT NULL),2)
               AS periodic_gap_p95_h,
             round(100.0*(SELECT recs FROM periodic)
               /(SELECT count(*) FROM eligible),2) AS periodic_pct_recs,
             round(100.0*(SELECT sec FROM periodic)
               /(SELECT sum(dur_s) FROM eligible),2) AS periodic_pct_table_time,
             round(100.0*(SELECT max_side_rows FROM periodic)
               /(SELECT sum(N) FROM eligible),2) AS periodic_pct_max_side_rows
      FROM day_stats
      """))
    # Is d stable within a segment: how far does "rediscovering the same set of differences" actually go?
    # Consecutive d>0 does not mean the difference set is unchanged -- old differences may go unfixed while new ones accumulate.
    con.execute("""
    CREATE TABLE segs AS
      WITH g AS (SELECT series, rn, d_ms, dur_s,
                        rn - row_number() OVER (PARTITION BY series,(d_ms>0) ORDER BY rn) AS grp
                 FROM seq)
      SELECT series, grp, count(*) AS len, sum(dur_s) AS sec,
             min(rn) AS r0, max(rn) AS r1,
             min(d_ms) AS dmin, max(d_ms) AS dmax, count(DISTINCT d_ms) AS nvals
      FROM g WHERE d_ms>0 GROUP BY 1,2;
    """)
    emit("a6b_within_segment_stability", q(con, """
      SELECT 'segment length>=2' AS subset, count(*) AS segs,
             round(100.0*count(*) FILTER (WHERE nvals=1)/count(*),1) AS pct_d_constant,
             round(100.0*count(*) FILTER (WHERE dmax<=2*dmin)/count(*),1) AS pct_dmax_le_2dmin,
             round(median(dmax*1.0/dmin),2) AS med_dmax_over_dmin,
             round(sum(sec)/3600.0,1) AS table_hours FROM segs WHERE len>=2
      UNION ALL
      SELECT 'segment length>=30', count(*),
             round(100.0*count(*) FILTER (WHERE nvals=1)/count(*),1),
             round(100.0*count(*) FILTER (WHERE dmax<=2*dmin)/count(*),1),
             round(median(dmax*1.0/dmin),2), round(sum(sec)/3600.0,1) FROM segs WHERE len>=30
      UNION ALL
      SELECT 'segment length>=2 and d exactly constant', count(*), 100.0, 100.0, 1.0,
             round(sum(sec)/3600.0,1) FROM segs WHERE len>=2 AND nvals=1"""))

    emit("a6c_recurrence", q(con, """
      SELECT count(*) AS series_with_diff, median(nseg)::INT AS med_segments,
             max(nseg) AS max_segments,
             round(100.0*count(*) FILTER (WHERE nseg>=2)/count(*),1) AS pct_multi_segment
      FROM (SELECT series, count(*) AS nseg FROM segs GROUP BY 1)"""))

    # A6b segment-length histogram: p50/p90 hide the shape; only after binning do the two peaks and the gap show.
    # span_ratio = segment length / that series' total run count, to tell whether a long segment spans the window.
    emit("a6_segment_length_histogram", q(con, """
      WITH b AS (
        SELECT CASE WHEN len=1 THEN '1_single'
                    WHEN len<=4  THEN '2_2-4'
                    WHEN len<=14 THEN '3_5-14'
                    WHEN len<=33 THEN '4_15-33'
                    WHEN len<=59 THEN '5_34-59'
                    WHEN len<=87 THEN '6_60-87'
                    ELSE '7_88+' END AS bin,
               len, s.series
        FROM segs s JOIN (SELECT series, max(rn) AS runs FROM seq GROUP BY 1) r
             USING (series)
      )
      SELECT bin, count(*) AS segs,
             round(100.0*count(*)/sum(count(*)) OVER (),1) AS pct,
             min(len) AS min_len, max(len) AS max_len
      FROM b GROUP BY 1 ORDER BY 1"""))

    # Are the endpoints inside the window: only a segment with both ends observed has a real duration.
    emit("a6e_segment_censoring", q(con, """
      SELECT CASE WHEN len=1 THEN '1_single-run segment'
                  WHEN len<=14 THEN '2_mid segment (2-14)'
                  ELSE '3_long segment (>=34)' END AS kind,
             count(*) AS segs,
             count(*) FILTER (WHERE r0>1)              AS start_observed,
             count(*) FILTER (WHERE r1<rmax)           AS end_observed,
             count(*) FILTER (WHERE r0>1 AND r1<rmax)  AS fully_observed
      FROM segs JOIN (SELECT series, max(rn) AS rmax FROM seq GROUP BY 1) USING (series)
      GROUP BY 1 ORDER BY 1"""))

    emit("a6d_long_segment_span", q(con, """
      SELECT count(*) AS segs_ge34,
             round(median(1.0*len/runs),3) AS med_span_ratio,
             round(quantile_cont(1.0*len/runs,0.25),3) AS p25_span_ratio,
             count(*) FILTER (WHERE len=runs) AS spans_whole_series
      FROM segs JOIN (SELECT series, max(rn) AS runs FROM seq GROUP BY 1) USING (series)
      WHERE len>=34"""))

    emit("a6_positive_segment_length", q(con, """
      WITH bounds AS (SELECT series, max(rn) AS rmax FROM seq GROUP BY 1)
      SELECT count(*) AS diff_segments,
             quantile_cont(len,0.5)::BIGINT AS p50_len,
             quantile_cont(len,0.9)::BIGINT AS p90_len, max(len) AS max_len,
             round(100.0*count(*) FILTER (WHERE len>=30)/count(*),1) AS pct_len_ge30,
             count(*) FILTER (WHERE r0=1) AS left_censored,
             count(*) FILTER (WHERE r1=rmax) AS right_censored,
             count(*) FILTER (WHERE r0=1 OR r1=rmax) AS either_censored,
             count(*) FILTER (WHERE len>=30 AND (r0=1 OR r1=rmax)) AS len_ge30_censored,
             round(sum(sec)/3600.0,1) AS table_hours_on_positive_segments
      FROM segs JOIN bounds USING(series)"""))

    # ---------- A7 ratio of two adjacent d values ----------
    con.execute("""
    CREATE TABLE pairs AS
      SELECT series, exec_time, N, dur_s, d_ms AS cur,
             lag(d_ms) OVER (PARTITION BY series ORDER BY exec_time) AS prev
      FROM seq;
    CREATE VIEW ratio AS
      SELECT cur::DOUBLE/prev AS rho FROM pairs WHERE prev>0 AND cur>0;
    """)
    emit("a7_adjacent_ratio", q(con, """
      SELECT count(*) AS pairs,
             quantile_cont(rho,0.05) AS p05, quantile_cont(rho,0.25) AS p25,
             quantile_cont(rho,0.50) AS p50, quantile_cont(rho,0.75) AS p75,
             quantile_cont(rho,0.95) AS p95, max(rho) AS mx FROM ratio"""))

    # Transition matrix: pct_all_pairs is the share of all adjacent pairs; pct_given_prev is conditional on the previous state.
    emit("a7_adjacent_transitions", q(con, """
      SELECT transition, pairs,
             round(100.0*pairs/sum(pairs) OVER (),2) AS pct_all_pairs,
             round(100.0*pairs/sum(pairs) OVER (PARTITION BY left(transition,1)),2)
               AS pct_given_prev
      FROM (
        SELECT '0->0' AS transition, count(*) FILTER (WHERE prev=0 AND cur=0) AS pairs
          FROM pairs WHERE prev IS NOT NULL
        UNION ALL SELECT '0->+', count(*) FILTER (WHERE prev=0 AND cur>0)
          FROM pairs WHERE prev IS NOT NULL
        UNION ALL SELECT '+->0', count(*) FILTER (WHERE prev>0 AND cur=0)
          FROM pairs WHERE prev IS NOT NULL
        UNION ALL SELECT '+->+', count(*) FILTER (WHERE prev>0 AND cur>0)
          FROM pairs WHERE prev IS NOT NULL
      ) ORDER BY transition"""))

    # State persistence: share of adjacent pairs that land in the same state.
    emit("a7b_state_persistence", q(con, """
      SELECT count(*) AS pairs,
             round(100.0*count(*) FILTER (WHERE (prev=0)=(cur=0))/count(*),2)
               AS pct_state_unchanged,
             round(100.0*count(*) FILTER (WHERE (prev=0)!=(cur=0))/count(*),2)
               AS pct_state_flipped,
             round(100.0*count(*) FILTER (WHERE prev=0 AND cur=0)
                   /nullif(count(*) FILTER (WHERE prev=0),0),2) AS pct_stay_zero,
             round(100.0*count(*) FILTER (WHERE prev>0 AND cur>0)
                   /nullif(count(*) FILTER (WHERE prev>0),0),2) AS pct_stay_positive
      FROM pairs WHERE prev IS NOT NULL"""))

    # The prior-capacity check uses the oracle workload substitution only: it uses the real d_prev, without the estimation error of d_hat.
    # If capacity is alpha*d_prev and decoding needs beta*d_cur, the correct ratio threshold is alpha/beta.
    emit("a7_prior_hit_rate", q(con, f"""
      SELECT 'positive->positive' AS population, 'rho<=1.11 (descriptive bandwidth)' AS criterion,
             count(*) AS pairs,
             round(100.0*count(*) FILTER (WHERE rho<=1.11)/count(*),2) AS pct
        FROM ratio
      UNION ALL SELECT 'positive->positive', 'rho<=alpha/beta={PRIOR_ORACLE_RHO:.3f} (oracle capacity condition)',
             count(*), round(100.0*count(*) FILTER (
               WHERE rho<={PRIOR_ORACLE_RHO})/count(*),2) FROM ratio
      UNION ALL SELECT 'positive->positive', 'rho<=alpha=1.52 (describes change bandwidth only)',
             count(*), round(100.0*count(*) FILTER (WHERE rho<={ALPHA})/count(*),2) FROM ratio
      UNION ALL SELECT 'this run d>0', 'max(512,alpha*d_prev)>=beta*d_cur (oracle)',
             count(*), round(100.0*count(*) FILTER (
               WHERE greatest({M1_CELLS}::DOUBLE,{ALPHA}*prev)>={BETA}*cur)/count(*),2)
        FROM pairs WHERE prev IS NOT NULL AND cur>0
      UNION ALL SELECT 'all adjacent pairs', 'this run d=0 or oracle capacity sufficient',
             count(*), round(100.0*count(*) FILTER (
               WHERE cur=0 OR greatest({M1_CELLS}::DOUBLE,{ALPHA}*prev)>={BETA}*cur)
               /count(*),2) FROM pairs WHERE prev IS NOT NULL
      UNION ALL SELECT 'positive->positive', 'rho>4 (jump)',
             count(*), round(100.0*count(*) FILTER (WHERE rho>4)/count(*),2) FROM ratio
      """))

    # Jump instances: blind-doubling rounds needed = ceil(log2(beta*d_cur / M1_prior))
    emit("a7_jump_instances", q(con, f"""
      SELECT anon_series, prev_d_ms, cur_d_ms, round(rho,1) AS ratio, doubling_rounds
      FROM (
        SELECT series AS anon_series, prev AS prev_d_ms, cur AS cur_d_ms,
               cur::DOUBLE/prev AS rho,
               ceil(log2(({BETA}*cur)/({ALPHA}*prev)))::INT AS doubling_rounds
        FROM pairs WHERE prev>0 AND cur>0)
      WHERE rho > 4 ORDER BY rho DESC LIMIT 15"""), show=False)

    # ---------- Round ledger: blind-doubling workload counterfactual under a fixed M1 ----------
    # First-round capacity M1 is fixed (the deployment configuration). Blind doubling starts at M1 and doubles until >= beta*d;
    # self-sizing is 1 round when the first round succeeds, and at most 2 rounds when capacity sizing succeeds after a failure.
    con.execute(f"""
    CREATE VIEW rounds AS
      SELECT d_ms, N, dur_s, bucket,
             CASE WHEN {BETA}*d_ms <= {M1_CELLS} THEN 1
                  ELSE 1 + ceil(log2({BETA}*d_ms/{M1_CELLS}))::INT END AS blind_rounds
      FROM eligible WHERE d_row > 0;
    """)
    emit("a7b_blind_rounds_model", q(con, f"""
      SELECT bucket, count(*) AS recs,
             median(blind_rounds)::INT AS med_blind_rounds,
             quantile_cont(blind_rounds,0.9)::INT AS p90_blind_rounds,
             max(blind_rounds) AS max_blind_rounds,
             round(100.0*count(*) FILTER (WHERE blind_rounds=1)/count(*),1) AS pct_round1_ok,
             round(100.0*count(*) FILTER (WHERE blind_rounds>=9)/count(*),1) AS pct_ge9_rounds
      FROM rounds GROUP BY 1
      UNION ALL
      SELECT '_total', count(*), median(blind_rounds)::INT,
             quantile_cont(blind_rounds,0.9)::INT, max(blind_rounds),
             round(100.0*count(*) FILTER (WHERE blind_rounds=1)/count(*),1),
             round(100.0*count(*) FILTER (WHERE blind_rounds>=9)/count(*),1)
      FROM rounds ORDER BY 1"""))

    # Prior-miss instances: the previous run had d=0 (expected a 1 RTT fast path) but this run has a large d.
    pf = q(con, f"""
      SELECT series, exec_time, N, cur AS d_ms, dur_s, series_runs, zero_runs,
             next_d_ms,
             1 + ceil(log2({BETA}*cur/{M1_CELLS}))::INT AS blind_rounds
      FROM (
        SELECT series, exec_time, N, dur_s, d_ms AS cur,
               lag(d_ms) OVER (PARTITION BY series ORDER BY exec_time) AS prev,
               lead(d_ms) OVER (PARTITION BY series ORDER BY exec_time) AS next_d_ms,
               count(*) OVER (PARTITION BY series) AS series_runs,
               count(*) FILTER (WHERE d_ms=0) OVER (PARTITION BY series) AS zero_runs
        FROM seq)
      WHERE prev = 0 AND cur > 100000 ORDER BY cur DESC LIMIT 10""")
    pf["series"] = pf["series"].map(anon)
    emit("a7c_prior_miss_from_zero", pf)

    # ---------- 90-day ledger + byte ledger ----------
    emit("90d_ledger", q(con, f"""
      SELECT round(sum(N)/1e8,1) AS max_side_rows_1e8,
             round(sum(rows_both_sides)/1e8,1) AS both_side_rows_1e8,
             round(sum(dur_s)/3600.0,0) AS table_hours,
             sum(d_row)::BIGINT AS diffs_row, sum(d_ms)::BIGINT AS diffs_ms,
             round(100.0*sum(dur_s) FILTER (WHERE d_row=0)/sum(dur_s),1)
               AS pct_table_time_on_d0,
             round(sum(N) FILTER (WHERE d_row=0)/1e8,1) AS max_side_rows_on_d0_1e8,
             round(sum(rows_both_sides) FILTER (WHERE d_row=0)/1e8,1)
               AS both_side_rows_on_d0_1e8,
             round(sum(N)*1.0/nullif(sum(d_ms),0),0) AS max_side_rows_per_diff,
             round(sum(rows_both_sides)*1.0/nullif(sum(d_ms),0),0)
               AS both_side_rows_per_diff
      FROM eligible"""))

    # Byte ledger per execution: the 5 executions with the largest total max-side table size.
    # round2_all_dpos is the upper bound of "retry every positive d"; round2_threshold retries
    # only tables with beta*d>M1, using the same deterministic capacity proxy as the blind-doubling
    # ledger in this file. Both substitute the real d, not the d_hat bytes actually observed in production.
    bl = q(con, f"""
      SELECT any_value(task_id) AS task, exec_id,
             count(*) AS tables, count(*) FILTER (WHERE d_row>0) AS tables_with_diff,
             count(*) FILTER (WHERE {BETA}*d_ms>{M1_CELLS}) AS modeled_round1_failures,
             round(sum(N)/1e8,2) AS max_side_rows_1e8,
             round(sum(rows_both_sides)/1e8,2) AS both_side_rows_1e8,
             round(sum(dur_s)/3600.0,2) AS table_hours,
             sum(d_row)::BIGINT AS d_row, sum(d_ms)::BIGINT AS d_ms,
             round(count(*)*{M1_CELLS}*{BYTES_PER_CELL}/1048576.0,2) AS round1_MB,
             round(sum(CASE WHEN d_row>0 THEN ceil({ALPHA}*d_ms)*{BYTES_PER_CELL} ELSE 0 END)
                   /1048576.0,2) AS round2_all_dpos_MB,
             round(sum(CASE WHEN {BETA}*d_ms>{M1_CELLS}
                       THEN ceil({ALPHA}*d_ms)*{BYTES_PER_CELL} ELSE 0 END)
                   /1048576.0,2) AS round2_threshold_MB
      FROM eligible GROUP BY exec_id ORDER BY sum(N) DESC LIMIT 5""")
    bl["task"] = bl["task"].map(anon)
    bl["total_all_dpos_MB"] = (bl["round1_MB"] + bl["round2_all_dpos_MB"]).round(2)
    bl["total_threshold_MB"] = (bl["round1_MB"] + bl["round2_threshold_MB"]).round(2)
    emit("byte_ledger_top_execs", bl)

    # ---------- anonymous mapping (kept local, not in the repository) ----------
    m = q(con, "SELECT DISTINCT series, task_id, tbl FROM r")
    m["anon"] = m["series"].map(anon)
    m.to_csv(out / "_name_map.local.csv", index=False)

    # rewrite the A7 instance table with anonymous IDs
    ji = q(con, f"""
      SELECT series, prev AS prev_d_ms, cur AS cur_d_ms, round(cur::DOUBLE/prev,1) AS ratio,
             ceil(log2(({BETA}*cur)/({ALPHA}*prev)))::INT AS doubling_rounds
      FROM pairs WHERE prev>0 AND cur>0 AND cur::DOUBLE/prev>4 ORDER BY 4 DESC LIMIT 15""")
    ji["series"] = ji["series"].map(anon)
    ji.sort_values(["ratio", "series"], ascending=[False, False], inplace=True)
    emit("a7_jump_instances", ji)

    print(f"aggregate CSVs written to {out}/ (table-name mapping only in {out}/_name_map.local.csv, not in the repository)")


if __name__ == "__main__":
    main()

# Production log analysis

## Production operation of self-sizing (Section 5.4)

`summarize_production_operation.py` produced
`data/paper/production-operation-summary.csv` from the self-sizing run logs
(`run_record.jsonl`, one JSON object per run). The logs contain table names
and run identifiers and are **not distributed**; the script docstring lists
the fields it reads, and the output contains only aggregate values.

```bash
python3 summarize_production_operation.py <LOG_ROOT> --m1 512 \
  --out ../../data/paper/production-operation-summary.csv
```

## Production comparison-log profile (Sections 2 and 5)

`analyze_a1_a7.py` is the script that produced the reviewed aggregate CSVs
shipped in `data/paper/` (`paper_production_summary.csv`,
`production-d-quantiles.csv`, `production-duration-quantiles.csv`,
`production-rank-buckets-anonymized.csv`, `production-size-buckets.csv`).

It reads three read-only CSV exports of the production comparison logs. Column
names are a **neutral schema** (defined in the script docstring), not the
original product schema; point the script at CSVs whose headers match it, or
add a renaming view over your own export.

| glob | one row per | key columns |
|---|---|---|
| `compare_tasks_*.csv` | comparison task | `id` |
| `compare_execs_*.csv` | task execution | `id, task_id, account_id, create_time, exec_type, status, sample_percent` |
| `compare_progress_*.csv` | table result within an execution | `exec_id, source_db, source_table, pk_type, status, start_time, end_time, source_rows, target_rows, changed_rows, source_only_rows, target_only_rows` |

These raw logs contain customer, account, task, and schema/table identifiers and
are **not distributed** (see `../../DATA_AVAILABILITY.md`). The script emits only
aggregate CSVs with no such identifiers; the table-name mapping is written to
`out/_name_map.local.csv`, which `.gitignore` excludes.

```bash
pip install duckdb pandas
python3 analyze_a1_a7.py --raw-dir <dir with the three CSV globs> --out out
```

Definitions match the paper (Section 3): `d_row = changed + source-only +
target-only`, `d_ms = 2*changed + source-only + target-only`,
`N = max(source_rows, target_rows)`. The deployment constants (`M1=512`,
`alpha=1.52`, `beta=1.3`, 32 B/cell) are from Section 5.4.

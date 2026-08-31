# MySQL SQL-pushdown comparison

This directory provides the SQL shape and parameterized experiment program for
the supplementary SQL-pushdown comparison. Fresh wall-clock time depends on the
database version, optimizer, permissions, cache state, and storage placement;
the frozen CSV in `data/paper/paper_sql_pushdown.csv` records the paper
measurements.

## What the program executes

`mysql_pushdown_replay.py` constructs the complete canonical fingerprint SQL for
the public P1-equivalent `KEY_01`/`VALUE_01`--`VALUE_43` schema and supports two
modes:

- `single-query` uses a multiply referenced CTE and
  `SET SESSION optimizer_switch='derived_merge=off'`. MySQL materializes the CTE,
  so each row's MD5 is evaluated once. This path uses one `SELECT` per range and
  does **not** require `CREATE TEMPORARY TABLES`.
- `temp-table` explicitly runs `CREATE TEMPORARY TABLE t_pos`, followed by the
  bucket aggregation. This earlier implementation requires the corresponding
  database privilege.

Both modes split the primary-key range across independent connections, construct
the same IBLT cells, and emit
`bucket_id,count,fp_xor,id_xor,checksum_xor`. Use `--print-sql` to print the exact
query for one range.

The public program has no endpoint or credential defaults. For example:

```bash
python3 -m pip install -r code/sql-pushdown-mysql/requirements.txt
export MYSQL_PASSWORD='<local-password>'
python3 code/sql-pushdown-mysql/mysql_pushdown_replay.py \
  --host 127.0.0.1 --user artifact_user \
  --database artifact_replay --table profile_p1 \
  --mode single-query --partitions 16 --buckets 262144 \
  --out /tmp/mysql-sql-pushdown-sketch.csv --print-sql
```

The sidecar comparison implementation is in `code/e29-sidecar/`; a
replay should use the same table, fingerprint contract, bucket count, worker
count, and cache state, then compare the two emitted sketches cell by cell.

## How to interpret the two reported MySQL gaps

The measurements represent different implementation and deployment points:

- The earlier 10M-row direct SQL expanded every row into three buckets while the
  optimizer repeatedly evaluated the per-row hash. It was about 11x slower than
  the sidecar. This remains representative of the unoptimized query path.
- The later 609M-row P1 run forced one-time CTE materialization and used 16 range
  partitions. It measured 886 s versus 626 s for the sidecar, or 1.42x. It was a
  single run on a tuned self-managed MySQL 8 instance.
- An intermediate explicit-temp-table implementation measured 1120 s and needs
  `CREATE TEMPORARY TABLES`; many managed or customer deployments do not grant
  that permission. The 886 s single-query path avoids that particular privilege,
  but still assumes session optimizer controls. Its measured environment also
  used server-level buffer/temp-storage tuning that cannot be assumed at a
  customer site.

The 10M and 609M measurements are not a same-workload before/after comparison.
They establish that MySQL SQL-pushdown cost is highly optimizer- and
deployment-dependent. This comparison supports the implementation choice to keep
one cross-engine encoding implementation in the sidecar; it is not a core
estimator claim and does not require identical seconds on another machine.

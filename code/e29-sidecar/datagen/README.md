# Synthetic relational data generator

`generate_ddl.py` creates neutral Oracle and MySQL DDL for the synthetic replay
workloads. It preserves the structural properties needed by the experiments:
key arity, canonical value types, and column counts.

`gen_dataset.py` fills those tables. One code path generates the P1-P3 profiles
(scale, key layout, dirty-block bands, and difference composition); the heavy
work is a set-based `INSERT ... SELECT` run inside the database, not row-by-row
inserts. It writes a source script, a target script (with modified /
source-only / target-only rows injected along the frozen paper key-rank bands),
and a ground-truth script with a collation sort-consistency guard.

Public table and column identifiers use `PROFILE_P*`, `KEY_nn`, and `VALUE_nn`.
Production schemas, business identifiers, endpoints, credentials, and task
mappings are not included.

```bash
python3 code/e29-sidecar/datagen/generate_ddl.py                 # write the checked-in DDL
python3 code/e29-sidecar/datagen/gen_dataset.py p2 --out smoke --scale 0.001   # small smoke dataset
python3 -m unittest datagen.test_gen_dataset                     # run from code/e29-sidecar/
```

The resulting SQL can be applied to a user-provided synthetic Oracle or MySQL
database. Database-specific drivers and binaries must be installed separately.

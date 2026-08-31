# Relational replay utilities

This directory contains synthetic relational replay materials for the P1--P3
and P1-extra experiments.

`e29` / `E29` is the internal codename for this production relational replay
(Oracle&#8596;MySQL). It appears as a prefix on the configuration variables
(`E29_ORACLE_*`, `E29_MYSQL_*`) and on the machine-readable `E29_RESULT` /
`E29_CLEANUP` lines that the compare entry points print for the timing scripts.

## Contents

- `rateless-vs-self-sizing/` — a self-contained Go comparison harness. Its
  README documents a local synthetic smoke test; large formal fingerprint
  snapshots are represented by checksums in the data manifest.
- `relational-sidecar/` — the Oracle/MySQL composite-primary-key IBLT and
  optimized-Merkle sidecar (Java 17). It reuses the IBLT core, SQL dialects,
  and controller from `../../cm-redis`; `build.sh` compiles both and runs the
  database-free self-tests.
- `datagen/` — neutral Oracle/MySQL DDL (`generate_ddl.py`) and the
  parameterized data generator (`gen_dataset.py`). Public tables and columns
  use `PROFILE_P*`, `KEY_nn`, and `VALUE_nn` identifiers.
- `scripts/` — replay runners, the TCP link emulator, and result summarizers.
- `config/runtime.env.example` — a placeholder-only configuration template.

Quick checks:

```bash
(cd rateless-vs-self-sizing && go test ./...)
python3 datagen/generate_ddl.py
bash relational-sidecar/build.sh
```

Database replay requires user-provided JDBC drivers, database binaries,
endpoints, and credentials. Keep filled-in configuration files outside Git and
use synthetic data for public reproduction.

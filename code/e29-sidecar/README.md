# Relational replay utilities

This directory contains synthetic relational replay materials for the P1--P3
and P1-extra experiments.

## Contents

- `rateless-vs-self-sizing/` — a self-contained Go comparison harness. Its
  README documents a local synthetic smoke test; large formal fingerprint
  snapshots are represented by checksums in the data manifest.
- `datagen/ddl/` and `datagen/generate_ddl.py` — neutral Oracle/MySQL DDL and
  a generator for synthetic schemas. Public tables and columns use
  `PROFILE_P*`, `KEY_nn`, and `VALUE_nn` identifiers.
- `scripts/` — replay runners, the TCP link emulator, and result summarizers.
- `config/runtime.env.example` — a placeholder-only configuration template.

Quick checks:

```bash
(cd rateless-vs-self-sizing && go test ./...)
python3 datagen/generate_ddl.py
```

Database replay requires user-provided JDBC drivers, database binaries,
endpoints, and credentials. Keep filled-in configuration files outside Git and
use synthetic data for public reproduction.

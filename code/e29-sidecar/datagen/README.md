# Synthetic relational data generator

`generate_ddl.py` creates neutral Oracle and MySQL DDL for the synthetic replay
workloads. It preserves the structural properties needed by the experiments:
key arity, canonical value types, and column counts.

Public table and column identifiers use `PROFILE_P*`, `KEY_nn`, and `VALUE_nn`.
Production schemas, business identifiers, endpoints, credentials, and task
mappings are not included.

Generate the checked-in DDL with:

```bash
python3 code/e29-sidecar/datagen/generate_ddl.py
```

The resulting SQL can be applied to a user-provided synthetic Oracle or MySQL
database. Database-specific drivers and binaries must be installed separately.

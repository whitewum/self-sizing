# Relational IBLT / optimized-Merkle sidecar

The Oracle/MySQL composite-primary-key variant of the sidecar. It reuses the
IBLT core, SQL dialects, and controller from the KV sidecar (`../../cm-redis`)
and replaces the single-column SQL / recheck protocol with
`fp + pk_0 + ... + pk_n`, encoding the whole primary-key tuple on the wire via
`CompositePkCodec`.

All classes are in package `io.github.selfsizing.iblt.relational`. Java 17, no
third-party runtime dependencies.

## Build

```bash
bash build.sh          # compiles ../../cm-redis, then this module, then runs the DB-free self-tests
```

or with Maven, after installing the KV module:

```bash
mvn -f ../../cm-redis install
mvn package
```

## What is here

- `CompositeIbltSidecarServer` / `CompositeMerkleSidecarServer` — the runnable
  sidecar servers; `CompositeIbltCompareMain` / `CompositeMerkleCompareMain` —
  the controller entry points.
- `CompositeIbltSql` / `CompositeMerkleSql` — the cross-database SQL adapter
  (fingerprint scan, shard predicates, NTILE checksum, key-range fences).
- `CompositePkCodec`, `ValueCanonType`, `OracleRowidChunker` — composite-key
  and value canonicalization helpers.
- `*SelfTest` — database-free gates for the SQL shapes and the sharding
  invariants; run by `build.sh`.

Running against a real database needs user-provided JDBC drivers, endpoints,
and credentials. Use the synthetic data from `../datagen/`; do not point it at
production data.

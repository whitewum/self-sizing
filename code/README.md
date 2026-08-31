# Source code

This directory contains the implementations and scripts used by the paper's
simulations and replay experiments.

## Components

- `simulations/plain-t1/` — plain-IBLT count-measurement simulation and
  plotting scripts;
- `simulations/irregular-iblt/` — irregular-IBLT and failure-conditioned
  simulations;
- `simulations/strata-baseline/` — Strata-style baseline and regret
  simulation;
- `simulations/e13-cpp/` — the E13 provenance generator;
- `e29-sidecar/` — synthetic relational replay utilities, the parameterized
  data generator, the rateless comparison harness, and the Oracle/MySQL
  composite-key IBLT/Merkle sidecar (`relational-sidecar/`, reuses `cm-redis`);
- `cm-redis/` — the standalone Redis/Pika IBLT and optimized-Merkle KV sidecar
  (Java 17, no third-party runtime dependencies);
- `sql-pushdown-mysql/` — the parameterized MySQL SQL-pushdown replay; and
- `production-analysis/` — the aggregation script behind the released
  production summary and quantile CSVs (raw comparison logs not distributed).

Figure and table inputs are under `../data/paper/`. The commands for running
the components and regenerating paper outputs are in `../REPRODUCE.md`.

## Configuration

Database replay requires user-provided endpoints, credentials, JDBC drivers,
and database binaries. Use the example configuration files as templates and
keep filled-in configurations outside Git. Do not use live production data
with the synthetic replay commands.

## License

MIT; see the repository-root `LICENSE`.

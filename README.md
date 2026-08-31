# Self-sizing Artifact

This repository contains the code, data summaries, and replay materials used by
the paper *IBLTs Measure Before They Decode: Self-Sizing Set Reconciliation for
Database Consistency Verification*. Every paper figure and table is mapped to
its generating script and input in `REPRODUCE.md`.

## Artifact availability

The actively maintained source repository is available at
<https://github.com/whitewum/self-sizing>. This release is archived at Zenodo
with DOI [10.5281/zenodo.22186193](https://doi.org/10.5281/zenodo.22186193).

## Repository contents

- `code/simulations/` — plain-IBLT, irregular-IBLT, and Strata-style baseline
  simulations, together with their plotting scripts;
- `code/e29-sidecar/` — synthetic relational replay utilities and the
  rateless-versus-self-sizing comparison harness;
- `code/sql-pushdown-mysql/` — a parameterized MySQL SQL-pushdown replay;
- `figures/` — figure-generation scripts for the released summary data; and
- `data/paper/` — the small CSV inputs used directly by the paper.

The repository does not contain live credentials, database binaries or vendor
drivers, raw production logs, live database/Redis snapshots, or original
production schemas.

## Requirements

- Python 3.10+ with `numpy`, `pandas`, and `matplotlib`;
- `scipy` for `make_figs_p2p3p6.py`;
- Go 1.24+ for the rateless harness and Go simulations; and
- a C++17 compiler plus OpenSSL for the E13 provenance generator.

The MySQL replay additionally requires PyMySQL:

```bash
python3 -m pip install -r code/sql-pushdown-mysql/requirements.txt
```

## Verification

```bash
python3 scripts/verify_manifest.py --scope git
python3 scripts/scan_public_export.py
python3 scripts/verify_paper_numbers.py
(cd code/e29-sidecar/rateless-vs-self-sizing && go test ./...)
```

See `REPRODUCE.md` for figure and table commands. Large inputs are referenced
by `data/manifest.tsv` and require a permanent public URL before they can be
downloaded.

## Configuration and network safety

Database replay uses endpoints, credentials, and drivers supplied by the
user. Copy the example configuration locally and keep any filled-in file out
of Git. The example networked endpoints are intended for local use; bind demo
servers to loopback or protect them with an appropriate network boundary.

## Data availability

See `DATA_AVAILABILITY.md` for the treatment of production-derived data and
the public substitutes provided by this repository.

## License

MIT; see `LICENSE`.

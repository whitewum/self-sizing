# Self-sizing Artifact

This repository contains the code, data summaries, and replay materials used by
the paper *IBLTs Measure Before They Decode: Self-Sizing Set Reconciliation for
Database Consistency Verification*. Every paper figure and table is mapped to
its generating script and input in `REPRODUCE.md`.

## Artifact availability

The actively maintained source repository is available at
<https://github.com/whitewum/self-sizing>. Every tagged release is archived at
Zenodo under the concept DOI
[10.5281/zenodo.22198044](https://doi.org/10.5281/zenodo.22198044), which always
resolves to the latest version.

The large raw simulation inputs are not part of the Zenodo archive; they are
attached to the [`v0.1` GitHub release](https://github.com/whitewum/self-sizing/releases/tag/v0.1),
with byte counts and SHA-256 in `data/manifest.tsv`.

## Repository contents

- `code/simulations/` — plain-IBLT, irregular-IBLT, Strata-style baseline, and
  `c_F` sign simulations, together with their plotting scripts;
- `code/e29-sidecar/` — synthetic relational replay utilities and the
  rateless-versus-self-sizing comparison harness;
- `code/sql-pushdown-mysql/` — a parameterized MySQL SQL-pushdown replay;
- `figures/` — figure-generation scripts for the released summary data; and
- `data/paper/` — the small CSV inputs used directly by the paper.

The capacity-tier calibration uses two checked-in 1M-trial summaries: the
original M={64,256,1024,4096} grid and a separate M=512 supplemental grid. The
corresponding 40M-row M=512 raw is attached to the `v0.1` release for audit.
The checked-in guidance covers all five tiers and uses the paper's finite-size
decoder multiplier, beta=1.56. The protocol calibration additionally includes
an M1=64 retry sweep; its raw grid can be regenerated with the public C++
generator, while the frozen aggregate used by the paper is checked in.

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
python3 scripts/verify_mapper_interop.py
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

## Acknowledgments

We thank Byungwoong Yoo (Independent Researcher) for identifying the
calibration mismatch between the theoretical fixed-degree estimator and the
mapper implementation.

## License

MIT; see `LICENSE`.

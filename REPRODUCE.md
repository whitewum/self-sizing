# Reproducing the paper figures and tables

Every paper figure and table maps to one script in this repository and one
input file. The tables below give that mapping, the command, and whether the
input ships in `data/paper/` or must be downloaded from the public archive
listed in `data/manifest.tsv`.

Paper labels (`fig:...`, `tab:...`) are the LaTeX labels in the manuscript.

## Artifact availability

Source code and reproduction materials are maintained at
<https://github.com/whitewum/self-sizing>. The released version corresponding
to this artifact is archived at Zenodo:
<https://doi.org/10.5281/zenodo.22186193>.

## Dependencies

- Python 3.10+, with `numpy`, `pandas`, `matplotlib`.
- `scipy` in addition, for `make_figs_p2p3p6.py` only.
- Go 1.24+ to re-run the simulations from scratch (not needed to redraw figures
  from the shipped summaries).
- A C++17 compiler and OpenSSL for the E13 provenance generator.

The plotting scripts write PNG and SVG next to themselves (`figs/`,
`results/figs/`, or `figures/out/`); those directories are git-ignored.

## Figures reproducible now (inputs in `data/paper/`)

| Paper label | Script | Input | Command |
|---|---|---|---|
| `fig:rsd-grid` | `code/simulations/plain-t1/make_t1_grid_figs.py` | `plain-t1-summary-1m.csv` | `python3 code/simulations/plain-t1/make_t1_grid_figs.py` |
| `fig:regret` | `code/simulations/strata-baseline/make_regret_fig.py` | `regret-pipeline-summary.csv` | `python3 code/simulations/strata-baseline/make_regret_fig.py` |
| `fig:prod-teaser` | `figures/make_s6_figures.py` (`fig62_production_profile`) | `production-d-quantiles.csv`, `production-duration-quantiles.csv` | `python3 figures/make_s6_figures.py` |
| `fig:rank` | `figures/make_rank_figure.py` | `production-rank-buckets-anonymized.csv` | `python3 figures/make_rank_figure.py data/paper/production-rank-buckets-anonymized.csv --output figures/out/fig_rank.svg` |
| `fig:relational` | `figures/make_p1_extra_figure.py` | `e29-p1-extra-paper-results.csv` | `python3 figures/make_p1_extra_figure.py data/paper/e29-p1-extra-paper-results.csv --output figures/out/fig_p1_extra.svg` |

`figures/make_s6_figures.py` also emits the P2 worker ablation
(`fig63a_p2_ablation` from `paper_p2_worker_ablation.csv`), the per-profile E2E
comparison (`fig63b_profile_comparison` from `paper_profiles_e2e.csv`), the KV
time/payload ranges (`fig64_mobile_ranges` from `paper_kv_scaling.csv`), and the
two standalone E27 panels, in the same run. It also draws `fig:rank`
(`fig62c_dirty_block_rank`); `figures/make_rank_figure.py` is the standalone
compact version.

`code/simulations/plain-t1/make_e13_fig.py` draws the supplementary E13
retry-success curve from `plain-e13-retry-success.csv`.

## Figures that need an archive download

The 160M-trial raw (`plain-t1-raw-160m`), the 4M fixed-d raw
(`plain-t1-fixed-d-4m`), and the 260k E8 failure-bias subset
(`plain-t1-e8-failure-bias`) are hosted externally; see `data/manifest.tsv` for
the archive location, byte count, and SHA-256. Each script takes the downloaded
file as an argument.

| Paper label | Script | Downloaded input | Command |
|---|---|---|---|
| `fig:estimator-validation`, `fig:estimator-validation-hist` | `code/simulations/plain-t1/make_figs_p2p3p6.py` | `plain-t1-raw-160m`, `plain-t1-fixed-d-4m` | `python3 make_figs_p2p3p6.py --raw <raw160m.csv> --fixed-d <fixedd4m.csv> --summary data/paper/plain-t1-summary-1m.csv` (needs `scipy`; run from a directory with `figs/` and `results/`) |
| `fig:qq-detrended` | `code/simulations/plain-t1/make_t1_qq_detrended.py` | `plain-t1-raw-160m` | `python3 make_t1_qq_detrended.py --raw <raw160m.csv>` |
| `fig:bias-fail`, `fig:estimator-validation-fail` | `code/simulations/plain-t1/make_fail_cond_bias_fig.py` | `plain-t1-e8-failure-bias` | `python3 make_fail_cond_bias_fig.py --e8-raw <e8-bias.csv>` (irregular half uses the shipped `irregular-failure-summary.csv`) |

## Tables

`tab:design-space` and `tab:phases` are definitional and ship with the LaTeX
source. The rest are checked against their frozen CSVs by one script:

```bash
python3 scripts/verify_paper_numbers.py
```

| Paper label | Input CSV in `data/paper/` |
|---|---|
| `tab:t1a`, `tab:t1b` | `plain-t1-summary-1m.csv` |
| `tab:capacity-tiers` | `plain-t1-q01-guidance.csv` |
| `tab:protocol-calibration` | `plain-e13-retry-success.csv` |
| `tab:profiles-e2e` | `paper_profiles_e2e.csv`, `paper_profiles_protocol_evidence.csv` |
| `tab:phase-breakdown` | `paper_p1_phase_breakdown.csv` |
| `tab:g1g6` | `paper_g1g6.csv` |

`tab:capacity-tiers` can also be regenerated from raw by
`make_figs_p2p3p6.py` (part P9); the shipped `plain-t1-q01-guidance.csv` is the
frozen result of that step.

## Re-running a simulation from scratch

Each `code/simulations/<name>/` directory has a Go `main` (or, for `e13-cpp`, a
`build.sh`). These regenerate the raw trials that the summaries above are built
from; they are not needed to redraw the figures. Seeds and parameters are in
each directory's source and, for E13, in `code/simulations/e13-cpp/README.md`.

# Reproducing the paper figures and tables

Every paper figure and table maps to one script in this repository and its
declared input file(s). The tables below give that mapping, the command, and
whether each input ships in `data/paper/` or must be downloaded from the public
archive listed in `data/manifest.tsv`.

Paper labels (`fig:...`, `tab:...`) are the LaTeX labels in the manuscript.

## Artifact availability

Source code and reproduction materials are maintained at
<https://github.com/whitewum/self-sizing> and archived at Zenodo under the
concept DOI <https://doi.org/10.5281/zenodo.22198044> (resolves to the latest
version). The large raw simulation inputs are attached to the `v0.1` GitHub
release, not the Zenodo archive; see `data/manifest.tsv`.

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
| `fig:rsd-grid` | `code/simulations/plain-t1/make_t1_grid_figs.py` | `plain-t1-summary-1m.csv`, `plain-t1-m512-summary-1m.csv` | `python3 code/simulations/plain-t1/make_t1_grid_figs.py --input data/paper/plain-t1-summary-1m.csv --m512-input data/paper/plain-t1-m512-summary-1m.csv` |
| `fig:regret` | `code/simulations/strata-baseline/make_regret_fig.py` | `regret-pipeline-summary.csv` | `python3 code/simulations/strata-baseline/make_regret_fig.py` |
| `fig:prod-teaser` | `figures/make_s6_figures.py` (`fig62_production_profile`) | `production-d-quantiles.csv`, `production-duration-quantiles.csv` | `python3 figures/make_s6_figures.py` |
| `fig:rank` | `figures/make_rank_figure.py` | `production-rank-buckets-anonymized.csv` | `python3 figures/make_rank_figure.py data/paper/production-rank-buckets-anonymized.csv --output figures/out/fig_rank.svg` |
| `fig:relational` | `figures/make_p1_extra_figure.py` | `e29-p1-extra-paper-results.csv` | `python3 figures/make_p1_extra_figure.py data/paper/e29-p1-extra-paper-results.csv --output figures/out/fig_p1_extra.svg` |
| `fig:sign-projection` | `code/simulations/plain-t1/make_sign_projection_fig.py` | `sign-imbalance-shape-points.csv` | `python3 code/simulations/plain-t1/make_sign_projection_fig.py` (writes `figs/sign_projection_combined.{png,svg}` next to the script) |

`figures/make_s6_figures.py` also emits the P2 worker ablation
(`fig63a_p2_ablation` from `paper_p2_worker_ablation.csv`), the per-profile E2E
comparison (`fig63b_profile_comparison` from `paper_profiles_e2e.csv`), the KV
time/payload ranges (`fig64_mobile_ranges` from `paper_kv_scaling.csv`), and the
two standalone E27 panels, in the same run. It also draws `fig:rank`
(`fig62c_dirty_block_rank`); `figures/make_rank_figure.py` is the standalone
compact version.

`code/simulations/plain-t1/make_e13_fig.py` draws the supplementary five-tier
E13 retry-success curve from `plain-e13-retry-success.csv`.

## Figures that need an archive download

The 160M-trial raw (`plain-t1-raw-160m`), the 40M M=512 supplemental raw
(`plain-t1-m512-raw-40m`), the 4M fixed-d raw (`plain-t1-fixed-d-4m`), and the
260k E8 failure-bias subset
(`plain-t1-e8-failure-bias`) are hosted externally; see `data/manifest.tsv` for
the archive location, byte count, and SHA-256. Each script takes the downloaded
file(s) as arguments.

The M=512 raw is retained for audit provenance. The capacity-tier table is
regenerated from its checked-in 1M-trial summary, so this 218 MB download is
not required for the normal table-reproduction path.

| Paper label | Script | Downloaded input | Command |
|---|---|---|---|
| `fig:estimator-validation`, `fig:estimator-validation-hist` | `code/simulations/plain-t1/make_figs_p2p3p6.py` | `plain-t1-raw-160m`, `plain-t1-m512-raw-40m`, `plain-t1-fixed-d-4m`, both 1M summaries | `python3 make_figs_p2p3p6.py --raw <raw160m.csv> --m512-raw <m512raw.csv> --fixed-d <fixedd4m.csv> --summary data/paper/plain-t1-summary-1m.csv --m512-summary data/paper/plain-t1-m512-summary-1m.csv` (needs `scipy`; run from a directory with `figs/` and `results/`) |
| `fig:qq-detrended` | `code/simulations/plain-t1/make_t1_qq_detrended.py` | `plain-t1-raw-160m`, `plain-t1-m512-raw-40m` | `python3 make_t1_qq_detrended.py --raw <raw160m.csv> --m512-raw <m512raw.csv>` |
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
| `tab:capacity-tiers` | `plain-t1-q01-guidance.csv` (generated from `plain-t1-summary-1m.csv` and `plain-t1-m512-summary-1m.csv`) |
| `tab:protocol-calibration` | `plain-e13-retry-success.csv` |
| `tab:profiles-e2e` | `paper_profiles_e2e.csv`, `paper_profiles_protocol_evidence.csv` |
| `tab:phase-breakdown` | `paper_p1_phase_breakdown.csv` |
| `tab:g1g6` | `paper_g1g6.csv` |
| `tab:prod-operation` | `production-operation-summary.csv` (aggregate of access-restricted logs; see below) |

`tab:capacity-tiers` is regenerated from the two shipped 1M-trial summaries.
The five-tier T1 grid has 200 cells: the original 160-cell summary covers
M={64,256,1024,4096}, and the supplemental summary adds the 40 M=512 cells.
The separate M=512 summary is retained because the original grid did not
include M=512:

```bash
python3 code/simulations/plain-t1/make_q01_guidance.py \
  --output results/generated/plain-t1-q01-guidance.csv
```

The script rejects any selected configuration whose trial count is not exactly
1,000,000. The corresponding M=512 raw CSV is archived separately for audit;
it is not necessary to download the raw data to regenerate the table.

The two summary inputs and generated five-tier selection can be audited with:

```bash
python3 code/simulations/plain-t1/audit_t1_1m_summaries.py
```

For E13, `plain-e13-retry-success-4tier.csv` preserves the original
M1={256,512,1024,4096} lock. The M1=64 raw grid is regenerated and checked
before extending that lock:

```bash
cd code/simulations/e13-cpp
./build/tier1_failed_only_sweep \
  --trials 10000 --threads 32 --M-list 64 --k-list 3 \
  --d-over-m-list 0.3,0.5,0.7,0.8,0.9,1.0,1.2,1.5,2.0,3.0,5.0,10.0 \
  --neg-ratios 0.1,0.5,0.9 --gamma-list 1.6,1.8,2.0 \
  --out /tmp/plain-e13-m64-raw.csv
python3 audit_retry_grid.py /tmp/plain-e13-m64-raw.csv
cd ../../..
python3 code/simulations/plain-t1/lock_e13.py \
  --m64-raw /tmp/plain-e13-m64-raw.csv \
  --output results/generated/plain-e13-retry-success.csv
```

The output contains both `all_m_ge_256`, which is the aggregate printed in the
paper, and `all_five_tiers`, which is retained for full-grid diagnostics.

`production-operation-summary.csv` holds every number reported for the
production operation (Section 5.4): run counts per path, row and difference
ranges, the `dhat/d` ranges and moments, and the fallback breakdown. It was
produced by `code/production-analysis/summarize_production_operation.py`
from run logs that are not distributed (see `DATA_AVAILABILITY.md`); the
script documents the log fields it reads.

## Sign of `c_F`

The statement that every measured `c_F` is positive for k=3 (Section 3.3)
comes from `code/simulations/cf-sign/`: an exact enumeration
(`cf_sign_exact.py`) and a C Monte Carlo (`cf_mc.c`), with their outputs and
SHA-256 sums checked in. See `code/simulations/cf-sign/README.md` for the
commands; a full rerun takes about 20 minutes on an 8-core laptop.

## Re-running a simulation from scratch

Each `code/simulations/<name>/` directory has a Go `main` (or, for `e13-cpp`, a
`build.sh`). These regenerate the raw trials that the summaries above are built
from; they are not needed to redraw the figures. Seeds and parameters are in
each directory's source and, for E13, in `code/simulations/e13-cpp/README.md`.

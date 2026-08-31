# Artifact data directory

- `manifest.tsv`: the manifest for shipped and externally hosted data.
- `paper/`: small, de-identified derived CSVs used directly by the paper.
- `samples/`: small synthetic inputs for local checks, when present.
- `cache/`, `staging/`: local download and upload staging; not tracked in Git.

Large objects are not copied into this directory. When a public URL is listed
in the manifest, download the file to a local cache and verify its byte count
and SHA-256 before using it in the reproduction pipeline.

## Current public derived data

| File | Contents | Key boundary |
|---|---|---|
| `production-rank-buckets-anonymized.csv` | 100 key-rank buckets for P1--P3 / T4--T5 | real task, schema, and table names removed; bucket counts sum to `dirty_blocks` |
| `paper_production_summary.csv` | 90-day production aggregates used by the paper | no per-task or per-table records |
| `production-size-buckets.csv` | run counts and time shares across five table-size tiers | the 5.8% / 77.4% for N >= 10^7 can be recomputed directly |
| `paper_profiles_e2e.csv` | formal end-to-end means for three relational profiles | each cell is the mean of two repeats |
| `paper_profiles_protocol_evidence.csv` | d and d-hat ranges and correctness counts for three profiles | each profile covers 12 formal IBLT runs |
| `paper_p1_phase_breakdown.csv` | IBLT / Merkle phase times for P1 | work-sum and wall time are separate columns and must not be added together |
| `paper_g1g6.csv` | table data for the six KV scenarios | G3 / G6 use `runtime_observed`; no fabricated fixed ground truth |
| `paper_rateless_comparison.csv` | per-run results for self-sizing / rateless | all three repeats of the unstable P2 RTT arm are retained |
| `e29-p1-extra-paper-results.csv` | formal completed cases and reruns for P1 and P1-extra | EOF / incomplete cases keep their status; performance values left blank |
| `paper_p2_worker_ablation.csv` | median and range for the P2 worker sweep | the range is min--max, not a confidence interval |
| `paper_kv_scaling.csv` | Redis / Pika time and payload plotting input | dynamic burst points and static injection points are distinguished by field |
| `plain-t1-fixed-d-raw.csv` | early 10k-trial-per-cell quick sample for fixed-d | quick checks only; the formal distribution figure uses the 4M-row `plain-t1-fixed-d-4m` in the manifest, never this file |

The public treatment of production-derived data is described in
`../DATA_AVAILABILITY.md`.

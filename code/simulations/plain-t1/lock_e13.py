#!/usr/bin/env python3
"""E13 aggregation lock: two-round retry success rate vs gamma.

Sources:
  - plain-f2-iblt-retry/results/tier2_phase_transition_fast.csv
  - results/tier2_phase_transition_m512_h100.csv.gz
Locked recipe (recorded here so the paper number is reproducible):
  - rows: ALL rows of both files (no M / neg_ratio subset)
  - balanced M grid: 256 / 512 / 1024 / 4096, each with the same 36
    (d/M, neg_ratio) points and 10,000 trials per point
  - success := (ok1 == 1) or (ok2 == 1)   [ok2 empty when round 1 succeeded]
  - failed-only success := ok2 among rows with ok1 == 0
  - group by gamma; per-(gamma, M) breakdown emitted for both overall and
    failed-only scopes
Output: results/e13_retry_success_locked.csv
"""
import csv
import gzip
import os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES = [
    os.path.join(HERE, "..", "..", "..", "plain-f2-iblt-retry",
                 "results", "tier2_phase_transition_fast.csv"),
    os.path.join(HERE, "results", "tier2_phase_transition_m512_h100.csv.gz"),
]
OUT = os.path.join(HERE, "results", "e13_retry_success_locked.csv")

tot = defaultdict(int)
succ = defaultdict(int)
tot_m = defaultdict(int)
succ_m = defaultdict(int)
fail_tot = defaultdict(int)
fail_succ = defaultdict(int)
fail_tot_m = defaultdict(int)
fail_succ_m = defaultdict(int)

for source in SOURCES:
    opener = gzip.open if source.endswith(".gz") else open
    with opener(source, "rt", newline="") as f:
        for row in csv.DictReader(f):
            g = float(row["gamma"])
            m = int(row["M"])
            ok = row["ok1"] == "1" or row["ok2"] == "1"
            tot[g] += 1
            tot_m[(g, m)] += 1
            if ok:
                succ[g] += 1
                succ_m[(g, m)] += 1
            if row["ok1"] != "1":
                fail_tot[g] += 1
                fail_tot_m[(g, m)] += 1
                if row["ok2"] == "1":
                    fail_succ[g] += 1
                    fail_succ_m[(g, m)] += 1

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", newline="") as f:
    w = csv.writer(f, lineterminator="\n")
    w.writerow(["scope", "gamma", "M", "trials", "success", "success_rate"])
    for g in sorted(tot):
        w.writerow(["all", f"{g:g}", "", tot[g], succ[g], f"{succ[g]/tot[g]:.6f}"])
    for (g, m) in sorted(tot_m):
        w.writerow(["by_M", f"{g:g}", m, tot_m[(g, m)], succ_m[(g, m)],
                    f"{succ_m[(g, m)]/tot_m[(g, m)]:.6f}"])
    for g in sorted(fail_tot):
        w.writerow(["failed_only_all", f"{g:g}", "", fail_tot[g], fail_succ[g],
                    f"{fail_succ[g]/fail_tot[g]:.6f}"])
    for (g, m) in sorted(fail_tot_m):
        w.writerow(["failed_only_by_M", f"{g:g}", m, fail_tot_m[(g, m)],
                    fail_succ_m[(g, m)],
                    f"{fail_succ_m[(g, m)]/fail_tot_m[(g, m)]:.6f}"])

print(f"wrote {OUT}")
for g in sorted(tot):
    print(f"gamma={g:g}: {succ[g]}/{tot[g]} = {succ[g]/tot[g]:.4%}")
for g in sorted(fail_tot):
    print(f"gamma={g:g} | F: {fail_succ[g]}/{fail_tot[g]} = "
          f"{fail_succ[g]/fail_tot[g]:.4%}")

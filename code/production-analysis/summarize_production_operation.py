#!/usr/bin/env python3
"""Aggregate the self-sizing production-operation logs for Section 5.4.

This script produced `data/paper/production-operation-summary.csv`, the
public substitute for the raw logs, which are not distributed (see
`../../DATA_AVAILABILITY.md`).

Input: LOG_ROOT holds one directory per capture batch, each containing a
`run_record.jsonl` with one JSON object per reconciliation run. Fields used:

    run_id        unique run identifier (captures overlap; deduplicated on it)
    src_table,    table identifiers; used only to group runs and never
    dst_table     written to the output
    m1, m2        first- and second-round sketch capacities (cells); m1 = -1
                  when a run failed before building any sketch
    rounds        1 or 2 for completed runs
    outcome       "OK" or "FALLBACK:<reason>"
    residual      undecoded keys after the final round (0 = complete)
    d_final       decoded difference size d
    d_plus,       source-only and target-only parts of d
    d_minus
    d_hat_r1      count-array estimate read from the first-round sketch
    n_rows_src,   row counts on each side
    n_rows_dst

Usage:
    python3 summarize_production_operation.py LOG_ROOT [--m1 512] [--out CSV]
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import os
import statistics as st
import sys

Q001 = 0.8598  # failed-only q_{0.01} at M1=512 (tab:capacity-tiers)


def load(root: str, m1: int) -> list[dict]:
    runs = {}
    for path in sorted(glob.glob(os.path.join(root, "*", "run_record.jsonl"))):
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.strip():
                    record = json.loads(line)
                    runs[record["run_id"]] = record
    # m1 < 0 marks runs that failed before any sketch was built; they belong to
    # whichever configuration was deployed at the time.
    return [r for r in runs.values() if r["m1"] == m1 or r["m1"] < 0]


def rows_max(r: dict) -> int:
    return max(r["n_rows_src"], r["n_rows_dst"])


def table(r: dict) -> str:
    return r["src_table"].lower()


def distinct(runs: list[dict]) -> list[dict]:
    # Hash seeds are fixed, so an unchanged difference set on the same table
    # reproduces the same estimate; count each such configuration once.
    return list({(table(r), r["d_final"], round(r["d_hat_r1"], 3)): r for r in runs}.values())


def summarize(runs: list[dict]) -> list[tuple]:
    ok = [r for r in runs if r["outcome"] == "OK"]
    fast = [r for r in ok if r["rounds"] == 1]
    second = [r for r in ok if r["rounds"] == 2]
    fallback = [r for r in runs if r["outcome"] != "OK"]
    fb_sql = sum("SQLException" in r["outcome"] for r in fallback)
    fb_cost = sum("dHatTooLarge" in r["outcome"] for r in fallback)
    ratio2 = [r["d_hat_r1"] / r["d_final"] for r in second]
    ratio_fast = [r["d_hat_r1"] / r["d_final"] for r in distinct(fast) if r["d_final"] >= 3]
    one_sided = [r for r in ok if r["d_final"] >= 3 and 0 in (r["d_plus"], r["d_minus"])]
    ratio_one = [r["d_hat_r1"] / r["d_final"] for r in distinct(one_sided)]
    zero = [r for r in ok if r["d_final"] == 0]
    unit = [r for r in ok if r["d_final"] == 1]

    scope_all = f"M1={runs[0]['m1']} production runs"
    out = [
        ("runs_total", len(runs), "runs", scope_all, "deduplicated by run id"),
        ("table_tasks", len({(table(r), r["dst_table"].lower()) for r in runs}), "tasks", scope_all, ""),
        ("runs_completed", len(ok), "runs", scope_all, "all decoded with zero residual"),
        ("runs_completed_residual_zero", sum(r["residual"] == 0 for r in ok), "runs", scope_all, ""),
        ("runs_fast_path", len(fast), "runs", "1-RTT fast path", ""),
        ("fast_path_share", round(len(fast) / len(ok), 4), "fraction", "completed runs", ""),
        ("fast_path_rows_max", max(map(rows_max, fast)), "rows", "1-RTT fast path", "max over both sides"),
        ("fast_path_d_min", min(r["d_final"] for r in fast), "keys", "1-RTT fast path", ""),
        ("fast_path_d_max", max(r["d_final"] for r in fast), "keys", "1-RTT fast path", "largest d decoded in round 1"),
        ("runs_second_round", len(second), "runs", "second round", ""),
        ("second_round_rows_max", max(map(rows_max, second)), "rows", "second round", "max over both sides"),
        ("second_round_d_min", min(r["d_final"] for r in second), "keys", "second round", "smallest d needing round 2"),
        ("second_round_d_max", max(r["d_final"] for r in second), "keys", "second round", ""),
        ("second_round_dhat_ratio_min", round(min(ratio2), 3), "ratio", "second round", f"dhat/d; q_0.01={Q001}"),
        ("second_round_dhat_ratio_max", round(max(ratio2), 3), "ratio", "second round", "dhat/d"),
        ("second_round_m2_over_d_min", round(min(r["m2"] / r["d_final"] for r in second), 3), "ratio", "second round", "M2/d"),
        ("runs_fallback", len(fallback), "runs", scope_all, ""),
        ("fallback_sql_error", fb_sql, "runs", "fallback", "SQL error during scan before any sketch"),
        ("fallback_cost_exit", fb_cost, "runs", "fallback", "second-round sketch would exceed direct key transfer"),
        ("zero_difference_runs", len(zero), "runs", "completed runs", ""),
        ("zero_difference_share", round(len(zero) / len(ok), 4), "fraction", "completed runs", ""),
        ("zero_difference_dhat_exact", int(all(r["d_hat_r1"] == 0 for r in zero)), "bool", "d=0 runs", "1 = every dhat equals 0"),
        ("unit_difference_runs", len(unit), "runs", "completed runs", ""),
        ("unit_difference_dhat_exact", int(all(abs(r["d_hat_r1"] - 1) < 1e-9 for r in unit)), "bool", "d=1 runs", "1 = every dhat equals 1"),
        ("fast_path_distinct_sets_d_ge3", len(ratio_fast), "sets", "1-RTT fast path, d>=3", "distinct (table, d, dhat)"),
        ("fast_path_dhat_ratio_mean", round(st.mean(ratio_fast), 4), "ratio", "1-RTT fast path, d>=3", "dhat/d over distinct sets"),
        ("fast_path_dhat_ratio_sd", round(st.stdev(ratio_fast), 4), "ratio", "1-RTT fast path, d>=3", "sample standard deviation"),
        ("one_sided_runs", len(one_sided), "runs", "fully one-sided, d>=3", ""),
        ("one_sided_tables", len({table(r) for r in one_sided}), "tables", "fully one-sided, d>=3", ""),
        ("one_sided_distinct_sets", len(ratio_one), "sets", "fully one-sided, d>=3", "distinct (table, d, dhat)"),
        ("one_sided_dhat_ratio_mean", round(st.mean(ratio_one), 4), "ratio", "fully one-sided, d>=3", ""),
        ("one_sided_dhat_ratio_sd", round(st.stdev(ratio_one), 4), "ratio", "fully one-sided, d>=3", "sample standard deviation"),
    ]
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("log_root")
    parser.add_argument("--m1", type=int, default=512)
    parser.add_argument("--out", help="CSV path (default: stdout)")
    args = parser.parse_args()

    runs = load(args.log_root, args.m1)
    if not runs:
        sys.exit(f"no runs with m1={args.m1} under {args.log_root}")
    handle = open(args.out, "w", newline="", encoding="utf-8") if args.out else sys.stdout
    writer = csv.writer(handle, lineterminator="\n")
    writer.writerow(["metric", "value", "unit", "scope", "note"])
    writer.writerows(summarize(runs))
    if args.out:
        handle.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

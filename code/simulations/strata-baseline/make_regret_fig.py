#!/usr/bin/env python3
"""Render the unified regret-vs-oracle figure for Section 5.7 (protocol cost
under unknown difference cardinality).

Reads the failure-conditioned pipeline summary CSV (no re-simulation). For each pipeline
and each d, byte regret = mean_bytes / mean_bytes(oracle at same d, same
neg_ratio). Uses the balanced sign split (neg_ratio=0.5); d=0 is excluded
from the log-x regret panel (the d=0 economics are reported via the pi_0
expected-bytes table in the text).

Outputs (horizontal, two panels side by side):
  results/figs/fig_regret_vs_oracle.{png,svg}
Outputs (vertical, two panels stacked, shared x-axis, for single-column use):
  results/figs/fig_regret_vs_oracle_vert.{png,svg}
"""

import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
_DATA = HERE.parents[2] / "data" / "paper"

_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--input", type=Path, default=_DATA / "regret-pipeline-summary.csv",
                 help="failure-conditioned pipeline summary CSV")
_ap.add_argument("--outdir", type=Path, default=HERE / "results" / "figs")
_args, _ = _ap.parse_known_args()

with _args.input.open(newline="") as fh:
    DF = list(csv.DictReader(fh))
OUT = _args.outdir
OUT.mkdir(parents=True, exist_ok=True)

NEG = 0.5
df = [r for r in DF if float(r["neg_ratio"]) == NEG and int(r["d"]) > 0]
oracle = {int(r["d"]): float(r["mean_bytes"])
          for r in DF if r["pipeline"] == "oracle" and float(r["neg_ratio"]) == NEG}

STYLE = {
    "self_sizing_joint": ("self-sizing + joint", "#1f77b4", "o", "-"),
    "self_sizing": ("self-sizing (independent R2)", "#17becf", "s", "-"),
    "blind_doubling": ("blind doubling", "#d62728", "^", "--"),
    "strata_first": ("strata-first", "#7f7f7f", "D", "-."),
}

def plot_series(ax1, ax2):
    for key, (label, color, marker, ls) in STYLE.items():
        sub = sorted((r for r in df if r["pipeline"] == key),
                     key=lambda r: int(r["d"]))
        dvals = [int(r["d"]) for r in sub]
        regret = [float(row["mean_bytes"]) / oracle[d]
                  for row, d in zip(sub, dvals)]
        ax1.plot(dvals, regret, marker=marker, ls=ls, color=color, label=label, ms=4.5, lw=1.4)
        ax2.plot(dvals, [float(r["mean_rounds"]) for r in sub], marker=marker,
                 ls=ls, color=color, label=label, ms=4.5, lw=1.4)

def finish_horiz(ax1, ax2):
    ax1.axhline(1.0, color="k", lw=0.8, alpha=0.6)
    ax1.text(11, 1.03, "oracle", fontsize=8, alpha=0.7)
    ax1.set_xscale("log")
    ax1.set_yscale("log")
    ax1.set_yticks([1, 1.5, 2, 3, 5, 10, 20])
    ax1.set_yticklabels(["1", "1.5", "2", "3", "5", "10", "20"])
    ax1.set_xlabel(r"true difference $d$")
    ax1.set_ylabel("byte regret vs oracle (mean bytes ratio)")
    ax1.set_title("(a) byte regret", fontsize=10)
    ax1.grid(alpha=0.3, which="both")

    ax2.set_xscale("log")
    ax2.set_xlabel(r"true difference $d$")
    ax2.set_ylabel("mean rounds")
    ax2.set_title("(b) rounds", fontsize=10)
    ax2.grid(alpha=0.3, which="both")
    ax2.legend(fontsize=8, frameon=False)

def finish_vert(ax1, ax2):
    ax1.axhline(1.0, color="k", lw=0.8, alpha=0.6)
    ax1.text(11, 1.03, "oracle", fontsize=8, alpha=0.7)
    ax1.set_xscale("log")
    ax1.set_yscale("log")
    ax1.set_yticks([1, 1.5, 2, 3, 5, 10, 20])
    ax1.set_yticklabels(["1", "1.5", "2", "3", "5", "10", "20"])
    ax1.set_xlabel("")
    ax1.set_ylabel("byte regret vs oracle (ratio)")
    ax1.yaxis.set_label_coords(-0.105, 0.42)
    ax1.tick_params(labelbottom=False)
    ax1.grid(alpha=0.3, which="both")
    ax1.legend(fontsize=7.5, frameon=False, loc="upper right")
    ax1.text(0.02, 0.90, "(a)", transform=ax1.transAxes, fontsize=9, va="top")

    ax2.set_xscale("log")
    ax2.set_yscale("log")
    ax2.set_yticks([1, 2, 4, 8, 12])
    ax2.set_yticklabels(["1", "2", "4", "8", "12"])
    ax2.set_xlabel(r"true difference $d$")
    ax2.set_ylabel("mean rounds")
    ax2.yaxis.set_label_coords(-0.105, 0.42)
    ax2.grid(alpha=0.3, which="both")
    ax2.text(0.02, 0.90, "(b)", transform=ax2.transAxes, fontsize=9, va="top")

# Horizontal layout: two panels side by side (arXiv / wide use).
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(9.2, 3.6))
plot_series(ax1, ax2)
finish_horiz(ax1, ax2)
fig.tight_layout()
for ext in ("png", "svg"):
    fig.savefig(OUT / f"fig_regret_vs_oracle.{ext}", dpi=200)
print("wrote", OUT / "fig_regret_vs_oracle.png")

# Vertical layout: two panels stacked, shared x-axis (single-column use).
figv, (ax1v, ax2v) = plt.subplots(2, 1, figsize=(5.2, 4.35), sharex=True)
plot_series(ax1v, ax2v)
finish_vert(ax1v, ax2v)
figv.tight_layout(h_pad=0.9)
for ext in ("png", "svg"):
    figv.savefig(OUT / f"fig_regret_vs_oracle_vert.{ext}", dpi=200)
print("wrote", OUT / "fig_regret_vs_oracle_vert.png")

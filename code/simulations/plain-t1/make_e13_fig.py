#!/usr/bin/env python3
"""Render the E13 round-2 success figure for the success-rate calibration in
Section 5.6 (Self-Sizing Protocol).

Reads the locked E13 retry-success CSV (locked by lock_e13.py, no
re-simulation). Left panel: overall retry success (ok1 or ok2); right panel:
strict failed-only P[ok2 | F]. Both plotted as failure probability on a log
axis so the 99.3%--99.99% range stays readable.

Outputs:
  figs/e13_retry_success.{png,svg}
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import argparse
import csv

HERE = Path(__file__).resolve().parent
_DATA = HERE.parents[2] / "data" / "paper"

_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--input", type=Path, default=_DATA / "plain-e13-retry-success.csv",
                 help="locked E13 retry-success CSV")
_ap.add_argument("--outdir", type=Path, default=HERE / "figs")
_args, _ = _ap.parse_known_args()

with _args.input.open(newline="") as fh:
    rows = list(csv.DictReader(fh))
OUT = _args.outdir
OUT.mkdir(parents=True, exist_ok=True)

M_COLORS = {256: "#1f77b4", 512: "#2ca02c", 1024: "#ff7f0e", 4096: "#9467bd"}

fig, axes = plt.subplots(1, 2, figsize=(9.2, 3.6), sharey=True)

for ax, scope, title in (
    (axes[0], "by_M", "(a) overall retry success"),
    (axes[1], "failed_only_by_M", r"(b) strict failed-only $\Pr[\mathrm{ok}_2 \mid F]$"),
):
    sub = [r for r in rows if r["scope"] == scope]
    for m, color in M_COLORS.items():
        s = sorted((r for r in sub if int(r["M"]) == m),
                   key=lambda r: float(r["gamma"]))
        if not s:
            continue
        ax.plot(
            [float(r["gamma"]) for r in s],
            [1.0 - float(r["success_rate"]) for r in s],
            marker="o",
            color=color,
            lw=1.4,
            ms=4.5,
            label=rf"$M_0={m}$",
        )
    ax.axhline(0.01, color="k", lw=0.8, ls="--", alpha=0.6)
    ax.text(1.955, 0.011, r"$1\%$", fontsize=8, alpha=0.7)
    ax.axhline(0.001, color="k", lw=0.8, ls=":", alpha=0.6)
    ax.text(1.95, 0.0011, r"$0.1\%$", fontsize=8, alpha=0.7)
    ax.set_yscale("log")
    ax.set_xticks([1.6, 1.8, 2.0])
    ax.set_xlabel(r"capacity multiplier $\alpha$")
    ax.set_title(title, fontsize=10)
    ax.grid(alpha=0.3, which="both")

axes[0].set_ylabel("failure probability (1 - success)")
axes[0].legend(fontsize=8, frameon=False)
fig.tight_layout()
for ext in ("png", "svg"):
    fig.savefig(OUT / f"e13_retry_success.{ext}", dpi=200)
print("wrote", OUT / "e13_retry_success.png")

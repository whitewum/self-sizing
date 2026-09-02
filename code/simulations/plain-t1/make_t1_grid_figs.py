#!/usr/bin/env python3
"""Render the 1M-trial split full-grid figures for Theorem 3.1 and Corollary 3.3.

Reads the authoritative 160-cell summary plus the separate 40-cell M=512
summary (both at 1M trials per cell) and performs no
re-simulation.  The earlier 10k-trial render is archived under
``archive/10k-grid/figs``.

Outputs:
  figs/t1_mean_unbiasedness.{png,svg}
  figs/t1_rsd_ratio.{png,svg}
"""

import argparse
from pathlib import Path

import matplotlib
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.lines import Line2D
import numpy as np
import pandas as pd

matplotlib.use("Agg")

MS = [64, 256, 512, 1024, 4096]
INK = "#334155"
GRAY = "#94a3b8"
BLUE = "#2563eb"
ORANGE = "#d97706"
LIGHT_BLUE = "#dbeafe"

ROOT = Path(__file__).resolve().parent
_DATA = Path(__file__).resolve().parents[3] / "data" / "paper"

_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--input", type=Path, default=_DATA / "plain-t1-summary-1m.csv",
                 help="160-cell 1M-trial T1 summary CSV")
_ap.add_argument("--m512-input", type=Path,
                 default=_DATA / "plain-t1-m512-summary-1m.csv",
                 help="40-cell M=512 supplemental 1M-trial summary CSV")
_ap.add_argument("--outdir", type=Path, default=ROOT / "figs")
_args, _ = _ap.parse_known_args()
SUMMARY = _args.input
FIGS = _args.outdir

plt.rcParams.update(
    {
        "font.size": 9,
        "axes.edgecolor": GRAY,
        "axes.labelcolor": INK,
        "xtick.color": INK,
        "ytick.color": INK,
        "axes.linewidth": 0.8,
        "savefig.bbox": "tight",
    }
)


def finish(ax: plt.Axes) -> None:
    ax.set_xticks(range(len(MS)), [f"$M={m}$" for m in MS])
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", color=GRAY, alpha=0.25, lw=0.5)


def save_both(fig: plt.Figure, stem: str) -> None:
    FIGS.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIGS / f"{stem}.png", dpi=240)
    fig.savefig(FIGS / f"{stem}.svg")
    plt.close(fig)


summary = pd.concat(
    [pd.read_csv(SUMMARY), pd.read_csv(_args.m512_input)],
    ignore_index=True,
)
grid = summary[summary["kind"] == "grid"].copy()
assert len(grid) == 200, len(grid)

xpos = {m: i for i, m in enumerate(MS)}
rng = np.random.default_rng(0)
grid["x"] = grid["M"].map(xpos) + rng.uniform(-0.10, 0.10, len(grid))

# Figure F3a: Theorem 3.1. The estimator is exactly unbiased; the nonzero
# vertical scatter is finite-Monte-Carlo error. A continuous +/-3-SE envelope
# makes its approximately (M-1)^(-1/2) contraction visible. The envelope is
# interpolated over the categorical five-tier x-axis.
fig, ax = plt.subplots(figsize=(5.8, 3.4), constrained_layout=True)
n_trials = int(grid["trials"].iloc[0])
x_envelope = np.linspace(-0.15, len(MS) - 0.85, 500)
band_tiers = 3.0 * np.sqrt(2.0 / ((np.asarray(MS) - 1.0) * n_trials))
band_envelope = np.interp(x_envelope, np.arange(len(MS)), band_tiers)
ax.fill_between(
    x_envelope,
    -band_envelope,
    band_envelope,
    color=LIGHT_BLUE,
    alpha=0.72,
    linewidth=0,
    zorder=0,
)
ax.plot(x_envelope, band_envelope, color=BLUE, alpha=0.40, lw=1.0, zorder=1)
ax.plot(x_envelope, -band_envelope, color=BLUE, alpha=0.40, lw=1.0, zorder=1)

for k, marker in [(3, "o"), (4, "^")]:
    sub = grid[grid["k"] == k]
    ax.scatter(
        sub["x"],
        sub["mean_ratio"] - 1,
        s=22,
        marker=marker,
        color=BLUE,
        alpha=0.68,
        edgecolors="none",
        label=f"$k={k}$",
        zorder=2,
    )

ax.axhline(0, color=INK, lw=0.9, ls="--", zorder=1)
ax.set_ylabel(r"$\operatorname{mean}_{\rm MC}(\widehat d/d)-1$")
ax.set_title("Unbiasedness and MC concentration", color=INK)
finish(ax)
handles = [
    Line2D([], [], marker="o", linestyle="none", color=BLUE, markersize=5, label="$k=3$"),
    Line2D([], [], marker="^", linestyle="none", color=BLUE, markersize=5, label="$k=4$"),
    Patch(
        facecolor=LIGHT_BLUE,
        edgecolor=BLUE,
        linewidth=0.8,
        label=r"$\pm3$ MC SE envelope, $\propto(M-1)^{-1/2}$",
    ),
]
ax.legend(handles=handles, frameon=False, loc="upper right", fontsize=8)
save_both(fig, "t1_mean_unbiasedness")

# Figure F3b: Corollary 3.3. The y-axis is a genuine quotient: MC RSD divided by
# the exact theoretical RSD. Agreement is represented by the horizontal line 1.
# Short-wide variant used by the paper: low canvas, no in-figure title,
# distinct colors, and a compact y-axis around the agreement line.
fig, ax = plt.subplots(figsize=(5.8, 1.95), constrained_layout=True)
for k, (marker, color) in {3: ("o", BLUE), 4: ("^", ORANGE)}.items():
    sub = grid[grid["k"] == k]
    ax.scatter(
        sub["x"],
        sub["rsd_emp_over_theory"],
        s=16,
        marker=marker,
        color=color,
        alpha=0.75,
        edgecolors="none",
        label=f"$k={k}$",
        zorder=2,
    )

ax.axhline(1, color=INK, lw=0.9, ls="--", zorder=1)
ax.set_ylim(0.9976, 1.0024)
ax.set_yticks([0.998, 0.999, 1.0, 1.001, 1.002])
ax.set_yticklabels(["0.998", "0.999", "1", "1.001", "1.002"])
ax.set_ylabel("measured/theory RSD", fontsize=8)
finish(ax)
ax.legend(frameon=False, loc="upper right", fontsize=7.5, ncol=1)
save_both(fig, "t1_rsd_ratio")

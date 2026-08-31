#!/usr/bin/env python3
"""Detrended Q-Q (Tukey mean-difference / Bland-Altman) for the failed-only
vs unconditional quantile comparison (P2b, Figure t1_qq_failed).

For each M, at each quantile level p we plot
    Delta(p) = q_failed(p) - q_unconditional(p)
against p.  The 45-degree line of the ordinary Q-Q plot becomes the shared
horizontal y=0 line, so all four M panels can be overlaid in one plot and
small deviations are visible on a tight +/- scale.

A null band shows +/- 1.96 * SE(Delta) under the hypothesis that the
failed-only and unconditional samples share the same distribution (chi-square
density as the reference density for the quantile SE).

Reads the same input as make_figs_p2p3p6.py P2b (results/t1_raw.csv).
The release-manifest object `plain-t1-raw-160m` is the official input.

Output: figs/t1_qq_detrended.{png,svg}
"""
import argparse
from pathlib import Path

import numpy as np
import pandas as pd

import math

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def chi2_pdf(x, nu):
    """PDF of chi-square with nu dof at x, via log-gamma to avoid overflow."""
    logf = ((nu / 2 - 1) * math.log(x) - x / 2
            - (nu / 2) * math.log(2) - math.lgamma(nu / 2))
    return math.exp(logf)

HERE = Path(__file__).resolve().parent

_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--raw", type=Path, default=HERE / "results" / "t1_raw.csv",
                 help="160M-trial raw CSV (archive download; see the manifest)")
_ap.add_argument("--outdir", type=Path, default=HERE / "figs")
_args, _ = _ap.parse_known_args()

DF = pd.read_csv(
    _args.raw,
    usecols=["kind", "M", "k", "d", "neg_ratio", "dhat_over_d", "failed"],
)
grid = DF[DF["kind"] == "grid"]

INK = "#334155"
GRAY = "#94a3b8"
BLUE = "#2563eb"
ORANGE = "#d97706"

plt.rcParams.update({
    "font.size": 9, "axes.edgecolor": GRAY, "axes.labelcolor": INK,
    "xtick.color": INK, "ytick.color": INK, "axes.linewidth": 0.8,
})

MS = [64, 256, 1024, 4096]
COLORS = ["#2563eb", "#22c55e", "#d97706", "#7f1d1d"]
qs = np.linspace(0.01, 0.99, 99)

fig, ax = plt.subplots(figsize=(6.4, 3.6), constrained_layout=True)

for M, color in zip(MS, COLORS):
    d = round(0.8 * M)
    sub = grid.query("M == @M and k == 3 and neg_ratio == -1 and d == @d")
    allr = sub["dhat_over_d"].to_numpy()
    fr = sub.loc[sub["failed"] == 1, "dhat_over_d"].to_numpy()
    nU, nF = len(allr), len(fr)
    qU = np.quantile(allr, qs)
    qF = np.quantile(fr, qs)
    delta = qF - qU

    nu = M - 1
    fq = np.array([nu * chi2_pdf(nu * q, nu) for q in qU])
    seU = np.sqrt(qs * (1 - qs) / nU) / fq
    seF = np.sqrt(qs * (1 - qs) / nF) / fq
    band = 1.96 * np.sqrt(seU**2 + seF**2)

    ax.plot(qs, delta, ".", color=color, ms=5, label=f"$M={M}$ ($n_F={nF}$)")
    ax.fill_between(qs, -band, band, color=color, alpha=0.08, lw=0)

ax.axhline(0.0, color=INK, lw=1.0)
ax.set_xlabel("quantile level $p$")
ax.set_ylabel(r"$\Delta = q_{\mathrm{failed}}(p) - q_{\mathrm{uncond}}(p)$")
ax.set_title("Detrended Q-Q: failed-only vs unconditional quantiles",
             fontsize=10, color=INK)
ax.spines[["top", "right"]].set_visible(False)
ax.grid(color=GRAY, alpha=0.25, lw=0.5)
ax.legend(fontsize=7.5, frameon=False, loc="lower right", ncol=1)

for ext in ("png", "svg"):
    _args.outdir.mkdir(parents=True, exist_ok=True)
    fig.savefig(_args.outdir / f"t1_qq_detrended.{ext}", dpi=220)
print("wrote", HERE / "figs" / "t1_qq_detrended.png")

for M in MS:
    d = round(0.8 * M)
    sub = grid.query("M == @M and k == 3 and neg_ratio == -1 and d == @d")
    allr = sub["dhat_over_d"].to_numpy()
    fr = sub.loc[sub["failed"] == 1, "dhat_over_d"].to_numpy()
    delta = np.quantile(fr, qs) - np.quantile(allr, qs)
    print(f"M={M}: max|Delta|={np.abs(delta).max():.4f}, "
          f"mean|Delta|={np.abs(delta).mean():.4f}, "
          f"delta@0.5={np.quantile(fr,0.5)-np.quantile(allr,0.5):+.4f}")

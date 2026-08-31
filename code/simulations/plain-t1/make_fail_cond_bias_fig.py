#!/usr/bin/env python3
"""Failure-conditioned relative bias vs p_F, two panels (no re-simulation).

Panel (a) Plain, fixed one-sided signs (neg_ratio=0, k=3): reads the E8
directional-curve rows (`kind == "e8"`) from the `--e8-raw` file (13 (M,d)
cells, 20,000 trials each). For each cell, p_F = empirical failure rate and
E[dhat/d | F] is the failed-only mean of `dhat_over_d`. The overlay is the
composition-aware envelope of Theorem 3.6 (with one-sided signs the
composition factor equals d-1, so the leading factor is 1)

    +/- sqrt(2*(d-1)/(d*(M-1))) * sqrt((1-p_F)/p_F).

Panel (b) Irregular, iid uniform +/-1 signs: reads the `--irregular-summary`
CSV (sign_mode == "iid", d=1024, 10 M values, 20,000 trials each). The
overlay is Corollary 4.9(iii)'s bound

    +/- (sigma_E / (gamma * sqrt(d))) * sqrt((1-p_F)/p_F),

read directly from the `theoretical_bound` column of that CSV (cross-checked
here against the moments formula). Points with p_F < 0.02 (sample too thin:
<500 failed trials) are drawn hollow; p_F == 1 rows are dropped (the bound is
undefined there).

Outputs (two separate panels, stitched side by side in LaTeX):
  figs/fail_cond_bias_plain.{png,svg}       -- (a) Plain, fixed one-sided signs
  figs/fail_cond_bias_irregular.{png,svg}   -- (b) Irregular, iid signs
"""
import argparse
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
_DATA = HERE.parents[2] / "data" / "paper"

_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--e8-raw", type=Path, default=HERE / "results" / "t1_raw.csv",
                 help="plain E8 failure-bias raw CSV (archive download: plain-t1-e8-failure-bias)")
_ap.add_argument("--irregular-summary", type=Path,
                 default=_DATA / "irregular-failure-summary.csv",
                 help="irregular failure-conditioned summary CSV")
_ap.add_argument("--outdir", type=Path, default=HERE / "figs")
_args, _ = _ap.parse_known_args()

OUT = _args.outdir
OUT.mkdir(parents=True, exist_ok=True)

INK = "#334155"
GRAY = "#94a3b8"
BLUE = "#2563eb"
ORANGE = "#d97706"
plt.rcParams.update({
    "font.size": 11, "axes.edgecolor": GRAY, "axes.labelcolor": INK,
    "xtick.color": INK, "ytick.color": INK, "axes.linewidth": 0.8,
})

# ---------- Panel (a): Plain, fixed one-sided signs (E8) ----------
raw = pd.read_csv(_args.e8_raw)
e8 = raw[raw["kind"] == "e8"]

rows_a = []
for (M, d), g in e8.groupby(["M", "d"]):
    p = g["failed"].mean()
    if not (0 < p < 1):
        continue
    mean_f = g.loc[g["failed"] == 1, "dhat_over_d"].mean()
    bound = np.sqrt(2 * (d - 1) / (d * (M - 1))) * np.sqrt((1 - p) / p)
    rows_a.append(dict(M=M, d=d, p=p, bias=mean_f - 1.0, bound=bound))
dfa = pd.DataFrame(rows_a).sort_values("p")

# ---------- Panel (b): Irregular, iid signs ----------
summary = pd.read_csv(_args.irregular_summary)
iid = summary[(summary["sign_mode"] == "iid") & (summary["p_f"] < 1)].copy()
iid["bias"] = iid["mean_ratio_failed"] - 1.0
iid = iid.sort_values("p_f")

fig_a, ax1 = plt.subplots(figsize=(4.6, 3.7), constrained_layout=True)

# Panel a: Plain, fixed one-sided signs
for M, marker, color in [(128, "o", BLUE), (256, "s", ORANGE)]:
    sub = dfa[dfa["M"] == M]
    ax1.plot(sub["p"], sub["bias"], marker=marker, ls="none", color=color,
              ms=6.5, mec="white", mew=0.5, label=f"$M={M}$ (empirical)")
    ax1.plot(sub["p"], sub["bound"], ls="--", color=color, lw=1.1, alpha=0.85)
    ax1.plot(sub["p"], -sub["bound"], ls="--", color=color, lw=1.1, alpha=0.85)
ax1.axhline(0, color=INK, lw=0.7, alpha=0.5)
ax1.set_xlabel(r"$p_F = \Pr[F]$")
ax1.set_ylabel(r"$\mathbb{E}[\widehat d/d \mid F] - 1$")
ax1.set_title("(a) Plain, fixed one-sided signs", fontsize=11.5, color=INK)
ax1.grid(alpha=0.3)
ax1.spines[["top", "right"]].set_visible(False)
ax1.legend(fontsize=9, frameon=False, loc="upper right")
for ext in ("png", "svg"):
    fig_a.savefig(OUT / f"fail_cond_bias_plain.{ext}", dpi=220)

fig_b, ax2 = plt.subplots(figsize=(4.6, 3.7), constrained_layout=True)

# Panel b: Irregular, iid signs
thin = iid["p_f"] < 0.02
ax2.plot(iid.loc[~thin, "p_f"], iid.loc[~thin, "bias"], marker="o", ls="none",
          color=BLUE, ms=6.5, mec="white", mew=0.5, label=r"iid signs (empirical, $d=1024$)")
ax2.plot(iid.loc[thin, "p_f"], iid.loc[thin, "bias"], marker="o", ls="none",
          mfc="none", mec=BLUE, ms=6.5, mew=1.1, label=r"$p_F<0.02$ (thin sample)")
ax2.plot(iid["p_f"], iid["theoretical_bound"], ls="--", color=BLUE, lw=1.1, alpha=0.85,
          label="Theory bound")
ax2.plot(iid["p_f"], -iid["theoretical_bound"], ls="--", color=BLUE, lw=1.1, alpha=0.85)
ax2.axhline(0, color=INK, lw=0.7, alpha=0.5)
ax2.set_xlabel(r"$p_F = \Pr[F]$")
ax2.set_ylabel(r"$\mathbb{E}[\widehat d/d \mid F] - 1$")
ax2.set_title("(b) Irregular, iid signs", fontsize=11.5, color=INK)
ax2.grid(alpha=0.3)
ax2.spines[["top", "right"]].set_visible(False)
ax2.legend(fontsize=9, frameon=False, loc="upper right")
for ext in ("png", "svg"):
    fig_b.savefig(OUT / f"fail_cond_bias_irregular.{ext}", dpi=220)

# ---------- Combined figure (a+b in one image, per-panel legends) -----------
# The paper uses one integrated image with no subcaptions: the two panels keep
# their own legends inside each plot area (as in the original two-panel
# figure), and the right y-axis is dropped so the middle space is freed and
# the whole figure is larger. The two individual panels above remain for the
# arXiv version, which stitches them.
TEAL = "#0d9488"
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(7.6, 3.3), sharey=True)
fig.subplots_adjust(left=0.105, right=0.995, top=0.90, bottom=0.09,
                    wspace=0.10)

# Left panel: Plain, fixed one-sided signs (E8)
for M, marker, color in [(128, "o", BLUE), (256, "s", ORANGE)]:
    sub = dfa[dfa["M"] == M]
    ax1.plot(sub["p"], sub["bias"], marker=marker, ls="none", color=color,
              ms=6.5, mec="white", mew=0.5, label=f"$M={M}$ (empirical)")
    ax1.plot(sub["p"], sub["bound"], ls="--", color=color, lw=1.1, alpha=0.85)
    ax1.plot(sub["p"], -sub["bound"], ls="--", color=color, lw=1.1, alpha=0.85)
ax1.axhline(0, color=INK, lw=0.7, alpha=0.5)
ax1.set_xlabel(r"$p_F = \Pr[F]$")
ax1.set_title("Plain, fixed one-sided signs", fontsize=11.5, color=INK)
ax1.grid(alpha=0.3)
ax1.spines[["top", "right"]].set_visible(False)
ax1.legend(loc="upper right", fontsize=9, frameon=False)

# Right panel: Irregular, iid signs
thin = iid["p_f"] < 0.02
ax2.plot(iid.loc[~thin, "p_f"], iid.loc[~thin, "bias"], marker="o", ls="none",
          color=TEAL, ms=6.5, mec="white", mew=0.5,
          label=r"iid signs, $d=1024$ (empirical)")
ax2.plot(iid.loc[thin, "p_f"], iid.loc[thin, "bias"], marker="o", ls="none",
          mfc="none", mec=TEAL, ms=6.5, mew=1.1, label=r"$p_F<0.02$ (thin)")
ax2.plot(iid["p_f"], iid["theoretical_bound"], ls="--", color=TEAL, lw=1.1,
          alpha=0.85, label="Analytic bound")
ax2.plot(iid["p_f"], -iid["theoretical_bound"], ls="--", color=TEAL, lw=1.1,
          alpha=0.85)
ax2.axhline(0, color=INK, lw=0.7, alpha=0.5)
ax2.set_xlabel(r"$p_F = \Pr[F]$")
ax2.set_title("Irregular, iid signs", fontsize=11.5, color=INK)
ax2.grid(alpha=0.3)
ax2.spines[["top", "right"]].set_visible(False)
ax2.legend(loc="upper right", fontsize=9, frameon=False)

ax1.set_ylabel(r"$\mathbb{E}[\widehat d/d \mid F] - 1$")
for ext in ("png", "svg"):
    fig.savefig(OUT / f"fail_cond_bias_combined.{ext}", dpi=220)

print("wrote", OUT / "fail_cond_bias_combined.png")
print("wrote", OUT / "fail_cond_bias_plain.png")
print("wrote", OUT / "fail_cond_bias_irregular.png")
print("panel a (Plain E8) rows:")
print(dfa[["M", "d", "p", "bias", "bound"]].to_string(index=False))
print("panel b (Irregular iid) rows:")
print(iid[["m", "p_f", "bias", "theoretical_bound", "sufficient_sample"]].to_string(index=False))

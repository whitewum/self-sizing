#!/usr/bin/env python3
"""P2/P3/P6 from t1_raw.csv (no re-simulation).

P2a  figs/t1_hist_chi2.png : unconditional dhat/d histograms (T1a display
     config: k=3, neg_ratio=0.5, d=1.6M) with chi^2_{M-1}/(M-1) density overlay.
P2c  figs/t1_hist_chi2_slices.{svg,png}: two orthogonal slices through (M,d):
     fixed M=64 with increasing d/M (Theorem 3.4 convergence), and fixed d=4096
     with increasing d/M (space/accuracy tradeoff). The fixed-d row uses the
     supplemental H100 artifact results/t1_fixed_d_raw_h100.csv.
P2b  figs/t1_qq_failed.png : failed-only vs unconditional QQ plots (T1b config:
     k=3, neg_ratio=rand(-1), d=0.8M).
P3+P6 results/t1c_bounds.csv (+ markdown to stdout): per M, failed-only
     empirical q05, order-statistic 95% lower confidence bound (Clopper-Pearson
     on the quantile), Cantelli bound (random signs) and Chebyshev bound
     (any fixed signs) from Proposition 3.8 (§3.4) with delta=0.05. The analytic
     bounds use a pointwise one-sided 95% Clopper-Pearson lower confidence bound
     for the failure probability, not the empirical point estimate.
P7   results/t1_fixed_sign_bound_coverage.csv: full-grid coverage audit of
     Theorem 3.6's fixed-sign conditional-mean bound, grouped by M. Configurations
     with empirical failure rate 0 or 1 are excluded because the plug-in bound
     utilization is undefined at those endpoints.
P8   results/t1_prop5_fixed_sign_coverage.csv: full-grid empirical coverage
     audit of Proposition 3.8 for fixed signs and delta=0.05. Configurations need
     at least 20 failed trials; the analytic q05 bound uses the pointwise
     one-sided 95% lower confidence bound for the failure probability.
P9   results/t1_q01_empirical_guidance.csv: descriptive failed-only q01 values
     for M0 in {64,256,512,1024}, k=3, d/M0 in {0.8,1.6,8}, and all five
     sign modes. M0=512 comes from the H100 supplemental raw file. This is
     configuration guidance, not an SLA.
"""
import argparse
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

MS = [64, 256, 1024, 4096]
DELTA = 0.05
INK = "#334155"
GRAY = "#94a3b8"
BLUE = "#2563eb"
ORANGE = "#d97706"

# The 160M-trial raw and the 4M fixed-d raw are archive downloads (see the
# manifest); the 1M summary ships in data/paper/. Paths are overridable.
_DATA = Path(__file__).resolve().parents[3] / "data" / "paper"
_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--raw", type=Path, default=Path("results/t1_raw.csv"),
                 help="160M-trial raw CSV (archive download)")
_ap.add_argument("--fixed-d", type=Path, default=Path("results/t1_fixed_d_raw_h100.csv"),
                 help="4M fixed-d raw CSV (archive download)")
_ap.add_argument("--summary", type=Path, default=Path("results/t1_summary.csv"),
                 help="160-cell 1M-trial summary (data/paper/plain-t1-summary-1m.csv)")
_args, _ = _ap.parse_known_args()

df = pd.read_csv(_args.raw)
grid = df[df["kind"] == "grid"]

plt.rcParams.update({
    "font.size": 9, "axes.edgecolor": GRAY, "axes.labelcolor": INK,
    "xtick.color": INK, "ytick.color": INK, "axes.linewidth": 0.8,
})

# ---------- P2a: histogram + chi-square overlay (T1a config) ----------
fig, axes = plt.subplots(1, 4, figsize=(11, 2.8), constrained_layout=True)
for ax, M in zip(axes, MS):
    d = round(1.6 * M)
    r = grid.query("M == @M and k == 3 and neg_ratio == 0.5 and d == @d")["dhat_over_d"].to_numpy()
    assert len(r) == 1_000_000, (M, len(r))
    ax.hist(r, bins=60, density=True, color=GRAY, alpha=0.55, edgecolor="none")
    xs = np.linspace(r.min(), r.max(), 400)
    ax.plot(xs, (M - 1) * stats.chi2.pdf((M - 1) * xs, M - 1), color=BLUE, lw=1.6,
            label=r"$\chi^2_{M-1}/(M-1)$")
    ax.set_title(f"$M={M}$, $d={d}$", fontsize=9, color=INK)
    ax.set_xlabel(r"$\widehat{d}/d$")
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", color=GRAY, alpha=0.25, lw=0.5)
axes[0].set_ylabel("density")
axes[0].legend(frameon=False, fontsize=8, loc="upper right")
fig.savefig("figs/t1_hist_chi2.png", dpi=200)
plt.close(fig)

# ---------- P2c: orthogonal fixed-M and fixed-d distribution slices ----------
loads = [0.4, 0.8, 1.6, 8.0]
fixed_d_path = _args.fixed_d
if fixed_d_path.exists():
    fixed_d_grid = pd.read_csv(fixed_d_path)
    fig, axes = plt.subplots(2, 4, figsize=(11, 5.2), constrained_layout=True)

    # Row 1: fix M and increase d, which matches Theorem 3.4's fixed-M limit.
    fixed_M = 64
    for col, (ax, load) in enumerate(zip(axes[0], loads)):
        d = round(load * fixed_M)
        # Pool k and sign modes: the grid is designed to test that the limit is
        # insensitive to both, and pooling reduces histogram noise.
        r = grid.query("M == @fixed_M and d == @d")["dhat_over_d"].to_numpy()
        assert len(r) == 10_000_000, (fixed_M, d, len(r))
        ax.hist(r, bins=70, density=True, color=GRAY, alpha=0.42, edgecolor="none")
        xs = np.linspace(max(0, np.quantile(r, 0.001)), np.quantile(r, 0.999), 500)
        ax.plot(
            xs,
            (fixed_M - 1) * stats.chi2.pdf((fixed_M - 1) * xs, fixed_M - 1),
            color=BLUE,
            lw=1.6,
        )
        ks = stats.kstest(r, lambda x: stats.chi2.cdf((fixed_M - 1) * x, fixed_M - 1)).statistic
        rsd = r.std(ddof=1)
        rsd_theory = np.sqrt(2 * (d - 1) / (d * (fixed_M - 1)))
        ax.set_title(f"$d/M={load:g}$, $d={d}$", fontsize=9, color=INK)
        ax.text(
            0.97, 0.93, f"KS={ks:.3f}\nRSD={rsd:.3f}", transform=ax.transAxes,
            ha="right", va="top", fontsize=7.5, color=INK,
        )
        if col == 0:
            ax.set_ylabel("fixed $M=64$\ndensity")
        assert abs(rsd / rsd_theory - 1) < 0.03, (d, rsd, rsd_theory)

    # Row 2: fix d and reduce M as d/M rises. This changes the chi-square
    # degrees of freedom and isolates the cell-budget/accuracy tradeoff.
    fixed_d_value = 4096
    for col, (ax, load) in enumerate(zip(axes[1], loads)):
        M = round(fixed_d_value / load)
        r = fixed_d_grid.query(
            "kind == 'fixed_d' and M == @M and d == @fixed_d_value"
        )["dhat_over_d"].to_numpy()
        assert len(r) == 1_000_000, (M, fixed_d_value, len(r))
        ax.hist(r, bins=60, density=True, color=GRAY, alpha=0.42, edgecolor="none")
        xs = np.linspace(max(0, np.quantile(r, 0.001)), np.quantile(r, 0.999), 500)
        ax.plot(
            xs,
            (M - 1) * stats.chi2.pdf((M - 1) * xs, M - 1),
            color=BLUE,
            lw=1.6,
        )
        rsd = r.std(ddof=1)
        rsd_theory = np.sqrt(2 * (fixed_d_value - 1) / (fixed_d_value * (M - 1)))
        ks = stats.kstest(r, lambda x: stats.chi2.cdf((M - 1) * x, M - 1)).statistic
        ax.set_title(f"$d/M={load:g}$, $M={M}$", fontsize=9, color=INK)
        ax.text(
            0.97, 0.93, f"KS={ks:.3f}\nRSD={rsd:.3f}", transform=ax.transAxes,
            ha="right", va="top", fontsize=7.5, color=INK,
        )
        if col == 0:
            ax.set_ylabel(f"fixed $d={fixed_d_value}$\ndensity")
        assert abs(rsd / rsd_theory - 1) < 0.03, (M, rsd, rsd_theory)

    for ax in axes.flat:
        ax.set_xlabel(r"$\widehat{d}/d$")
        ax.spines[["top", "right"]].set_visible(False)
        ax.grid(axis="y", color=GRAY, alpha=0.22, lw=0.5)

    handles = [
        plt.Rectangle((0, 0), 1, 1, color=GRAY, alpha=0.42, ec="none"),
        plt.Line2D([0], [0], color=BLUE, lw=1.6),
    ]
    fig.legend(
        handles,
        ["empirical density", r"$\chi^2_{M-1}/(M-1)$"],
        frameon=False,
        fontsize=8,
        loc="outside upper center",
        ncol=2,
    )
    fig.savefig("figs/t1_hist_chi2_slices.svg")
    fig.savefig("figs/t1_hist_chi2_slices.png", dpi=220)
    plt.close(fig)

# ---------- P2b: QQ failed-only vs unconditional (T1b config) ----------
qs = np.linspace(0.01, 0.99, 99)
fig, axes = plt.subplots(2, 2, figsize=(7.2, 6.2), constrained_layout=True)
for ax, M in zip(axes.flat, MS):
    d = round(0.8 * M)
    sub = grid.query("M == @M and k == 3 and neg_ratio == -1 and d == @d")
    allr = sub["dhat_over_d"].to_numpy()
    fr = sub.loc[sub["failed"] == 1, "dhat_over_d"].to_numpy()
    qa, qf = np.quantile(allr, qs), np.quantile(fr, qs)
    lo, hi = min(qa.min(), qf.min()), max(qa.max(), qf.max())
    ax.plot([lo, hi], [lo, hi], color=GRAY, lw=1, ls="--")
    ax.plot(qa, qf, ".", color=ORANGE, ms=4)
    ax.set_title(f"$M={M}$, $d={d}$, $n_F={len(fr)}$", fontsize=9, color=INK)
    ax.set_aspect("equal")
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(color=GRAY, alpha=0.25, lw=0.5)
for ax in axes[:, 0]:
    ax.set_ylabel("failed-only quantile")
for ax in axes[1, :]:
    ax.set_xlabel("unconditional quantile")
fig.savefig("figs/t1_qq_failed.svg")
fig.savefig("figs/t1_qq_failed.png", dpi=200)
plt.close(fig)

# ---------- P3 + P6: bounds table (T1b config) ----------
rows = []
for M in MS:
    d = round(0.8 * M)
    sub = grid.query("M == @M and k == 3 and neg_ratio == -1 and d == @d")
    n_all = len(sub)
    fr = np.sort(sub.loc[sub["failed"] == 1, "dhat_over_d"].to_numpy())
    n = len(fr)
    p = n / n_all
    p_lcb95 = float(stats.beta.ppf(0.05, n, n_all - n + 1))
    q05 = float(np.quantile(fr, DELTA))
    # P6: largest r with BinomCDF(r-1; n, delta) <= 0.05  ->  X_(r) is a 95%
    # lower confidence bound for the delta-quantile.
    cdf = stats.binom.cdf(np.arange(0, n), n, DELTA)  # cdf[r-1] at index r-1
    r_idx = int(np.searchsorted(cdf, 0.05, side="right"))  # count of r with cdf<=0.05
    lcb = float(fr[r_idx - 1]) if r_idx >= 1 else float("nan")
    # P3: Proposition 3.8 (§3.4) closed-form q_delta with (d-1)/d <= 1 simplification.
    # Use a one-sided 95% lower confidence bound for p so the resulting q bound
    # is conservative with respect to failure-rate estimation uncertainty.
    cheb = 1 - np.sqrt(2 / ((M - 1) * p_lcb95 * DELTA))
    cant = 1 - np.sqrt(2 * (1 - DELTA) / ((M - 1) * p_lcb95 * DELTA))
    rows.append(dict(M=M, d=d, p_fail_emp=round(p, 4),
                     p_fail_lcb95=round(p_lcb95, 4), n_failed=n,
                     q05_emp=round(q05, 4), q05_lcb95=round(lcb, 4),
                     q05_cantelli=round(cant, 4), q05_chebyshev=round(cheb, 4)))

tab = pd.DataFrame(rows)
tab.to_csv("results/t1c_bounds.csv", index=False)
print(tab.to_string(index=False))

# ---------- P5: full-grid overview (all 160 cells, from t1_summary.csv) ----------
sm = pd.read_csv(_args.summary)
sm = sm[sm["kind"] == "grid"].copy()
assert len(sm) == 160
xpos = {64: 0, 256: 1, 1024: 2, 4096: 3}
rng = np.random.default_rng(0)  # display jitter only
jit = rng.uniform(-0.18, 0.18, len(sm))
x = sm["M"].map(xpos).to_numpy() + jit

fig, axes = plt.subplots(1, 2, figsize=(9.5, 3.0), constrained_layout=True)
ax = axes[0]
ax.axhline(0, color=GRAY, lw=0.8, ls="--")
ax.scatter(x, sm["mean_ratio"] - 1, s=10, color=BLUE, alpha=0.55, edgecolors="none")
ax.set_ylabel(r"mean$(\widehat{d}/d)-1$")
ax.set_title("Per-cell mean bias (160 cells)", fontsize=9, color=INK)
ax = axes[1]
ax.axhline(1, color=GRAY, lw=0.8, ls="--")
ax.scatter(x, sm["rsd_emp_over_theory"], s=10, color=ORANGE, alpha=0.55, edgecolors="none")
ax.set_ylabel("RSD empirical / closed-form")
ax.set_title("Per-cell RSD ratio (160 cells)", fontsize=9, color=INK)
for ax in axes:
    ax.set_xticks(range(4), [f"$M={m}$" for m in MS])
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", color=GRAY, alpha=0.25, lw=0.5)
fig.savefig("figs/t1_grid_overview.png", dpi=200)
plt.close(fig)

per_m = sm.groupby("M").apply(
    lambda g: pd.Series({
        "n_cells": len(g),
        "max_abs_mean_bias": (g["mean_ratio"] - 1).abs().max().round(4),
        "rsd_ratio_min": g["rsd_emp_over_theory"].min().round(4),
        "rsd_ratio_max": g["rsd_emp_over_theory"].max().round(4),
        "max_abs_q05_vs_chi2": (g["q05_emp"] - g["q05_chi2"]).abs().max().round(4),
    }), include_groups=False).reset_index()
per_m.to_csv("results/t1_grid_per_m.csv", index=False)
print(per_m.to_string(index=False))

# ---------- P7: Theorem 3.6 fixed-sign conditional-mean bound coverage ----------
fixed = sm[
    (sm["neg_ratio"] >= 0)
    & (sm["fail_rate"] > 0)
    & (sm["fail_rate"] < 1)
    & sm["failed_mean_ratio"].notna()
    & sm["lemma5_rel_bound"].notna()
].copy()
fixed["abs_failed_mean_bias"] = (fixed["failed_mean_ratio"] - 1).abs()
fixed["bound_utilization"] = fixed["abs_failed_mean_bias"] / fixed["lemma5_rel_bound"]
fixed["violation"] = fixed["abs_failed_mean_bias"] > fixed["lemma5_rel_bound"]

fixed_coverage = fixed.groupby("M").agg(
    n_nondegenerate_configs=("M", "size"),
    max_abs_failed_mean_bias=("abs_failed_mean_bias", "max"),
    max_bound_utilization=("bound_utilization", "max"),
    violations=("violation", "sum"),
).reset_index()
fixed_coverage["max_abs_failed_mean_bias"] = fixed_coverage["max_abs_failed_mean_bias"].round(6)
fixed_coverage["max_bound_utilization"] = fixed_coverage["max_bound_utilization"].round(6)
fixed_coverage.to_csv("results/t1_fixed_sign_bound_coverage.csv", index=False)
print(fixed_coverage.to_string(index=False))

# ---------- P8: Proposition 3.8 fixed-sign q05 coverage ----------
prop5 = sm[
    (sm["neg_ratio"] >= 0)
    & (sm["failed_n"] >= 20)
    & sm["failed_q05"].notna()
].copy()
prop5["p_fail_lcb95"] = stats.beta.ppf(
    0.05,
    prop5["failed_n"],
    prop5["trials"] - prop5["failed_n"] + 1,
)
prop5["q05_prop5"] = 1 - np.sqrt(
    2 * (prop5["d"] - 1)
    / (
        prop5["d"]
        * (prop5["M"] - 1)
        * prop5["p_fail_lcb95"]
        * DELTA
    )
)
prop5["coverage_margin"] = prop5["failed_q05"] - prop5["q05_prop5"]
prop5["violation"] = prop5["coverage_margin"] < 0

prop5_coverage = prop5.groupby("M").agg(
    n_configs=("M", "size"),
    min_coverage_margin=("coverage_margin", "min"),
    violations=("violation", "sum"),
).reset_index()
prop5_coverage["min_coverage_margin"] = prop5_coverage["min_coverage_margin"].round(6)
prop5_coverage.to_csv("results/t1_prop5_fixed_sign_coverage.csv", index=False)
print(prop5_coverage.to_string(index=False))

# ---------- P9: protocol q01 empirical guidance (not certification) ----------
q01_grid = grid
m512_raw = Path("results/t1_m512_raw_h100.csv")
if m512_raw.exists():
    m512_grid = pd.read_csv(m512_raw)
    q01_grid = pd.concat(
        [q01_grid, m512_grid[m512_grid["kind"] == "grid"]],
        ignore_index=True,
    )
q01_source = q01_grid[
    (q01_grid["M"].isin([64, 256, 512, 1024]))
    & (q01_grid["k"] == 3)
    & (q01_grid["failed"] == 1)
    & (
        ((q01_grid["d"] / q01_grid["M"] - 0.8).abs() < 0.01)
        | ((q01_grid["d"] / q01_grid["M"] - 1.6).abs() < 0.01)
        | ((q01_grid["d"] / q01_grid["M"] - 8.0).abs() < 0.01)
    )
]
q01_rows = []
for (M, d, neg_ratio), group in q01_source.groupby(["M", "d", "neg_ratio"]):
    q01 = float(group["dhat_over_d"].quantile(0.01))
    q01_rows.append({
        "M": int(M),
        "k": 3,
        "d": int(d),
        "load_d_over_M": round(d / M, 6),
        "neg_ratio": neg_ratio,
        "n_failed": len(group),
        "q01_emp": round(q01, 6),
        "alpha_beta_1p3": round(1.3 / q01, 6),
    })
q01_guidance = pd.DataFrame(q01_rows).sort_values(["M", "d", "neg_ratio"])
q01_guidance.to_csv("results/t1_q01_empirical_guidance.csv", index=False)
print(q01_guidance.to_string(index=False))

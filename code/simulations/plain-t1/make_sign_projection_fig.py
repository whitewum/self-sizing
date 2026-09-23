#!/usr/bin/env python3
"""Validate and visualize the exact fixed-sign conditional-mean projection.

This script performs no simulation.  It reads the 165-configuration aggregate
of the sign-imbalance sweep (data/paper/sign-imbalance-shape-points.csv) and fits, separately for each
fixed (M, k, d), the one-parameter identity

    E[dhat/d | F] - 1 = c_F * (d * rho^2 - 1).

The fit is weighted by the Monte Carlo standard error of each failed-only
mean.  Leave-one-out predictions provide a non-circular audit of all points.

Outputs (next to this script):
  results/sign-projection/validation_points.csv
  results/sign-projection/validation_fits.csv
  results/sign-projection/validation_summary.csv
  figs/candidates/sign_projection_mean_M1024.{png,svg}
  figs/candidates/sign_projection_observed_vs_predicted.{png,svg}
  figs/candidates/sign_projection_combined_candidate.{png,svg}
  figs/sign_projection_combined.{png,svg}   (paper fig:sign-projection)
"""

from __future__ import annotations

from pathlib import Path

import matplotlib
import numpy as np
import pandas as pd

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.lines import Line2D


HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[2] / "data" / "paper" / "sign-imbalance-shape-points.csv"
RESULT_DIR = HERE / "results" / "sign-projection"
FIG_DIR = HERE / "figs" / "candidates"
FORMAL_FIG_DIR = HERE / "figs"

# Match the shared paper-figure palette.
INK = "#222222"
GRAY = "#66717e"
GRID = "#dde2e7"
BLUE = "#2468b4"
ORANGE = "#c85a17"
GREEN = "#167c63"
COLORS = [BLUE, GREEN, ORANGE, GRAY, INK]

plt.rcParams.update(
    {
        "font.size": 9,
        "axes.edgecolor": GRAY,
        "axes.labelcolor": INK,
        "xtick.color": INK,
        "ytick.color": INK,
        "axes.linewidth": 0.8,
    }
)


def weighted_slope(z: np.ndarray, y: np.ndarray, se: np.ndarray) -> tuple[float, float]:
    """Fit y = slope * z and return the slope and its nominal standard error."""
    weights = 1.0 / np.square(se)
    denominator = float(np.dot(weights * z, z))
    slope = float(np.dot(weights * z, y) / denominator)
    slope_se = float(denominator ** -0.5)
    return slope, slope_se


def ordered_load_groups(selected: pd.DataFrame) -> tuple[list[pd.DataFrame], list]:
    """Load lines sorted by ascending p_F, colored by a monotonic red->purple ramp.

    The lines are the load levels $d/M$ of the left panel; sorting by the
    empirical failure rate keeps the color legend ordered with increasing
    $p_F$ and makes the ramp readable as one gradient.
    """
    groups = [group for _, group in selected.groupby(["d", "load"], sort=True)]
    groups.sort(key=lambda group: float(group["fail_rate"].mean()))
    ramp = LinearSegmentedColormap.from_list(
        "red_yellow_green_blue_purple",
        ["#d62728", "#f0c419", "#167c63", "#2468b4", "#8e44ad"],
    )
    colors = [
        ramp(i / max(len(groups) - 1, 1)) for i in range(len(groups))
    ]
    return groups, colors


def prepare_points() -> pd.DataFrame:
    data = pd.read_csv(SOURCE)
    data = data[data["rho"].notna()].copy()
    data["rho2"] = np.square(data["rho"])
    data["projection_coordinate"] = data["d"] * data["rho2"] - 1.0
    data["projection_factor"] = data["projection_coordinate"] / (data["d"] - 1.0)
    data["observed_bias"] = data["failed_mean"] - 1.0
    data["mean_se"] = np.sqrt(data["failed_variance"] / data["failed_n"])

    output_groups: list[pd.DataFrame] = []
    group_columns = ["M", "k", "d", "load"]
    for _, group in data.groupby(group_columns, sort=True):
        group = group.sort_values("rho2").copy()
        z = group["projection_coordinate"].to_numpy(dtype=float)
        y = group["observed_bias"].to_numpy(dtype=float)
        se = group["mean_se"].to_numpy(dtype=float)

        fitted_c_f, fitted_c_f_se = weighted_slope(z, y, se)
        group["fitted_c_f"] = fitted_c_f
        group["fitted_c_f_se"] = fitted_c_f_se
        group["fitted_bias"] = fitted_c_f * z

        loo_prediction = np.empty(len(group), dtype=float)
        loo_prediction_se = np.empty(len(group), dtype=float)
        for index in range(len(group)):
            keep = np.arange(len(group)) != index
            loo_c_f, loo_c_f_se = weighted_slope(z[keep], y[keep], se[keep])
            loo_prediction[index] = loo_c_f * z[index]
            loo_prediction_se[index] = abs(z[index]) * loo_c_f_se

        group["loo_predicted_bias"] = loo_prediction
        group["loo_prediction_se"] = loo_prediction_se
        group["loo_residual"] = group["observed_bias"] - group["loo_predicted_bias"]
        group["loo_residual_se"] = np.sqrt(
            np.square(group["mean_se"]) + np.square(group["loo_prediction_se"])
        )
        group["loo_residual_z"] = group["loo_residual"] / group["loo_residual_se"]

        # This audit uses the group-average empirical p_F only as a Monte Carlo
        # proxy for the common true p_F.  It is not a probability certification.
        p_f_proxy = float(group["fail_rate"].mean())
        relative_std = np.sqrt(
            2.0 * (group["d"] - 1.0) / (group["d"] * (group["M"] - 1.0))
        )
        group["p_f_proxy"] = p_f_proxy
        group["composition_aware_bound"] = (
            np.abs(group["projection_factor"])
            * relative_std
            * np.sqrt((1.0 - p_f_proxy) / p_f_proxy)
        )
        group["point_inside_bound"] = (
            np.abs(group["observed_bias"]) <= group["composition_aware_bound"]
        )
        group["ci95_compatible_with_bound"] = (
            np.abs(group["observed_bias"])
            <= group["composition_aware_bound"] + 1.96 * group["mean_se"]
        )
        output_groups.append(group)

    return pd.concat(output_groups, ignore_index=True)


def write_summary(points: pd.DataFrame) -> pd.DataFrame:
    summaries: list[dict[str, float | int | str]] = []
    for label, subset in [("all", points), *[(str(m), g) for m, g in points.groupby("M")]]:
        residual = subset["loo_residual"].to_numpy(dtype=float)
        residual_z = subset["loo_residual_z"].to_numpy(dtype=float)
        summaries.append(
            {
                "M": label,
                "points": len(subset),
                "loo_rmse": float(np.sqrt(np.mean(np.square(residual)))),
                "loo_max_abs_residual": float(np.max(np.abs(residual))),
                "loo_abs_z_gt_1p96": int(np.sum(np.abs(residual_z) > 1.96)),
                "loo_abs_z_gt_3": int(np.sum(np.abs(residual_z) > 3.0)),
                "point_bound_violations": int(np.sum(~subset["point_inside_bound"])),
                "ci95_bound_incompatibilities": int(
                    np.sum(~subset["ci95_compatible_with_bound"])
                ),
            }
        )
    return pd.DataFrame(summaries)


def write_group_fits(points: pd.DataFrame) -> pd.DataFrame:
    rows: list[dict[str, float | int]] = []
    for (m, k, d, load), group in points.groupby(["M", "k", "d", "load"]):
        observed = group["observed_bias"].to_numpy(dtype=float)
        fitted = group["fitted_bias"].to_numpy(dtype=float)
        residual = observed - fitted
        residual_sum = float(np.sum(np.square(residual)))
        total_sum = float(np.sum(np.square(observed - np.mean(observed))))
        rows.append(
            {
                "M": int(m),
                "k": int(k),
                "d": int(d),
                "load": float(load),
                "p_f_proxy": float(group["p_f_proxy"].iloc[0]),
                "points": len(group),
                "fitted_c_f": float(group["fitted_c_f"].iloc[0]),
                "fitted_c_f_se": float(group["fitted_c_f_se"].iloc[0]),
                "constrained_r2": 1.0 - residual_sum / total_sum,
                "fit_rmse": float(np.sqrt(np.mean(np.square(residual)))),
                "fit_max_abs_residual": float(np.max(np.abs(residual))),
                "loo_rmse": float(np.sqrt(np.mean(np.square(group["loo_residual"])))),
                "loo_max_abs_residual": float(np.max(np.abs(group["loo_residual"]))),
            }
        )
    return pd.DataFrame(rows).sort_values(["M", "load"])


def plot_reader_candidate(points: pd.DataFrame) -> None:
    selected = points[points["M"] == 1024].copy()
    fig, axis = plt.subplots(figsize=(5.4, 3.8), constrained_layout=True)

    load_groups, load_colors = ordered_load_groups(selected)
    for color, group in zip(load_colors, load_groups):
        group = group.sort_values("rho2")
        p_f = float(group["fail_rate"].mean())
        axis.errorbar(
            group["rho2"],
            100.0 * group["observed_bias"],
            yerr=196.0 * group["mean_se"],
            fmt="o",
            color=color,
            ms=4.2,
            capsize=1.5,
            elinewidth=0.8,
            label=fr"$p_F={p_f:.2f}$",
        )
        x_grid = np.linspace(0.0, 1.0, 200)
        predicted = float(group["fitted_c_f"].iloc[0]) * (
            float(group["d"].iloc[0]) * x_grid - 1.0
        )
        axis.plot(x_grid, 100.0 * predicted, color=color, lw=1.25)

    axis.axhline(0.0, color=INK, lw=0.7, alpha=0.55)
    axis.set_xlabel(r"fixed-sign imbalance $\theta_\Delta^2$")
    axis.set_ylabel(r"$100\,\{\mathbb{E}[\widehat d/d\mid F]-1\}$ (%)")
    axis.set_title(r"One-parameter sign projection ($M=1024$, $k=3$)", color=INK)
    axis.grid(alpha=0.25)
    axis.spines[["top", "right"]].set_visible(False)
    axis.legend(frameon=False, fontsize=8.5, ncol=2, loc="upper left")

    for extension in ("png", "svg"):
        fig.savefig(FIG_DIR / f"sign_projection_mean_M1024.{extension}", dpi=220)
    plt.close(fig)


def plot_full_audit(points: pd.DataFrame) -> None:
    fig, axis = plt.subplots(figsize=(4.8, 4.2), constrained_layout=True)
    markers = {256: "o", 1024: "s", 4096: "^"}
    colors = {256: BLUE, 1024: GREEN, 4096: ORANGE}

    for m, group in points.groupby("M"):
        axis.scatter(
            100.0 * group["loo_predicted_bias"],
            100.0 * group["observed_bias"],
            s=20,
            marker=markers[int(m)],
            color=colors[int(m)],
            alpha=0.72,
            edgecolors="white",
            linewidths=0.35,
            label=fr"$M={int(m)}$",
        )

    all_values = 100.0 * pd.concat(
        [points["loo_predicted_bias"], points["observed_bias"]], ignore_index=True
    )
    lower = float(all_values.min())
    upper = float(all_values.max())
    padding = 0.04 * (upper - lower)
    limits = (lower - padding, upper + padding)
    axis.plot(limits, limits, color=INK, lw=1.0, ls="--", label="identity")
    axis.set_xlim(limits)
    axis.set_ylim(limits)
    axis.set_aspect("equal", adjustable="box")
    axis.set_xlabel("leave-one-out predicted bias (pp)")
    axis.set_ylabel("observed bias (pp)")
    axis.set_title("Sign-projection audit: 165 configurations", color=INK)
    axis.grid(alpha=0.25)
    axis.spines[["top", "right"]].set_visible(False)
    axis.legend(frameon=False, fontsize=8.5, loc="upper left")

    for extension in ("png", "svg"):
        fig.savefig(
            FIG_DIR / f"sign_projection_observed_vs_predicted.{extension}", dpi=220
        )
    plt.close(fig)


def plot_combined_candidate(points: pd.DataFrame) -> None:
    """Single-column (3.3 in) two-panel candidate at final print size.

    Drawn at the final single-column width so fonts are literal print sizes.
    No panel titles; axis text, ticks and legend are enlarged relative to the
    previous full-width candidate.  The right panel drops ``aspect='equal'`` so
    it fills its slot (no dead side margins); the identity line is the y=x
    curve regardless of aspect.
    """
    label_fs = 5.7
    tick_fs = 5.0
    legend_fs = 5.4

    fig, (left, right) = plt.subplots(
        1, 2, figsize=(3.3, 1.5), gridspec_kw={"width_ratios": [1.15, 1.0]}
    )
    # Keep a visible character-sized gap between the two panels at print size.
    # Leave room for the subscript of the x-axis label; do not cut it off.
    fig.subplots_adjust(left=0.105, right=0.985, top=0.97, bottom=0.18, wspace=0.24)

    selected = points[points["M"] == 1024].copy()
    load_groups, load_colors = ordered_load_groups(selected)
    for color, group in zip(load_colors, load_groups):
        group = group.sort_values("rho2")
        p_f = float(group["fail_rate"].mean())
        left.errorbar(
            group["rho2"],
            100.0 * group["observed_bias"],
            yerr=196.0 * group["mean_se"],
            fmt="o",
            color=color,
            ms=1.6,
            capsize=0.7,
            elinewidth=0.45,
            label=fr"$p_F={p_f:.2f}$",
        )
        x_grid = np.linspace(0.0, 1.0, 200)
        predicted = float(group["fitted_c_f"].iloc[0]) * (
            float(group["d"].iloc[0]) * x_grid - 1.0
        )
        left.plot(x_grid, 100.0 * predicted, color=color, lw=0.7)

    left.axhline(0.0, color=GRAY, lw=0.6, alpha=0.85)
    left.set_xlabel(r"imbalance $\theta_\Delta^2$", fontsize=label_fs, labelpad=0)
    left.set_ylabel(r"conditional-mean bias (%)", fontsize=label_fs, labelpad=3)
    left.tick_params(labelsize=tick_fs, axis="y", pad=4.5)
    left.tick_params(labelsize=tick_fs, axis="x", pad=2)
    left.set_xticks([0.0, 0.5, 1.0])
    left.grid(color=GRID, alpha=1.0, linewidth=0.45)
    left.spines[["top", "right"]].set_visible(False)
    left_legend_handles = [
        Line2D(
            [],
            [],
            color=color,
            marker="o",
            linestyle="-",
            linewidth=0.55,
            markersize=1.8,
            markeredgewidth=0,
            label=fr"$p_F={float(group['fail_rate'].mean()):.2f}$",
        )
        for color, group in zip(load_colors, load_groups)
    ]
    left_legend = left.legend(
        handles=left_legend_handles,
        frameon=False,
        fontsize=legend_fs,
        ncol=1,
        loc="upper left",
        labelspacing=0.30,
        handletextpad=0.35,
        borderaxespad=0.1,
    )
    plt.setp(left_legend.get_texts(), color=GRAY)
    left.text(
        0.69,
        0.98,
        "$M=1024$\n$k=3$",
        transform=left.transAxes,
        ha="center",
        va="top",
        fontsize=legend_fs,
        color=GRAY,
    )

    markers = {256: "o", 1024: "s", 4096: "^"}
    colors = {256: BLUE, 1024: GREEN, 4096: ORANGE}
    for m, group in points.groupby("M"):
        right.scatter(
            100.0 * group["loo_predicted_bias"],
            100.0 * group["observed_bias"],
            s=7,
            marker=markers[int(m)],
            color=colors[int(m)],
            alpha=0.62,
            edgecolors="white",
            linewidths=0.2,
            label=fr"$M={int(m)}$",
        )
    all_values = 100.0 * pd.concat(
        [points["loo_predicted_bias"], points["observed_bias"]], ignore_index=True
    )
    lower = float(all_values.min())
    upper = float(all_values.max())
    padding = 0.04 * (upper - lower)
    limits = (lower - padding, upper + padding)
    right.plot(limits, limits, color=GRAY, lw=0.75, ls="--", label="identity")
    right.set_xlim(limits)
    right.set_ylim(limits)
    right.set_xticks([0, 2, 4])
    right.set_xlabel("predicted bias (pp)", fontsize=label_fs, labelpad=0)
    right.set_ylabel("observed bias (pp)", fontsize=label_fs, labelpad=0)
    right.tick_params(labelsize=tick_fs, axis="y", pad=4.5)
    right.tick_params(labelsize=tick_fs, axis="x", pad=2)
    right.grid(color=GRID, alpha=1.0, linewidth=0.45)
    right.spines[["top", "right"]].set_visible(False)
    right_legend = right.legend(
        frameon=False,
        fontsize=legend_fs,
        loc="upper left",
        labelspacing=0.30,
        handletextpad=0.35,
        borderaxespad=0.1,
    )
    plt.setp(right_legend.get_texts(), color=GRAY)

    for extension in ("png", "svg"):
        fig.savefig(
            FIG_DIR / f"sign_projection_combined_candidate.{extension}", dpi=220
        )
        fig.savefig(
            FORMAL_FIG_DIR / f"sign_projection_combined.{extension}", dpi=220
        )
    plt.close(fig)


def main() -> None:
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)
    FORMAL_FIG_DIR.mkdir(parents=True, exist_ok=True)

    points = prepare_points()
    fits = write_group_fits(points)
    summary = write_summary(points)
    points.to_csv(RESULT_DIR / "validation_points.csv", index=False)
    fits.to_csv(RESULT_DIR / "validation_fits.csv", index=False)
    summary.to_csv(RESULT_DIR / "validation_summary.csv", index=False)
    plot_reader_candidate(points)
    plot_full_audit(points)
    plot_combined_candidate(points)

    print(summary.to_string(index=False))
    print(f"wrote {RESULT_DIR / 'validation_points.csv'}")
    print(f"wrote {RESULT_DIR / 'validation_fits.csv'}")
    print(f"wrote {RESULT_DIR / 'validation_summary.csv'}")
    print(f"wrote {FIG_DIR / 'sign_projection_mean_M1024.png'}")
    print(f"wrote {FIG_DIR / 'sign_projection_observed_vs_predicted.png'}")
    print(f"wrote {FIG_DIR / 'sign_projection_combined_candidate.png'}")
    print(f"wrote {FORMAL_FIG_DIR / 'sign_projection_combined.png'}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate the system-evaluation figure assets from checked-in summary data.

Covers the production-profile teaser (fig:prod-teaser), the observed
dirty-block rank heatmap (fig:rank), the P2 worker ablation, the per-profile
E2E comparison, and the KV time/payload ranges. This script deliberately does
not infer missing wire bytes, missing repetitions, or missing per-block row
counts.
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import Normalize
from matplotlib.lines import Line2D

DATA = Path(__file__).resolve().parents[1] / "data" / "paper"

_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--data-dir", type=Path, default=DATA,
                 help="directory holding the paper CSVs")
_ap.add_argument("--outdir", type=Path, default=Path(__file__).resolve().parent / "out")
_args, _ = _ap.parse_known_args()

DATA = _args.data_dir
OUT = _args.outdir
SOURCES = {
    "e27_quantiles": DATA / "production-d-quantiles.csv",
    "e27_duration": DATA / "production-duration-quantiles.csv",
    "e28_rank": DATA / "production-rank-buckets-anonymized.csv",
    "p2_ablation": DATA / "paper_p2_worker_ablation.csv",
    "profiles": DATA / "paper_profiles_e2e.csv",
    "kv_scaling": DATA / "paper_kv_scaling.csv",
}

COLORS = {
    "iblt": "#0072B2",
    "merkle": "#D55E00",
    "e27": "#009E73",
    "e28": "#CC79A7",
    "accent": "#E69F00",
    "grid": "#D9D9D9",
    "ink": "#222222",
}
plt.rcParams.update({
    "font.family": "DejaVu Sans",
    "font.size": 8.5,
    "axes.titlesize": 9,
    "axes.labelsize": 8.5,
    "xtick.labelsize": 7.5,
    "ytick.labelsize": 7.5,
    "legend.fontsize": 7.5,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.linewidth": 0.8,
    "savefig.facecolor": "white",
})


def save(fig: plt.Figure, stem: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT / f"{stem}.svg", bbox_inches="tight", pad_inches=0.02)
    fig.savefig(OUT / f"{stem}.png", dpi=220, bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def quantile_value(label: str) -> float:
    """Parse p10/p99.9/max without inventing an x-coordinate."""
    if label == "max":
        return 1.0
    return float(label[1:]) / 100.0


def style_axes(ax):
    ax.grid(axis="y", color=COLORS["grid"], linewidth=0.55, linestyle="--")
    ax.set_axisbelow(True)
    ax.tick_params(length=3, width=0.7)


def fig62() -> None:
    """E27 production workload figures + E28 observed dirty-block rank heatmap."""
    drows = read_csv(SOURCES["e27_quantiles"])
    trows = read_csv(SOURCES["e27_duration"])
    q = np.array([quantile_value(r["quantile"]) for r in drows])
    d = np.array([float(r["d_ms"]) for r in drows])
    cum_d = np.array([float(r["cum_pct_diffs"]) for r in drows])
    tq = np.array([quantile_value(r["quantile"]) for r in trows])
    dur = np.array([float(r["dur_s"]) for r in trows])
    cum_time = np.array([float(r["cum_pct_table_time"]) for r in trows])
    qpos = np.arange(len(drows))
    tqpos = np.arange(len(trows))

    rank_rows = read_csv(SOURCES["e28_rank"])
    display_order = ["P1", "P2", "P3", "T4", "T5"]
    by_profile = {r["profile"]: r for r in rank_rows}
    rank_rows = [by_profile[profile] for profile in display_order]
    labels = display_order
    buckets = [f"b{i:02d}" for i in range(100)]
    rates = np.array([[float(r[b]) / float(r["blocks"]) for b in buckets] for r in rank_rows])

    # Keep the two standalone E27 assets for the complete-paper views.  The
    # combined E27 teaser below uses a shared quantile axis and twin y-axes;
    # the E28 rank-position heatmap remains an independent asset.
    fig, ax1 = plt.subplots(figsize=(4.35, 3.15))
    ax1.plot(qpos, d, color=COLORS["e27"], marker="o", markersize=3.2, linewidth=1.4)
    ax1.set_yscale("log")
    ax1.set_ylabel(r"$d_{\mathrm{ms}}$ (log scale)")
    ax1.set_ylim(0.8, 8e7)
    ax1.set_title(r"Non-zero $d_{\mathrm{ms}}$ quantiles")
    ax1.set_xticks(qpos, [r["quantile"] for r in drows], rotation=35, ha="right")
    ax1.set_xlim(-0.25, len(drows) - 0.75)
    style_axes(ax1)
    fig.subplots_adjust(left=0.17, right=0.98, top=0.88, bottom=0.27)
    save(fig, "fig62a_difference_quantiles")

    fig, ax1b = plt.subplots(figsize=(4.35, 3.15))
    ax1b.plot(tqpos, cum_time, color=COLORS["accent"], marker="s", markersize=3.0, linewidth=1.35)
    ax1b.set_xlabel("workload quantile")
    ax1b.set_ylabel("cumulative table-time share (%)")
    ax1b.set_ylim(0, 106)
    ax1b.set_title("Cumulative table-time share")
    ax1b.set_xticks(tqpos, [r["quantile"] for r in trows], rotation=35, ha="right")
    ax1b.set_xlim(-0.25, len(trows) - 0.75)
    p95_index = [r["quantile"] for r in trows].index("p95")
    ax1b.scatter([p95_index], [18.78], s=20, facecolor="white", edgecolor=COLORS["accent"], zorder=4)
    ax1b.annotate("top 5% = 81.2%", xy=(p95_index, 18.78), xytext=(6.2, 67),
                  arrowprops={"arrowstyle": "-", "color": COLORS["accent"], "lw": 0.8},
                  fontsize=7.1, color=COLORS["ink"])
    style_axes(ax1b)
    fig.subplots_adjust(left=0.17, right=0.98, top=0.88, bottom=0.27)
    save(fig, "fig62b_cumulative_table_time")

    # The teaser combines the two E27 views on one shared quantile axis.
    # The quantities have different units and scales, so retain both readings
    # with a log-scaled left axis for d_ms and a linear right axis for cost.
    if [r["quantile"] for r in drows] != [r["quantile"] for r in trows]:
        raise ValueError("E27 quantile files must use the same quantile axis")
    fig, ax1 = plt.subplots(figsize=(4.4, 3.05))
    ax2 = ax1.twinx()
    left_line, = ax1.plot(
        qpos, d, color=COLORS["e27"], marker="o", markersize=3.4,
        linewidth=1.45, label=r"difference magnitude $d$",
    )
    right_line, = ax2.plot(
        qpos, cum_time, color=COLORS["accent"], marker="s", markersize=3.1,
        linewidth=1.4, label="cumulative table-time share",
    )
    ax1.set_yscale("log")
    ax1.set_ylabel(r"difference magnitude $d$ (log scale)", color=COLORS["e27"])
    ax1.set_ylim(0.8, 8e7)
    ax1.tick_params(axis="y", colors=COLORS["e27"])
    ax2.set_ylabel("cumulative table-time share (%)", color=COLORS["accent"])
    ax2.set_ylim(0, 106)
    ax2.tick_params(axis="y", colors=COLORS["accent"])
    ax2.spines["right"].set_visible(True)
    ax2.spines["right"].set_color(COLORS["accent"])
    ax1.set_xticks(qpos, [r["quantile"] for r in drows], rotation=35, ha="right")
    ax1.set_xlim(-0.25, len(drows) - 0.75)
    p95_index = [r["quantile"] for r in trows].index("p95")
    ax2.axvline(
        p95_index, color=COLORS["accent"], linestyle="--", linewidth=0.85, zorder=1,
    )
    ax2.annotate(
        "top 5%", xy=(p95_index, 18.78), xytext=(5.6, 12),
        arrowprops={"arrowstyle": "-", "color": COLORS["accent"], "lw": 0.8},
        fontsize=7.1, color=COLORS["ink"],
    )
    style_axes(ax1)
    ax2.grid(False)
    fig.suptitle("Production workload profile", fontsize=9, y=0.985)
    fig.subplots_adjust(left=0.16, right=0.90, top=0.88, bottom=0.27)
    save(fig, "fig62_production_profile")

    # Short-wide heatmap used by the paper page-budget version.
    fig, ax2 = plt.subplots(figsize=(5.05, 1.8))
    im = ax2.imshow(rates, aspect="auto", interpolation="nearest", cmap="YlGnBu", norm=Normalize(vmin=0, vmax=float(rates.max())))
    ax2.set_yticks(np.arange(len(labels)), labels)
    ax2.set_xticks([0, 19, 39, 59, 79, 99], ["0", "20", "40", "60", "80", "100"])
    ax2.grid(False)
    ax2.tick_params(length=2.5, width=0.6, labelsize=7.5)
    cbar = fig.colorbar(im, ax=ax2, fraction=0.046, pad=0.04)
    cbar.set_label("dirty share", fontsize=7)
    cbar.ax.tick_params(labelsize=6.5)
    fig.subplots_adjust(left=0.07, right=0.86, top=0.94, bottom=0.22)
    save(fig, "fig62c_dirty_block_rank")


def fig63a() -> None:
    rows = read_csv(SOURCES["p2_ablation"])
    workers = np.array([8, 16, 32, 64])
    networks = list(dict.fromkeys(row["network"] for row in rows))
    fig, axes = plt.subplots(1, 3, figsize=(7.0, 2.85), sharey=True)
    for ax, network in zip(axes, networks):
        for name, color, marker, ls in [("IBLT", COLORS["iblt"], "o", "-"), ("Merkle", COLORS["merkle"], "s", "--")]:
            selected = sorted(
                (row for row in rows if row["network"] == network and row["method"] == name),
                key=lambda row: int(row["workers"]),
            )
            arr = np.array(
                [[row["median_ms"], row["min_ms"], row["max_ms"]] for row in selected],
                dtype=float,
            ) / 1000.0
            med = arr[:, 0]
            lo = med - arr[:, 1]
            hi = arr[:, 2] - med
            ax.errorbar(workers, med, yerr=[lo, hi], color=color, marker=marker, linestyle=ls,
                        linewidth=1.35, markersize=3.5, capsize=2.2, label=name)
        ax.set_title(network)
        ax.set_xticks(workers)
        ax.set_xlabel("workers")
        ax.set_ylim(35, 112)
        style_axes(ax)
    axes[0].set_ylabel("E2E time (s)")
    axes[0].legend(frameon=False, loc="upper right")
    fig.tight_layout(w_pad=0.9)
    save(fig, "fig63a_p2_ablation")


def fig63b() -> None:
    rows = read_csv(SOURCES["profiles"])
    networks = list(dict.fromkeys(row["network"] for row in rows))
    fig, axes = plt.subplots(1, 3, figsize=(7.0, 2.9), sharey=True)
    profiles = ["P1", "P2", "P3"]
    x = np.arange(3)
    offsets = {"IBLT16": -0.12, "IBLT32": -0.04, "Merkle16": 0.04, "Merkle32": 0.12}
    for ax, network in zip(axes, networks):
        vals = {row["profile"]: row for row in rows if row["network"] == network}
        iblt16 = [float(vals[p]["iblt_w16_s"]) for p in profiles]
        iblt32 = [float(vals[p]["iblt_w32_s"]) for p in profiles]
        merkle16 = [float(vals[p]["merkle_w16_s"]) for p in profiles]
        merkle32 = [float(vals[p]["merkle_w32_s"]) for p in profiles]
        for xx, value in zip(x, iblt16):
            ax.plot(xx + offsets["IBLT16"], value, marker="o", markersize=4.2,
                    color=COLORS["iblt"], markerfacecolor=COLORS["iblt"],
                    markeredgewidth=0.8, linestyle="none")
        for xx, value in zip(x, iblt32):
            ax.plot(xx + offsets["IBLT32"], value, marker="o", markersize=4.2,
                    color=COLORS["iblt"], markerfacecolor="white",
                    markeredgewidth=1.1, linestyle="none")
        for xx, value in zip(x, merkle16):
            ax.plot(xx + offsets["Merkle16"], value, marker="s", markersize=4.0,
                    color=COLORS["merkle"], markerfacecolor=COLORS["merkle"],
                    markeredgewidth=0.8, linestyle="none")
        for xx, value in zip(x, merkle32):
            ax.plot(xx + offsets["Merkle32"], value, marker="s", markersize=4.0,
                    color=COLORS["merkle"], markerfacecolor="white",
                    markeredgewidth=1.1, linestyle="none")
        ax.set_title(network)
        ax.set_xticks(x, profiles)
        ax.set_yscale("log")
        ax.set_ylim(25, 2500)
        ax.set_xlabel("production profile")
        style_axes(ax)
    axes[0].set_ylabel("E2E time (s; log scale)")
    axes[0].legend(handles=[
        Line2D([0], [0], marker="o", color="none", markerfacecolor=COLORS["iblt"],
               markeredgecolor=COLORS["iblt"], markersize=4.5, label="IBLT W16"),
        Line2D([0], [0], marker="o", color="none", markerfacecolor="white",
               markeredgecolor=COLORS["iblt"], markersize=4.5, label="IBLT W32"),
        Line2D([0], [0], marker="s", color="none", markerfacecolor=COLORS["merkle"],
               markeredgecolor=COLORS["merkle"], markersize=4.2, label="Merkle W16"),
        Line2D([0], [0], marker="s", color="none", markerfacecolor="white",
               markeredgecolor=COLORS["merkle"], markersize=4.2, label="Merkle W32"),
    ], frameon=False, ncol=2, loc="upper left", fontsize=6.8)
    fig.tight_layout(w_pad=0.9)
    save(fig, "fig63b_profile_comparison")


def reported_range(ax, x, lo, hi, color, marker, label, offset=0.0):
    """Draw a reported min-max/range; never imply a confidence interval."""
    if lo is None:
        return
    xx = x if x == 0 else x * np.exp(offset)
    if lo == hi:
        ax.plot(xx, lo, marker=marker, color=color, markersize=4.3, linestyle="none", label=label)
        return
    ax.vlines(xx, lo, hi, color=color, linewidth=1.5, alpha=0.85)
    ax.plot(xx, lo, marker="_", color=color, markersize=7, linestyle="none")
    ax.plot(xx, hi, marker="_", color=color, markersize=7, linestyle="none")
    ax.plot(xx, (lo + hi) / 2, marker=marker, color=color, markersize=4.3, linestyle="none", label=label)


def static_series(ax, x, values, color, marker, label, offset=0.0, linestyle="-"):
    """Plot only supplied formal points; gaps remain gaps."""
    points = [(xx if xx == 0 else xx * np.exp(offset), value) for xx, value in zip(x, values)
              if value is not None and np.isscalar(value)]
    if not points:
        return
    px, py = zip(*points)
    ax.plot(px, py, color=color, linewidth=1.1, linestyle=linestyle, alpha=0.85)
    ax.plot(px, py, color=color, marker=marker, markersize=4.2, linestyle="none", label=label)


def fig64() -> None:
    rows = read_csv(SOURCES["kv_scaling"])
    cats = ["0", "1", "100", "500", "937", "~4e4"]
    x = np.array([0, 1, 100, 500, 937, 4e4], dtype=float)
    fig, axes = plt.subplots(2, 2, figsize=(7.15, 5.35), sharex=True,
                             gridspec_kw={"hspace": 0.46, "wspace": 0.28})

    def series(engine_pair, method, field):
        selected = {
            int(row["difference_size"]): row
            for row in rows
            if row["engine_pair"] == engine_pair and row["method"] == method
        }
        values = []
        for difference_size in (0, 1, 100, 500, 937, 40000):
            row = selected.get(difference_size)
            values.append(float(row[field]) if row and row[field] else None)
        return values

    rr_time = {
        "IBLT": series("Redis-to-Redis", "self-sizing", "e2e_s"),
        "Merkle": series("Redis-to-Redis", "Merkle", "e2e_s"),
    }
    pika_time = {
        "IBLT": series("Redis-to-Pika", "self-sizing", "e2e_s"),
        "Merkle": series("Redis-to-Pika", "Merkle", "e2e_s"),
    }
    rr_payload = {
        "IBLT": series("Redis-to-Redis", "self-sizing", "payload_mb"),
        "Merkle": series("Redis-to-Redis", "Merkle", "payload_mb"),
    }
    pika_payload = {
        "IBLT": series("Redis-to-Pika", "self-sizing", "payload_mb"),
        "Merkle": series("Redis-to-Pika", "Merkle", "payload_mb"),
    }
    pika_self_sizing_burst = next(
        row for row in rows
        if row["engine_pair"] == "Redis-to-Pika"
        and row["method"] == "self-sizing"
        and row["difference_size"] == "40000"
    )
    pika_payload["IBLT"][-1] = (
        float(pika_self_sizing_burst["payload_min_mb"]),
        float(pika_self_sizing_burst["payload_max_mb"]),
    )

    def add_time(ax, data, row_label, floor):
        # Connect only the static formal points.  The final burst point is an
        # independent diamond so it cannot be read as a continuation of d=937.
        static_series(ax, x[:-1], data["IBLT"][:-1], COLORS["iblt"], "o", "IBLT", offset=-0.08)
        static_series(ax, x[:-1], data["Merkle"][:-1], COLORS["merkle"], "s", "Merkle", offset=0.08)
        for values, color, offset in ((data["IBLT"], COLORS["iblt"], -0.08),
                                      (data["Merkle"], COLORS["merkle"], 0.08)):
            if np.isscalar(values[-1]):
                ax.plot(x[-1] * np.exp(offset), values[-1], marker="D", markersize=4.4,
                        color=color, linestyle="none")
        ax.set_yscale("log")
        ax.set_ylim(20, 700)
        ax.set_ylabel("E2E time (s)")
        ax.set_title(row_label)
        floor_color = "#666666"
        if isinstance(floor, tuple):
            ax.axhspan(*floor, color=floor_color, alpha=0.08, linewidth=0)
            ax.text(0.05, 0.88, "scan floor ~73-80 s", transform=ax.transAxes,
                    ha="left", fontsize=6.8, color=floor_color)
        else:
            ax.axhline(floor, color=floor_color, linewidth=0.9, linestyle=":", alpha=0.85)
            ax.text(0.98, 0.88, f"scan floor ~{floor:.1f} s", transform=ax.transAxes,
                    ha="right", fontsize=6.8, color=floor_color)
        style_axes(ax)

    def add_payload(ax, data, row_label):
        # As above, keep the dynamic companion visually separate from the
        # static d=0/1/100/500/937 series.
        static_series(ax, x[:-1], data["IBLT"][:-1], COLORS["iblt"], "o", "IBLT", offset=-0.08)
        static_series(ax, x[:-1], data["Merkle"][:-1], COLORS["merkle"], "s", "Merkle", offset=0.08)
        burst = data["IBLT"][-1]
        if isinstance(burst, tuple):
            reported_range(ax, x[-1], burst[0], burst[1], COLORS["iblt"], "D", "IBLT reported range", -0.08)
            ax.text(x[-1] * np.exp(-0.08), 6.1, "reported range", fontsize=6.6,
                    color=COLORS["iblt"], ha="right")
        elif np.isscalar(burst):
            ax.plot(x[-1] * np.exp(-0.08), burst, marker="D", markersize=4.4,
                    color=COLORS["iblt"], linestyle="none")
        if np.isscalar(data["Merkle"][-1]):
            ax.plot(x[-1] * np.exp(0.08), data["Merkle"][-1], marker="D", markersize=4.4,
                    color=COLORS["merkle"], linestyle="none")
        ax.set_yscale("symlog", linthresh=0.1)
        ax.set_ylim(0.01, 800)
        ax.set_ylabel("payload (MB)")
        ax.set_title(row_label)
        style_axes(ax)

    add_time(axes[0, 0], rr_time, "(a) Redis->Redis - E2E", 28.1)
    add_payload(axes[0, 1], rr_payload, "(b) Redis->Redis - cumulative payload")
    add_time(axes[1, 0], pika_time, "(c) Redis->Pika - E2E", (73, 80))
    add_payload(axes[1, 1], pika_payload, "(d) Redis->Pika - cumulative payload")
    for ax in axes.flat:
        # A strict log axis cannot display d=0.  symlog keeps zero at the
        # origin while placing the positive formal points on a log scale.
        ax.set_xscale("symlog", linthresh=1, linscale=1.0, base=10)
        ax.set_xlim(-1, 6e4)
    for ax in axes[1, :]:
        ax.set_xlabel("formal difference size d (symlog scale; d=0 at origin)")
        ax.set_xticks(x, cats)
        ax.tick_params(axis="x", labelrotation=45, labelsize=7.0)
        for tick in ax.get_xticklabels():
            tick.set_ha("right")
    axes[0, 0].legend(handles=[
        Line2D([0], [0], marker="o", color=COLORS["iblt"], linestyle="-", markersize=4.2, label="IBLT"),
        Line2D([0], [0], marker="s", color=COLORS["merkle"], linestyle="-", markersize=4.2, label="Merkle"),
    ], frameon=False, fontsize=6.8, loc="upper left")
    fig.text(0.5, 0.015,
             "Static formal points are connected within each series; the largest point is a dynamic companion, not a static comparison point.",
             ha="center", fontsize=6.8, color=COLORS["ink"])
    fig.subplots_adjust(left=0.08, right=0.98, top=0.95, bottom=0.15)
    save(fig, "fig64_mobile_ranges")


def main() -> None:
    missing = [str(p) for p in SOURCES.values() if not p.exists()]
    if missing:
        raise FileNotFoundError("Missing source files:\n" + "\n".join(missing))
    fig62()
    fig63a()
    fig63b()
    fig64()
    print("wrote figures to", OUT)


if __name__ == "__main__":
    main()

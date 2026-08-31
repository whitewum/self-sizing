#!/usr/bin/env python3
"""Generate the paper's W16 P1 versus P1-extra comparison from public data."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


NETWORKS = ("22.5ms/100Mbps", "22.5ms/10Mbps", "100ms/10Mbps")
NETWORK_SHORT = ("100 Mbps\n22.5 ms", "10 Mbps\n22.5 ms", "10 Mbps\n100 ms")
PLOT_WORKERS = 16
METHODS = (("self-sizing", f"IBLT (W{PLOT_WORKERS})"), ("Merkle", f"Merkle-style (W{PLOT_WORKERS})"))

PAPER_ORANGE = "#C85A17"
PAPER_GRAY = "#66717E"
PAPER_RULE = "#AAB2BC"
PAPER_GRID = "#DDE2E7"
PAPER_INK = "#222222"

plt.rcParams.update(
    {
        "font.family": "serif",
        "font.size": 8.5,
        "axes.labelsize": 8.5,
        "axes.titlesize": 9,
        "xtick.labelsize": 8,
        "ytick.labelsize": 8,
        "legend.fontsize": 7.5,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.linewidth": 0.6,
        "savefig.facecolor": "white",
        "figure.facecolor": "white",
        "mathtext.fontset": "cm",
    }
)


def style_axes(ax: plt.Axes) -> None:
    ax.grid(axis="y", color=PAPER_GRID, linewidth=0.45, linestyle="--")
    ax.grid(which="minor", axis="y", color=PAPER_GRID, linewidth=0.3, alpha=0.6)
    ax.set_axisbelow(True)
    ax.tick_params(length=3, width=0.6, color=PAPER_RULE)


def incomplete_marker(ax: plt.Axes, x: float, y0: float, y1: float) -> None:
    ax.plot([x, x], [y0, y1], color=PAPER_ORANGE,
            linestyle=(0, (2, 2)), linewidth=1.0)
    ax.plot(x, y1, marker="^", markersize=5.0, color=PAPER_ORANGE)
    right_edge = x > 1.7
    ax.annotate(
        "did not finish",
        xy=(x, y1),
        xytext=(-3 if right_edge else 3, 0),
        textcoords="offset points",
        va="center",
        ha="right" if right_edge else "left",
        fontsize=6.9,
        color=PAPER_ORANGE,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    with args.input.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    by_key = {(row["network"], int(row["workers"]), row["method"]): row for row in rows}

    fig, axes = plt.subplots(
        1, 2, figsize=(7.1, 3.0), sharey=True, gridspec_kw={"wspace": 0.07}
    )
    x = np.arange(len(NETWORKS))
    width = 0.32
    for ax, (csv_method, display_method) in zip(axes, METHODS):
        selected = [by_key[(network, PLOT_WORKERS, csv_method)] for network in NETWORKS]
        baseline = [float(row["p1_e2e_s"]) for row in selected]
        extra = [
            float(row["p1_extra_e2e_s"]) if row["p1_extra_e2e_s"] else None
            for row in selected
        ]

        ax.bar(
            x - width / 2, baseline, width, color=PAPER_GRAY,
            edgecolor=PAPER_INK, linewidth=0.45, label="P1",
        )
        complete_x = [index for index, value in enumerate(extra) if value is not None]
        complete_y = [extra[index] for index in complete_x]
        ax.bar(
            np.array(complete_x) + width / 2, complete_y, width,
            color=PAPER_ORANGE, alpha=0.82, hatch="///",
            edgecolor=PAPER_INK, linewidth=0.45, label="P1-extra",
        )
        for index, value in enumerate(baseline):
            ax.annotate(
                f"{value:g}", (index - width / 2, value), xytext=(0, 3),
                textcoords="offset points", ha="center", va="bottom", fontsize=6.4,
            )
        for index, value in enumerate(extra):
            if value is None:
                incomplete_marker(ax, index + width / 2, 2000, 30000)
            else:
                ax.annotate(
                    f"{value:g}", (index + width / 2, value), xytext=(0, 3),
                    textcoords="offset points", ha="center", va="bottom",
                    fontsize=6.4, color=PAPER_ORANGE,
                )
        ax.set_title(display_method, pad=5)
        ax.set_xticks(x, NETWORK_SHORT)
        ax.set_yscale("log")
        ax.set_ylim(500, 40000)
        style_axes(ax)

    axes[0].set_ylabel("P1 end-to-end time (s; log scale)")
    axes[0].legend(frameon=False, loc="upper left", ncol=2)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=240, bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

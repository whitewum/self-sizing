#!/usr/bin/env python3
"""
Parameterized synthetic-data generator for the public relational replay.

One code path generates the P1-P3 and P1-extra profiles: only PROFILE changes.
The heavy lifting is a set-based INSERT...SELECT run inside the database, not
row-by-row INSERTs.
Differences are injected along the frozen paper profile key-rank bands; string
keys are forced into an upper-case + digit domain.

Output:
  <out>/<p>-oracle.sql       source (Oracle): N baseline rows, no differences
  <out>/<p>-mysql.sql        target (MySQL): N baseline rows + banded modified / source-only / target-only
  <out>/<p>-groundtruth.sql  difference-truth checks + collation sort-consistency guard
  <out>/<p>-worst-source-only-append.sql  (worst case only) append script, does not truncate the table
  <out>/<p>-worst-source-only-delete.sql  (worst case only) rollback script for the injected Oracle rows

The P1-extra worst-case profile appends, roughly every 10,000 baseline rows, a
deterministic source-only record on the Oracle source. Each new numeric key uses
a free slot inside the corresponding jumpnum spacing bucket, mathematically
disjoint from the baseline keys and from every other new key; the script also
emits a collision pre-check and rollback SQL. The target does not append these
rows. Plain P1 can also enable the same mechanism temporarily with
``--single-sided-dirty-every N``.

Usage:
  python3 gen/gen_dataset.py p2 --out pilot-full          # medium scale, run first
  python3 gen/gen_dataset.py p2 --out smoke --scale 0.001  # 1/1000 smoke, validates the pipeline
  python3 gen/gen_dataset.py p1 --out p1-full             # 600M, run later / overnight
  python3 gen/gen_dataset.py p1-extra --out p1-extra-full

Note: this script only populates already-created tables. The public export uses
neutral schema / table names and contains no DDL from the original experiment
environment and no production identifiers.
"""
import argparse
import math
import sys

BLOCK = 100_000  # replay block size; block rank is approximately row rank


def generic_keys(count):
    return [f"KEY_{index:02d}" for index in range(1, count + 1)]


def generic_values(count):
    return [f"VALUE_{index:02d}" for index in range(1, count + 1)]


# ---------------------------------------------------------------------------
# Profiles: per table pair, the scale, keys, value columns, dirty-block bands and
# difference composition.
# String keys always use 'PREFIX' + LPAD(i) (upper-case letters + digits), so
# lex order == numeric order == row rank.
# ---------------------------------------------------------------------------
PROFILES = {
    "p2": {
        "oracle_table": "ARTIFACT_REPLAY.PROFILE_P2",
        "mysql_table": "artifact_replay.profile_p2",
        "rows": 31_034_507,
        # Three-column public key contract: string, integer, string.
        "pk_cols": generic_keys(3),
        "pk_types": ["string", "int", "string"],
        "modified_col": "VALUE_01",
        # The frozen reference has 313 logical blocks; the following runs contain
        # 24 dirty blocks (0-based). The full-scale dataset has 311 blocks, so
        # indices from the 313-block space cannot be copied directly;
        # dirty_block_indices() maps them by block-center rank.
        "reference_blocks": 313,
        "reference_dirty_runs": [
            (12, 12), (16, 24), (271, 271), (281, 281), (286, 286),
            (288, 288), (297, 297), (299, 299), (302, 303), (306, 311),
        ],
        # Summary of the same observation batch, kept for documentation / checks,
        # not used as input for placement generation.
        "bands": [
            (0.038, 0.080, 10),   # [3.8%,8.0%]
            (0.866, 0.866, 1),    # [86.6%] single point
            (0.898, 0.923, 3),    # [89.8%,92.3%]
            (0.949, 0.997, 10),   # [94.9%,99.7%]
        ],
        # Difference composition: d_ms=2*mod+src+tgt; d_row=mod+src+tgt.
        "modified": 650,
        "source_only": 42,
        "target_only": 0,
    },
    "p3": {
        "oracle_table": "ARTIFACT_REPLAY.PROFILE_P3",
        "mysql_table": "artifact_replay.profile_p3",
        # Frozen paper profile. Values from later snapshots must not be mixed in.
        "rows": 154_356_834,
        "pk_cols": generic_keys(3),
        "pk_types": ["int", "string", "int"],
        "modified_col": "VALUE_08",
        "insert_cols": None,       # filled below with INSERT_COLS_P3
        "row_fn": "p3",
        # KEY_01 is the Merkle order key. It is strictly increasing but
        # deliberately sparse; sequential IDs would make the benchmark an
        # artificial numeric-range best case.
        "key_mode": "jumpnum",
        "jump_spacing": 16,
        "jump_seed": 3,
        "item_prefix": "IT",
        "item_width": 7,
        "reference_blocks": 1538,
        "reference_dirty_runs": [(1492, 1492), (1507, 1507), (1522, 1522)],
        "modified": 0,
        "source_only": 11,
        "target_only": 7,
        "target_only_in_dirty": True,
    },
    "p1": {
        "oracle_table": "ARTIFACT_REPLAY.PROFILE_P1",
        "mysql_table": "artifact_replay.profile_p1",
        "rows": 609_671_577,
        # One-column public numeric-key contract.
        "pk_cols": generic_keys(1),
        "pk_types": ["int"],
        # Parallel P1 path markers: column set and row generator (see INSERT_COLS_P1 / row_columns_p1).
        "insert_cols": None,        # placeholder, filled with INSERT_COLS_P1 after module load
        "row_fn": "p1",
        "modified_col": "VALUE_11",
        # Sparse numeric jump key: KEY_01 = spacing*i + jitter, strictly increasing, randomly gapped, reproducible.
        # A numeric key has no collation constraint; with_key projects v ahead of time, and row_columns_p1 reads v directly.
        "key_mode": "jumpnum",
        "jump_spacing": 16,
        "jump_seed": 1,
        # Reference block space is 6096; dirty_block_indices maps by block-center rank.
        "reference_blocks": 6096,
        "reference_dirty_runs": [
            (3551,3551), (3554,3554), (3577,3577), (3586,3586), (3597,3597), (3602,3604), (3609,3609), (3631,3632),
            (3636,3637), (3640,3640), (3642,3645), (3647,3648), (3650,3652), (3654,3658), (3660,3660), (3662,3665),
            (3667,3667), (3671,3671), (3673,3673), (3675,3675), (3677,3679), (3682,3683), (3688,3694), (3697,3702),
            (3704,3715), (3717,3721), (3725,3726), (3728,3728), (3730,3740), (3742,3742), (3744,3759), (3762,3769),
            (3771,3784), (3786,3797), (3799,3828), (3830,3836), (3838,3847), (3849,3863), (3865,3865), (3867,3872),
            (3874,3883), (3885,3886), (3888,3888), (3890,3890), (3892,3898), (3900,3904), (3906,3906), (3908,3910),
            (3912,3912), (3915,3915), (3917,3931), (3933,3938), (3940,3942), (3950,3950), (3953,3953), (3960,3960),
            (3963,3964), (3969,3969), (3981,3981), (3990,3990), (3994,3994), (5160,5168), (5170,5189), (5191,5204),
            (5206,5235), (5237,5239), (5241,5243), (5245,5249), (5251,5255), (5257,5270), (5272,5275), (5277,5281),
            (5283,5283), (5285,5285), (5287,5291), (5295,5296), (5298,5298), (5300,5301), (5303,5304), (5308,5312),
            (5315,5316), (5318,5320), (5322,5325), (5329,5334), (5336,5341), (5343,5354), (5356,5358), (5360,5363),
            (5365,5365), (5368,5368), (5370,5372), (5374,5378), (5380,5380), (5382,5392), (5394,5407), (5409,5410),
            (5413,5414), (5416,5416), (5420,5424), (5427,5429), (5432,5438), (5440,5440), (5442,5442), (5446,5447),
            (5450,5451), (5456,5456), (5458,5458), (5460,5460), (5462,5462), (5465,5468), (5471,5471), (5473,5474),
            (5477,5481), (5483,5483), (5485,5490), (5494,5495), (5497,5497), (5499,5499), (5502,5503), (5506,5508),
            (5510,5511), (5513,5515), (5520,5522), (5524,5524), (5527,5527), (5531,5534), (5536,5537), (5539,5540),
            (5542,5543), (5546,5550), (5553,5557), (5559,5560), (5566,5570), (5572,5576), (5578,5578), (5580,5580),
            (5582,5584), (5591,5591), (5594,5594), (5596,5596), (5601,5607), (5609,5609), (5611,5611), (5616,5617),
            (5619,5620), (5622,5622), (5627,5629), (5637,5637), (5640,5640), (5643,5643), (5645,5646), (5648,5648),
            (5651,5651), (5654,5654), (5657,5658), (5662,5664), (5668,5669), (5673,5674), (5679,5681), (5683,5683),
            (5686,5688), (5690,5693), (5705,5705), (5728,5728), (5734,5736), (5740,5740), (5743,5744), (5754,5754),
            (5760,5760), (5817,5817), (5825,5825), (5843,5843), (5851,5851), (5858,5858), (5867,5867), (5871,5871),
            (5874,5874), (5892,5892), (5900,5900), (5933,5933), (5943,5943), (5956,5957), (5960,5960), (5992,5992),
            (6002,6002), (6004,6004), (6021,6021), (6049,6049), (6054,6054), (6056,6056), (6068,6068), (6075,6076),
            (6082,6082), (6091,6092), (6094,6094),
        ],
        # Frozen difference composition: modified 44,333 + source-only 3,174 -> d_ms=91,840.
        "modified": 44333,
        "source_only": 3174,
        "target_only": 0,
    },
}


def digits_seq(dialect, n_rows, start=0, end=None):
    """Generate a scalable tally for i in [start, end) (no recursive CTE).

    When loading in segments, the range predicate is pushed down into the tally,
    so each INSERT does not first materialize the full N rows.
    """
    if end is None:
        end = n_rows
    if not (0 <= start < end <= n_rows):
        raise ValueError(f"invalid tally range [{start}, {end}) for {n_rows}")
    d = max(1, math.ceil(math.log10(max(end, 10))))  # decimal digits needed
    row = "SELECT 0 n{f} UNION ALL " + " UNION ALL ".join(
        f"SELECT {k} n{{f}}" for k in range(1, 10))
    suffix = " FROM dual" if dialect == "oracle" else ""
    tables, expr = [], []
    for k in range(d):
        tables.append("(" + row.format(f=suffix) + f") t{k}")
        expr.append(f"t{k}.n * {10**k}")
    i = " + ".join(expr)
    # Oracle SQL*Plus rejects a single line longer than 2499 characters by
    # default. Split each decimal table of the tally onto its own line so the 8
    # cross joins of large-scale P2 are not truncated; MySQL accepts this format too.
    predicates = [f"{i} < {end}"]
    if start:
        predicates.append(f"{i} >= {start}")
    return (f"(SELECT {i} AS i\n"
            f" FROM " + ",\n      ".join(tables) +
            f"\n WHERE {' AND '.join(predicates)})")


def q(dialect, col):
    return f'"{col.upper()}"' if dialect == "oracle" else f"`{col.lower()}`"


BASE36_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
JUMP_MULT = 2654435761         # Knuth multiplicative hash constant
JUMP_SEED_STRIDE = 2246822519   # makes different --jump-seed values land on different jitter phases
JUMP_MOD_PRIME = 2147483647     # Mersenne prime 2^31-1: modulo it first to scatter the low bits, then modulo spacing


def jump_value_expr(i, spacing, seed):
    """Strictly increasing, randomly spaced (adjacent gap in [1, 2*spacing-1]),
    reproducible key number:
    scramble = MOD(i*MULT + seed*STRIDE, PRIME); jitter = MOD(scramble, spacing);
    v(i) = spacing*i + jitter. Modulo a large prime first, then modulo spacing,
    to avoid linear degeneration into an arithmetic sequence when spacing is a
    power of 2; pure arithmetic on i -> still set-based; adjacent gap always >= 1
    -> unique and monotone overall."""
    scramble = f"MOD({i} * {JUMP_MULT} + {seed * JUMP_SEED_STRIDE}, {JUMP_MOD_PRIME})"
    jitter = f"MOD({scramble}, {spacing})"
    return f"({spacing} * {i} + {jitter})"


def jump_jitter_expr(i, spacing, seed):
    """The jitter component used by :func:`jump_value_expr` (kept as SQL)."""
    scramble = f"MOD({i} * {JUMP_MULT} + {seed * JUMP_SEED_STRIDE}, {JUMP_MOD_PRIME})"
    return f"MOD({scramble}, {spacing})"


def disjoint_jump_value_expr(i, spacing, seed):
    """A deterministic key in the same spacing bucket, but not the base key.

    The normal P1 key is ``spacing*i + jitter(i)``.  Choosing the next slot in
    that bucket (wrapping at ``spacing``) is provably distinct from the base
    row and from every other anchor bucket.  This lets the worst-case
    single-sided rows be distributed through the key order without guessing a
    large key offset or risking a collision with an existing ID.
    """
    jitter = jump_jitter_expr(i, spacing, seed)
    alternate = f"MOD(({jitter}) + 1, {spacing})"
    return f"({spacing} * {i} + {alternate})"


def base36_expr(dialect, value_sql, width):
    """Integer expression -> fixed-width base-36: [0-9A-Z], order-preserving,
    collation-safe, byte-identical across the two databases for the same v.
    MySQL uses the built-in CONV; Oracle has no CONV, so digits are expanded with
    SUBSTR -- value_sql should be a short column reference (e.g. 'v'), otherwise
    the jump expression is repeated once per width digit and approaches the
    SQL*Plus 2499-character line limit."""
    if dialect == "mysql":
        return f"LPAD(CONV({value_sql}, 10, 36), {width}, '0')"
    parts = []
    for k in range(width - 1, -1, -1):
        inner = value_sql if k == 0 else f"FLOOR({value_sql} / {36 ** k})"
        parts.append(f"SUBSTR('{BASE36_DIGITS}', MOD({inner}, 36) + 1, 1)")
    return " || ".join(parts)


def item_expr(dialect, p, i="i"):
    """KEY_01 string value. sequential = plain auto-increment; jump36 =
    reproducible random-jump base-36. Both stay in a fixed-width upper-case +
    digit domain, so Oracle binary order == MySQL general_ci order (guard holds)."""
    if p.get("key_mode") == "jump36":
        body = base36_expr(dialect, "v", p["item_width"])  # v is pre-projected by with_key()
        prefix = p.get("item_prefix", "IT")
        return f"'{prefix}' || {body}" if dialect == "oracle" else f"CONCAT('{prefix}', {body})"
    if dialect == "oracle":
        return f"'IT' || LPAD(TO_CHAR({i}), 10, '0')"
    return f"CONCAT('IT', LPAD({i}, 10, '0'))"


def with_key(inner, p):
    """Under jump36, project the jump key number v alongside the row ordinal i,
    so item_expr can base-36-encode it via the short column reference 'v';
    sequential is returned unchanged. i is still the consecutive row ordinal, and
    dirty blocks / injection / truth all stay based on i."""
    if p.get("key_mode") in ("jump36", "jumpnum"):
        v = jump_value_expr("i", p["jump_spacing"], p["jump_seed"])
        # The inner derived table must be aliased: MySQL requires it (ERROR 1248), Oracle accepts it too.
        # jump36: v is base-36-encoded into a string key; jumpnum (P1): v is used directly as the numeric KEY_01.
        return f"(SELECT i, {v} AS v FROM {inner} keysrc)"
    return inner


def token_expr(dialect, prefix, i, modulus, width):
    number = f"MOD({i},{modulus})"
    if dialect == "oracle":
        return f"'{prefix}' || LPAD(TO_CHAR({number}), {width}, '0')"
    return f"CONCAT('{prefix}', LPAD({number}, {width}, '0'))"


def row_columns(dialect, p, i="i", modified=False):
    """One row's 3 PK + 31 common business columns; nullable columns also get a deterministic NULL / non-NULL distribution."""
    cost = f"10 + MOD({i}, 100000) / 10000"
    if modified:
        cost = f"({cost}) + 1.2345"
    origin = (f"CASE MOD({i},3) WHEN 0 THEN 'CN' WHEN 1 THEN 'US' ELSE 'JP' END")
    supp_ind = f"CASE WHEN MOD({i},2)=0 THEN 'Y' ELSE 'N' END"
    create_dt = (f"DATE '2026-08-01' + MOD({i},86400)/86400" if dialect == "oracle"
                 else f"TIMESTAMP('2026-08-01 00:00:00') + INTERVAL MOD({i},86400) SECOND")
    update_dt = (f"DATE '2026-08-02' + MOD({i},86400)/86400" if dialect == "oracle"
                 else f"TIMESTAMP('2026-08-02 00:00:00') + INTERVAL MOD({i},86400) SECOND")
    round_lvl = (f"CASE MOD({i},6) WHEN 0 THEN 'C' WHEN 1 THEN 'L' WHEN 2 THEN 'P' "
                 "WHEN 3 THEN 'CL' WHEN 4 THEN 'LP' ELSE 'CLP' END")
    packing = f"CASE WHEN MOD({i},5)=0 THEN NULL WHEN MOD({i},2)=0 THEN 'HANG' ELSE 'FLAT' END"
    default_uop = f"CASE WHEN MOD({i},6)=0 THEN NULL WHEN MOD({i},2)=0 THEN 'EA' ELSE 'CA' END"
    tolerance_type = f"CASE WHEN MOD({i},4)=0 THEN NULL WHEN MOD({i},2)=0 THEN 'A' ELSE 'P' END"
    return [
        item_expr(dialect, p, i),
        f"100000000 + {i}",
        origin,
        cost,
        f"CASE WHEN MOD({i},5)=0 THEN NULL ELSE MOD({i},29)+1 END",
        f"CASE WHEN MOD({i},7)=0 THEN NULL ELSE MOD({i},11)+1 END",
        f"1 + MOD({i},160000)/10000",
        f"1 + MOD({i},80000)/10000",
        round_lvl,
        f"MOD({i},10001)/10000",
        f"MOD({i}+2500,10001)/10000",
        f"MOD({i}+5000,10001)/10000",
        f"MOD({i}+7500,10001)/10000",
        f"CASE WHEN MOD({i},8)=0 THEN NULL ELSE 1+MOD({i},50000)/10000 END",
        f"CASE WHEN MOD({i},9)=0 THEN NULL ELSE 10+MOD({i},90000)/10000 END",
        packing,
        supp_ind,
        f"CASE WHEN MOD({i},3)=0 THEN 'Y' ELSE 'N' END",
        default_uop,
        f"1 + MOD({i},10000)/10000",
        f"1 + MOD({i},20000)/10000",
        f"CASE WHEN MOD({i},3)=0 THEN NULL ELSE 'TYPE1' END",
        f"CASE WHEN MOD({i},3)=0 THEN NULL ELSE {token_expr(dialect, 'D', i, 1000, 3)} END",
        f"CASE WHEN MOD({i},4)=0 THEN NULL ELSE 'TYPE2' END",
        f"CASE WHEN MOD({i},4)=0 THEN NULL ELSE {token_expr(dialect, 'C', i, 1000, 3)} END",
        f"CASE WHEN MOD({i},5)=0 THEN NULL ELSE 'TYPE3' END",
        f"CASE WHEN MOD({i},5)=0 THEN NULL ELSE {token_expr(dialect, 'S', i, 1000, 3)} END",
        create_dt, update_dt, "'ARTIFACT-GEN'",
        f"CASE WHEN MOD({i},2)=0 THEN 'EA' ELSE 'KG' END",
        tolerance_type,
        f"CASE WHEN MOD({i},4)=0 THEN NULL ELSE MOD({i},10000)/10000 END",
        f"CASE WHEN MOD({i},6)=0 THEN NULL ELSE -MOD({i},5000)/10000 END",
    ]


INSERT_COLS = generic_keys(3) + generic_values(31)

VALUE_COLS = INSERT_COLS[3:]
VALUE_TYPES = [
    "decimal4", "int", "int", "decimal4", "decimal4", "string",
    "decimal4", "decimal4", "decimal4", "decimal4", "decimal4", "decimal4", "string",
    "string", "string", "string", "decimal4", "decimal4", "string", "string", "string",
    "string", "string", "string", "datetime_sec", "datetime_sec", "string", "string",
    "string", "decimal4", "decimal4",
]

if len(INSERT_COLS) != 34 or len(VALUE_COLS) != 31 or len(VALUE_TYPES) != 31:
    raise AssertionError("P2 common column/type contract must be 3 PK + 31 values")


# --------------------------------------------------------------------------
# P1: 1 neutralized numeric key + 43 neutralized value columns.
# --------------------------------------------------------------------------
INSERT_COLS_P1 = generic_keys(1) + generic_values(43)
VALUE_TYPES_P1 = [
    "string", "int", "int", "int", "string", "string", "int", "datetime_sec", "int",
    "string", "decimal4", "decimal4", "decimal4", "int", "int", "int", "decimal4",
    "decimal4", "string", "string", "decimal4", "decimal4", "datetime_sec", "string",
    "decimal4", "int", "int", "int", "string", "string", "datetime_sec", "datetime_sec",
    "int", "string", "string", "string", "string", "string", "string", "string",
    "string", "int", "string",
]
if len(INSERT_COLS_P1) != 44 or len(VALUE_TYPES_P1) != 43:
    raise AssertionError("P1 contract must be 1 key + 43 values")
PROFILES["p1"]["insert_cols"] = INSERT_COLS_P1
# A separate worst-case test profile: keeps P1's table shape / baseline key
# generation unchanged, and only enables by default one Oracle-only source-only
# dirty row roughly every 10,000 rows. A separate profile name keeps output
# files, ground truth and experiment records from being mislabeled as plain P1.
PROFILES["p1-extra"] = dict(PROFILES["p1"], single_sided_dirty_every=10000)


def row_columns_p1(dialect, p, i="i"):
    """P1's 44 column expressions (with the NULL distribution and 4 DATE columns).
    The first column KEY_01 = the jump key number v (pre-projected by with_key as a short column reference)."""
    def date_at(base):
        return (f"DATE '{base}' + MOD({i},86400)/86400" if dialect == "oracle"
                else f"TIMESTAMP('{base} 00:00:00') + INTERVAL MOD({i},86400) SECOND")
    def nz(expr, k):  # one NULL every k rows
        return f"CASE WHEN MOD({i},{k})=0 THEN NULL ELSE {expr} END"
    return [
        "v",
        token_expr(dialect, 'IT', i, 100000, 6),
        f"MOD({i},9000)+1",
        f"MOD({i},9000)+1",
        f"MOD({i},9000)+1",
        nz("'Y'", 4),
        f"CASE WHEN MOD({i},2)=0 THEN 'S' ELSE 'W' END",
        f"1000000 + MOD({i},9000000)",
        date_at('2026-08-01'),
        f"MOD({i},90)+1",
        nz("'A'", 5),
        f"1 + MOD({i},1000000)/10000",
        nz(f"MOD({i},50000000)/10000", 3),
        nz(f"MOD({i}+1000,50000000)/10000", 3),
        nz(f"MOD({i},1000000000)", 4),
        nz(f"MOD({i}+7,1000000000)", 4),
        nz(f"MOD({i}+13,1000000000)", 6),
        nz(f"MOD({i},9000000)/10000", 5),
        nz(f"MOD({i}+50,9000000)/10000", 5),
        "'ARTIFACT-GEN'",
        nz("'R'", 6),
        nz(f"MOD({i},250000)/10000", 7),
        nz(f"MOD({i},80000000)/10000", 3),
        date_at('2026-08-02'),
        nz(token_expr(dialect, 'PK', i, 100000, 5), 4),
        nz(f"MOD({i},40000000)/10000", 5),
        nz(f"MOD({i},1000000000)", 6),
        nz(f"MOD({i},100000)", 6),
        nz(f"MOD({i},400000)", 8),
        nz(token_expr(dialect, 'GRP', i, 100000, 5), 3),
        nz(token_expr(dialect, 'WR', i, 1000000, 6), 4),
        nz(date_at('2026-07-01'), 5),
        nz(date_at('2026-07-15'), 5),
        nz(f"MOD({i},400000)", 7),
        f"CASE MOD({i},3) WHEN 0 THEN 'N' WHEN 1 THEN 'Y' ELSE 'V' END",
        nz(token_expr(dialect, 'ERR', i, 1000, 3), 2),
        nz(token_expr(dialect, 'GG', i, 100000, 5), 3),
        nz(token_expr(dialect, 'GWR', i, 1000000, 6), 4),
        nz("'N'", 5),
        nz(token_expr(dialect, 'GEM', i, 1000, 3), 2),
        f"CASE MOD({i},4) WHEN 0 THEN NULL WHEN 1 THEN 'N' WHEN 2 THEN 'S' ELSE 'E' END",
        nz(token_expr(dialect, 'VDM', i, 1000, 3), 2),
        nz(f"MOD({i},1000000000)", 5),
        nz(token_expr(dialect, 'VT', i, 100, 2), 4),
    ]


# Physical expression order is key 1, key 3, key 2, followed by 22 values.
INSERT_COLS_P3 = ["KEY_01", "KEY_03", "KEY_02"] + generic_values(22)
VALUE_TYPES_P3 = [
    "int", "string", "string", "string", "int", "string", "decimal4", "decimal4",
    "decimal4", "decimal4", "int", "string", "int", "string", "datetime_sec", "string",
    "string", "decimal4", "decimal4", "string", "decimal4", "string",
]
if len(INSERT_COLS_P3) != 25 or len(VALUE_TYPES_P3) != 22:
    raise AssertionError("P3 contract must have 3 PK columns + 22 values")
PROFILES["p3"]["insert_cols"] = INSERT_COLS_P3


def p3_item_expr(dialect, p):
    """Render the sparse KEY_01 jump value as an ASCII-safe KEY_02."""
    body = base36_expr(dialect, "v", p["item_width"])
    return f"'IT' || {body}" if dialect == "oracle" else f"CONCAT('IT', {body})"


def row_columns_p3(dialect, p, i="i"):
    """P3 expressions in physical INSERT-column order.

    KEY_01 is the strictly increasing sparse value ``v`` projected by
    ``with_key``; it is intentionally not a sequential identifier.
    """
    def date_at(base):
        return (f"DATE '{base}' + MOD({i},86400)/86400" if dialect == "oracle"
                else f"TIMESTAMP('{base} 00:00:00') + INTERVAL MOD({i},86400) SECOND")

    ref_item = (f"'IT' || LPAD(TO_CHAR(MOD({i}+17,100000)), 6, '0')" if dialect == "oracle"
                else f"CONCAT('IT', LPAD(MOD({i}+17,100000), 6, '0'))")
    carton = (f"'CT' || LPAD(TO_CHAR(MOD({i},1000000)), 8, '0')" if dialect == "oracle"
              else f"CONCAT('CT', LPAD(MOD({i},1000000), 8, '0'))")
    user_id = (f"'USR' || LPAD(TO_CHAR(MOD({i},10000)), 4, '0')" if dialect == "oracle"
               else f"CONCAT('USR', LPAD(MOD({i},10000), 4, '0'))")
    return [
        "v", "1", p3_item_expr(dialect, p),
        f"100000 + MOD({i},900000)",
        f"CASE MOD({i},2) WHEN 0 THEN 'A' ELSE 'T' END",
        ref_item, carton, f"MOD({i},4)+1",
        f"CASE MOD({i},4) WHEN 0 THEN 'H' WHEN 1 THEN 'A' WHEN 2 THEN 'R' ELSE 'C' END",
        f"CASE WHEN MOD({i},5)=0 THEN NULL ELSE 1 + MOD({i},100000)/10000 END",
        f"10 + MOD({i},100000)/10000", f"20 + MOD({i}+7,100000)/10000",
        f"CASE WHEN MOD({i},7)=0 THEN NULL ELSE 1 + MOD({i},100000)/10000 END",
        f"CASE WHEN MOD({i},11)=0 THEN NULL ELSE 200000 + MOD({i},500000) END",
        f"CASE MOD({i},7) WHEN 0 THEN 'FC' WHEN 1 THEN 'FR' WHEN 2 THEN 'RE' "
        "WHEN 3 THEN 'RL' WHEN 4 THEN 'SL' WHEN 5 THEN 'NL' ELSE 'BL' END",
        f"1000 + MOD({i},900000)",
        f"CASE WHEN MOD({i},6)=0 THEN NULL ELSE {user_id} END",
        f"CASE WHEN MOD({i},5)=0 THEN NULL ELSE {date_at('2026-08-03')} END",
        f"CASE WHEN MOD({i},4)=0 THEN NULL ELSE 'Y' END",
        f"CASE WHEN MOD({i},6)=0 THEN NULL ELSE 'Y' END",
        f"CASE WHEN MOD({i},8)=0 THEN NULL ELSE 1 + MOD({i},100000)/10000 END",
        f"CASE WHEN MOD({i},9)=0 THEN NULL ELSE MOD({i},50000)/10000 END",
        f"CASE MOD({i},2) WHEN 0 THEN 'KG' ELSE 'LB' END",
        f"CASE WHEN MOD({i},10)=0 THEN NULL ELSE 2 + MOD({i},50000)/10000 END",
        f"CASE MOD({i},2) WHEN 0 THEN 'KG' ELSE 'LB' END",
    ]


def cols_of(p):
    """The current profile's INSERT column set (P1 uses insert_cols, P2 falls back to the global INSERT_COLS)."""
    return p.get("insert_cols") or INSERT_COLS


def row_values_of(dialect, p, i="i"):
    """Dispatch to the row generator by profile."""
    if p.get("row_fn") == "p1":
        return row_columns_p1(dialect, p, i)
    if p.get("row_fn") == "p3":
        return row_columns_p3(dialect, p, i)
    return row_columns(dialect, p)


def extra_source_only_count(p, n_rows):
    """Number of opt-in worst-case single-sided rows.

    ``single_sided_dirty_every=10000`` means one additional source-only row
    for each anchor i=0,10000,20000,... below ``n_rows``.  It is deliberately
    opt-in so existing P1/P2 datasets and their historical truth stay byte
    compatible.
    """
    every = int(p.get("single_sided_dirty_every", 0) or 0)
    if every <= 0:
        return 0
    return (n_rows + every - 1) // every


def emit_extra_source_only(dialect, p, n_rows):
    """Emit deterministic, distributed source-only rows for the P1 stress case.

    The rows are added only to the Oracle/source script.  For jumpnum P1,
    each new numeric key uses a free slot in the anchor's spacing bucket, so it
    cannot equal the baseline key for that anchor or any other anchor.
    A collision-count query is emitted before the INSERT as an executable
    guard; it should always return zero.
    """
    count = extra_source_only_count(p, n_rows)
    if not count:
        return ""
    if dialect != "oracle":
        return ""
    if p.get("row_fn") != "p1" or p.get("key_mode") != "jumpnum":
        raise ValueError("--single-sided-dirty-every currently requires the P1/P1-extra jumpnum profile")

    every = int(p["single_sided_dirty_every"])
    tbl = p["oracle_table"]
    key_col = p["pk_cols"][0]
    cols = ", ".join(q(dialect, c) for c in cols_of(p))
    seq = digits_seq(dialect, count)
    # Anchor rows are spread through the baseline key order.  The anchor i is
    # a short expression in the outer query, while v is chosen from the free
    # slot immediately after the normal jump jitter in that bucket.
    anchor = f"({every} * s.i)"
    extra_v = disjoint_jump_value_expr(anchor, p["jump_spacing"], p["jump_seed"])
    source = f"(SELECT {anchor} AS i, {extra_v} AS v FROM {seq} s)"
    vals = ", ".join(row_values_of(dialect, p))
    return (
        f"-- worst-case stress: append 1 Oracle-only dirty row roughly every {every} baseline rows; "
        f"{count} rows total, {key_col} uses a free slot in each jump bucket so it never hits a baseline key.\n"
        f"-- pre-check: should be 0; non-zero means the key construction or existing data has a collision, do not run the experiment.\n"
        f"SELECT COUNT(*) AS key_collision_candidates FROM {source} extra\n"
        f"WHERE EXISTS (SELECT 1 FROM {tbl} base WHERE base.\"{key_col}\" = extra.v);\n"
        f"INSERT /*+ APPEND */ INTO {tbl} ({cols})\n"
        f"SELECT {vals}\nFROM {source} extra;\nCOMMIT;\n"
    )


def emit_extra_source_only_delete(dialect, p, n_rows):
    """Emit a rollback script for exactly the generated Oracle-only keys."""
    count = extra_source_only_count(p, n_rows)
    if not count:
        return ""
    if dialect != "oracle" or p.get("row_fn") != "p1" or p.get("key_mode") != "jumpnum":
        raise ValueError("worst-case rollback currently requires the P1/P1-extra jumpnum profile")
    every = int(p["single_sided_dirty_every"])
    tbl = p["oracle_table"]
    key_col = p["pk_cols"][0]
    seq = digits_seq(dialect, count)
    anchor = f"({every} * s.i)"
    extra_v = disjoint_jump_value_expr(anchor, p["jump_spacing"], p["jump_seed"])
    source = f"(SELECT {anchor} AS i, {extra_v} AS v FROM {seq} s)"
    return (
        f"-- roll back the P1 worst-case injection: delete the {count} deterministically generated Oracle-only keys.\n"
        f"-- run only against a stress table generated with the same parameters; you can SELECT COUNT(*) first to preview.\n"
        f"ALTER SESSION SET CURRENT_SCHEMA = ARTIFACT_REPLAY;\n"
        f"DELETE FROM {tbl}\n"
        f"WHERE \"{key_col}\" IN (SELECT extra.v FROM {source} extra);\n"
        f"COMMIT;\n"
    )


def emit_extra_source_only_append(dialect, p, n_rows):
    """Emit an append-only stress script for an already loaded P1 baseline."""
    count = extra_source_only_count(p, n_rows)
    if not count:
        return ""
    if dialect != "oracle":
        raise ValueError("worst-case append currently targets the Oracle source only")
    return (
        "ALTER SESSION SET CURRENT_SCHEMA = ARTIFACT_REPLAY;\n"
        + emit_extra_source_only(dialect, p, n_rows)
    )


def modified_col_of(p):
    return p.get("modified_col", "VALUE_01")


def dirty_block_indices(p, total_blocks):
    """Map a frozen profile's exact dirty-block runs into the current dataset's logical block space.

    The mapping keeps each block center's relative rank position:
      target = floor((reference + 0.5) * total / reference_total)

    So the full-scale 311-block run yields 24 dirty blocks at the same band
    positions as the reference; under --scale, a small dataset naturally
    de-duplicates when several reference blocks map to the same block.
    """
    if "reference_dirty_runs" in p:
        reference_blocks = p["reference_blocks"]
        idx = set()
        for lo, hi in p["reference_dirty_runs"]:
            for reference_idx in range(lo, hi + 1):
                mapped = math.floor(
                    (reference_idx + 0.5) * total_blocks / reference_blocks
                )
                idx.add(max(0, min(total_blocks - 1, mapped)))
        return sorted(idx)

    # Compatibility for profiles that do not yet provide exact runs: still place points evenly across the summary bands.
    idx = set()
    for lo, hi, count in p["bands"]:
        b_lo = min(total_blocks - 1, int(lo * total_blocks))
        b_hi = min(total_blocks - 1, int(hi * total_blocks))
        if count <= 1 or b_hi <= b_lo:
            idx.add(b_lo)
            continue
        step = (b_hi - b_lo) / (count - 1)
        for k in range(count):
            idx.add(min(total_blocks - 1, b_lo + round(k * step)))
    return sorted(idx)


def dirty_predicate(p, n_rows):
    """i is inside a dirty block and its in-block offset < the per-block injection count -> it takes part in the difference. Returns (modified_pred, srconly_pred, mpb, spb, nblk)."""
    total_blocks = math.ceil(n_rows / BLOCK)
    dirty = dirty_block_indices(p, total_blocks)
    nblk = len(dirty)
    if not nblk:
        return "1=0", "1=0", 0, 0, 0

    # P3 has only 11 source-only and 7 target-only rows.  Round-up-per-block
    # would silently turn 11 into 12, so its predicates use an exact,
    # deterministic distribution across the mapped dirty blocks.
    if p.get("row_fn") == "p3":
        def distribute(total):
            base, rem = divmod(total, nblk)
            return [base + (1 if i < rem else 0) for i in range(nblk)]
        mod_counts = distribute(p["modified"])
        src_counts = distribute(p["source_only"])
        blk = f"FLOOR(i / {BLOCK})"
        off = f"MOD(i, {BLOCK})"
        mod_parts, src_parts = [], []
        for b, mc, sc in zip(dirty, mod_counts, src_counts):
            if mc:
                mod_parts.append(f"({blk} = {b} AND {off} < {mc})")
            if sc:
                src_parts.append(f"({blk} = {b} AND {off} >= {mc} AND {off} < {mc + sc})")
        modified = "(" + " OR ".join(mod_parts) + ")" if mod_parts else "1=0"
        srconly = "(" + " OR ".join(src_parts) + ")" if src_parts else "1=0"
        return modified, srconly, max(mod_counts), max(src_counts), nblk

    mpb = math.ceil(p["modified"] / nblk)          # modified rows per dirty block
    spb = math.ceil(p["source_only"] / nblk) if p["source_only"] else 0
    blocks = ",".join(str(b) for b in dirty)
    blk = f"FLOOR(i / {BLOCK})"
    off = f"MOD(i, {BLOCK})"
    in_dirty = f"{blk} IN ({blocks})"
    modified = f"({in_dirty} AND {off} < {mpb})"
    srconly = f"({in_dirty} AND {off} >= {mpb} AND {off} < {mpb + spb})" if spb else "1=0"
    return modified, srconly, mpb, spb, nblk


def p3_target_only_source(dialect, p, n_rows, count):
    """Return an inline (i,v) relation for P3 target-only rows.

    Each target-only key uses the alternate free slot in a high-rank jump
    bucket, keeping it inside one of the three dirty ranges instead of
    appending all target-only rows beyond the source's maximum key.
    """
    if count <= 0:
        return ""
    dirty = dirty_block_indices(p, math.ceil(n_rows / BLOCK))
    base, rem = divmod(count, len(dirty))
    counts = [base + (1 if i < rem else 0) for i in range(len(dirty))]
    rows = []
    ordinal = 0
    for b, amount in zip(dirty, counts):
        # Keep smoke rows valid too; for full P3 this is simply offset 100.
        block_end = min(n_rows, (b + 1) * BLOCK)
        offset = min(100, max(0, block_end - b * BLOCK - 1))
        for j in range(amount):
            anchor = b * BLOCK + offset + j
            # If a tiny smoke block runs out of room, wrap within that block;
            # the alternate jump slot remains distinct from its baseline key.
            if anchor >= block_end:
                anchor = b * BLOCK + (offset + j) % max(1, block_end - b * BLOCK)
            extra_v = disjoint_jump_value_expr(str(anchor), p["jump_spacing"], p["jump_seed"])
            select = f"SELECT {anchor} AS i, {extra_v} AS v"
            if dialect == "oracle":
                select += " FROM dual"
            rows.append(select)
            ordinal += 1
    return "(" + " UNION ALL ".join(rows) + ")"


def batch_ranges(n_rows, batches):
    """Cut the row ordinals i in [0, n_rows) into `batches` contiguous, even segments. batches<=1 -> one segment (old behavior)."""
    if batches <= 1:
        return [(0, n_rows)]
    step = math.ceil(n_rows / batches)
    return [(lo, min(lo + step, n_rows)) for lo in range(0, n_rows, step)]


def emit_source(dialect, p, n_rows):
    """Source: N baseline rows, no differences. Can be split into load_batches segments, each with its own COMMIT."""
    cols = ", ".join(q(dialect, c) for c in cols_of(p))
    vals = ", ".join(row_values_of(dialect, p))
    tbl = p["oracle_table"] if dialect == "oracle" else p["mysql_table"]
    hdr = ("ALTER SESSION SET CURRENT_SCHEMA = ARTIFACT_REPLAY;\n" if dialect == "oracle"
           else "USE artifact_replay;\n")
    insert = "INSERT /*+ APPEND */ INTO" if dialect == "oracle" else "INSERT INTO"
    ranges = batch_ranges(n_rows, p.get("load_batches", 1))
    body = ""
    for lo, hi in ranges:
        seq = digits_seq(dialect, n_rows, lo, hi)
        body += (f"{insert} {tbl} ({cols})\nSELECT {vals}\n"
                 f"FROM {with_key(seq, p)} src;\nCOMMIT;\n")
    if dialect == "oracle":
        body += emit_extra_source_only(dialect, p, n_rows)
    return (f"-- {p['oracle_table']} source baseline {n_rows} rows, {len(ranges)} batches; Artifact replay only.\n{hdr}"
            f"TRUNCATE TABLE {tbl};\n{body}")


def emit_target(dialect, p, n_rows):
    """Target: baseline (source-only removed, modified re-valued) + appended target-only."""
    insert_cols = cols_of(p)
    cols = ", ".join(q(dialect, c) for c in insert_cols)
    tbl = p["oracle_table"] if dialect == "oracle" else p["mysql_table"]
    hdr = ("USE artifact_replay;\n" if dialect == "mysql"
           else "ALTER SESSION SET CURRENT_SCHEMA = ARTIFACT_REPLAY;\n")
    mod_pred, src_pred, mpb, spb, nblk = dirty_predicate(p, n_rows)
    # the modified column uses a CASE: modified rows get +1.2345, the rest keep the original value.
    base_vals = row_values_of(dialect, p)
    cost_idx = insert_cols.index(modified_col_of(p))
    base_cost = base_vals[cost_idx]
    base_vals[cost_idx] = (
        f"CASE WHEN {mod_pred} THEN ({base_cost}) + 1.2345 "
        f"ELSE {base_cost} END")
    vals = ", ".join(base_vals)
    ranges = batch_ranges(n_rows, p.get("load_batches", 1))
    body = ""
    for lo, hi in ranges:
        seq = digits_seq(dialect, n_rows, lo, hi)
        body += (f"INSERT INTO {tbl} ({cols})\nSELECT {vals}\nFROM {with_key(seq, p)} base\n"
                 f"WHERE NOT {src_pred};\nCOMMIT;\n")   # exclude source-only
    # target-only: its own segment, keys in [n_rows, n_rows+tgt) -- not in the source.
    tgt = p["target_only"]
    if tgt:
        if p.get("target_only_in_dirty"):
            # P3 target-only rows must remain inside the high-rank dirty
            # ranges; putting them after MAX(KEY_01) would be outside the
            # Oracle-derived Merkle fence set.
            tseq = p3_target_only_source(dialect, p, n_rows, tgt)
            tvals = ", ".join(row_values_of(dialect, p))
            body += f"INSERT INTO {tbl} ({cols})\nSELECT {tvals}\nFROM {tseq} tgt;\nCOMMIT;\n"
        else:
            tseq = f"(SELECT n_rows_base + s.i AS i FROM {digits_seq(dialect, tgt)} s)".replace(
                "n_rows_base", str(n_rows))
            tvals = ", ".join(row_values_of(dialect, p))
            body += f"INSERT INTO {tbl} ({cols})\nSELECT {tvals}\nFROM {with_key(tseq, p)} tgt;\nCOMMIT;\n"
    return (f"-- {p['mysql_table']} target: baseline - source_only + modified re-valued + target_only, {len(ranges)} batches\n"
            f"-- per dirty block modified={mpb} source_only={spb}; dirty blocks={nblk}\n{hdr}"
            f"TRUNCATE TABLE {tbl};\n{body}")


def emit_groundtruth(p, n_rows):
    """Difference-truth checks + collation sort-consistency guard. Reports the actual injected counts after per-dirty-block rounding."""
    mod_pred, src_pred, mpb, spb, nblk = dirty_predicate(p, n_rows)
    dirty = dirty_block_indices(p, math.ceil(n_rows / BLOCK))
    if p.get("row_fn") == "p3":
        # P3 uses exact 11/7 totals rather than round-up-per-dirty-block.
        exp_mod = p["modified"]
        exp_src = p["source_only"]
    else:
        exp_mod = mpb * nblk              # actually injected (mpb rows per dirty block x nblk blocks)
        exp_src = spb * nblk
    extra_src = extra_source_only_count(p, n_rows)
    exp_src_total = exp_src + extra_src
    exp_tgt = p["target_only"]
    d_ms = 2 * exp_mod + exp_src_total + exp_tgt
    mod_col = modified_col_of(p)
    key_col = cols_of(p)[0]
    numeric_key = p.get("key_mode") == "jumpnum"  # P1 numeric key: no collation constraint
    guard = (
        f"-- ===== key-order consistency guard (numeric key {key_col}, no collation constraint) =====\n"
        f"-- a numeric key sorts identically on both sides; still, pull the first 10000 {key_col} from each side and md5-compare them, equal = pass.\n"
        if numeric_key else
        f"-- ===== collation sort-consistency guard =====\n"
        f"-- pull the leading key column from each side in that side's own sort order and compare row by row; any mismatch means the key domain has left the \"upper-case + digit\" constraint and you must stop.\n"
        f"-- Oracle:  SELECT {key_col} FROM {p['oracle_table']} ORDER BY {key_col};   -- binary order\n"
        f"-- MySQL:   SELECT {key_col.lower()} FROM {p['mysql_table']} ORDER BY {key_col.lower()}; -- _ci\n"
        f"-- expected: the two sequences are identical (over an upper-case + digit domain, _ci is byte-identical to binary).\n")
    extra_note = (
        f"-- worst-case extra Oracle-only dirty={extra_src} (1 per {p['single_sided_dirty_every']} baseline rows; "
        "KEY_01 is the deterministic free slot in each jump bucket)\n"
        if extra_src else ""
    )
    return f"""-- ===== ground truth (actual injected values, for a two-side count check) =====
-- nominal composition modified={p['modified']} source_only={p['source_only']} target_only={p['target_only']}
-- actually injected modified={exp_mod}  source_only={exp_src}  target_only={exp_tgt} (spread across {nblk} dirty blocks)
{extra_note}-- source_only total (incl. worst-case extra rows)={exp_src_total}
-- d_row={exp_mod + exp_src_total + exp_tgt}  d_ms={d_ms}
-- dirty blocks={nblk}  per block modified~={mpb} source_only~={spb}
-- actual dirty blocks (0-based, current dataset): {','.join(map(str, dirty))}
-- Reference profile block count: {p.get('reference_blocks', 'n/a')}
--
-- Oracle source rows = {n_rows} + {extra_src}
-- target rows = {n_rows} - {exp_src} + {exp_tgt}
-- modified hits: target {mod_col} differs from source (re-valued +1.2345); recover by i or join the source to check.
--
{guard}"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("profile", choices=sorted(PROFILES))
    ap.add_argument("--out", required=True, help="output directory")
    size = ap.add_mutually_exclusive_group()
    size.add_argument("--scale", type=float, default=1.0, help="row-count scale (minimum 100k, compatible with the old flow)")
    size.add_argument("--rows", type=int, help="explicit small-table row count; canonical smoke recommends 256")
    ap.add_argument("--modified", type=int, help="override the profile's modified count")
    ap.add_argument("--source-only", type=int, help="override the profile's source-only count")
    ap.add_argument("--target-only", type=int, help="override the profile's target-only count")
    ap.add_argument("--single-sided-dirty-every", type=int, metavar="N",
                    help="P1/P1-extra worst case: append 1 deterministic, unique dirty row on the Oracle side every N baseline rows")
    ap.add_argument("--oracle-table", help="override the Oracle table name (isolate the smoke table)")
    ap.add_argument("--mysql-table", help="override the MySQL table name (isolate the smoke table)")
    ap.add_argument("--key-mode", choices=["sequential", "jump36", "jumpnum"], default="sequential",
                    help="KEY_01 construction: sequential = plain auto-increment (default, compatible with old experiments); "
                         "jump36 = reproducible random-jump base-36 key (more realistic, defeats numeric-suffix range cheating)")
    ap.add_argument("--jump-spacing", type=int, default=16,
                    help="jump36 average spacing C (adjacent key gap in [1,2C-1]), default 16")
    ap.add_argument("--jump-seed", type=int, default=1, help="jump36 reproducible random seed, default 1")
    ap.add_argument("--load-batches", type=int, default=1,
                    help="split the load INSERT...SELECT into N segments, each with its own COMMIT (bounds MySQL undo/redo), default 1")
    args = ap.parse_args()

    p = dict(PROFILES[args.profile])
    if args.oracle_table: p["oracle_table"] = args.oracle_table
    if args.mysql_table: p["mysql_table"] = args.mysql_table
    for arg_name, profile_name in (("modified", "modified"),
                                   ("source_only", "source_only"),
                                   ("target_only", "target_only")):
        value = getattr(args, arg_name)
        if value is not None:
            if value < 0: ap.error(f"--{arg_name.replace('_', '-')} must be non-negative")
            p[profile_name] = value
    if args.rows is not None and args.rows < 1: ap.error("--rows must be positive")
    if args.load_batches < 1: ap.error("--load-batches must be >= 1")
    if args.single_sided_dirty_every is not None:
        if args.single_sided_dirty_every < 1:
            ap.error("--single-sided-dirty-every must be >= 1")
        if args.profile not in ("p1", "p1-extra"):
            ap.error("--single-sided-dirty-every currently requires profile p1 or p1-extra")
        p["single_sided_dirty_every"] = args.single_sided_dirty_every
    p["load_batches"] = args.load_batches
    n_rows = args.rows if args.rows is not None else max(BLOCK, int(p["rows"] * args.scale))
    if args.key_mode == "jump36":
        if args.jump_spacing < 2:
            ap.error("--jump-spacing must be >= 2 for jump36")
        p["key_mode"] = "jump36"
        p["jump_spacing"] = args.jump_spacing
        p["jump_seed"] = args.jump_seed
        # The fixed width must hold the largest jump key number (including the target-only tail), otherwise base-36 overflows / grows and breaks order preservation.
        max_i = n_rows - 1 + p.get("target_only", 0)
        max_v = args.jump_spacing * max_i + (args.jump_spacing - 1)
        width = 1
        while 36 ** width <= max_v:
            width += 1
        p["item_width"] = width
    import os
    os.makedirs(args.out, exist_ok=True)
    base = os.path.join(args.out, args.profile)

    with open(base + "-oracle.sql", "w") as f:
        f.write(emit_source("oracle", p, n_rows))
    with open(base + "-mysql.sql", "w") as f:
        f.write(emit_target("mysql", p, n_rows))
    with open(base + "-groundtruth.sql", "w") as f:
        f.write(emit_groundtruth(p, n_rows))
    extra_src = extra_source_only_count(p, n_rows)
    if extra_src:
        with open(base + "-worst-source-only-append.sql", "w") as f:
            f.write(emit_extra_source_only_append("oracle", p, n_rows))
        with open(base + "-worst-source-only-delete.sql", "w") as f:
            f.write(emit_extra_source_only_delete("oracle", p, n_rows))

    _, _, mpb, spb, nblk = dirty_predicate(p, n_rows)
    if p.get("row_fn") == "p3":
        act_dms = 2 * p["modified"] + p["source_only"] + extra_src + p["target_only"]
    else:
        act_dms = 2 * mpb * nblk + spb * nblk + extra_src + p["target_only"]
    nominal_dms = 2 * p["modified"] + p["source_only"] + p["target_only"] + extra_src
    print(f"[gen] profile={args.profile} rows={n_rows} "
          f"blocks={math.ceil(n_rows/BLOCK)} dirty_blocks={nblk} "
          f"injected_d_ms={act_dms} (nominal {nominal_dms})")
    suffix = ",worst-source-only-delete" if extra_src else ""
    print(f"[gen] wrote {base}-{{oracle,mysql,groundtruth{suffix}}}.sql")
    if p.get("key_mode") == "jump36":
        print(f"[gen] key_mode=jump36 spacing={p['jump_spacing']} seed={p['jump_seed']} "
              f"item_width={p['item_width']} -> KEY_01='{p.get('item_prefix', 'IT')}'+{p['item_width']}-digit base36"
                  f" (consecutive i drives dirty blocks / truth unchanged; key number v=C*i+jitter is strictly increasing with gaps)")
    if extra_src:
        print(f"[gen] worst_case_extra_source_only={extra_src} every={p['single_sided_dirty_every']} "
              "(Oracle-only, deterministic disjoint key slots)")
    if "reference_dirty_runs" in p:
        dirty = dirty_block_indices(p, math.ceil(n_rows / BLOCK))
        print(f"[gen] dirty_blocks={dirty} (frozen reference runs, center-rank normalized)")
    else:
        print("[gen] dirty_blocks uses the profile-bands compatibility fallback logic.")


if __name__ == "__main__":
    sys.exit(main())

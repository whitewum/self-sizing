#!/usr/bin/env python3
"""Generate de-identified Oracle/MySQL DDL for the public replay profiles.

The DDL preserves only the experiment-relevant key arity, canonical value
types, and column counts. Generic KEY_nn/VALUE_nn identifiers deliberately do
not reproduce production schema or business column names.
"""

from __future__ import annotations

import argparse
from pathlib import Path

try:
    from datagen import gen_dataset as gen
except ModuleNotFoundError:  # direct ``python datagen/generate_ddl.py`` execution
    import gen_dataset as gen


PROFILE_NAMES = ("p1", "p2", "p3")

ORACLE_TYPES = {
    "string": "VARCHAR2(128 CHAR)",
    "int": "NUMBER(19,0)",
    "decimal4": "NUMBER(24,4)",
    "datetime_sec": "TIMESTAMP(0)",
}
MYSQL_TYPES = {
    "string": "VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin",
    "int": "BIGINT",
    "decimal4": "DECIMAL(24,4)",
    "datetime_sec": "DATETIME(0)",
}


def value_types(profile_name: str) -> list[str]:
    if profile_name == "p1":
        return gen.VALUE_TYPES_P1
    if profile_name == "p3":
        return gen.VALUE_TYPES_P3
    return gen.VALUE_TYPES


def column_types(profile_name: str) -> dict[str, str]:
    profile = gen.PROFILES[profile_name]
    if len(profile["pk_cols"]) != len(profile["pk_types"]):
        raise ValueError(f"{profile_name}: key/type arity mismatch")
    types = dict(zip(profile["pk_cols"], profile["pk_types"]))
    types.update(zip(gen.generic_values(len(value_types(profile_name))),
                     value_types(profile_name)))
    return types


def quote(dialect: str, identifier: str) -> str:
    return f'"{identifier.upper()}"' if dialect == "oracle" else f'`{identifier.lower()}`'


def render_table(profile_name: str, dialect: str) -> str:
    profile = gen.PROFILES[profile_name]
    columns = gen.cols_of(profile)
    types = column_types(profile_name)
    sql_types = ORACLE_TYPES if dialect == "oracle" else MYSQL_TYPES
    definitions = []
    for column in columns:
        nullability = " NOT NULL" if column in profile["pk_cols"] else ""
        definitions.append(f"  {quote(dialect, column)} {sql_types[types[column]]}{nullability}")
    primary_key = ", ".join(quote(dialect, column) for column in profile["pk_cols"])
    definitions.append(f"  PRIMARY KEY ({primary_key})")
    body = ",\n".join(definitions)
    table = profile["oracle_table"] if dialect == "oracle" else profile["mysql_table"]
    suffix = "" if dialect == "oracle" else " ENGINE=InnoDB"
    return f"CREATE TABLE {table} (\n{body}\n){suffix};\n"


def render(dialect: str) -> str:
    preamble = [
        "-- Public synthetic replay DDL.",
        "-- KEY_nn/VALUE_nn names are de-identified; this is not production DDL.",
        "-- Value columns are nullable so every deterministic generator pattern is accepted.",
        "",
    ]
    if dialect == "oracle":
        preamble.extend([
            "-- Connect as (or set CURRENT_SCHEMA to) the neutral ARTIFACT_REPLAY user.",
            "ALTER SESSION SET CURRENT_SCHEMA = ARTIFACT_REPLAY;",
            "",
        ])
    else:
        preamble.extend([
            "CREATE DATABASE IF NOT EXISTS artifact_replay",
            "  CHARACTER SET ascii COLLATE ascii_bin;",
            "USE artifact_replay;",
            "",
        ])
    tables = "\n".join(render_table(profile, dialect) for profile in PROFILE_NAMES)
    return "\n".join(preamble) + tables


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path(__file__).with_name("ddl"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for dialect in ("oracle", "mysql"):
        output = args.out / f"{dialect}.sql"
        output.write_text(render(dialect), encoding="utf-8")
        print(f"wrote {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

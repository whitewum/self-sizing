#!/usr/bin/env python3
"""Reproduce the MySQL SQL-pushdown shape on the public P1-equivalent schema.

The default single-query mode uses a multiply referenced CTE plus
derived_merge=off so the canonical fingerprint is evaluated once per row.
The temp-table mode exposes the earlier two-step implementation and its
CREATE TEMPORARY TABLES permission requirement.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import os
import re
import time
from pathlib import Path

PK = "key_01"
VALUE_COLUMNS = [f"value_{index:02d}" for index in range(1, 44)]
VALUE_TYPES = [
    "string", "int", "int", "int", "string", "string", "int",
    "datetime_sec", "int", "string", "decimal4", "decimal4", "decimal4",
    "int", "int", "int", "decimal4", "decimal4", "string", "string",
    "decimal4", "decimal4", "datetime_sec", "string", "decimal4", "int",
    "int", "int", "string", "string", "datetime_sec", "datetime_sec",
    "int", "string", "string", "string", "string", "string", "string",
    "string", "string", "int", "string",
]
if len(VALUE_COLUMNS) != len(VALUE_TYPES):
    raise AssertionError("P1 SQL profile must contain exactly 43 typed value columns")
SAFE_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def quote_identifier(identifier: str) -> str:
    parts = identifier.split(".")
    if not parts or any(not SAFE_IDENTIFIER.fullmatch(part) for part in parts):
        raise ValueError(f"unsafe SQL identifier: {identifier!r}")
    return ".".join(f"`{part}`" for part in parts)


def q(column: str) -> str:
    return quote_identifier(column)


def canonical_expr() -> str:
    pieces = [f"COALESCE(CAST({q(PK)} AS CHAR), '<NULL>')"]
    for column, value_type in zip(VALUE_COLUMNS, VALUE_TYPES):
        value = q(column)
        if value_type in {"int", "string"}:
            pieces.append(f"COALESCE(CAST({value} AS CHAR), '<NULL>')")
        elif value_type == "decimal4":
            pieces.append(
                "COALESCE(CAST(CAST(ROUND("
                + value
                + ",4)*10000 AS DECIMAL(38,0)) AS CHAR), '<NULL>')"
            )
        elif value_type == "datetime_sec":
            pieces.append(
                f"COALESCE(DATE_FORMAT({value}, '%Y-%m-%d %H:%i:%s'), '<NULL>')"
            )
        else:
            raise ValueError(value_type)
    return "LOWER(CONCAT(" + ", CHAR(31), ".join(pieces) + "))"


def fingerprint_expr() -> str:
    return (
        "CAST(CONV(SUBSTRING(MD5("
        + canonical_expr()
        + "),1,14),16,10) AS UNSIGNED)"
    )


def positions_sql(table: str, lower: int, upper: int, buckets: int) -> str:
    return f"""SELECT {q(PK)} AS id, fp,
       (fp % {buckets}) AS p1,
       (FLOOR(fp / 1048576) % {buckets}) AS p2,
       (FLOOR(fp / 1099511627776) % {buckets}) AS p3,
       CAST(MOD((CAST(fp AS DECIMAL(65,0)) * CAST(fp AS DECIMAL(65,0)))
                + CAST(fp AS DECIMAL(65,0)) * 1000003
                + 1442695040888963407,
                9223372036854775807) AS UNSIGNED) AS chk
FROM (SELECT {q(PK)}, {fingerprint_expr()} AS fp
      FROM {quote_identifier(table)}
      WHERE {q(PK)} >= {lower} AND {q(PK)} < {upper}) base"""


def aggregate_sql(source: str) -> str:
    return f"""SELECT bucket_id, COUNT(*) AS cell_count,
       BIT_XOR(fp) AS fp_xor, BIT_XOR(id) AS id_xor,
       BIT_XOR(chk) AS checksum_xor
FROM (
  SELECT p1 AS bucket_id, id, fp, chk FROM {source}
  UNION ALL
  SELECT p2 AS bucket_id, id, fp, chk FROM {source} WHERE p2 <> p1
  UNION ALL
  SELECT p3 AS bucket_id, id, fp, chk FROM {source}
    WHERE p3 <> p1 AND p3 <> p2
) expanded
GROUP BY bucket_id
ORDER BY bucket_id"""


def single_query_sql(table: str, lower: int, upper: int, buckets: int) -> str:
    return (
        "WITH pos AS (\n"
        + positions_sql(table, lower, upper, buckets)
        + "\n)\n"
        + aggregate_sql("pos")
    )


def temp_table_sql(table: str, lower: int, upper: int, buckets: int) -> str:
    return "CREATE TEMPORARY TABLE t_pos AS\n" + positions_sql(
        table, lower, upper, buckets
    )


def connect(args: argparse.Namespace):
    try:
        import pymysql
    except ImportError as exc:
        raise SystemExit(
            "PyMySQL is required; install code/sql-pushdown-mysql/requirements.txt"
        ) from exc
    password = os.environ.get(args.password_env)
    if password is None:
        raise SystemExit(f"missing password environment variable {args.password_env}")
    return pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=password,
        database=args.database,
        charset="utf8mb4",
        autocommit=True,
    )


def run_partition(args: argparse.Namespace, lower: int, upper: int):
    connection = connect(args)
    started = time.perf_counter()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION optimizer_switch='derived_merge=off'")
            if args.tmp_table_size:
                cursor.execute(
                    "SET SESSION tmp_table_size=%s", (args.tmp_table_size,)
                )
                cursor.execute(
                    "SET SESSION max_heap_table_size=%s", (args.tmp_table_size,)
                )
            if args.mode == "single-query":
                cursor.execute(single_query_sql(args.table, lower, upper, args.buckets))
                rows = list(cursor.fetchall())
            else:
                cursor.execute("DROP TEMPORARY TABLE IF EXISTS t_pos")
                cursor.execute(temp_table_sql(args.table, lower, upper, args.buckets))
                cursor.execute(aggregate_sql("t_pos"))
                rows = list(cursor.fetchall())
                cursor.execute("DROP TEMPORARY TABLE t_pos")
        return rows, time.perf_counter() - started
    finally:
        connection.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", required=True)
    parser.add_argument("--password-env", default="MYSQL_PASSWORD")
    parser.add_argument("--database", required=True)
    parser.add_argument("--table", required=True)
    parser.add_argument("--mode", choices=("single-query", "temp-table"),
                        default="single-query")
    parser.add_argument("--buckets", type=int, default=262144)
    parser.add_argument("--partitions", type=int, default=16)
    parser.add_argument("--tmp-table-size", type=int, default=0,
                        help="optional per-session byte limit; zero keeps server defaults")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--print-sql", action="store_true",
                        help="print one representative partition query before execution")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.buckets <= 0 or args.partitions <= 0:
        raise SystemExit("buckets and partitions must be positive")
    quote_identifier(args.table)

    connection = connect(args)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"SELECT MIN({q(PK)}), MAX({q(PK)}), COUNT(*) "
                f"FROM {quote_identifier(args.table)}"
            )
            minimum, maximum, total_rows = cursor.fetchone()
    finally:
        connection.close()
    if minimum is None:
        raise SystemExit("input table is empty")

    stop = int(maximum) + 1
    span = stop - int(minimum)
    ranges = []
    for index in range(args.partitions):
        lower = int(minimum) + span * index // args.partitions
        upper = int(minimum) + span * (index + 1) // args.partitions
        if lower < upper:
            ranges.append((lower, upper))

    if args.print_sql:
        lower, upper = ranges[0]
        print(single_query_sql(args.table, lower, upper, args.buckets)
              if args.mode == "single-query"
              else temp_table_sql(args.table, lower, upper, args.buckets)
                   + "\n;\n" + aggregate_sql("t_pos"))

    started = time.perf_counter()
    merged: dict[int, list[int]] = {}
    partition_seconds = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(ranges)) as pool:
        futures = [pool.submit(run_partition, args, lower, upper)
                   for lower, upper in ranges]
        for future in concurrent.futures.as_completed(futures):
            rows, seconds = future.result()
            partition_seconds.append(seconds)
            for bucket, count, fp_xor, id_xor, checksum_xor in rows:
                values = [int(count), int(fp_xor), int(id_xor), int(checksum_xor)]
                current = merged.setdefault(int(bucket), [0, 0, 0, 0])
                current[0] += values[0]
                current[1] ^= values[1]
                current[2] ^= values[2]
                current[3] ^= values[3]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["bucket_id", "count", "fp_xor", "id_xor", "checksum_xor"])
        for bucket in sorted(merged):
            writer.writerow([bucket, *merged[bucket]])

    wall_seconds = time.perf_counter() - started
    print(f"rows={total_rows} mode={args.mode} partitions={len(ranges)} "
          f"buckets={args.buckets} wall_s={wall_seconds:.3f} "
          f"max_partition_s={max(partition_seconds):.3f} out={args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""User-space TCP link emulator; no CAP_NET_ADMIN or tc required.

It forwards arbitrary TCP bytes, applies a shared token-bucket in each
direction, and adds one propagation delay to the first chunk of a burst.
For the sidecar HTTP protocol, a request and its response therefore incur
approximately delay_up + delay_down, while a large body is streamed at rate.
"""

from __future__ import annotations

import argparse
import json
import re
import socket
import threading
import time
from dataclasses import dataclass
from dataclasses import field
from pathlib import Path


def parse_endpoint(value: str) -> tuple[str, int]:
    host, sep, port = value.rpartition(":")
    if not sep or not host or not port.isdigit():
        raise argparse.ArgumentTypeError(f"invalid endpoint: {value!r}")
    return host, int(port)


def parse_rate(value: str) -> float:
    m = re.fullmatch(r"\s*([0-9]+(?:\.[0-9]+)?)\s*(bit/s|bps|kbit|mbit|gbit|kbps|mbps|gbps)\s*", value, re.I)
    if not m:
        raise argparse.ArgumentTypeError(f"invalid rate: {value!r}")
    number = float(m.group(1))
    unit = m.group(2).lower()
    multiplier = {
        "bit/s": 1, "bps": 1,
        "kbit": 1_000, "kbps": 1_000,
        "mbit": 1_000_000, "mbps": 1_000_000,
        "gbit": 1_000_000_000, "gbps": 1_000_000_000,
    }[unit]
    return number * multiplier


def parse_duration_ms(value: str) -> float:
    m = re.fullmatch(r"\s*([0-9]+(?:\.[0-9]+)?)\s*(ms|s)\s*", value, re.I)
    if not m:
        raise argparse.ArgumentTypeError(f"invalid duration: {value!r}")
    number = float(m.group(1))
    return number if m.group(2).lower() == "ms" else number * 1000


class TokenBucket:
    def __init__(self, rate_bps: float, burst_bytes: int) -> None:
        if rate_bps <= 0 or burst_bytes <= 0:
            raise ValueError("rate and burst must be positive")
        self.rate_bytes = rate_bps / 8.0
        self.capacity = float(burst_bytes)
        self.tokens = self.capacity
        self.updated = time.monotonic()
        self.lock = threading.Lock()

    def consume(self, amount: int) -> None:
        remaining = float(amount)
        while remaining > 0:
            with self.lock:
                now = time.monotonic()
                self.tokens = min(self.capacity, self.tokens + (now - self.updated) * self.rate_bytes)
                self.updated = now
                take = min(remaining, self.tokens)
                self.tokens -= take
                remaining -= take
                wait = (remaining / self.rate_bytes) if remaining > 0 else 0.0
            if wait > 0:
                time.sleep(min(wait, 0.2))


@dataclass
class Counters:
    started: float
    connections: int = 0
    up_bytes: int = 0
    down_bytes: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock)

    def add(self, direction: str, count: int) -> None:
        with self.lock:
            if direction == "up":
                self.up_bytes += count
            else:
                self.down_bytes += count

    def snapshot(self) -> dict[str, object]:
        with self.lock:
            elapsed = max(time.monotonic() - self.started, 1e-9)
            return {
                "elapsed_s": round(elapsed, 3),
                "connections": self.connections,
                "up_bytes": self.up_bytes,
                "down_bytes": self.down_bytes,
                "aggregate_mbps": round((self.up_bytes + self.down_bytes) * 8 / elapsed / 1_000_000, 3),
            }


def pump(
    source: socket.socket,
    target: socket.socket,
    label: str,
    bucket: TokenBucket,
    delay_ms: float,
    idle_ms: float,
    chunk_bytes: int,
    counters: Counters,
) -> None:
    first = True
    last_send: float | None = None
    delay_s = delay_ms / 1000.0
    idle_s = idle_ms / 1000.0
    try:
        while True:
            data = source.recv(chunk_bytes)
            if not data:
                break
            now = time.monotonic()
            if first or (last_send is not None and now - last_send >= idle_s):
                if delay_s:
                    time.sleep(delay_s)
            bucket.consume(len(data))
            target.sendall(data)
            counters.add(label, len(data))
            first = False
            last_send = time.monotonic()
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            target.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(
    client: socket.socket,
    upstream_address: tuple[str, int],
    up_bucket: TokenBucket,
    down_bucket: TokenBucket,
    delay_up_ms: float,
    delay_down_ms: float,
    idle_ms: float,
    chunk_bytes: int,
    counters: Counters,
) -> None:
    upstream: socket.socket | None = None
    try:
        upstream = socket.create_connection(upstream_address, timeout=15)
        upstream.settimeout(None)
        client.settimeout(None)
        threads = [
            threading.Thread(target=pump, args=(client, upstream, "up", up_bucket, delay_up_ms, idle_ms, chunk_bytes, counters), daemon=True),
            threading.Thread(target=pump, args=(upstream, client, "down", down_bucket, delay_down_ms, idle_ms, chunk_bytes, counters), daemon=True),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
    except (ConnectionError, OSError):
        pass
    finally:
        for sock in (client, upstream):
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass


def stats_loop(counters: Counters, path: Path | None, interval: float) -> None:
    while True:
        time.sleep(interval)
        record = counters.snapshot()
        print("[link] " + json.dumps(record, ensure_ascii=False), flush=True)
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a", encoding="utf-8") as output:
                output.write(json.dumps(record, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", type=parse_endpoint, required=True, metavar="HOST:PORT")
    parser.add_argument("--upstream", type=parse_endpoint, required=True, metavar="HOST:PORT")
    parser.add_argument("--rate-up", type=parse_rate, default=parse_rate("40mbit"))
    parser.add_argument("--rate-down", type=parse_rate, default=parse_rate("40mbit"))
    parser.add_argument("--delay-up", type=parse_duration_ms, default=parse_duration_ms("11.25ms"))
    parser.add_argument("--delay-down", type=parse_duration_ms, default=parse_duration_ms("11.25ms"))
    parser.add_argument("--idle-ms", type=float, default=2.0,
                        help="delay the first chunk after this idle gap; default: 2")
    parser.add_argument("--burst-bytes", type=int, default=64 * 1024)
    parser.add_argument("--chunk-bytes", type=int, default=16 * 1024)
    parser.add_argument("--stats-file", type=Path)
    parser.add_argument("--stats-interval", type=float, default=5.0)
    args = parser.parse_args()

    listen = args.listen
    upstream = args.upstream
    counters = Counters(time.monotonic())
    up_bucket = TokenBucket(args.rate_up, args.burst_bytes)
    down_bucket = TokenBucket(args.rate_down, args.burst_bytes)

    threading.Thread(target=stats_loop, args=(counters, args.stats_file, args.stats_interval), daemon=True).start()
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(listen)
        server.listen(128)
        print(
            "[link] listening=" + f"{listen[0]}:{listen[1]} "
            + f"upstream={upstream[0]}:{upstream[1]} "
            + f"rate_up={args.rate_up / 1_000_000:g}Mbps "
            + f"rate_down={args.rate_down / 1_000_000:g}Mbps "
            + f"delay_up={args.delay_up:g}ms delay_down={args.delay_down:g}ms",
            flush=True,
        )
        while True:
            client, address = server.accept()
            with counters.lock:
                counters.connections += 1
            threading.Thread(
                target=handle,
                args=(client, upstream, up_bucket, down_bucket, args.delay_up, args.delay_down,
                      args.idle_ms, args.chunk_bytes, counters),
                daemon=True,
            ).start()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Receive jump events over UDP and press Space for the focused game window."""

from __future__ import annotations

import argparse
import csv
import json
import socket
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class JumpEvent:
    event: str
    sequence: int
    phone_time_ns: int
    vertical_acceleration: float | None = None


class JumpOutput(Protocol):
    def trigger(self) -> None:
        """Trigger one game jump."""


class DryRunOutput:
    def trigger(self) -> None:
        print("[dry-run] SPACE")


class PynputOutput:
    def __init__(self, hold_ms: int) -> None:
        try:
            from pynput.keyboard import Controller, Key
        except ImportError as exc:
            raise RuntimeError(
                "pynput is not installed. Run: pip install -r requirements.txt"
            ) from exc

        self._keyboard = Controller()
        self._space = Key.space
        self._hold_seconds = max(1, hold_ms) / 1000.0

    def trigger(self) -> None:
        self._keyboard.press(self._space)
        time.sleep(self._hold_seconds)
        self._keyboard.release(self._space)


def parse_jump_event(payload: bytes) -> JumpEvent:
    """Parse and validate one UTF-8 JSON UDP datagram."""
    try:
        decoded = payload.decode("utf-8")
        data = json.loads(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("payload is not valid UTF-8 JSON") from exc

    if not isinstance(data, dict):
        raise ValueError("payload must be a JSON object")
    if data.get("event") != "JUMP":
        raise ValueError("unsupported event")

    sequence = data.get("sequence")
    phone_time_ns = data.get("phone_time_ns")
    vertical_acceleration = data.get("vertical_acceleration")

    if not isinstance(sequence, int) or sequence < 0:
        raise ValueError("sequence must be a non-negative integer")
    if not isinstance(phone_time_ns, int) or phone_time_ns < 0:
        raise ValueError("phone_time_ns must be a non-negative integer")
    if vertical_acceleration is not None and not isinstance(
        vertical_acceleration, (int, float)
    ):
        raise ValueError("vertical_acceleration must be numeric or null")

    return JumpEvent(
        event="JUMP",
        sequence=sequence,
        phone_time_ns=phone_time_ns,
        vertical_acceleration=(
            float(vertical_acceleration)
            if vertical_acceleration is not None
            else None
        ),
    )


class CsvLogger:
    FIELDNAMES = (
        "pc_wall_time",
        "pc_receive_monotonic_ns",
        "source_ip",
        "source_port",
        "sequence",
        "phone_time_ns",
        "vertical_acceleration",
        "accepted",
        "reason",
    )

    def __init__(self, path: Path | None) -> None:
        self._file = None
        self._writer = None
        if path is None:
            return

        path.parent.mkdir(parents=True, exist_ok=True)
        new_file = not path.exists() or path.stat().st_size == 0
        self._file = path.open("a", encoding="utf-8", newline="")
        self._writer = csv.DictWriter(self._file, fieldnames=self.FIELDNAMES)
        if new_file:
            self._writer.writeheader()
            self._file.flush()

    def write(
        self,
        *,
        address: tuple[str, int],
        event: JumpEvent,
        receive_ns: int,
        accepted: bool,
        reason: str,
    ) -> None:
        if self._writer is None or self._file is None:
            return
        self._writer.writerow(
            {
                "pc_wall_time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "pc_receive_monotonic_ns": receive_ns,
                "source_ip": address[0],
                "source_port": address[1],
                "sequence": event.sequence,
                "phone_time_ns": event.phone_time_ns,
                "vertical_acceleration": event.vertical_acceleration,
                "accepted": int(accepted),
                "reason": reason,
            }
        )
        self._file.flush()

    def close(self) -> None:
        if self._file is not None:
            self._file.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0", help="UDP bind address")
    parser.add_argument("--port", type=int, default=5005, help="UDP port")
    parser.add_argument(
        "--cooldown-ms",
        type=int,
        default=250,
        help="receiver-side duplicate suppression window",
    )
    parser.add_argument(
        "--hold-ms",
        type=int,
        default=25,
        help="how long Space is held",
    )
    parser.add_argument(
        "--log",
        type=Path,
        default=Path("jump_events.csv"),
        help="CSV log path; pass an empty string to disable",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print accepted events without injecting keyboard input",
    )
    return parser


def run(args: argparse.Namespace) -> int:
    if not 1 <= args.port <= 65535:
        raise ValueError("port must be between 1 and 65535")
    if args.cooldown_ms < 0:
        raise ValueError("cooldown-ms cannot be negative")

    output: JumpOutput
    output = DryRunOutput() if args.dry_run else PynputOutput(args.hold_ms)
    log_path = args.log if str(args.log) else None
    logger = CsvLogger(log_path)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.host, args.port))
    cooldown_ns = args.cooldown_ms * 1_000_000
    last_accepted_ns = -(10**30)
    last_sequence_by_ip: dict[str, int] = {}

    print(
        f"Listening on udp://{args.host}:{args.port} "
        f"({'dry-run' if args.dry_run else 'keyboard enabled'})"
    )
    print("Focus Chrome's dinosaur game before jumping. Press Ctrl+C to stop.")

    try:
        while True:
            payload, address = sock.recvfrom(2048)
            receive_ns = time.monotonic_ns()

            try:
                event = parse_jump_event(payload)
            except ValueError as exc:
                print(f"Ignored invalid packet from {address[0]}: {exc}", file=sys.stderr)
                continue

            accepted = True
            reason = "accepted"
            previous_sequence = last_sequence_by_ip.get(address[0])

            if previous_sequence is not None and event.sequence <= previous_sequence:
                accepted = False
                reason = "duplicate_or_out_of_order_sequence"
            elif receive_ns - last_accepted_ns < cooldown_ns:
                accepted = False
                reason = "receiver_cooldown"

            last_sequence_by_ip[address[0]] = max(
                event.sequence, previous_sequence if previous_sequence is not None else -1
            )

            if accepted:
                output.trigger()
                last_accepted_ns = receive_ns

            logger.write(
                address=address,
                event=event,
                receive_ns=receive_ns,
                accepted=accepted,
                reason=reason,
            )

            acceleration = (
                f"{event.vertical_acceleration:.2f} m/s^2"
                if event.vertical_acceleration is not None
                else "n/a"
            )
            print(
                f"seq={event.sequence} source={address[0]} "
                f"vertical={acceleration} result={reason}"
            )
    except KeyboardInterrupt:
        print("\nStopped.")
        return 0
    finally:
        logger.close()
        sock.close()


def main() -> int:
    parser = build_parser()
    try:
        return run(parser.parse_args())
    except (OSError, RuntimeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

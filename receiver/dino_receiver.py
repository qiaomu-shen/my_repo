#!/usr/bin/env python3
"""Receive phone motion ACTION events and inject keyboard input."""

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


JUMP_ACTIONS = frozenset({"JUMP_UP", "JUMP_LEFT", "JUMP_RIGHT"})
CONTINUOUS_ACTIONS = frozenset({"SQUAT", "RUNNING"})
VALID_PHASES = frozenset({"TRIGGER", "START", "UPDATE", "STOP"})
LEGACY_SESSION_ID = "<legacy>"


@dataclass(frozen=True)
class ActionEvent:
    event: str
    action: str
    phase: str
    sequence: int
    phone_time_ns: int
    confidence: float = 1.0
    vertical_acceleration: float | None = None
    lateral_acceleration: float | None = None
    session_id: str | None = None


class ActionOutput(Protocol):
    def handle(self, event: ActionEvent) -> None:
        """Apply one accepted action event."""

    def release_all(self) -> None:
        """Release every held key."""


class DryRunOutput:
    def handle(self, event: ActionEvent) -> None:
        print(f"[dry-run] {event.action} {event.phase}")

    def release_all(self) -> None:
        pass


class PynputOutput:
    def __init__(self, hold_ms: int) -> None:
        try:
            from pynput.keyboard import Controller, Key
        except ImportError as exc:
            raise RuntimeError(
                "pynput is not installed. Run: pip install -r requirements.txt"
            ) from exc

        self._keyboard = Controller()
        self._key = Key
        self._hold_seconds = max(1, hold_ms) / 1000.0
        self._held_actions: dict[str, object] = {}

    def handle(self, event: ActionEvent) -> None:
        if event.action in JUMP_ACTIONS:
            self._trigger_jump(event.action)
            return

        key = self._key.down if event.action == "SQUAT" else self._key.up
        if event.phase in {"START", "UPDATE"}:
            if event.action not in self._held_actions:
                self._keyboard.press(key)
                self._held_actions[event.action] = key
        elif event.phase == "STOP":
            held_key = self._held_actions.pop(event.action, None)
            if held_key is not None:
                self._keyboard.release(held_key)

    def release_all(self) -> None:
        for key in tuple(self._held_actions.values()):
            try:
                self._keyboard.release(key)
            except Exception:
                pass
        self._held_actions.clear()

    def _trigger_jump(self, action: str) -> None:
        direction_key = {
            "JUMP_LEFT": self._key.left,
            "JUMP_RIGHT": self._key.right,
        }.get(action)
        if direction_key is not None:
            self._keyboard.press(direction_key)
        try:
            self._keyboard.press(self._key.space)
            time.sleep(self._hold_seconds)
            self._keyboard.release(self._key.space)
        finally:
            if direction_key is not None:
                self._keyboard.release(direction_key)


def parse_action_event(payload: bytes) -> ActionEvent:
    """Parse and validate one UTF-8 JSON UDP datagram."""
    try:
        decoded = payload.decode("utf-8")
        data = json.loads(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("payload is not valid UTF-8 JSON") from exc

    if not isinstance(data, dict):
        raise ValueError("payload must be a JSON object")

    event_type = data.get("event")
    if event_type == "JUMP":
        action = "JUMP_UP"
        phase = "TRIGGER"
    elif event_type == "ACTION":
        action = data.get("action")
        phase = data.get("phase")
    else:
        raise ValueError("unsupported event")

    if action not in JUMP_ACTIONS | CONTINUOUS_ACTIONS:
        raise ValueError("unsupported action")
    if phase not in VALID_PHASES:
        raise ValueError("unsupported phase")
    if action in JUMP_ACTIONS and phase != "TRIGGER":
        raise ValueError("jump actions require TRIGGER phase")
    if action in CONTINUOUS_ACTIONS and phase not in {"START", "UPDATE", "STOP"}:
        raise ValueError("continuous actions require START, UPDATE, or STOP phase")

    sequence = data.get("sequence")
    phone_time_ns = data.get("phone_time_ns")
    confidence = data.get("confidence", 1.0)
    vertical_acceleration = data.get("vertical_acceleration")
    lateral_acceleration = data.get("lateral_acceleration")
    session_id = data.get("session_id")

    if not isinstance(sequence, int) or sequence < 0:
        raise ValueError("sequence must be a non-negative integer")
    if not isinstance(phone_time_ns, int) or phone_time_ns < 0:
        raise ValueError("phone_time_ns must be a non-negative integer")
    if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
        raise ValueError("confidence must be between 0 and 1")
    if vertical_acceleration is not None and not isinstance(
        vertical_acceleration, (int, float)
    ):
        raise ValueError("vertical_acceleration must be numeric or null")
    if lateral_acceleration is not None and not isinstance(
        lateral_acceleration, (int, float)
    ):
        raise ValueError("lateral_acceleration must be numeric or null")
    if session_id is not None and (not isinstance(session_id, str) or not session_id):
        raise ValueError("session_id must be a non-empty string or null")

    return ActionEvent(
        event="ACTION",
        action=action,
        phase=phase,
        sequence=sequence,
        phone_time_ns=phone_time_ns,
        confidence=float(confidence),
        vertical_acceleration=(
            float(vertical_acceleration)
            if vertical_acceleration is not None
            else None
        ),
        lateral_acceleration=(
            float(lateral_acceleration)
            if lateral_acceleration is not None
            else None
        ),
        session_id=session_id,
    )


def parse_jump_event(payload: bytes) -> ActionEvent:
    """Backward-compatible alias for older imports."""
    return parse_action_event(payload)


def source_key(address: tuple[str, int], event: ActionEvent) -> tuple[str, str]:
    return address[0], event.session_id or LEGACY_SESSION_ID


def classify_event(
    *,
    address: tuple[str, int],
    event: ActionEvent,
    receive_ns: int,
    cooldown_ns: int,
    last_sequence_by_source: dict[tuple[str, str], int],
    last_trigger_ns_by_source: dict[tuple[str, str], int],
) -> tuple[bool, str]:
    key = source_key(address, event)
    previous_sequence = last_sequence_by_source.get(key)
    legacy_reset = (
        event.session_id is None
        and event.sequence == 0
        and previous_sequence is not None
        and previous_sequence > 0
    )
    if previous_sequence is not None and event.sequence <= previous_sequence and not legacy_reset:
        return False, "duplicate_or_out_of_order_sequence"

    last_sequence_by_source[key] = event.sequence
    if event.action in JUMP_ACTIONS:
        previous_trigger_ns = last_trigger_ns_by_source.get(key, -(10**30))
        if receive_ns - previous_trigger_ns < cooldown_ns:
            return False, "trigger_cooldown"
        last_trigger_ns_by_source[key] = receive_ns

    return True, "accepted_legacy_sequence_reset" if legacy_reset else "accepted"


class CsvLogger:
    FIELDNAMES = (
        "pc_wall_time",
        "pc_receive_monotonic_ns",
        "source_ip",
        "source_port",
        "session_id",
        "sequence",
        "phone_time_ns",
        "action",
        "phase",
        "confidence",
        "vertical_acceleration",
        "lateral_acceleration",
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
        event: ActionEvent,
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
                "session_id": event.session_id,
                "sequence": event.sequence,
                "phone_time_ns": event.phone_time_ns,
                "action": event.action,
                "phase": event.phase,
                "confidence": event.confidence,
                "vertical_acceleration": event.vertical_acceleration,
                "lateral_acceleration": event.lateral_acceleration,
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
        default=120,
        help="receiver-side cooldown for discrete jump triggers only",
    )
    parser.add_argument(
        "--state-timeout-ms",
        type=int,
        default=1500,
        help="release a continuous-action key when its heartbeat disappears",
    )
    parser.add_argument(
        "--hold-ms",
        type=int,
        default=25,
        help="how long the jump key is held",
    )
    parser.add_argument(
        "--log",
        default="action_events.csv",
        help="CSV log path; pass an empty string to disable",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print accepted events without injecting keyboard input",
    )
    return parser


def release_expired_actions(
    *,
    output: ActionOutput,
    active_action_ns: dict[str, int],
    now_ns: int,
    timeout_ns: int,
) -> list[str]:
    expired = [
        action
        for action, last_seen_ns in active_action_ns.items()
        if now_ns - last_seen_ns >= timeout_ns
    ]
    for action in expired:
        output.handle(
            ActionEvent(
                event="ACTION",
                action=action,
                phase="STOP",
                sequence=0,
                phone_time_ns=0,
            )
        )
        del active_action_ns[action]
    return expired


def run(args: argparse.Namespace) -> int:
    if not 1 <= args.port <= 65535:
        raise ValueError("port must be between 1 and 65535")
    if args.cooldown_ms < 0:
        raise ValueError("cooldown-ms cannot be negative")
    if args.state_timeout_ms <= 0:
        raise ValueError("state-timeout-ms must be positive")

    output: ActionOutput
    output = DryRunOutput() if args.dry_run else PynputOutput(args.hold_ms)
    log_path = Path(args.log) if args.log else None
    logger = CsvLogger(log_path)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.host, args.port))
    sock.settimeout(0.2)
    cooldown_ns = args.cooldown_ms * 1_000_000
    state_timeout_ns = args.state_timeout_ms * 1_000_000
    last_sequence_by_source: dict[tuple[str, str], int] = {}
    last_trigger_ns_by_source: dict[tuple[str, str], int] = {}
    active_action_ns: dict[str, int] = {}

    print(
        f"Listening on udp://{args.host}:{args.port} "
        f"({'dry-run' if args.dry_run else 'keyboard enabled'})"
    )
    print("Focus the controlled game window. Press Ctrl+C to stop.")

    try:
        while True:
            try:
                payload, address = sock.recvfrom(4096)
            except socket.timeout:
                expired = release_expired_actions(
                    output=output,
                    active_action_ns=active_action_ns,
                    now_ns=time.monotonic_ns(),
                    timeout_ns=state_timeout_ns,
                )
                for action in expired:
                    print(f"watchdog released {action}", file=sys.stderr)
                continue

            receive_ns = time.monotonic_ns()
            try:
                event = parse_action_event(payload)
            except ValueError as exc:
                print(f"Ignored invalid packet from {address[0]}: {exc}", file=sys.stderr)
                continue

            accepted, reason = classify_event(
                address=address,
                event=event,
                receive_ns=receive_ns,
                cooldown_ns=cooldown_ns,
                last_sequence_by_source=last_sequence_by_source,
                last_trigger_ns_by_source=last_trigger_ns_by_source,
            )

            if accepted:
                output.handle(event)
                if event.action in CONTINUOUS_ACTIONS:
                    if event.phase in {"START", "UPDATE"}:
                        active_action_ns[event.action] = receive_ns
                    elif event.phase == "STOP":
                        active_action_ns.pop(event.action, None)

            logger.write(
                address=address,
                event=event,
                receive_ns=receive_ns,
                accepted=accepted,
                reason=reason,
            )
            print(
                f"seq={event.sequence} source={address[0]} "
                f"action={event.action} phase={event.phase} "
                f"confidence={event.confidence:.2f} result={reason}"
            )
    except KeyboardInterrupt:
        print("\nStopped.")
        return 0
    finally:
        output.release_all()
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

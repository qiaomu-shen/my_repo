#!/usr/bin/env python3
"""Send one test JUMP datagram to the desktop receiver."""

from __future__ import annotations

import argparse
import json
import socket
import time


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5005)
    args = parser.parse_args()

    payload = {
        "event": "JUMP",
        "sequence": 0,
        "phone_time_ns": time.monotonic_ns(),
        "vertical_acceleration": None,
    }
    encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.sendto(encoded, (args.host, args.port))

    print(f"Sent JUMP to udp://{args.host}:{args.port}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

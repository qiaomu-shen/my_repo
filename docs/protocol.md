# UDP ACTION protocol

Version 0.2 uses one UTF-8 JSON object per UDP datagram.

```json
{
  "event": "ACTION",
  "action": "JUMP_LEFT",
  "phase": "TRIGGER",
  "confidence": 0.86,
  "session_id": "a6ed250c-78cf-4729-87a6-f4a943bdce5a",
  "sequence": 12,
  "phone_time_ns": 6723312345678,
  "vertical_acceleration": -8.91,
  "lateral_acceleration": -2.37
}
```

## Actions and phases

| Action | Valid phases | Meaning |
|---|---|---|
| `JUMP_UP` | `TRIGGER` | One confirmed vertical jump |
| `JUMP_LEFT` | `TRIGGER` | One confirmed left jump |
| `JUMP_RIGHT` | `TRIGGER` | One confirmed right jump |
| `SQUAT` | `START`, `UPDATE`, `STOP` | Continuous squat/duck state |
| `RUNNING` | `START`, `UPDATE`, `STOP` | Continuous running or high-knee state |

`UPDATE` is a heartbeat sent every 500 ms while a continuous action remains active. The receiver releases its key after 1500 ms without a heartbeat, so a stopped app or lost `STOP` packet cannot leave a key held forever.

## Fields

- `event`: always `ACTION` for version 0.2.
- `action`: one of the actions above.
- `phase`: event phase valid for that action.
- `confidence`: detector confidence in the inclusive range 0 to 1.
- `session_id`: random ID generated when the Android sender starts. It makes sequence numbers independent across app sessions.
- `sequence`: non-negative counter incremented for every transmitted datagram.
- `phone_time_ns`: Android sensor timestamp in the phone's monotonic time domain.
- `vertical_acceleration`: upward world-frame linear acceleration in m/s².
- `lateral_acceleration`: body-right linear acceleration in m/s²; negative means left.

The receiver still accepts the legacy version 0.1 `JUMP` payload and maps it to `JUMP_UP TRIGGER`.

## Reliability model

UDP is intentional: stale movement commands should not arrive late. The receiver rejects repeated or out-of-order sequence numbers per source IP and `session_id`. A short receiver cooldown applies only to discrete jump triggers and never suppresses continuous `START`, `UPDATE`, or `STOP` events.

Phone and computer monotonic clocks are not synchronized. Do not subtract `phone_time_ns` from the desktop receive timestamp to claim end-to-end latency; use synchronized clocks or high-frame-rate video.

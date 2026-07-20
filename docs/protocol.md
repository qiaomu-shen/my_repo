# UDP event protocol

V0 uses one JSON object per UDP datagram. UTF-8 encoding is required.

## JUMP

```json
{
  "event": "JUMP",
  "sequence": 12,
  "phone_time_ns": 6723312345678,
  "vertical_acceleration": 3.42
}
```

Fields:

- `event`: currently always `JUMP`.
- `sequence`: non-negative counter incremented by the Android app for every transmitted jump.
- `phone_time_ns`: Android sensor timestamp in the phone's monotonic time domain.
- `vertical_acceleration`: filtered upward linear acceleration in m/s² at the trigger.

## Reliability model

UDP is intentionally used for the first prototype because stale movement commands should not be retransmitted later. The sender emits one datagram per detected jump. The desktop receiver rejects repeated or out-of-order sequence numbers from the same source IP and applies its own short cooldown.

The phone and computer monotonic clocks are not assumed to be synchronized. Do not subtract `phone_time_ns` from the desktop receive timestamp to claim end-to-end latency. Use synchronized clocks or high-frame-rate video for that measurement.

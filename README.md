# Phone Motion Controller

An Android waist-mounted motion controller that recognizes jumps, running/high knees, and squats, then sends low-latency UDP actions to a desktop keyboard receiver.

```text
Accelerometer + gyroscope + rotation vector
  -> body/world coordinate conversion and calibration
  -> jump, running, and squat rule experts
  -> priority arbitration and continuous-action heartbeat
  -> UDP ACTION event
  -> desktop KeyDown / KeyUp
```

## Supported actions

| Physical action | Protocol output | Default desktop keys |
|---|---|---|
| Vertical jump | `JUMP_UP TRIGGER` | Space |
| Left jump | `JUMP_LEFT TRIGGER` | Left + Space |
| Right jump | `JUMP_RIGHT TRIGGER` | Right + Space |
| Squat | `SQUAT START/UPDATE/STOP` | hold/release Down |
| Running or high knees | `RUNNING START/UPDATE/STOP` | hold/release Up |

Running and high knees intentionally belong to the same continuous action class.

## Recognition structure

- The jump expert uses a `READY -> CANDIDATE -> FLIGHT -> LANDING -> SETTLING` state machine. It requires flight evidence before emitting a jump, and landing can never emit a second trigger.
- Jump direction comes from lateral takeoff impulse, not landing impact.
- The running expert looks for repeated, regular acceleration peaks over a 1.3 second window and applies start/stop hysteresis.
- The squat expert follows standing, descent, active bottom, rising, and standing phases using short-term vertical integration plus posture change.
- Arbitration priority is jump, then squat, then running. A confirmed jump stops lower-priority continuous actions.

These rule experts establish the runtime and dataset contract. A future causal TCN can replace their scoring logic without changing UDP, key-state safety, or action phases.

## Start the desktop receiver

Python 3.10 or newer is recommended.

```bash
cd receiver
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python dino_receiver.py --port 5005
```

Focus the controlled game window before moving. Test the receiver locally:

```bash
python send_test_jump.py --host 127.0.0.1 --port 5005
```

Use `--dry-run` to verify events without injecting keyboard input. Continuous keys are automatically released after 1500 ms without a phone heartbeat; change this with `--state-timeout-ms`.

On Ubuntu, `pynput` works most reliably under X11:

```bash
echo $XDG_SESSION_TYPE
```

## Run the Android app

Open `android-app` in Android Studio and run it on an Android 8.0 or newer phone.

1. Enter the computer LAN IP and UDP port `5005`.
2. Press **Send test JUMP_UP** to verify networking.
3. Select the actions enabled for this session: running, lateral jumps, vertical jump, and/or squat.
4. Fix the phone firmly at the front center of the waist in portrait orientation.
5. Press **Start detection** and stand still during the 1.5 second calibration.
6. Test one action at a time before combining transitions.

Find the computer IP with `hostname -I`. If Ubuntu blocks the port, allow `5005/udp` in the firewall.

The checked-in APK under `apk/` is the legacy jump-only version 0.1.0. Build version 0.2.0 from source to test the multi-action implementation.

## Sensor recordings

Every detection session records processed 100 Hz samples and rule outputs to the app-specific external `recordings` directory. The exact path is displayed in the app. A typical file contains:

```text
timestamp_ns, vertical/lateral/forward acceleration, raw magnitude,
gyroscope XYZ/magnitude, tilt, recognizer scores/states, detected actions
```

Each row also records the selected action space for that session. Detected actions are diagnostic pseudo-labels, not neural-network ground truth. Before training, recordings should be annotated with the actual action time ranges and split by whole session or person, never by randomly mixed windows.

## Initial tuning

The takeoff threshold remains editable in the app and defaults to `2.8 m/s²`. Other first-pass constants are centralized in `JumpDetector`, `RunningDetector`, and `SquatDetector` so real recordings can drive tuning.

- A jump requires at least two takeoff samples and five low-magnitude flight samples.
- Continuous actions send a heartbeat every 500 ms.
- The phone must remain firmly mounted; hand-held movement is outside the supported input contract.

## Tests

Receiver tests:

```bash
cd receiver
python -m unittest discover -s tests -v
```

Android rule-expert tests:

```bash
cd android-app
./gradlew testDebugUnitTest
```

## Safety

Use small jumps, a clear floor, supportive shoes, and a secure phone mount. Stop if the phone moves in its holder or repeated motion becomes uncomfortable.

## License

MIT. This repository does not include game source code or artwork; it only emits ordinary keyboard input to a locally running game.

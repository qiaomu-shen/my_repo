# Phone Motion Dino Controller

A V0 prototype that turns a waist-mounted Android phone into a low-latency jump controller for Chrome's offline dinosaur game.

```text
Human takeoff
  -> Android accelerometer
  -> orientation-independent jump detector
  -> UDP JUMP event over local Wi-Fi
  -> desktop receiver presses Space
  -> chrome://dino jumps
```

## Scope

This first version intentionally supports **jump only**. It is designed to answer four questions before adding crouching, lane changes, machine learning, or a custom game:

1. Can a phone fixed at the waist detect takeoff early enough?
2. Can landing impact be prevented from causing a second trigger?
3. Is local Wi-Fi + UDP + keyboard injection responsive enough?
4. Is the interaction reliable during continuous play?

## Repository layout

```text
android-app/   Android sensor and UDP sender app
apk/           Verified installable APK, checksum, and signature report
receiver/      Cross-platform Python UDP receiver and keyboard output
docs/          Wire protocol and experiment notes
.github/       CI tests and reproducible APK build workflow
```

## Download the installable APK

[Download `phone-motion-dino-controller-v0.1.0-debug.apk`](./apk/phone-motion-dino-controller-v0.1.0-debug.apk?raw=1)

Build information:

- App name: `Dino Jump Controller`
- Package: `com.qiaomushen.dinojump`
- Version: `0.1.0` (`versionCode 1`)
- Minimum Android version: Android 8.0 / API 26
- Target SDK: API 35
- Signature: Android debug certificate, verified with APK Signature Scheme v2
- SHA-256: `717f3d6303fc010d31d412082f4dda81ac49bf97e5dd7ef25b7064f2aaf2c7b8`

Related verification files:

- [`SHA-256 checksum`](./apk/phone-motion-dino-controller-v0.1.0-debug.apk.sha256)
- [`APK signature report`](./apk/apksigner-verify.txt)
- [`APK manifest metadata`](./apk/apk-badging.txt)

On the phone, allow installation from the browser or file manager when Android asks, then open the downloaded APK. This is a debug-signed prototype for direct testing, not a Google Play release build.

You can also install it over USB with ADB:

```bash
adb install -r phone-motion-dino-controller-v0.1.0-debug.apk
```

## 1. Start the desktop receiver

Python 3.10 or newer is recommended.

```bash
cd receiver
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python dino_receiver.py --port 5005
```

Open `chrome://dino` in Chrome and click the game once so that the browser has keyboard focus.

Test the receiver locally before using the phone:

```bash
python send_test_jump.py --host 127.0.0.1 --port 5005
```

The dinosaur should jump once.

### Ubuntu / Linux note

`pynput` works most reliably under X11. Check the current session:

```bash
echo $XDG_SESSION_TYPE
```

If it prints `wayland` and keyboard injection fails, log out and choose **Ubuntu on Xorg** from the login screen. You can still validate networking with:

```bash
python dino_receiver.py --dry-run
```

## 2. Run the Android app

1. Install the APK above, or open the `android-app` directory in Android Studio and run it from source.
2. Enter the desktop computer's LAN IP and port `5005`.
3. Press **Send test jump** to verify phone-to-computer networking.
4. Fix the phone firmly at the front of the waist, with minimal wobble.
5. Press **Start detection**, stand still during the 1.5 second calibration, then make a small jump.

Find the computer's LAN IP on Ubuntu with:

```bash
hostname -I
```

If Ubuntu's firewall blocks packets:

```bash
sudo ufw allow 5005/udp
```

## Detector parameters

Initial defaults:

| Parameter | Default | Purpose |
|---|---:|---|
| Sampling | about 100 Hz | Early takeoff detection |
| Threshold | 2.8 m/s² | Required upward linear acceleration |
| Consecutive samples | 2 | Reject isolated sensor spikes |
| Cooldown | 850 ms | Reject landing as a second jump |
| Calibration | 1.5 s | Estimate gravity while standing still |

The detector estimates the gravity vector with a low-pass filter and projects linear acceleration onto it. This makes the vertical signal less dependent on the exact phone orientation than reading a fixed sensor axis.

Tuning guidance:

- Missed jumps: lower the threshold gradually, for example `2.8 -> 2.4 -> 2.0`.
- Walking causes false triggers: raise it, for example `2.8 -> 3.2`.
- Landing triggers a second jump: increase cooldown to `950-1100 ms`.
- Response feels late: slightly lower the threshold before reducing filtering.

## Validation plan

Perform at least 30 isolated jumps and record:

- true human jumps;
- game jumps;
- missed detections;
- duplicate detections;
- false detections while standing and walking;
- subjective response delay.

For end-to-end latency, use a second phone at 120 or 240 fps to film both the human and the screen. Device monotonic clocks are useful for local logs but are not automatically synchronized across the phone and computer.

## Run receiver tests

```bash
cd receiver
python -m unittest discover -s tests -v
```

## Safety

Use small jumps, a clear floor, supportive shoes, and a secure phone mount. Stop testing if the phone moves inside the holder or if repeated jumping becomes uncomfortable.

## License

MIT. This repository does not include Chromium or Chrome dinosaur game source code or artwork. It only emits ordinary keyboard input to a locally running game.

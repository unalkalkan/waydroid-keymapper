# keymapper

An ad-free, backend-free gamepad → touch mapper for Android, tested on Waydroid. Design decisions
and evidence are in `docs/DESIGN.md`; the app ↔ helper wire protocol is in `docs/PROTOCOL.md`.

- **No network.** The app has no `INTERNET` permission. No accounts, analytics or ads.
- **Privileged helper** (`keymapperd`) runs as the adb shell user — no root needed, no global setting
  changed, nothing executable on external storage.
- **Locked-down IPC**: abstract socket with a random per-install name, kernel-checked peer uid, and a
  mutual HMAC handshake with a key only adb/root can read.

## Layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin: profile model + JSON, evdev → logical input mapping, stick maths, multitouch planner, mapping engine, wire protocol. Host unit tests. |
| `helper/` | The daemon (`HelperMain`), its CLI (`Cli`), the JNI evdev/uinput bridge (`src/main/cpp/kmnative.c`) and the client the app uses. |
| `app/` | Compose UI, Room storage, HUD overlay service, pairing provider, root activator, touch-test screen. |
| `scripts/` | `deploy.sh`, `stop.sh`, `kmctl` (host side); `device/ctl.sh` (runs on the device). |
| `tools/kmpad/` | Virtual HID gamepad for testing (needs root on the device). |
| `docs/` | Design notes, wire protocol, example profiles. |

## Build

Needs JDK 17 and an Android SDK with platform 35, build-tools 36.0.0, NDK 27.2.12479018 and CMake
3.22.1. Point `local.properties` at it (`sdk.dir=…`). The scripts use `$ADB`, or
`.sdk/platform-tools/adb` when that's unset (`.sdk/` is git-ignored, so it can be a symlink to your
SDK).

```sh
./gradlew :core:test            # engine/protocol tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk (contains the helper)
./gradlew :helper:helperDist    # optional: standalone keymapperd.jar + libs for --standalone deploys
```

## Run (Waydroid or a phone with USB/wireless debugging)

```sh
adb connect <ip>:5555                   # Waydroid: `waydroid status` shows the IP
export ANDROID_SERIAL=<ip>:5555
./scripts/deploy.sh                     # install app, pair, push helper, verify hashes, start
./scripts/kmctl status                  # helper status as JSON
./scripts/stop.sh                       # stop (add --uninstall to remove /data/local/tmp/keymapper)
```

Re-run `deploy.sh` after each app update (the helper version is shown on the Status screen) and after
"New pairing key". After a device reboot: `adb shell sh /data/local/tmp/keymapper/ctl.sh start`.
With root, the Status screen's "Start with root" does the same from the device.

In the app:

1. **Games** → Add game → pick it → pencil opens the editor (creates and assigns a profile).
2. **Editor**: ⊕ adds Button / Joystick / Look / Phase switch; drag to place; tap to edit bindings
   (or **Learn** and press the pad button), modes, response curve; phases via the chips; save.
3. **Status** → enable the HUD. Launch the game: the profile activates by itself; the pill shows
   profile · phase. Tap it to switch phase, long-press to pause.
4. **Controllers**: choose which pads to use, live test, calibrate.

Gamepads on Waydroid: plug the pad into the host; with `persist.waydroid.uevent=true` it should show up
as `/dev/input/eventN` inside the container (`adb shell ls /dev/input`). The helper grabs it only
while a profile is mapping (or while learning), so it works normally in Android menus otherwise.

## Playing from a PC with scrcpy

A controller plugged into the PC can drive a game on a USB-connected phone through
[scrcpy](https://github.com/Genymobile/scrcpy) ≥ 2.7, which forwards PC gamepads to the device as
virtual HID pads (`Microsoft X-Box 360 Pad`, 045e:028e). The helper treats them like any physical pad.

```sh
export ANDROID_SERIAL=<serial>          # `adb devices`
./scripts/deploy.sh
scrcpy -G --no-mouse-hover              # -G = --gamepad=uhid
```

Lowest latency measured on a Samsung SM-G770F (Snapdragon 855, Android 13) over USB, with
VA-API decoding on the PC:

```sh
scrcpy -G --no-mouse-hover -m 1600 -b 16M -S -w \
    --video-codec-options=vendor.qti-ext-enc-low-latency.enable=1
```

Phone capture → frame drawn on the PC: 22 ms median / 30 ms p90, against 38 / 51 ms with the
defaults. `-m 1600` saves ~13 ms (1280 is no faster, 1920 costs ~9 ms), the Qualcomm low-latency
flag ~2 ms, VA-API vs software decoding ~6 ms; H.265, 16M bitrate and `-S` (phone screen off)
made no difference. Controller input is negligible next to that: PC pad → pad event on the phone
2.5 ms, pad event → keymapper touch 1.2 ms (medians).

- `--no-mouse-hover`: on Android ≤ 13, mouse events from scrcpy cancel held fingers (see Known
  gaps). Don't click in the scrcpy window during a match.
- Green lines in the scrcpy window (NVIDIA, KDE Wayland): scrcpy ≥ 5 forces EGL, which NVIDIA
  doesn't offer under XWayland, so SDL falls back to its Vulkan renderer, which garbles the
  video. Run scrcpy as `SDL_VIDEO_DRIVER=wayland scrcpy …`; its log should say
  `Renderer: opengl`.
- Frozen window with `VA-API: could not import DMA-BUF as EGLImage`: nvidia-vaapi-driver 0.0.13
  (Debian 13) misreads NVIDIA ≥ 575 drivers and exports a wrong DMA-BUF modifier. Add
  `--hwdec=disabled`, or use nvidia-vaapi-driver ≥ 0.0.14.
- scrcpy's pad never sends the digital `BTN_TL2`/`BTN_TR2` it advertises; L2/R2 come from the
  trigger axes like on every other pad.

## Testing without a controller

```sh
./scripts/kmctl load docs/examples/demo-profile.json   # force a profile
adb shell am start -n games.forgemaster.keymapper/.app.TouchTestActivity
adb logcat -s KmTouchTest                              # every MotionEvent the screen receives
./scripts/kmctl key A press
./scripts/kmctl stick left 0.5 0 400
./scripts/kmctl key SELECT press                       # next phase
./scripts/kmctl tap 640 400                            # raw finger
```

## Example profiles

`docs/examples/` holds profiles to import (**Profiles** → Import, or `./scripts/kmctl load FILE`):

- `demo-profile.json`: every control type across two phases, for the touch-test screen.
- `mobile-legends.json`: Mobile Legends: Bang Bang on an Xbox-layout pad. Left stick moves; A
  attacks; X/Y/B/RB cast (hold, aim with the right stick, release to fire); LT + X/Y/B upgrades a
  skill; RT buys the recommended item; L3 recalls; LB regens; Start opens the shop.
- `brawl-stars.json`: Brawl Stars, laid out like the Mobile Legends profile. Left stick moves; A
  attacks and X casts the Super (tap = auto-aim, or hold and aim with the right stick); RT/LT do
  the same while leaving the thumb on the right stick, release to fire; Y/RB gadget; B/LB
  Hypercharge; Start opens quick chat. Aimed controls use `aimMin`, because Brawl Stars treats a
  drag under ~5% of the screen height as a tap and fires auto-aimed.

## Status

| Area | State |
|---|---|
| Feasibility on Waydroid | uinput is absent there; the InputManager path works (`docs/DESIGN.md`) |
| Helper, injection, CLI | Verified on Waydroid |
| Activation (`deploy.sh`, hash check, `setsid`, PID file, stop) and connect screen | Verified |
| Profile editor, library, Room storage, JSON import/export | Verified through the UI |
| Engine: buttons, chords, sticks, curves, triggers, D-pad, aimed buttons, phases, hot reload | Unit tests and on-device touch logs |
| HUD, phase switching, foreground auto-switch | Verified |
| Physical pad | Xbox One S pad on Waydroid: probe, grab and mapping verified in a real match |
| uinput backend, scrcpy pads | Samsung SM-G770F (Android 13): uinput touches, PC pad via `scrcpy -G`, every button and axis |
| Calibration UI, haptics, curve editor | Built; curve editor verified; calibration and rumble untested |

## Known gaps / next steps

- Rumble and calibration are untested on a physical pad. `kmpad` (needs root) provides a virtual
  pad for testing without one.
- uinput emit path is verified on one phone only; `deploy.sh --backend inputmanager` forces the
  InputManager path if a device misbehaves.
- Real touches and injected touches come from different input devices; Android ≤ 13 keeps one touch
  stream per display, so a gesture or even a mouse hover from another device (on Waydroid: moving
  the host mouse over the window) cancels ours and drops its later events. The InputManager emitter
  injects with WAIT_FOR_RESULT, repeats held fingers every 100 ms, and re-lands them when Android
  rejects an event, so control comes back ≤ 100 ms after the mouse stops. While the mouse keeps
  moving, held fingers keep getting cancelled. Merging the real touchscreen into the virtual one is
  future work.
- No per-controller button remap UI yet (`ControllerConfig.buttonMap` is supported by the engine).
- Next: live overlay editor, in-app wireless-debugging activation (`dadb`), macros.

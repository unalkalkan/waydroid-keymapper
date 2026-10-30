# Design notes

What was decided while building keymapper, why, and how it was verified.

## Feasibility on the target (Waydroid 1.6.3, LineageOS 20 / Android 13, x86_64)

Probed on 2026-10-30 over adb (shell uid 2000):

| Question | Finding | Consequence |
|---|---|---|
| `/dev/uinput` in the container | **absent** — not in Waydroid's LXC node list | the uinput emit path is impossible here |
| `/dev/input` | only Waydroid's two FIFOs (`wl_keyboard_events`, `wl_tablet_events`) | host gamepads arrive through Waydroid's uevent passthrough (`persist.waydroid.uevent=true`, already set) |
| shell groups | includes `input` (1004) and `uhid` (3011) | the helper can open and `EVIOCGRAB` passed-through pads |
| `/dev/uhid` in the container | present, but `root:root 0600` | no virtual test pads without root (`kmpad` needs `sudo waydroid shell`) |
| injection | `InputManager.injectInputEvent` from the shell uid works; no `hidden_api_policy` change needed for `app_process` code | used as the Waydroid emit path |
| display | 1280×800, rotation 0, from `DisplayManagerGlobal.getDisplayInfo` | no `dumpsys` needed |
| `ro.debuggable` | 0 (`adb root` refused) | — |

## Key decisions

| Decision | Why |
|---|---|
| The helper is a **Kotlin daemon under `app_process`** plus a small C JNI library (`libkmnative.so`, raw evdev/uinput ioctls, no libevdev), not a native daemon | Injecting through `InputManager` needs a JVM, and on Waydroid that is the only emit path. The JVM also lets the app share the engine (the curve editor plots the exact runtime function) and lets it be unit-tested on the host. |
| Helper files live in `/data/local/tmp/keymapper` (0700, files 0600, `MANIFEST.sha256` checked on every start, `setsid`, PID file). The classpath file `helper.apk` is the app APK itself (or `keymapperd.jar` with `--standalone`) | One artifact for app + helper keeps versions in step; the root activator can install from the app's own APK. |
| The app generates the HMAC key and socket name; `content://…pairing/config` answers only uid 0/2000; `deploy.sh` copies them into `helper.conf` (0600) | The key never appears in argv or on shared storage. An app-readable file would conflict with the 0700 directory, and SELinux on real phones blocks apps from reading `/data/local/tmp` anyway. |
| Foreground detection runs in the helper (`ActivityTaskManager.getTasks` over binder, `dumpsys` fallback), not through `UsageStatsManager` in the app | The helper already has the rights, so no usage-access grant is needed, and auto-switch keeps working if the app process dies. Mapping without the helper is impossible anyway. |
| The app dials an abstract socket, and on stock Android the helper hands it a connected socket pair through the app's provider instead | Phone SELinux policy (checked on a Samsung SM-G770F, Android 13) gives `untrusted_app` binder calls, `fd use` and read/write on `shell` sockets but not `connectto`; Waydroid allows the connect. Handing over a socket keeps one protocol, one handshake and no `INTERNET` permission. |
| Frames are `u32 len \| u8 type \| JSON` | Both ends are JVM; kotlinx-serialization gives versionable messages for free. Hot paths are tiny. |
| Room stores each profile as a JSON column | Profiles are documents; the same JSON is the export format. |
| The editor is a screenshot/grid editor at the display's aspect, not a live overlay | Simpler to build first; the overlay editor is listed under next steps in the README. |
| The HUD is a plain `TextView` overlay, not Compose | No lifecycle plumbing for a 1-line pill. |

For testing without hardware there is a `KEY` frame and CLI commands that inject *synthetic
controller input*, so the whole engine can be exercised without a pad, plus the `kmpad` virtual HID
pad. The HUD hides itself over Keymapper's own screens.

## Processes and threads

```
app (uid 10xxx)                       helper "keymapperd" (uid 2000, app_process)
 Compose UI, Room, HUD service         km-ipc-accept   SO_PEERCRED check → per-client thread
 HelperRepository ── abstract ──────▶  km-ipc-<pid>    handshake, decode, post to engine
   (HelperClient, reconnects)  socket  km-ipc-handover same, on a handed-over socket pair
 PairingProvider (uid 0/2000 only) ◀── km-app-link     app running, no session → call("connect", fd)
                                       km-engine       MappingEngine + Emitter (single owner)
                                       km-pad-<n>      evdev read → DeviceMapper → engine
                                       km-scan         /dev/input hotplug (1 s)
                                       km-watch        display + foreground (1 s)
                                       km-signal       SIGTERM/INT/HUP → clean shutdown
```

All mapping state lives on `km-engine`; everything else posts closures to its queue. Device fds are
closed only by their own reader thread, under the device lock, so an fd number is never reused under
a live reader or a grab ioctl.

## Engine semantics (`core/.../engine/MappingEngine.kt`)

- Positions are fractions of the logical screen; radii are fractions of the shorter side.
- Engine pointer ids 0–9, allocated lowest-free; an id lifted in a batch is never reused in the same
  batch (a uinput frame can't express UP+DOWN on one slot). Manual CLI fingers use 10–15.
- Buttons: HOLD, TAP (40 ms), TOGGLE, DOUBLE_TAP (40/60/40 ms), RAPID (1–30 Hz, 50 % duty). Chords of
  up to 3 buttons. A button already held when its phase becomes active needs a fresh press.
  The longer chord wins: when a chord becomes held together with a longer one that contains it
  (L1 held, then X, with both `X` and `L1+X` bound), only the longer one fires, and releasing L1
  first doesn't fire `X` either. A binding that's already held stays held when a longer chord
  completes on top of it.
- Aimed buttons (`aim` = a stick): the finger lands on the button, and from the next frame the stick
  drags it up to `aimRadius` from the centre (joystick response defaults, settle MOVE after each
  drag). Release lifts it where it was aimed, like dragging a MOBA skill and letting go.
- Joystick: lands on the centre, moves to `centre + v·radius` one frame (16 ms) later. Response:
  square→circle, radial deadzone, outer limit, power curve; optional 8-way snapping.
- Look: finger lands on the centre and integrates `v · sensitivity · shortSide` px/s; at the radius
  it lifts and re-lands on the centre one frame later; lifts after 120 ms at rest.
- **Settle MOVE**: Android resamples touch MOVEs and extrapolates from the last two samples. A single
  120 px joystick jump was measured arriving at the app as 494 instead of 440. The engine now repeats
  the position one frame after each joystick move and when the look stick comes to rest, so the
  resampler sees zero velocity. Measured afterwards: half-tilt lands at exactly the computed 375 px.
- Phases: NEXT / PREVIOUS / GOTO / HOLD (returns when the binding is released). Switching lifts every
  finger. HUD taps and `SET_PHASE` use the same path.
- Triggers without a digital button synthesize L2/R2 (press ≥ 0.5, release ≤ 0.4); a hat becomes the
  four D-pad buttons.

## What was verified, and how

| Area | Evidence |
|---|---|
| Engine, planner, rotation maths, device mapping, protocol, handshake, JSON | 37 JUnit tests (`./gradlew :core:test`), one mutation-checked |
| Helper start/stop, hash check, perms, PID | `deploy.sh` / `ctl.sh` on Waydroid |
| Injection lands where intended | CLI tap → Settings opened the tapped pane |
| Every control type | Synthetic input → `TouchTestActivity` log (`KmTouchTest`), coordinates checked against the profile maths |
| Peer-uid rejection | Helper re-paired to another uid: the real app's connection was dropped at accept |
| Wrong key rejection | CLI with a zeroed key: "authentication failed" in the helper log |
| Auto-switch | Settings ⇄ launcher ⇄ Calculator ⇄ the installed game |
| Physical pad | Xbox One S pad (045e:02ea) through Waydroid's uevent passthrough: probe, grab, read, and the Mobile Legends profile in a real match |
| App UI | Driven by injected taps: add game, editor (2 buttons + stick, curve edit), save, import, assign, HUD phase switch |

Not verified on hardware: rumble, calibration, the uinput emit path (no `/dev/uinput` on this Waydroid), rotation on a real phone, the root activator.

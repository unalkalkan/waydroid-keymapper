# keymapper helper protocol (v1)

Our own protocol between the app (or the CLI) and the helper daemon. Not compatible with anything
else, on purpose.

## Transport

- Unix domain socket in the **abstract** namespace, name `keymapper.<16 hex>` — random per install,
  so another app cannot squat a predictable name before the helper starts.
- On `accept()` the helper reads `SO_PEERCRED`. It serves only: the paired app's uid (any Android
  user), root (0), and its own uid (the CLI runs as the same shell user). Everything else is closed
  without a reply.
- The client checks the server's `SO_PEERCRED` too and refuses a server that isn't uid 0 or 2000.

## Frames

```
u32 length (big-endian, counts the type byte) | u8 type | payload (length - 1 bytes)
```

Max frame 1 MiB. Payloads are UTF-8 JSON (kotlinx-serialization, unknown keys ignored) unless empty.
Message classes live in `core/src/main/kotlin/.../protocol/Messages.kt`.

| Type | Name | Direction | Payload |
|---|---|---|---|
| 0x01 | HELLO | helper → client | `Hello{proto, helperVersion, nonce, pid, uid}` |
| 0x02 | AUTH | client → helper | `Auth{nonce, mac, client, proto}` |
| 0x03 | AUTH_OK | helper → client | `AuthOk{mac}` |
| 0x10 | SET_PROFILES | client → helper | `SetProfiles{profiles, assignments: pkg→profileId, forcedProfileId}` |
| 0x11 | SET_PHASE | client → helper | `SetPhase{index?, delta}` |
| 0x12 | SET_SCREEN | client → helper | `SetScreen{screen?}` (null = auto-detect) |
| 0x13 | TOUCH | client → helper | `TouchCmd{pointer 0..5, action, x, y}` — manual finger, logical px |
| 0x14 | KEY | client → helper | `KeyCmd{button? , axis?, value}` — synthetic controller input |
| 0x15 | SET_CONTROLLERS | client → helper | `SetControllers{selected: ["vvvv:pppp"], configs}` |
| 0x16 | SET_MONITOR | client → helper | `SetMonitor{enabled}` — stream events, pause mapping, grab pads |
| 0x17 | SET_ENABLED | client → helper | `SetEnabled{enabled}` |
| 0x20 | STATUS | helper → client | `Status{…}` — sent on connect and on every change |
| 0x21 | RAW_EVENT | helper → monitor clients | `RawEvent{device, type, code, value}` |
| 0x22 | LOGICAL_EVENT | helper → monitor clients | `LogicalInput{device, button?, axis?, value}` |
| 0x30 | PING | client → helper | empty |
| 0x31 | PONG | helper → client | empty |
| 0x40 | SHUTDOWN | client → helper | `Shutdown{reason}` |
| 0x7f | ERROR | helper → client | `ErrorMsg{message}` |

## Handshake

```
helper  → HELLO   { nonce: Nh (16 random bytes, hex) }
client  → AUTH    { nonce: Nc, mac: HMAC-SHA256(key, "km1|client|" + Nh + "|" + Nc) }
helper  → AUTH_OK { mac: HMAC-SHA256(key, "km1|helper|" + Nc + "|" + Nh) }
```

Both sides compare in constant time. A wrong MAC closes the connection silently. The handshake has
a 3 s timeout. `key` is 32 random bytes (64 hex chars).

## Pairing (where the key comes from)

1. The app generates `key` and the socket name on first use and keeps them in private storage.
2. The app exposes them through the content provider
   `content://games.forgemaster.keymapper.pairing/config`, which answers **only** callers with
   uid 0 or 2000 (`Binder.getCallingUid()`), i.e. `adb shell content query …`.
3. `scripts/deploy.sh` reads them over adb and writes `/data/local/tmp/keymapper/helper.conf`
   (dir 0700, file 0600, owned by shell). The key never appears on a command line.

The root activator in the app writes the same file through `su`'s stdin.

## Semantics

- The helper keeps profiles in memory only. It picks the forced profile if one is set, else the
  profile assigned to the current foreground package, else none.
- Mapping is active when enabled, a profile is selected and no client is monitoring. Controllers are
  grabbed (`EVIOCGRAB`) while mapping with a profile that has `grab: true`, or while monitoring.
- Engine pointer ids are 0–9; manual `TOUCH` fingers use 10–15.

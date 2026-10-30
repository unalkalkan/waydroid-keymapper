#!/usr/bin/env bash
# Deploys and starts the keymapper helper on a device over adb.
#
#   scripts/deploy.sh [-s SERIAL] [--apk PATH] [--no-install] [--standalone] [--backend B] [--no-start]
#
#   default       install the app APK, pair through the app's provider (shell-only), push the APK as
#                 the helper classpath plus the native lib for the device ABI, verify hashes, start.
#   --standalone  don't involve the app: classpath = helper/build/dist/keymapperd.jar and the key is
#                 generated here (reused if the device already has one). Re-run without it to pair.
#   --backend B   auto (uinput, else InputManager) | uinput | inputmanager
#
# Everything lands in /data/local/tmp/keymapper (mode 0700, files 0600). Nothing is written to
# external storage and no global setting is changed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=/data/local/tmp/keymapper
PKG=games.forgemaster.keymapper
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
STANDALONE=0
INSTALL=1
START=1
BACKEND=auto
SERIAL=${ANDROID_SERIAL:-}

while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL=$2; shift 2 ;;
        --apk) APK=$2; shift 2 ;;
        --no-install) INSTALL=0; shift ;;
        --standalone) STANDALONE=1; shift ;;
        --backend) BACKEND=$2; shift 2 ;;
        --no-start) START=0; shift ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "unknown option $1" >&2; exit 64 ;;
    esac
done
case "$BACKEND" in auto|uinput|inputmanager) ;; *) echo "bad --backend $BACKEND" >&2; exit 64 ;; esac

ADB=${ADB:-$(command -v adb || true)}
[ -x "${ADB:-}" ] || ADB="$ROOT/.sdk/platform-tools/adb"
[ -x "$ADB" ] || { echo "adb not found (set ADB=...)" >&2; exit 1; }
A=("$ADB")
[ -n "$SERIAL" ] && A+=(-s "$SERIAL")
dsh() { "${A[@]}" shell "$@" | tr -d '\r'; }

TMP=$(mktemp -d)
chmod 700 "$TMP"
trap 'rm -rf "$TMP"' EXIT

"${A[@]}" get-state >/dev/null || { echo "no device" >&2; exit 1; }
ABI=$(dsh getprop ro.product.cpu.abi)
SDK=$(dsh getprop ro.build.version.sdk)
echo "device: abi=$ABI sdk=$SDK"

if [ "$STANDALONE" = 1 ]; then
    DIST="$ROOT/helper/build/dist"
    [ -f "$DIST/keymapperd.jar" ] || { echo "build it first: ./gradlew :helper:helperDist" >&2; exit 1; }
    cp "$DIST/keymapperd.jar" "$TMP/helper.apk"
    cp "$DIST/lib/$ABI/libkmnative.so" "$TMP/libkmnative.so" || { echo "no native lib for $ABI" >&2; exit 1; }
    OLD=$(dsh "cat $DIR/helper.conf 2>/dev/null" || true)
    KEY=$(printf '%s\n' "$OLD" | sed -n 's/^key=//p')
    SOCK=$(printf '%s\n' "$OLD" | sed -n 's/^socket=//p')
    if [ -z "$KEY" ] || [ -z "$SOCK" ]; then
        KEY=$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')
        SOCK="keymapper.$(od -An -tx1 -N8 /dev/urandom | tr -d ' \n')"
        echo "generated a new key (standalone)"
    fi
    UID_=$( (dsh pm list packages -U "$PKG" || true) | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | head -n1)
    UID_=${UID_:--1}
else
    [ -f "$APK" ] || { echo "APK not found: $APK (./gradlew :app:assembleDebug)" >&2; exit 1; }
    if [ "$INSTALL" = 1 ]; then
        echo "installing $(basename "$APK")"
        "${A[@]}" install -r -t "$APK" >/dev/null
    fi
    ROW=$(dsh content query --uri "content://$PKG.pairing/config")
    KEY=$(printf '%s' "$ROW" | sed -n 's/.*key=\([0-9a-f]*\).*/\1/p')
    SOCK=$(printf '%s' "$ROW" | sed -n 's/.*socket=\(keymapper\.[0-9a-f]*\).*/\1/p')
    UID_=$(printf '%s' "$ROW" | sed -n 's/.*uid=\([0-9]*\).*/\1/p')
    if [ -z "$KEY" ] || [ -z "$SOCK" ] || [ -z "$UID_" ]; then
        echo "pairing query failed: $ROW" >&2
        exit 1
    fi
    cp "$APK" "$TMP/helper.apk"
    (cd "$TMP" && unzip -p helper.apk "lib/$ABI/libkmnative.so" > libkmnative.so)
    [ -s "$TMP/libkmnative.so" ] || { echo "APK has no lib/$ABI/libkmnative.so" >&2; exit 1; }
    echo "paired with $PKG (uid $UID_)"
fi

cp "$ROOT/scripts/device/ctl.sh" "$TMP/ctl.sh"
umask 077
printf 'key=%s\nsocket=%s\npackage=%s\nuid=%s\nbackend=%s\n' "$KEY" "$SOCK" "$PKG" "$UID_" "$BACKEND" > "$TMP/helper.conf"
(cd "$TMP" && sha256sum helper.apk libkmnative.so ctl.sh > MANIFEST.sha256)

dsh "[ -f $DIR/ctl.sh ] && sh $DIR/ctl.sh stop; mkdir -p $DIR && chmod 700 $DIR" || true
for f in helper.apk libkmnative.so ctl.sh helper.conf MANIFEST.sha256; do
    "${A[@]}" push "$TMP/$f" "$DIR/$f" >/dev/null
done
dsh "cd $DIR && chmod 600 helper.apk libkmnative.so ctl.sh helper.conf MANIFEST.sha256 && sha256sum -c MANIFEST.sha256" \
    | sed 's/^/verify: /'
dsh "cd $DIR && sha256sum -c MANIFEST.sha256 >/dev/null" || { echo "hash verification failed on device" >&2; exit 2; }

if [ "$START" = 1 ]; then
    dsh sh "$DIR/ctl.sh" start
fi

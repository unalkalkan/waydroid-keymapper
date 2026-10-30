#!/usr/bin/env bash
# Stops the keymapper helper. --uninstall also deletes /data/local/tmp/keymapper.
#   scripts/stop.sh [-s SERIAL] [--uninstall]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERIAL=${ANDROID_SERIAL:-}
CMD=stop
while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL=$2; shift 2 ;;
        --uninstall) CMD=uninstall; shift ;;
        *) echo "unknown option $1" >&2; exit 64 ;;
    esac
done
ADB=${ADB:-$(command -v adb || true)}
[ -x "${ADB:-}" ] || ADB="$ROOT/.sdk/platform-tools/adb"
A=("$ADB")
[ -n "$SERIAL" ] && A+=(-s "$SERIAL")
"${A[@]}" shell "sh /data/local/tmp/keymapper/ctl.sh $CMD" | tr -d '\r'

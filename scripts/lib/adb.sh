# Sourced by the host scripts (needs $ROOT). Sets ADB to the adb binary to use:
#   1. the binary of the adb server that is already running. A client from another platform-tools
#      release kills and restarts the server, which drops scrcpy and every other open session;
#   2. else $ADB, else adb on PATH, else $ROOT/.sdk/platform-tools/adb.
pick_adb() {
    local pid exe
    for pid in $(pgrep -x adb 2>/dev/null || true); do
        tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -q 'fork-server' || continue
        exe=$(readlink "/proc/$pid/exe" 2>/dev/null) || continue
        if [ -x "$exe" ]; then
            ADB=$exe
            return 0
        fi
    done
    ADB=${ADB:-$(command -v adb || true)}
    [ -x "${ADB:-}" ] || ADB="$ROOT/.sdk/platform-tools/adb"
}
pick_adb

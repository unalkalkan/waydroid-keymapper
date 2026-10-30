#!/system/bin/sh
# keymapper helper control. Runs on the device as shell (adb) or root.
#
#   sh /data/local/tmp/keymapper/ctl.sh start|stop|restart|status|log [lines]|uninstall
#
# Layout of /data/local/tmp/keymapper (0700): helper.apk (classpath), libkmnative.so (JNI for the
# primary ABI), ctl.sh, MANIFEST.sha256, helper.conf (0600), helper.pid, helper.log.
# Every start re-checks MANIFEST.sha256, so a partial or altered push never runs.

DIR=/data/local/tmp/keymapper
MAIN=games.forgemaster.keymapper.helper.HelperMain
NAME=keymapperd

umask 077
cd "$DIR" 2>/dev/null || { echo "keymapper: $DIR is missing (run deploy.sh)"; exit 1; }

running_pid() {
    [ -f helper.pid ] || return 1
    pid=$(cat helper.pid 2>/dev/null)
    [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ] || return 1
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q "$NAME" || return 1
    echo "$pid"
}

verify() {
    if ! sha256sum -c MANIFEST.sha256 >/dev/null 2>&1; then
        echo "keymapper: hash check failed, refusing to start:"
        sha256sum -c MANIFEST.sha256 2>&1 | grep -v ': OK$'
        return 1
    fi
}

start() {
    if pid=$(running_pid); then
        echo "keymapper: running (pid $pid)"
        return 0
    fi
    verify || return 2
    [ -f helper.conf ] || { echo "keymapper: helper.conf missing (pair first)"; return 2; }
    rm -f helper.pid
    if command -v setsid >/dev/null 2>&1; then DETACH=setsid; else DETACH=nohup; fi
    CLASSPATH="$DIR/helper.apk" $DETACH app_process /system/bin --nice-name=$NAME $MAIN --dir "$DIR" \
        >"$DIR/helper.log" 2>&1 </dev/null &
    i=0
    while [ $i -lt 30 ]; do
        sleep 0.2
        if pid=$(running_pid); then
            echo "keymapper: started (pid $pid)"
            return 0
        fi
        i=$((i + 1))
    done
    echo "keymapper: helper did not come up; last log lines:"
    tail -n 20 helper.log
    return 3
}

stop() {
    if ! pid=$(running_pid); then
        rm -f helper.pid
        echo "keymapper: not running"
        return 0
    fi
    kill "$pid"
    i=0
    while [ $i -lt 25 ]; do
        sleep 0.2
        if ! running_pid >/dev/null; then
            rm -f helper.pid
            echo "keymapper: stopped"
            return 0
        fi
        i=$((i + 1))
    done
    kill -9 "$pid" 2>/dev/null
    rm -f helper.pid
    echo "keymapper: killed (did not exit in time)"
}

case "${1:-status}" in
    start) start ;;
    stop) stop ;;
    restart) stop; start ;;
    status)
        if pid=$(running_pid); then echo "keymapper: running (pid $pid)"; else echo "keymapper: stopped"; exit 1; fi ;;
    log) tail -n "${2:-50}" helper.log ;;
    uninstall)
        stop
        cd / && rm -rf "$DIR" && echo "keymapper: removed $DIR" ;;
    *) echo "usage: ctl.sh start|stop|restart|status|log [lines]|uninstall"; exit 64 ;;
esac

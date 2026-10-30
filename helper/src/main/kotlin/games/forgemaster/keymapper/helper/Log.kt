package games.forgemaster.keymapper.helper

import android.util.Log

/** Logs to logcat (tag keymapperd) and stderr, which ctl.sh sends to helper.log. Never logs secrets. */
internal object L {
    private const val TAG = "keymapperd"

    fun i(msg: String) {
        Log.i(TAG, msg)
        System.err.println("I $msg")
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        System.err.println("W $msg" + (t?.let { ": $it" } ?: ""))
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        System.err.println("E $msg" + (t?.let { ": $it" } ?: ""))
        t?.printStackTrace()
    }
}

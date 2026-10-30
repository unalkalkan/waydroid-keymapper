package games.forgemaster.keymapper.helper

import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Method

/**
 * Connects the app when it can't connect itself. Stock Android's SELinux policy lets an app read and
 * write a socket owned by adb shell, but not connect() to one (Waydroid's allows both). So while the
 * app's process runs without a session, the helper makes a socket pair, serves one end like an
 * accepted connection and passes the other to the app's PairingProvider ("connect"); the app then
 * proves the key over it as usual. It only offers while the app is running, never starts it.
 */
internal class AppLink(private val config: HelperConfig, private val ipc: IpcServer) {
    @Volatile private var running = false
    private var lastOffer = 0L
    private var lastError: String? = null

    fun start() {
        if (config.appUid < 0) return
        running = true
        Thread({
            while (running) {
                try {
                    tick()
                } catch (e: Exception) {
                    L.w("app link failed", e)
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                }
            }
        }, "km-app-link").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
    }

    private fun tick() {
        if (ipc.sessions.any { it.uid == config.appUid }) return
        val now = System.currentTimeMillis()
        if (now - lastOffer < RETRY_MS || !appRunning()) return
        lastOffer = now
        offer()
    }

    /** The app's main process exists. Shell is in the readproc group, so /proc shows every process. */
    private fun appRunning(): Boolean {
        val pids = File("/proc").list() ?: return false
        for (pid in pids) {
            if (pid.isEmpty() || !pid.all { it.isDigit() }) continue
            val uid = try {
                Os.stat("/proc/$pid").st_uid
            } catch (_: ErrnoException) {
                continue
            }
            if (uid != config.appUid) continue
            val cmdline = try {
                File("/proc/$pid/cmdline").readBytes()
            } catch (_: Exception) {
                continue
            }
            if (String(cmdline).substringBefore('\u0000') == config.appPackage) return true
        }
        return false
    }

    private fun offer() {
        val ours = FileDescriptor()
        val theirs = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, ours, theirs)
        try {
            if (!ipc.adopt(ours, config.appUid)) return
            val reply = ParcelFileDescriptor.dup(theirs).use { fd ->
                callProvider(HelperPaths.CONNECT_METHOD, Bundle().apply { putParcelable(HelperPaths.CONNECT_EXTRA_FD, fd) })
            }
            // Declined: the app is still starting and closed its end, so offer again on the next tick.
            if (reply?.getBoolean(HelperPaths.CONNECT_EXTRA_OK) != true) lastOffer = 0
            lastError = null
        } catch (e: Exception) {
            report("could not hand the app a connection: ${e.cause ?: e}")
        } finally {
            try {
                Os.close(theirs)
            } catch (_: ErrnoException) {
            }
        }
    }

    private fun report(msg: String) {
        if (msg != lastError) L.w(msg)
        lastError = msg
    }

    /** IContentProvider.call on the app's provider, after checking it belongs to the paired app's uid. */
    private fun callProvider(method: String, extras: Bundle): Bundle? {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token: IBinder = Binder()
        val userId = config.appUid / PER_USER_RANGE
        val holder = getProviderExternal(am, userId, token) ?: throw IllegalStateException("provider $AUTHORITY not found")
        try {
            val info = holder.javaClass.getField("info").get(holder) as ProviderInfo
            if (info.applicationInfo.uid != config.appUid) {
                throw SecurityException("$AUTHORITY belongs to uid ${info.applicationInfo.uid}, not the paired app")
            }
            val provider = holder.javaClass.getField("provider").get(holder)!!
            return call(provider, method, extras)
        } finally {
            try {
                am.javaClass.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                    .invoke(am, AUTHORITY, token)
            } catch (_: Exception) {
            }
        }
    }

    private fun getProviderExternal(am: Any, userId: Int, token: IBinder): Any? {
        val cls = am.javaClass
        return try {
            cls.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(am, AUTHORITY, userId, token, PROCESS_TAG)
        } catch (_: NoSuchMethodException) { // Android 8-9
            cls.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java)
                .invoke(am, AUTHORITY, userId, token)
        }
    }

    /** The signature of IContentProvider.call changed in Android 10, 11 and 12. */
    private fun call(provider: Any, method: String, extras: Bundle): Bundle? {
        val cls = provider.javaClass
        val s = String::class.java
        val b = Bundle::class.java
        val pkg = if (Os.getuid() == 0) "root" else SHELL_PACKAGE
        fun find(vararg types: Class<*>): Method? = try {
            cls.getMethod("call", *types)
        } catch (_: NoSuchMethodException) {
            null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            find(AttributionSource::class.java, s, s, s, b)?.let {
                val source = AttributionSource.Builder(Os.getuid()).setPackageName(pkg).build()
                return it.invoke(provider, source, AUTHORITY, method, null, extras) as Bundle?
            }
        }
        find(s, s, s, s, s, b)?.let { return it.invoke(provider, pkg, null, AUTHORITY, method, null, extras) as Bundle? }
        find(s, s, s, s, b)?.let { return it.invoke(provider, pkg, AUTHORITY, method, null, extras) as Bundle? }
        val legacy = find(s, s, s, b) ?: throw NoSuchMethodException("IContentProvider.call")
        return legacy.invoke(provider, pkg, method, null, extras) as Bundle?
    }

    private companion object {
        const val AUTHORITY = HelperPaths.PROVIDER_AUTHORITY
        const val SHELL_PACKAGE = "com.android.shell"
        const val PROCESS_TAG = "keymapperd"
        const val PER_USER_RANGE = 100000
        const val POLL_MS = 500L
        /** Longer than the handshake timeout, so an unanswered offer has expired before the next. */
        const val RETRY_MS = 4000L
    }
}

package games.forgemaster.keymapper.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import games.forgemaster.keymapper.app.data.PairingStore
import games.forgemaster.keymapper.helper.HelperPaths

/**
 * `adb shell content query --uri content://games.forgemaster.keymapper.pairing/config`
 * prints `key=…, socket=…, package=…, uid=…`. Only the shell user and root get an answer, so other
 * apps can never read the key even though the provider is exported.
 *
 * `call("connect")` is how the helper reaches the app on stock Android, where SELinux lets an app use
 * a socket owned by adb shell but not connect to one: the helper passes one end of a connected socket
 * pair and the app runs the usual key handshake over it. Also shell/root only.
 *
 * `call("enable")` turns the Mapping switch on for `scripts/km on`; calling the provider also starts
 * the app's process if it isn't running. Shell/root only. Returns ok=false while the process is
 * still starting, so the caller asks again.
 */
class PairingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        requireShellOrRoot()
        if (uri.path != "/config") return null
        val ctx = context ?: return null
        val pairing = PairingStore.get(ctx).get()
        return MatrixCursor(arrayOf("key", "socket", "package", "uid")).apply {
            addRow(arrayOf<Any>(pairing.key, pairing.socketName, ctx.packageName, Process.myUid()))
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        HelperPaths.CONNECT_METHOD -> connect(extras)
        METHOD_ENABLE -> enable()
        else -> super.call(method, arg, extras)
    }

    private fun connect(extras: Bundle?): Bundle {
        requireShellOrRoot()
        val fd = extras?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable(HelperPaths.CONNECT_EXTRA_FD, ParcelFileDescriptor::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelable(HelperPaths.CONNECT_EXTRA_FD)
            }
        } ?: throw IllegalArgumentException("no socket")
        val graph = context?.let { KeymapperApp.graphOrNull(it) }
        if (graph == null) {
            fd.close() // still starting; the helper offers again shortly
        } else {
            graph.helper.offer(fd)
        }
        return Bundle().apply { putBoolean(HelperPaths.CONNECT_EXTRA_OK, graph != null) }
    }

    private fun enable(): Bundle {
        requireShellOrRoot()
        val graph = context?.let { KeymapperApp.graphOrNull(it) }
        graph?.settings?.setMappingEnabled(true)
        return Bundle().apply { putBoolean(HelperPaths.CONNECT_EXTRA_OK, graph != null) }
    }

    private fun requireShellOrRoot() {
        val caller = Binder.getCallingUid()
        if (caller != 0 && caller % 100_000 != SHELL_APP_ID) {
            throw SecurityException("pairing is only available to adb shell or root")
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private companion object {
        const val SHELL_APP_ID = 2000
        const val METHOD_ENABLE = "enable"
    }
}

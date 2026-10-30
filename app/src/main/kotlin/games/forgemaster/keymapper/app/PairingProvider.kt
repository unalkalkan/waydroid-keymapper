package games.forgemaster.keymapper.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import games.forgemaster.keymapper.app.data.PairingStore

/**
 * `adb shell content query --uri content://games.forgemaster.keymapper.pairing/config`
 * prints `key=…, socket=…, package=…, uid=…`. Only the shell user and root get an answer, so other
 * apps can never read the key even though the provider is exported.
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
        val caller = Binder.getCallingUid()
        val appId = caller % 100_000
        if (caller != 0 && appId != SHELL_APP_ID) {
            throw SecurityException("pairing is only available to adb shell or root")
        }
        if (uri.path != "/config") return null
        val ctx = context ?: return null
        val pairing = PairingStore.get(ctx).get()
        return MatrixCursor(arrayOf("key", "socket", "package", "uid")).apply {
            addRow(arrayOf<Any>(pairing.key, pairing.socketName, ctx.packageName, Process.myUid()))
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
    }
}

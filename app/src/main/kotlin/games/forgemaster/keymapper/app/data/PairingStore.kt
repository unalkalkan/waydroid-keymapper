package games.forgemaster.keymapper.app.data

import android.content.Context
import games.forgemaster.keymapper.core.protocol.Handshake
import games.forgemaster.keymapper.helper.client.Pairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The per-install socket name and HMAC key, kept in the app's private files dir. Generated on
 * first use; handed to the helper only through [games.forgemaster.keymapper.app.PairingProvider]
 * (adb shell / root) or the root activator.
 */
class PairingStore private constructor(private val file: File) {
    @Serializable
    private data class Stored(val socket: String, val key: String)

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing

    @Synchronized
    fun get(): Pairing {
        _pairing.value?.let { return it }
        val p = load() ?: generate()
        _pairing.value = p
        return p
    }

    /** New key and socket name; the helper must be re-provisioned afterwards. */
    @Synchronized
    fun regenerate(): Pairing {
        val p = generate()
        _pairing.value = p
        return p
    }

    private fun load(): Pairing? = runCatching {
        val s = Json.decodeFromString(Stored.serializer(), file.readText())
        if (Handshake.isValidKey(s.key) && Handshake.isValidSocketName(s.socket)) Pairing(s.socket, s.key) else null
    }.getOrNull()

    private fun generate(): Pairing {
        val p = Pairing(Handshake.newSocketName(), Handshake.newKey())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(Stored.serializer(), Stored(p.socketName, p.key)))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        return p
    }

    companion object {
        @Volatile
        private var instance: PairingStore? = null

        fun get(context: Context): PairingStore = instance ?: synchronized(this) {
            instance ?: PairingStore(File(context.filesDir, "pairing.json")).also { instance = it }
        }
    }
}

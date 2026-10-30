package games.forgemaster.keymapper.core.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mutual challenge-response on top of the kernel-checked peer uid:
 * helper sends nonce Nh; client answers Nc + HMAC(key, "km1|client|Nh|Nc"); helper answers
 * HMAC(key, "km1|helper|Nc|Nh"). The key is 32 random bytes shared through the pairing step.
 */
object Handshake {
    private val random = SecureRandom()

    fun newNonce(): String = hex(ByteArray(16).also(random::nextBytes))

    fun newKey(): String = hex(ByteArray(32).also(random::nextBytes))

    /** Abstract socket names are per install so another app can't squat a predictable name. */
    fun newSocketName(): String = "keymapper." + hex(ByteArray(8).also(random::nextBytes))

    fun clientMac(key: String, helperNonce: String, clientNonce: String): String =
        hmac(key, "km1|client|$helperNonce|$clientNonce")

    fun helperMac(key: String, helperNonce: String, clientNonce: String): String =
        hmac(key, "km1|helper|$clientNonce|$helperNonce")

    fun verify(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))

    fun isValidKey(key: String) = key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }

    fun isValidSocketName(name: String) =
        name.length in 11..64 && name.startsWith("keymapper.") && name.all { it.isLetterOrDigit() || it == '.' }

    private fun hmac(key: String, message: String): String {
        require(isValidKey(key)) { "bad key" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(key), "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

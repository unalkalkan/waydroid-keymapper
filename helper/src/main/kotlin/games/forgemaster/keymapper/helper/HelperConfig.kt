package games.forgemaster.keymapper.helper

import games.forgemaster.keymapper.core.protocol.Handshake
import java.io.File

/**
 * On-device layout shared by ctl.sh, the daemon, the CLI and the app's root activator.
 *
 * `/data/local/tmp/keymapper` (0700, owned by the user running the helper) holds `helper.apk`
 * (classpath), `libkmnative.so`, `ctl.sh`, `MANIFEST.sha256`, `helper.conf` (0600), `helper.pid`
 * and `helper.log`. Nothing executable ever lives on external storage.
 */
object HelperPaths {
    const val DIR = "/data/local/tmp/keymapper"
    const val APK = "helper.apk"
    const val NATIVE_LIB = "libkmnative.so"
    const val CTL = "ctl.sh"
    const val MANIFEST = "MANIFEST.sha256"
    const val CONF = "helper.conf"
    const val PID = "helper.pid"
    const val LOG = "helper.log"
    const val PROCESS_NAME = "keymapperd"
    const val MAIN_CLASS = "games.forgemaster.keymapper.helper.HelperMain"
    const val CLI_CLASS = "games.forgemaster.keymapper.helper.Cli"
    const val APP_PACKAGE = "games.forgemaster.keymapper"
    const val SHELL_UID = 2000
}

/** `helper.conf`: key=value lines. The key never leaves this file, the app's private storage or adb. */
data class HelperConfig(
    val key: String,
    val socketName: String,
    val appPackage: String = HelperPaths.APP_PACKAGE,
    val appUid: Int = -1,
    /** "auto", "uinput" or "inputmanager". */
    val backend: String = "auto",
) {
    init {
        require(Handshake.isValidKey(key)) { "invalid key" }
        require(Handshake.isValidSocketName(socketName)) { "invalid socket name" }
    }

    fun serialize(): String = buildString {
        append("key=").append(key).append('\n')
        append("socket=").append(socketName).append('\n')
        append("package=").append(appPackage).append('\n')
        append("uid=").append(appUid).append('\n')
        append("backend=").append(backend).append('\n')
    }

    companion object {
        fun parse(text: String): HelperConfig {
            val map = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            return HelperConfig(
                key = map["key"] ?: error("helper.conf: missing key"),
                socketName = map["socket"] ?: error("helper.conf: missing socket"),
                appPackage = map["package"] ?: HelperPaths.APP_PACKAGE,
                appUid = map["uid"]?.toIntOrNull() ?: -1,
                backend = map["backend"] ?: "auto",
            )
        }

        fun load(dir: String = HelperPaths.DIR): HelperConfig = parse(File(dir, HelperPaths.CONF).readText())
    }
}

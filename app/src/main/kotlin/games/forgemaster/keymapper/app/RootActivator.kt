package games.forgemaster.keymapper.app

import android.content.Context
import android.os.Process
import games.forgemaster.keymapper.helper.HelperConfig
import games.forgemaster.keymapper.helper.HelperPaths
import games.forgemaster.keymapper.helper.client.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installs and starts the helper through `su`. The script (including helper.conf) goes through
 * stdin, so the key never appears on a command line.
 */
object RootActivator {
    private val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su")

    fun hasSu(): Boolean = suPaths.any { File(it).exists() } ||
        (System.getenv("PATH") ?: "").split(':').any { it.isNotEmpty() && File(it, "su").exists() }

    data class Result(val ok: Boolean, val output: String)

    suspend fun activate(context: Context, pairing: Pairing): Result = withContext(Dispatchers.IO) {
        val apk = context.applicationInfo.sourceDir
        val lib = File(context.applicationInfo.nativeLibraryDir, HelperPaths.NATIVE_LIB)
        if (!lib.exists()) return@withContext Result(false, "Native library not found at ${lib.path}")
        val ctl = File(context.cacheDir, HelperPaths.CTL)
        context.assets.open(HelperPaths.CTL).use { input -> ctl.outputStream().use { input.copyTo(it) } }
        ctl.setReadable(true, false)

        val conf = HelperConfig(
            key = pairing.key,
            socketName = pairing.socketName,
            appPackage = context.packageName,
            appUid = Process.myUid(),
        ).serialize()

        val script = buildString {
            appendLine("umask 077")
            appendLine("D=${HelperPaths.DIR}")
            appendLine("mkdir -p \"\$D\" || exit 1")
            appendLine("chmod 700 \"\$D\"")
            appendLine("cd \"\$D\" || exit 1")
            appendLine("cat '${apk}' > ${HelperPaths.APK} || exit 1")
            appendLine("cat '${lib.path}' > ${HelperPaths.NATIVE_LIB} || exit 1")
            appendLine("cat '${ctl.path}' > ${HelperPaths.CTL} || exit 1")
            appendLine("cat > ${HelperPaths.CONF} <<'KM_CONF_EOF'")
            append(conf)
            appendLine("KM_CONF_EOF")
            appendLine("sha256sum ${HelperPaths.APK} ${HelperPaths.NATIVE_LIB} ${HelperPaths.CTL} > ${HelperPaths.MANIFEST}")
            appendLine("chmod 600 ${HelperPaths.APK} ${HelperPaths.NATIVE_LIB} ${HelperPaths.CTL} ${HelperPaths.CONF} ${HelperPaths.MANIFEST}")
            appendLine("chown -R 2000:2000 \"\$D\"")
            appendLine("sh ${HelperPaths.CTL} restart")
            appendLine("exit \$?")
        }

        try {
            val proc = ProcessBuilder("su").redirectErrorStream(true).start()
            proc.outputStream.use { it.write(script.toByteArray()) }
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroy()
                return@withContext Result(false, output + "\n(timed out)")
            }
            Result(proc.exitValue() == 0, output.ifBlank { "exit ${proc.exitValue()}" })
        } catch (e: Exception) {
            Result(false, "Could not run su: ${e.message}")
        } finally {
            ctl.delete()
        }
    }
}

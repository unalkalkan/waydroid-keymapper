package games.forgemaster.keymapper.helper

import android.os.Build
import android.os.Looper
import android.system.Os
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point, started by ctl.sh as
 * `CLASSPATH=<dir>/helper.apk app_process /system/bin --nice-name=keymapperd <this class> --dir <dir>`.
 */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = args.indexOf("--dir").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: HelperPaths.DIR
        L.i("keymapperd ${HelperVersion.NAME} starting: uid ${Os.getuid()}, pid ${Os.getpid()}, sdk ${Build.VERSION.SDK_INT}, abi ${Build.SUPPORTED_ABIS.firstOrNull()}")
        try {
            Looper.prepareMainLooper()
        } catch (_: Exception) {
        }

        var config = try {
            HelperConfig.load(dir)
        } catch (e: Exception) {
            L.e("cannot read $dir/${HelperPaths.CONF}: ${e.message}")
            exitProcess(2)
        }
        if (config.appUid <= 0) {
            val uid = try {
                Os.stat("/data/data/${config.appPackage}").st_uid
            } catch (_: Exception) {
                -1
            }
            L.i("app uid not in config; resolved $uid from the data dir")
            config = config.copy(appUid = uid)
        }

        try {
            NativeInput.load("$dir/${HelperPaths.NATIVE_LIB}")
        } catch (e: Throwable) {
            L.e("cannot load native library", e)
            exitProcess(3)
        }

        val pid = Os.getpid()
        val pidFile = File(dir, HelperPaths.PID)
        pidFile.writeText("$pid\n")

        val daemon = Daemon(config)
        val signalFd = NativeInput.installSignalPipe()
        if (signalFd >= 0) {
            Thread({
                val sig = NativeInput.waitSignal(signalFd)
                daemon.shutdown("signal $sig")
            }, "km-signal").apply {
                isDaemon = true
                start()
            }
        }

        val code = try {
            daemon.run()
            0
        } catch (e: Throwable) {
            L.e("fatal", e)
            1
        }
        if (pidFile.readTextOrNull()?.trim() == pid.toString()) pidFile.delete()
        L.i("keymapperd exited ($code)")
        exitProcess(code)
    }

    private fun File.readTextOrNull() = try {
        readText()
    } catch (_: Exception) {
        null
    }
}

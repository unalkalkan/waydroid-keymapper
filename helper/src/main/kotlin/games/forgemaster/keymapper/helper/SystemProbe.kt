package games.forgemaster.keymapper.helper

import games.forgemaster.keymapper.core.engine.Screen
import java.util.concurrent.TimeUnit

/**
 * Display and foreground-app queries. Binder calls through hidden framework classes first; a fixed
 * `dumpsys` invocation (constant argv, no shell, nothing from clients) is the fallback.
 */
internal object SystemProbe {
    @Volatile private var displayReflectionBroken = false
    @Volatile private var tasksReflectionBroken = false

    fun screen(): Screen? {
        if (!displayReflectionBroken) {
            try {
                val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
                val info = dmg.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dmg, 0)
                if (info != null) {
                    val c = info.javaClass
                    return Screen(
                        c.getField("logicalWidth").getInt(info),
                        c.getField("logicalHeight").getInt(info),
                        c.getField("rotation").getInt(info),
                    )
                }
            } catch (e: Throwable) {
                displayReflectionBroken = true
                L.w("display query via DisplayManagerGlobal failed, using dumpsys: $e")
            }
        }
        return screenFromDumpsys()
    }

    private val displayInfoRe = Regex("""real (\d+) x (\d+).*?rotation (\d)""")

    private fun screenFromDumpsys(): Screen? {
        val text = run("/system/bin/dumpsys", "display") ?: return null
        val line = text.lineSequence().firstOrNull { "mOverrideDisplayInfo" in it || "mBaseDisplayInfo" in it } ?: return null
        val m = displayInfoRe.find(line) ?: return null
        return Screen(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    fun foregroundPackage(): String? {
        if (!tasksReflectionBroken) {
            try {
                return foregroundFromTasks()
            } catch (e: Throwable) {
                tasksReflectionBroken = true
                L.w("task query failed, using dumpsys: $e")
            }
        }
        return foregroundFromDumpsys()
    }

    private fun foregroundFromTasks(): String? {
        val service = try {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        } catch (_: ClassNotFoundException) {
            Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        }!!
        val method = service.javaClass.methods
            .filter { it.name == "getTasks" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType }
            .minByOrNull { it.parameterTypes.size }
            ?: throw NoSuchMethodException("getTasks")
        val args = method.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> 1
                t == Boolean::class.javaPrimitiveType -> false
                t == Int::class.javaPrimitiveType -> -1 // displayId: INVALID_DISPLAY = all displays
                else -> null
            }
        }.toTypedArray()
        val tasks = method.invoke(service, *args) as? List<*> ?: return null
        val task = tasks.firstOrNull() ?: return null
        val top = task.javaClass.getField("topActivity").get(task) as? android.content.ComponentName
        return top?.packageName
    }

    private val resumedRe = Regex("""(?:topResumedActivity|mResumedActivity|ResumedActivity)[=:].*? u\d+ ([\w.]+)/""")

    private fun foregroundFromDumpsys(): String? {
        val text = run("/system/bin/dumpsys", "activity", "activities") ?: return null
        return resumedRe.find(text)?.groupValues?.get(1)
    }

    private fun run(vararg argv: String): String? = try {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
        out
    } catch (e: Exception) {
        L.w("${argv.joinToString(" ")} failed: $e")
        null
    }
}

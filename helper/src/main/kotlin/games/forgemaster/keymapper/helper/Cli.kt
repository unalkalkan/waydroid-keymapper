package games.forgemaster.keymapper.helper

import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.engine.TouchAction
import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.KmJson
import games.forgemaster.keymapper.core.protocol.KeyCmd
import games.forgemaster.keymapper.core.protocol.LogicalInput
import games.forgemaster.keymapper.core.protocol.MsgType
import games.forgemaster.keymapper.core.protocol.RawEvent
import games.forgemaster.keymapper.core.protocol.SetEnabled
import games.forgemaster.keymapper.core.protocol.SetMonitor
import games.forgemaster.keymapper.core.protocol.SetPhase
import games.forgemaster.keymapper.core.protocol.SetProfiles
import games.forgemaster.keymapper.core.protocol.SetScreen
import games.forgemaster.keymapper.core.protocol.Shutdown
import games.forgemaster.keymapper.core.protocol.Status
import games.forgemaster.keymapper.core.protocol.TouchCmd
import games.forgemaster.keymapper.core.protocol.Wire
import games.forgemaster.keymapper.helper.client.HelperConnection
import games.forgemaster.keymapper.helper.client.Pairing
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * Test/maintenance client, run on the device as shell:
 * `CLASSPATH=/data/local/tmp/keymapper/helper.apk app_process /system/bin games.forgemaster.keymapper.helper.Cli <cmd>`
 * (scripts/kmctl wraps this from the PC).
 */
object Cli {
    private const val USAGE = """usage: Cli [--dir DIR] <command>
  status                         print helper status as JSON
  touch down|move|up P X Y       raw finger P (0-5) at logical pixel X,Y
  tap X Y [MS]                   finger down, wait MS (default 60), up
  swipe X1 Y1 X2 Y2 [MS]         drag over MS (default 300)
  key BUTTON [down|up|press]     synthetic controller button (A, B, L1, DPAD_UP, ...)
  axis AXIS VALUE                synthetic axis (LX LY RX RY in -1..1, LT RT in 0..1)
  stick left|right X Y [MS]      hold a stick deflection for MS (default 500), then centre
  load FILE.json [PKG...]        load a profile (export or bare); force it active, or with
                                 package names assign it to those games (auto-switch)
  unload                         clear all profiles
  phase N|next|prev              select a phase
  enable | disable               toggle mapping
  screen W H [ROT] | screen auto override the detected display
  monitor [SECONDS]              stream raw and logical controller events (default 10s)
  ping | shutdown"""

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    @JvmStatic
    fun main(argv: Array<String>) {
        val args = argv.toMutableList()
        var dir = HelperPaths.DIR
        val d = args.indexOf("--dir")
        if (d >= 0 && d + 1 < args.size) {
            dir = args[d + 1]
            args.removeAt(d + 1)
            args.removeAt(d)
        }
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
            println(USAGE)
            exitProcess(if (args.isEmpty()) 64 else 0)
        }
        val config = try {
            HelperConfig.load(dir)
        } catch (e: Exception) {
            fail("cannot read $dir/${HelperPaths.CONF}: ${e.message}")
        }
        val conn = try {
            HelperConnection.open(Pairing(config.socketName, config.key), "cli")
        } catch (e: Exception) {
            fail("cannot connect to the helper: ${e.message}")
        }
        try {
            run(conn, args)
            sync(conn)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            exitProcess(64)
        } finally {
            conn.close()
        }
    }

    private fun run(c: HelperConnection, a: List<String>) {
        fun arg(i: Int) = a.getOrNull(i) ?: throw IllegalArgumentException("missing argument ${i}")
        fun num(i: Int) = arg(i).toFloatOrNull() ?: throw IllegalArgumentException("not a number: ${a[i]}")
        when (a[0]) {
            "status" -> println(pretty.encodeToString(Status.serializer(), awaitStatus(c)))
            "touch" -> {
                val action = TouchAction.valueOf(arg(1).uppercase())
                touch(c, num(2).toInt(), action, num(3), num(4))
            }
            "tap" -> {
                val ms = a.getOrNull(3)?.toLongOrNull() ?: 60L
                touch(c, 0, TouchAction.DOWN, num(1), num(2))
                Thread.sleep(ms)
                touch(c, 0, TouchAction.UP, num(1), num(2))
            }
            "swipe" -> {
                val ms = a.getOrNull(5)?.toLongOrNull() ?: 300L
                val (x1, y1, x2, y2) = listOf(num(1), num(2), num(3), num(4))
                val steps = (ms / 16).coerceAtLeast(1)
                touch(c, 0, TouchAction.DOWN, x1, y1)
                for (i in 1..steps) {
                    Thread.sleep(16)
                    val t = i.toFloat() / steps
                    touch(c, 0, TouchAction.MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
                }
                touch(c, 0, TouchAction.UP, x2, y2)
            }
            "key" -> {
                val button = Button.valueOf(arg(1).uppercase())
                when (a.getOrNull(2) ?: "press") {
                    "down" -> key(c, KeyCmd(button = button, value = 1f))
                    "up" -> key(c, KeyCmd(button = button, value = 0f))
                    "press" -> {
                        key(c, KeyCmd(button = button, value = 1f))
                        Thread.sleep(80)
                        key(c, KeyCmd(button = button, value = 0f))
                    }
                    else -> throw IllegalArgumentException("key action must be down, up or press")
                }
            }
            "axis" -> key(c, KeyCmd(axis = Axis.valueOf(arg(1).uppercase()), value = num(2)))
            "stick" -> {
                val (ax, ay) = when (arg(1)) {
                    "left" -> Axis.LX to Axis.LY
                    "right" -> Axis.RX to Axis.RY
                    else -> throw IllegalArgumentException("stick must be left or right")
                }
                key(c, KeyCmd(axis = ax, value = num(2)))
                key(c, KeyCmd(axis = ay, value = num(3)))
                Thread.sleep(a.getOrNull(4)?.toLongOrNull() ?: 500L)
                key(c, KeyCmd(axis = ax, value = 0f))
                key(c, KeyCmd(axis = ay, value = 0f))
            }
            "load" -> {
                val export = KmJson.importProfile(File(arg(1)).readText())
                val p = export.profile
                val packages = a.drop(2)
                val msg = if (packages.isEmpty()) SetProfiles(listOf(p), forcedProfileId = p.id)
                else SetProfiles(listOf(p), assignments = packages.associateWith { p.id })
                c.send(MsgType.SET_PROFILES, SetProfiles.serializer(), msg)
                val how = if (packages.isEmpty()) "forced" else "for ${packages.joinToString()}"
                println("loaded '${p.name}' (${p.phases.size} phase(s), ${p.phases.sumOf { it.controls.size }} controls), $how")
            }
            "unload" -> c.send(MsgType.SET_PROFILES, SetProfiles.serializer(), SetProfiles(emptyList()))
            "phase" -> {
                val msg = when (val v = arg(1)) {
                    "next" -> SetPhase(delta = 1)
                    "prev" -> SetPhase(delta = -1)
                    else -> SetPhase(index = v.toIntOrNull() ?: throw IllegalArgumentException("bad phase $v"))
                }
                c.send(MsgType.SET_PHASE, SetPhase.serializer(), msg)
            }
            "enable", "disable" -> c.send(MsgType.SET_ENABLED, SetEnabled.serializer(), SetEnabled(a[0] == "enable"))
            "screen" -> {
                val screen = if (arg(1) == "auto") null else Screen(num(1).toInt(), num(2).toInt(), a.getOrNull(3)?.toIntOrNull() ?: 0)
                c.send(MsgType.SET_SCREEN, SetScreen.serializer(), SetScreen(screen))
            }
            "monitor" -> monitor(c, a.getOrNull(1)?.toLongOrNull() ?: 10L)
            "ping" -> {
                val t = System.nanoTime()
                sync(c)
                println("pong in %.2f ms".format((System.nanoTime() - t) / 1e6))
            }
            "shutdown" -> c.send(MsgType.SHUTDOWN, Shutdown.serializer(), Shutdown("cli"))
            else -> throw IllegalArgumentException("unknown command ${a[0]}")
        }
    }

    private fun touch(c: HelperConnection, pointer: Int, action: TouchAction, x: Float, y: Float) =
        c.send(MsgType.TOUCH, TouchCmd.serializer(), TouchCmd(pointer, action, x, y))

    private fun key(c: HelperConnection, cmd: KeyCmd) = c.send(MsgType.KEY, KeyCmd.serializer(), cmd)

    private fun awaitStatus(c: HelperConnection): Status {
        c.setReadTimeout(3000)
        while (true) {
            val f = c.read() ?: fail("helper closed the connection")
            if (f.type == MsgType.STATUS) return Wire.decode(Status.serializer(), f)
        }
    }

    /** Round-trips a PING so every earlier frame has been read by the helper before we exit. */
    private fun sync(c: HelperConnection) {
        c.send(MsgType.PING)
        c.setReadTimeout(3000)
        try {
            while (true) {
                val f = c.read() ?: return
                if (f.type == MsgType.PONG) return
            }
        } catch (_: java.io.IOException) {
            // Timed out. Some builds (Samsung, Android 13) report an expired SO_RCVTIMEO on a
            // LocalSocket as a plain IOException ("Try again") rather than SocketTimeoutException.
        }
    }

    private fun monitor(c: HelperConnection, seconds: Long) {
        c.send(MsgType.SET_MONITOR, SetMonitor.serializer(), SetMonitor(true))
        // No read timeout (see sync): a timer turns monitoring off and its PONG ends the loop.
        Thread {
            Thread.sleep(seconds * 1000)
            try {
                c.send(MsgType.SET_MONITOR, SetMonitor.serializer(), SetMonitor(false))
                c.send(MsgType.PING)
            } catch (_: java.io.IOException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        while (true) {
            val f = c.read() ?: break
            when (f.type) {
                MsgType.RAW_EVENT -> Wire.decode(RawEvent.serializer(), f).let {
                    println("raw   pad=${it.device} type=${it.type} code=0x%03x value=${it.value}".format(it.code))
                }
                MsgType.LOGICAL_EVENT -> Wire.decode(LogicalInput.serializer(), f).let {
                    println("input pad=${it.device} ${it.button ?: it.axis} = ${"%.3f".format(it.value)}")
                }
                MsgType.PONG -> break
            }
        }
    }

    private fun fail(msg: String): Nothing {
        System.err.println(msg)
        exitProcess(1)
    }
}

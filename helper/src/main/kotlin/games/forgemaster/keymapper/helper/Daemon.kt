package games.forgemaster.keymapper.helper

import android.net.LocalServerSocket
import android.os.Process
import games.forgemaster.keymapper.core.engine.MappingEngine
import games.forgemaster.keymapper.core.engine.PointerChange
import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.input.LogicalEvent
import games.forgemaster.keymapper.core.model.Profile
import games.forgemaster.keymapper.core.model.sanitized
import games.forgemaster.keymapper.core.protocol.ErrorMsg
import games.forgemaster.keymapper.core.protocol.Frame
import games.forgemaster.keymapper.core.protocol.KeyCmd
import games.forgemaster.keymapper.core.protocol.LogicalInput
import games.forgemaster.keymapper.core.protocol.MsgType
import games.forgemaster.keymapper.core.protocol.RawEvent
import games.forgemaster.keymapper.core.protocol.SetControllers
import games.forgemaster.keymapper.core.protocol.SetEnabled
import games.forgemaster.keymapper.core.protocol.SetMonitor
import games.forgemaster.keymapper.core.protocol.SetPhase
import games.forgemaster.keymapper.core.protocol.SetProfiles
import games.forgemaster.keymapper.core.protocol.SetScreen
import games.forgemaster.keymapper.core.protocol.Status
import games.forgemaster.keymapper.core.protocol.TouchCmd
import games.forgemaster.keymapper.core.protocol.Wire
import games.forgemaster.keymapper.helper.client.HelperConnection
import games.forgemaster.keymapper.helper.client.Pairing
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The helper process. One engine thread owns all mapping state; device readers, IPC sessions and
 * watchers hand work to it through [post].
 */
class Daemon(private val config: HelperConfig) : IpcServer.Dispatcher {
    private val queue = LinkedBlockingQueue<Runnable>()
    @Volatile private var running = true
    @Volatile private var monitorFlag = false
    @Volatile private var needForeground = false

    private lateinit var engine: MappingEngine
    private lateinit var emitter: Emitter
    private lateinit var devices: DeviceManager
    private lateinit var ipc: IpcServer

    // Engine-thread state.
    private var profiles: Map<String, Profile> = emptyMap()
    private var assignments: Map<String, String> = emptyMap()
    private var forcedProfileId: String? = null
    private var enabled = true
    private var foreground: String? = null
    private var autoScreen: Screen? = null
    private var overrideScreen: Screen? = null
    private var monitorOn = false
    private var lastStatus: Status? = null

    fun run() {
        val server = bindSocket()
        autoScreen = SystemProbe.screen()
        L.i("display: ${autoScreen ?: "unknown"}")
        emitter = Emitter.create(config.backend) { engine.screen }
        L.i("touch backend: ${emitter.name}")
        engine = MappingEngine(emitter, object : MappingEngine.Listener {
            override fun onPhaseChanged(index: Int, count: Int, name: String, byUser: Boolean) {
                if (byUser && engine.profile?.rumbleOnPhaseSwitch == true) devices.rumble(0x5000, 0x7000, 90)
                publishStatus()
            }
        })
        engine.setEnabled(false, now())
        engine.setScreen(currentScreen(), now())

        devices = DeviceManager(
            sink = { _, events -> post { applyInput(events) } },
            monitor = object : DeviceManager.Monitor {
                override val enabled get() = monitorFlag
                override fun raw(padId: Int, type: Int, code: Int, value: Int) =
                    ipc.broadcast(MsgType.RAW_EVENT, Wire.encode(RawEvent.serializer(), RawEvent(padId, type, code, value)), true)

                override fun logical(padId: Int, event: LogicalEvent) {
                    val msg = when (event) {
                        is LogicalEvent.ButtonChange -> LogicalInput(padId, button = event.button, value = if (event.pressed) 1f else 0f)
                        is LogicalEvent.AxisChange -> LogicalInput(padId, axis = event.axis, value = event.value)
                    }
                    ipc.broadcast(MsgType.LOGICAL_EVENT, Wire.encode(LogicalInput.serializer(), msg), true)
                }
            },
            onChanged = { post { publishStatus() } },
        )
        ipc = IpcServer(server, config, this)
        ipc.start()
        devices.start()
        startWatcher()
        L.i("ready on @${config.socketName} (app uid ${config.appUid})")
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {
        }
        loop()

        L.i("shutting down")
        runSafely { engine.setEnabled(false, now()) }
        runSafely { emitter.close() }
        devices.stop()
        ipc.close()
    }

    fun shutdown(reason: String) {
        L.i("shutdown requested: $reason")
        running = false
        queue.offer(Runnable {})
    }

    private fun post(task: Runnable) {
        queue.offer(task)
    }

    private fun loop() {
        while (running) {
            val deadline = listOfNotNull(engine.nextDeadline(), emitter.nextDeadline()).minOrNull()
            val waitNs = if (deadline == null) IDLE_WAIT_NS else (deadline - System.nanoTime()).coerceAtLeast(0L)
            val first = if (waitNs == 0L) queue.poll() else queue.poll(waitNs, TimeUnit.NANOSECONDS)
            if (first != null) {
                runSafely(first)
                while (true) runSafely(queue.poll() ?: break)
            }
            val due = engine.nextDeadline()
            if (due != null && System.nanoTime() >= due) runSafely { engine.tick(System.nanoTime()) }
            val emitterDue = emitter.nextDeadline()
            if (emitterDue != null && System.nanoTime() >= emitterDue) runSafely { emitter.tick(System.nanoTime()) }
        }
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            L.e("engine task failed", e)
        }
    }

    private fun runSafely(task: Runnable) = runSafely { task.run() }

    private fun now() = System.nanoTime()

    private fun applyInput(events: List<LogicalEvent>) {
        val t = now()
        for (e in events) when (e) {
            is LogicalEvent.ButtonChange -> engine.onButton(e.button, e.pressed, t)
            is LogicalEvent.AxisChange -> engine.onAxis(e.axis, e.value, t)
        }
    }

    private fun currentScreen(): Screen = overrideScreen ?: autoScreen ?: Screen(1280, 720)

    /** Picks the profile (forced, else by foreground game), then decides mapping and grabbing. */
    private fun reselect() {
        val t = now()
        val target = forcedProfileId?.let(profiles::get) ?: foreground?.let(assignments::get)?.let(profiles::get)
        if (target != engine.profile) engine.setProfile(target, t)
        val active = enabled && target != null && !monitorOn
        engine.setEnabled(active, t)
        devices.setGrab((active && target?.grab == true) || monitorOn)
        needForeground = forcedProfileId == null && assignments.isNotEmpty()
        publishStatus()
    }

    private fun publishStatus() {
        if (!::ipc.isInitialized) return
        val status = buildStatus()
        if (status == lastStatus) return
        lastStatus = status
        ipc.broadcast(MsgType.STATUS, Wire.encode(Status.serializer(), status))
    }

    private fun buildStatus(): Status {
        val p = engine.profile
        return Status(
            helperVersion = HelperVersion.NAME,
            uid = android.os.Process.myUid(),
            backend = emitter.name,
            enabled = enabled,
            mapping = engine.enabled && p != null,
            monitor = monitorOn,
            profileId = p?.id,
            profileName = p?.name,
            phase = engine.phase,
            phaseCount = p?.phases?.size ?: 0,
            phaseName = p?.phases?.getOrNull(engine.phase)?.name,
            foreground = foreground,
            screen = engine.screen,
            controllers = devices.snapshot(),
            clients = ipc.sessions.size,
        )
    }

    private fun startWatcher() {
        Thread({
            while (running) {
                try {
                    val s = SystemProbe.screen()
                    if (s != null) post {
                        if (s != autoScreen) {
                            autoScreen = s
                            L.i("display changed: $s")
                            engine.setScreen(currentScreen(), now())
                            publishStatus()
                        }
                    }
                    if (needForeground) {
                        val fg = SystemProbe.foregroundPackage()
                        post {
                            if (fg != foreground) {
                                foreground = fg
                                reselect()
                            }
                        }
                    }
                    Thread.sleep(WATCH_MS)
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    L.w("watcher", e)
                }
            }
        }, "km-watch").apply {
            isDaemon = true
            start()
        }
    }

    // --- IPC (called on session threads) ---

    override fun onSessionOpened(session: IpcServer.Session) {
        post {
            session.send(MsgType.STATUS, Wire.encode(Status.serializer(), buildStatus()))
            publishStatus()
        }
    }

    override fun onSessionClosed(session: IpcServer.Session) {
        post {
            if (session.monitor) updateMonitor()
            publishStatus()
        }
    }

    override fun onFrame(session: IpcServer.Session, frame: Frame) {
        when (frame.type) {
            MsgType.SET_PROFILES -> {
                val m = Wire.decode(SetProfiles.serializer(), frame)
                post {
                    profiles = m.profiles.map { it.sanitized() }.associateBy { it.id }
                    assignments = m.assignments
                    forcedProfileId = m.forcedProfileId
                    if (forcedProfileId != null || assignments.isEmpty()) foreground = null
                    reselect()
                }
            }
            MsgType.SET_PHASE -> {
                val m = Wire.decode(SetPhase.serializer(), frame)
                post {
                    engine.selectPhase(m.index, m.delta, now())
                    publishStatus()
                }
            }
            MsgType.SET_ENABLED -> {
                val m = Wire.decode(SetEnabled.serializer(), frame)
                post {
                    enabled = m.enabled
                    reselect()
                }
            }
            MsgType.SET_SCREEN -> {
                val m = Wire.decode(SetScreen.serializer(), frame)
                post {
                    overrideScreen = m.screen?.takeIf { it.width in 1..16384 && it.height in 1..16384 }
                    engine.setScreen(currentScreen(), now())
                    publishStatus()
                }
            }
            MsgType.TOUCH -> {
                val m = Wire.decode(TouchCmd.serializer(), frame)
                post { manualTouch(m) }
            }
            MsgType.KEY -> {
                val m = Wire.decode(KeyCmd.serializer(), frame)
                post {
                    m.button?.let { engine.onButton(it, m.value >= 0.5f, now()) }
                    m.axis?.let { engine.onAxis(it, m.value.coerceIn(if (it.isTrigger) 0f else -1f, 1f), now()) }
                }
            }
            MsgType.SET_CONTROLLERS -> {
                val m = Wire.decode(SetControllers.serializer(), frame)
                devices.setConfigs(m.configs)
                devices.setSelection(m.selected.toSet())
                post { publishStatus() }
            }
            MsgType.SET_MONITOR -> {
                val m = Wire.decode(SetMonitor.serializer(), frame)
                session.monitor = m.enabled
                post { updateMonitor() }
            }
            MsgType.PING -> session.send(MsgType.PONG)
            MsgType.SHUTDOWN -> shutdown("requested by $session")
            else -> session.send(MsgType.ERROR, Wire.encode(ErrorMsg.serializer(), ErrorMsg("unknown frame ${frame.type}")))
        }
    }

    private fun updateMonitor() {
        monitorOn = ipc.sessions.any { it.monitor }
        monitorFlag = monitorOn
        reselect()
    }

    private fun manualTouch(m: TouchCmd) {
        val s = engine.screen
        val id = Emitter.MANUAL_BASE + m.pointer.coerceIn(0, Emitter.SLOTS - Emitter.MANUAL_BASE - 1)
        val x = m.x.coerceIn(0f, (s.width - 1).toFloat())
        val y = m.y.coerceIn(0f, (s.height - 1).toFloat())
        emitter.apply(listOf(PointerChange(id, m.action, x, y)))
    }

    /** Binds the socket, asking a previous helper instance to exit if it still holds the name. */
    private fun bindSocket(): LocalServerSocket {
        repeat(4) { attempt ->
            try {
                return LocalServerSocket(config.socketName)
            } catch (e: IOException) {
                if (attempt == 3) throw e
                L.i("socket name in use; asking the previous helper to exit")
                try {
                    HelperConnection.open(Pairing(config.socketName, config.key), "takeover").use {
                        it.send(MsgType.SHUTDOWN, games.forgemaster.keymapper.core.protocol.Shutdown.serializer(), games.forgemaster.keymapper.core.protocol.Shutdown("replaced"))
                    }
                } catch (t: Exception) {
                    L.w("takeover failed: ${t.message}")
                }
                Thread.sleep(700)
            }
        }
        error("unreachable")
    }

    private companion object {
        const val IDLE_WAIT_NS = 1_000_000_000L
        const val WATCH_MS = 1000L
    }
}

package games.forgemaster.keymapper.helper.client

import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.protocol.ErrorMsg
import games.forgemaster.keymapper.core.protocol.Hello
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
import games.forgemaster.keymapper.core.protocol.Shutdown
import games.forgemaster.keymapper.core.protocol.Status
import games.forgemaster.keymapper.core.protocol.TouchCmd
import games.forgemaster.keymapper.core.protocol.Wire
import kotlinx.serialization.KSerializer

/**
 * Keeps one authenticated connection to the helper alive, reconnecting with backoff, and delivers
 * helper messages to [listener] on its own background thread.
 */
class HelperClient(
    private val client: String,
    private val pairing: () -> Pairing?,
    private val listener: Listener,
) {
    interface Listener {
        /** Called after every (re)connect: push the full desired state here. */
        fun onConnected(hello: Hello) {}
        fun onDisconnected(reason: String) {}
        fun onStatus(status: Status) {}
        fun onRawEvent(event: RawEvent) {}
        fun onLogicalInput(input: LogicalInput) {}
        fun onHelperError(message: String) {}
    }

    @Volatile private var running = false
    @Volatile private var connection: HelperConnection? = null
    @Volatile private var lastReason: String? = null
    private var thread: Thread? = null

    val isConnected: Boolean get() = connection != null
    val hello: Hello? get() = connection?.hello

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "km-helper-client").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        connection?.close()
        thread?.interrupt()
        thread = null
    }

    /** Drops the current connection so the loop reconnects immediately (e.g. after re-pairing). */
    fun reconnect() {
        connection?.close()
        thread?.interrupt()
    }

    fun send(type: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val c = connection ?: return false
        return try {
            c.send(type, payload)
            true
        } catch (e: Exception) {
            c.close()
            false
        }
    }

    fun <T> send(type: Int, serializer: KSerializer<T>, value: T): Boolean = send(type, Wire.encode(serializer, value))

    fun setProfiles(msg: SetProfiles) = send(MsgType.SET_PROFILES, SetProfiles.serializer(), msg)
    fun setPhase(index: Int? = null, delta: Int = 0) = send(MsgType.SET_PHASE, SetPhase.serializer(), SetPhase(index, delta))
    fun setEnabled(enabled: Boolean) = send(MsgType.SET_ENABLED, SetEnabled.serializer(), SetEnabled(enabled))
    fun setControllers(msg: SetControllers) = send(MsgType.SET_CONTROLLERS, SetControllers.serializer(), msg)
    fun setMonitor(enabled: Boolean) = send(MsgType.SET_MONITOR, SetMonitor.serializer(), SetMonitor(enabled))
    fun setScreen(screen: Screen?) = send(MsgType.SET_SCREEN, SetScreen.serializer(), SetScreen(screen))
    fun touch(cmd: TouchCmd) = send(MsgType.TOUCH, TouchCmd.serializer(), cmd)
    fun key(cmd: KeyCmd) = send(MsgType.KEY, KeyCmd.serializer(), cmd)
    fun shutdown(reason: String = "app") = send(MsgType.SHUTDOWN, Shutdown.serializer(), Shutdown(reason))

    private fun loop() {
        var backoff = 500L
        while (running) {
            val p = pairing()
            if (p == null) {
                disconnected("not paired")
                sleep(2000)
                continue
            }
            try {
                val c = HelperConnection.open(p, client)
                connection = c
                lastReason = null
                backoff = 500L
                listener.onConnected(c.hello)
                readLoop(c)
                disconnected("helper closed the connection")
            } catch (e: Exception) {
                disconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                connection?.close()
                connection = null
            }
            if (!running) break
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(5000L)
        }
    }

    private fun readLoop(c: HelperConnection) {
        while (running) {
            val frame = c.read() ?: return
            when (frame.type) {
                MsgType.STATUS -> listener.onStatus(Wire.decode(Status.serializer(), frame))
                MsgType.RAW_EVENT -> listener.onRawEvent(Wire.decode(RawEvent.serializer(), frame))
                MsgType.LOGICAL_EVENT -> listener.onLogicalInput(Wire.decode(LogicalInput.serializer(), frame))
                MsgType.ERROR -> listener.onHelperError(Wire.decode(ErrorMsg.serializer(), frame).message)
                else -> Unit
            }
        }
    }

    private fun disconnected(reason: String) {
        if (reason == lastReason) return
        lastReason = reason
        listener.onDisconnected(reason)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}

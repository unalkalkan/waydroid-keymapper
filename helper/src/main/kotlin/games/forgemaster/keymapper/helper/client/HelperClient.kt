package games.forgemaster.keymapper.helper.client

import android.os.ParcelFileDescriptor
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
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps one authenticated connection to the helper alive, reconnecting with backoff, and delivers
 * helper messages to [listener] on its own background thread. It dials the helper's abstract
 * socket, or takes a socket the helper hands over through [offer] (the only way in on stock Android,
 * where SELinux won't let an app connect to a socket owned by adb shell).
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
    private val offers = LinkedBlockingQueue<ParcelFileDescriptor>()

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
        while (true) closeQuietly(offers.poll() ?: break)
    }

    /** A connected socket from the helper; used for the next connection attempt. Takes ownership. */
    fun offer(fd: ParcelFileDescriptor) {
        if (!running || connection != null) closeQuietly(fd) else offers.offer(fd)
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
        var handed: ParcelFileDescriptor? = null
        while (running) {
            val p = pairing()
            if (p == null) {
                handed?.let(::closeQuietly)
                handed = null
                disconnected("not paired")
                sleep(2000)
                continue
            }
            try {
                val fd = handed ?: offers.poll()
                handed = null
                val c = if (fd != null) HelperConnection.adopt(fd, p, client) else HelperConnection.open(p, client)
                connection = c
                lastReason = null
                backoff = 500L
                listener.onConnected(c.hello)
                readLoop(c)
                disconnected("helper closed the connection")
            } catch (e: Exception) {
                disconnected(reason(e))
            } finally {
                connection?.close()
                connection = null
            }
            if (!running) break
            handed = try {
                offers.poll(backoff, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            backoff = (backoff * 2).coerceAtMost(5000L)
        }
        handed?.let(::closeQuietly)
    }

    /** EACCES on connect: a helper is listening but SELinux keeps apps off it; it hands a socket over instead. */
    private fun reason(e: Exception): String =
        if (e is IOException && e.message?.contains("Permission denied") == true) "waiting for the helper to connect"
        else e.message ?: e.javaClass.simpleName

    private fun closeQuietly(fd: ParcelFileDescriptor) {
        try {
            fd.close()
        } catch (_: IOException) {
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

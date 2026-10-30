package games.forgemaster.keymapper.helper

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import games.forgemaster.keymapper.core.protocol.Auth
import games.forgemaster.keymapper.core.protocol.AuthOk
import games.forgemaster.keymapper.core.protocol.Frame
import games.forgemaster.keymapper.core.protocol.FrameCodec
import games.forgemaster.keymapper.core.protocol.Handshake
import games.forgemaster.keymapper.core.protocol.Hello
import games.forgemaster.keymapper.core.protocol.MsgType
import games.forgemaster.keymapper.core.protocol.PROTOCOL_VERSION
import games.forgemaster.keymapper.core.protocol.Wire
import kotlinx.serialization.KSerializer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Abstract-namespace socket server. A peer is served only if the kernel-reported uid (SO_PEERCRED)
 * is the paired app, root, or the helper's own uid, and only after it proves the shared key.
 * Anything else is closed without a reply.
 */
class IpcServer(
    private val server: LocalServerSocket,
    private val config: HelperConfig,
    private val dispatcher: Dispatcher,
) {
    interface Dispatcher {
        fun onSessionOpened(session: Session)
        fun onFrame(session: Session, frame: Frame)
        fun onSessionClosed(session: Session)
    }

    inner class Session(private val socket: LocalSocket, val uid: Int, val pid: Int) {
        val id = ids.incrementAndGet()
        @Volatile var client: String = "?"
        @Volatile var monitor = false
        private val out = BufferedOutputStream(socket.outputStream)
        private val writeLock = Any()

        fun send(type: Int, payload: ByteArray = ByteArray(0)): Boolean = try {
            synchronized(writeLock) { FrameCodec.write(out, type, payload) }
            true
        } catch (_: IOException) {
            close()
            false
        }

        fun <T> send(type: Int, serializer: KSerializer<T>, value: T) = send(type, Wire.encode(serializer, value))

        fun close() {
            try {
                socket.shutdownInput()
            } catch (_: IOException) {
            }
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }

        override fun toString() = "session $id ($client, uid $uid, pid $pid)"
    }

    private val ids = AtomicInteger()
    private val myUid = Os.getuid()
    val sessions = CopyOnWriteArrayList<Session>()
    @Volatile private var running = true

    fun start() {
        Thread(::acceptLoop, "km-ipc-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun broadcast(type: Int, payload: ByteArray, monitorOnly: Boolean = false) {
        for (s in sessions) if (!monitorOnly || s.monitor) s.send(type, payload)
    }

    fun close() {
        running = false
        sessions.forEach { it.close() }
        try {
            server.close()
        } catch (_: IOException) {
        }
    }

    private fun allowed(uid: Int): Boolean {
        if (uid == 0 || uid == myUid) return true
        val app = config.appUid
        return app > 0 && uid % PER_USER_RANGE == app % PER_USER_RANGE
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running) L.w("accept failed", e)
                continue
            }
            val cred = try {
                socket.peerCredentials
            } catch (_: IOException) {
                null
            }
            if (cred == null || !allowed(cred.uid)) {
                L.w("rejected connection from uid ${cred?.uid} pid ${cred?.pid}")
                closeQuietly(socket)
                continue
            }
            if (sessions.size >= MAX_SESSIONS) {
                L.w("too many sessions; rejecting uid ${cred.uid}")
                closeQuietly(socket)
                continue
            }
            Thread({ serve(socket, cred.uid, cred.pid) }, "km-ipc-${cred.pid}").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(socket: LocalSocket, uid: Int, pid: Int) {
        val session = Session(socket, uid, pid)
        var opened = false
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.inputStream))
            val nonce = Handshake.newNonce()
            session.send(MsgType.HELLO, Hello.serializer(), Hello(PROTOCOL_VERSION, HelperVersion.NAME, nonce, Os.getpid(), myUid))
            val frame = FrameCodec.read(input) ?: return
            if (frame.type != MsgType.AUTH) return
            val auth = Wire.decode(Auth.serializer(), frame)
            if (auth.nonce.length != 32 || !auth.nonce.all { it in '0'..'9' || it in 'a'..'f' }) return
            if (!Handshake.verify(Handshake.clientMac(config.key, nonce, auth.nonce), auth.mac)) {
                L.w("authentication failed for uid $uid pid $pid")
                return
            }
            session.send(MsgType.AUTH_OK, AuthOk.serializer(), AuthOk(Handshake.helperMac(config.key, nonce, auth.nonce)))
            socket.soTimeout = 0
            session.client = auth.client.take(16)
            sessions.add(session)
            opened = true
            L.i("connected: $session")
            dispatcher.onSessionOpened(session)
            while (running) {
                val f = FrameCodec.read(input) ?: break
                try {
                    dispatcher.onFrame(session, f)
                } catch (e: Exception) {
                    L.w("bad frame ${f.type} from $session: $e")
                    session.send(MsgType.ERROR, Wire.encode(games.forgemaster.keymapper.core.protocol.ErrorMsg.serializer(),
                        games.forgemaster.keymapper.core.protocol.ErrorMsg("bad frame ${f.type}: ${e.message}")))
                }
            }
        } catch (e: Exception) {
            if (opened && running) L.i("$session ended: ${e.message}")
        } finally {
            session.close()
            if (opened) {
                sessions.remove(session)
                L.i("disconnected: $session")
                dispatcher.onSessionClosed(session)
            }
        }
    }

    private fun closeQuietly(socket: LocalSocket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val PER_USER_RANGE = 100000
        const val MAX_SESSIONS = 8
        const val HANDSHAKE_TIMEOUT_MS = 3000
    }
}

package games.forgemaster.keymapper.helper.client

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import games.forgemaster.keymapper.core.protocol.Auth
import games.forgemaster.keymapper.core.protocol.AuthOk
import games.forgemaster.keymapper.core.protocol.Frame
import games.forgemaster.keymapper.core.protocol.FrameCodec
import games.forgemaster.keymapper.core.protocol.Handshake
import games.forgemaster.keymapper.core.protocol.Hello
import games.forgemaster.keymapper.core.protocol.MsgType
import games.forgemaster.keymapper.core.protocol.PROTOCOL_VERSION
import games.forgemaster.keymapper.core.protocol.Wire
import games.forgemaster.keymapper.helper.HelperPaths
import kotlinx.serialization.KSerializer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException

/** Socket name + shared key, produced by pairing. */
data class Pairing(val socketName: String, val key: String)

/** One authenticated connection to the helper. Reads must come from one thread; sends are thread-safe. */
class HelperConnection private constructor(
    private val stream: SocketStream,
    private val input: DataInputStream,
    private val output: BufferedOutputStream,
    val hello: Hello,
) : Closeable {
    private val writeLock = Any()

    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        synchronized(writeLock) { FrameCodec.write(output, type, payload) }
    }

    fun <T> send(type: Int, serializer: KSerializer<T>, value: T) = send(type, Wire.encode(serializer, value))

    /** Blocks for the next frame; null when the helper closed the connection. */
    fun read(): Frame? = FrameCodec.read(input)

    fun setReadTimeout(ms: Int) = stream.setReadTimeout(ms)

    override fun close() = stream.close()

    companion object {
        /**
         * Connects and runs the mutual handshake. Refuses a server that isn't running as root or
         * shell, so another app squatting the socket name can't impersonate the helper.
         */
        fun open(pairing: Pairing, client: String, timeoutMs: Int = 3000): HelperConnection {
            val socket = LocalSocket()
            val stream = SocketStream.of(socket)
            try {
                socket.connect(LocalSocketAddress(pairing.socketName, LocalSocketAddress.Namespace.ABSTRACT))
                val peer = socket.peerCredentials
                if (peer.uid != 0 && peer.uid != HelperPaths.SHELL_UID) {
                    throw SecurityException("socket is owned by uid ${peer.uid}, not the helper")
                }
                return handshake(stream, pairing, client, timeoutMs)
            } catch (e: Exception) {
                stream.close()
                throw e
            }
        }

        /**
         * Runs the handshake over a socket the helper handed over (the app's provider has already
         * checked the caller is shell or root). Takes ownership of [fd].
         */
        fun adopt(fd: ParcelFileDescriptor, pairing: Pairing, client: String, timeoutMs: Int = 3000): HelperConnection {
            val stream = SocketStream.of(fd.fileDescriptor) { fd.close() }
            try {
                return handshake(stream, pairing, client, timeoutMs)
            } catch (e: Exception) {
                stream.close()
                throw e
            }
        }

        private fun handshake(stream: SocketStream, pairing: Pairing, client: String, timeoutMs: Int): HelperConnection {
            stream.setReadTimeout(timeoutMs)
            val input = DataInputStream(BufferedInputStream(stream.input))
            val output = BufferedOutputStream(stream.output)
            val first = FrameCodec.read(input) ?: throw IOException("helper closed the connection")
            if (first.type != MsgType.HELLO) throw IOException("unexpected frame ${first.type}")
            val hello = Wire.decode(Hello.serializer(), first)
            if (hello.proto != PROTOCOL_VERSION) {
                throw IOException("protocol mismatch: helper ${hello.proto}, app $PROTOCOL_VERSION")
            }
            val nonce = Handshake.newNonce()
            val auth = Auth(nonce, Handshake.clientMac(pairing.key, hello.nonce, nonce), client)
            FrameCodec.write(output, MsgType.AUTH, Wire.encode(Auth.serializer(), auth))
            val reply = FrameCodec.read(input) ?: throw SecurityException("helper rejected the key (re-pair)")
            if (reply.type != MsgType.AUTH_OK) throw IOException("unexpected frame ${reply.type}")
            val ok = Wire.decode(AuthOk.serializer(), reply)
            if (!Handshake.verify(Handshake.helperMac(pairing.key, hello.nonce, nonce), ok.mac)) {
                throw SecurityException("helper failed to prove the key")
            }
            stream.setReadTimeout(0)
            return HelperConnection(stream, input, output, hello)
        }
    }
}

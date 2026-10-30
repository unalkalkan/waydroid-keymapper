package games.forgemaster.keymapper.core.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Frame type ids. Frame layout: `u32 length (big-endian, includes the type byte) | u8 type | payload`. */
object MsgType {
    const val HELLO = 0x01
    const val AUTH = 0x02
    const val AUTH_OK = 0x03

    const val SET_PROFILES = 0x10
    const val SET_PHASE = 0x11
    const val SET_SCREEN = 0x12
    const val TOUCH = 0x13
    const val KEY = 0x14
    const val SET_CONTROLLERS = 0x15
    const val SET_MONITOR = 0x16
    const val SET_ENABLED = 0x17

    const val STATUS = 0x20
    const val RAW_EVENT = 0x21
    const val LOGICAL_EVENT = 0x22

    const val PING = 0x30
    const val PONG = 0x31
    const val SHUTDOWN = 0x40
    const val ERROR = 0x7f
}

class Frame(val type: Int, val payload: ByteArray) {
    val text: String get() = payload.toString(Charsets.UTF_8)
}

class ProtocolException(message: String) : IOException(message)

object FrameCodec {
    const val MAX_FRAME = 1 shl 20

    fun write(out: OutputStream, type: Int, payload: ByteArray) {
        require(payload.size < MAX_FRAME) { "frame too large" }
        val header = ByteArray(5)
        val len = payload.size + 1
        header[0] = (len ushr 24).toByte()
        header[1] = (len ushr 16).toByte()
        header[2] = (len ushr 8).toByte()
        header[3] = len.toByte()
        header[4] = type.toByte()
        // One write per frame keeps frames from interleaving when callers synchronize on the stream.
        out.write(header + payload)
        out.flush()
    }

    /** Returns null on a clean end of stream before a frame starts. */
    fun read(input: InputStream): Frame? {
        val data = if (input is DataInputStream) input else DataInputStream(input)
        val len = try {
            data.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (len < 1 || len > MAX_FRAME) throw ProtocolException("bad frame length $len")
        val type = data.readUnsignedByte()
        val payload = ByteArray(len - 1)
        data.readFully(payload)
        return Frame(type, payload)
    }

}

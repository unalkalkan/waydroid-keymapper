package games.forgemaster.keymapper.helper.client

import android.net.LocalSocket
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A connected stream socket under one helper session: a [LocalSocket] on the abstract socket, or
 * a raw fd for one end of a socket pair that the helper handed to the app (see AppLink).
 */
class SocketStream private constructor(
    val input: InputStream,
    val output: OutputStream,
    private val timeout: (Int) -> Unit,
    private val closer: () -> Unit,
) : Closeable {
    /** 0 = block forever. A read that times out fails or reads as end of stream. */
    fun setReadTimeout(ms: Int) = timeout(ms)

    override fun close() = closer()

    companion object {
        fun of(socket: LocalSocket) = SocketStream(socket.inputStream, socket.outputStream, { socket.soTimeout = it }) {
            try {
                socket.shutdownInput()
            } catch (_: IOException) {
            }
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }

        /** [release] closes the fd itself (or its owner); a shutdown first wakes a blocked reader. */
        fun of(fd: FileDescriptor, release: () -> Unit) = SocketStream(FileInputStream(fd), FileOutputStream(fd), { ms ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(ms.toLong()))
                } catch (_: ErrnoException) {
                }
            }
        }) {
            try {
                Os.shutdown(fd, OsConstants.SHUT_RDWR)
            } catch (_: ErrnoException) {
            }
            try {
                release()
            } catch (_: Exception) {
            }
        }
    }
}

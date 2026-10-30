package games.forgemaster.keymapper.helper

/** JNI bridge to libkmnative.so (raw evdev/uinput ioctls). Negative ints are -errno. */
object NativeInput {
    @Volatile
    var loaded = false
        private set

    fun load(path: String) {
        if (loaded) return
        System.load(path)
        loaded = true
    }

    @JvmStatic external fun open(path: String, rw: Boolean): Int
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun grab(fd: Int, on: Boolean): Int
    @JvmStatic external fun name(fd: Int): String?
    /** bustype, vendor, product, version */
    @JvmStatic external fun id(fd: Int): IntArray?
    /** EVIOCGBIT bitmap for an event type; type 0 returns the event-type bitmap. */
    @JvmStatic external fun bits(fd: Int, type: Int): ByteArray?
    @JvmStatic external fun props(fd: Int): ByteArray?
    /** value, min, max, fuzz, flat, resolution */
    @JvmStatic external fun absInfo(fd: Int, code: Int): IntArray?
    /** Fills (type, code, value) triples; returns the event count, 0 on timeout, -errno on error. */
    @JvmStatic external fun read(fd: Int, out: IntArray, timeoutMs: Int): Int
    /** Returns the effect id to reuse next time, or -errno. */
    @JvmStatic external fun rumble(fd: Int, effectId: Int, strong: Int, weak: Int, ms: Int): Int
    @JvmStatic external fun uinputCreate(name: String, slots: Int): Int
    @JvmStatic external fun uinputEmit(fd: Int, events: IntArray, count: Int): Int
    @JvmStatic external fun uinputDestroy(fd: Int)
    @JvmStatic external fun installSignalPipe(): Int
    @JvmStatic external fun waitSignal(fd: Int): Int

    fun hasBit(bits: ByteArray?, bit: Int): Boolean {
        if (bits == null || bit < 0) return false
        val i = bit / 8
        return i < bits.size && (bits[i].toInt() shr (bit % 8)) and 1 == 1
    }

    fun setBits(bits: ByteArray?): List<Int> {
        if (bits == null) return emptyList()
        val out = ArrayList<Int>()
        for (i in bits.indices) {
            val b = bits[i].toInt() and 0xff
            if (b == 0) continue
            for (j in 0 until 8) if (b shr j and 1 == 1) out.add(i * 8 + j)
        }
        return out
    }

    const val EINTR = 4
    const val ENODEV = 19
    const val EACCES = 13
    const val ENOENT = 2
}

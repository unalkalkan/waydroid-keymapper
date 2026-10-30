package games.forgemaster.keymapper.helper

import games.forgemaster.keymapper.core.input.AbsRange
import games.forgemaster.keymapper.core.input.DeviceCaps
import games.forgemaster.keymapper.core.input.DeviceMapper
import games.forgemaster.keymapper.core.input.Ev
import games.forgemaster.keymapper.core.input.LogicalEvent
import games.forgemaster.keymapper.core.model.ControllerConfig
import games.forgemaster.keymapper.core.model.controllerKey
import games.forgemaster.keymapper.core.protocol.ControllerInfo
import java.io.File

/**
 * Finds gamepads under /dev/input (polling for hotplug), reads them on one thread each, and
 * grabs or releases them on request. Logical events from active pads go to [sink] in per-report
 * batches; everything is mirrored to [monitor] while monitoring is on.
 */
class DeviceManager(
    private val sink: (padId: Int, events: List<LogicalEvent>) -> Unit,
    private val monitor: Monitor,
    private val onChanged: () -> Unit,
) {
    interface Monitor {
        val enabled: Boolean
        fun raw(padId: Int, type: Int, code: Int, value: Int)
        fun logical(padId: Int, event: LogicalEvent)
    }

    private inner class Pad(val id: Int, val path: String, val fd: Int, val writable: Boolean, val caps: DeviceCaps) {
        val key = controllerKey(caps.vendor, caps.product)
        @Volatile var mapper = DeviceMapper(caps, configs[key])
        @Volatile var grabbed = false
        @Volatile var active = false
        @Volatile var alive = true
        var effectId = -1
        val thread = Thread({ readLoop(this) }, "km-pad-$id")
    }

    private val lock = Any()
    private val pads = LinkedHashMap<String, Pad>()
    private val ignored = HashSet<String>() // non-gamepad nodes already probed
    private var nextId = 1
    @Volatile private var running = false
    @Volatile private var wantGrab = false
    @Volatile private var selected: Set<String> = emptySet()
    @Volatile private var configs: Map<String, ControllerConfig> = emptyMap()
    private var scanner: Thread? = null

    fun start() {
        running = true
        scanner = Thread({
            while (running) {
                try {
                    scan()
                } catch (e: Exception) {
                    L.w("input scan failed", e)
                }
                try {
                    Thread.sleep(SCAN_MS)
                } catch (_: InterruptedException) {
                }
            }
        }, "km-scan").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        scanner?.interrupt()
        // Readers notice within one poll timeout and close their own fds (which also drops grabs).
        synchronized(lock) { pads.values.forEach { it.alive = false } }
    }

    /** Grab active pads (mapping or learning) or release every pad. */
    fun setGrab(on: Boolean) {
        wantGrab = on
        applyPolicy()
    }

    /** Controller keys to use; empty = every gamepad. */
    fun setSelection(keys: Set<String>) {
        selected = keys
        applyPolicy()
    }

    fun setConfigs(list: List<ControllerConfig>) {
        configs = list.associateBy { it.key }
        synchronized(lock) {
            pads.values.forEach { pad ->
                val old = pad.mapper
                pad.mapper = DeviceMapper(pad.caps, configs[pad.key])
                if (pad.active) old.reset { e -> sink(pad.id, listOf(e)) }
            }
        }
    }

    fun rumble(strong: Int, weak: Int, ms: Int) {
        synchronized(lock) {
            pads.values.filter { it.alive && it.active && it.caps.hasRumble && it.writable }.forEach { pad ->
                val r = NativeInput.rumble(pad.fd, pad.effectId, strong, weak, ms)
                if (r >= 0) pad.effectId = r else L.w("rumble on ${pad.caps.name} failed (errno ${-r})")
            }
        }
    }

    fun snapshot(): List<ControllerInfo> = synchronized(lock) {
        pads.values.map { p ->
            ControllerInfo(
                id = p.id, path = p.path, name = p.caps.name, vendor = p.caps.vendor, product = p.caps.product,
                key = p.key, active = p.active, grabbed = p.grabbed, hasRumble = p.caps.hasRumble,
                axes = p.caps.abs,
            )
        }
    }

    private fun applyPolicy() {
        var changed = false
        synchronized(lock) {
            val sel = selected
            for (pad in pads.values) {
                if (!pad.alive) continue
                val active = sel.isEmpty() || pad.key in sel
                if (active != pad.active) {
                    if (!active) pad.mapper.reset { e -> sink(pad.id, listOf(e)) }
                    pad.active = active
                    changed = true
                }
                val grab = wantGrab && active
                if (grab != pad.grabbed) {
                    val r = NativeInput.grab(pad.fd, grab)
                    if (r == 0) {
                        pad.grabbed = grab
                        changed = true
                    } else {
                        L.w("${if (grab) "grab" else "ungrab"} ${pad.caps.name} failed (errno ${-r})")
                    }
                }
            }
        }
        if (changed) onChanged()
    }

    private fun scan() {
        val nodes = File("/dev/input").list()?.filter { it.startsWith("event") }?.map { "/dev/input/$it" }?.toSet() ?: emptySet()
        var changed = false
        synchronized(lock) {
            for (path in pads.keys.filter { it !in nodes }) {
                pads.remove(path)?.alive = false
                changed = true
            }
            ignored.retainAll(nodes)
        }
        for (path in nodes) {
            val known = synchronized(lock) { path in pads || path in ignored }
            if (known) continue
            val pad = probe(path)
            synchronized(lock) {
                if (pad == null) {
                    ignored.add(path)
                } else {
                    pads[path] = pad
                    pad.thread.isDaemon = true
                    pad.thread.start()
                    L.i("gamepad ${pad.id}: '${pad.caps.name}' ${pad.key} at $path (rumble=${pad.caps.hasRumble})")
                    changed = true
                }
            }
        }
        if (changed) {
            applyPolicy()
            onChanged()
        }
    }

    private fun probe(path: String): Pad? {
        var writable = true
        var fd = NativeInput.open(path, true)
        if (fd < 0) {
            writable = false
            fd = NativeInput.open(path, false)
        }
        if (fd < 0) {
            if (-fd != NativeInput.ENOENT) L.w("cannot open $path (errno ${-fd})")
            return null
        }
        val caps = readCaps(fd)
        if (caps == null || !caps.isGamepad) {
            NativeInput.close(fd)
            return null
        }
        return Pad(nextId++, path, fd, writable, caps)
    }

    private fun readCaps(fd: Int): DeviceCaps? {
        val name = NativeInput.name(fd) ?: return null
        val id = NativeInput.id(fd) ?: intArrayOf(0, 0, 0, 0)
        val types = NativeInput.bits(fd, 0)
        val keys = if (NativeInput.hasBit(types, Ev.KEY)) NativeInput.setBits(NativeInput.bits(fd, Ev.KEY)).toSet() else emptySet()
        val absCodes = if (NativeInput.hasBit(types, Ev.ABS)) NativeInput.setBits(NativeInput.bits(fd, Ev.ABS)) else emptyList()
        val abs = absCodes.mapNotNull { code ->
            NativeInput.absInfo(fd, code)?.let { code to AbsRange(min = it[1], max = it[2], flat = it[4], fuzz = it[3]) }
        }.toMap()
        val direct = NativeInput.hasBit(NativeInput.props(fd), Ev.INPUT_PROP_DIRECT)
        val rumble = NativeInput.hasBit(types, Ev.FF) && NativeInput.hasBit(NativeInput.bits(fd, Ev.FF), FF_RUMBLE)
        return DeviceCaps(name, id[0], id[1], id[2], keys, abs, direct, rumble)
    }

    private fun readLoop(pad: Pad) {
        val buf = IntArray(64 * 3)
        val batch = ArrayList<LogicalEvent>()
        while (running && pad.alive) {
            val n = NativeInput.read(pad.fd, buf, 250)
            if (n < 0) {
                if (-n == NativeInput.ENODEV) {
                    L.i("gamepad ${pad.id} '${pad.caps.name}' disconnected")
                } else {
                    L.w("read ${pad.path} failed (errno ${-n})")
                }
                break
            }
            for (i in 0 until n) {
                val type = buf[i * 3]
                val code = buf[i * 3 + 1]
                val value = buf[i * 3 + 2]
                if (type == Ev.SYN) {
                    if (batch.isNotEmpty() && pad.active) sink(pad.id, ArrayList(batch))
                    batch.clear()
                    continue
                }
                if (type == Ev.MSC) continue
                val watching = monitor.enabled
                if (watching) monitor.raw(pad.id, type, code, value)
                pad.mapper.onEvent(type, code, value) { e ->
                    batch.add(e)
                    if (watching) monitor.logical(pad.id, e)
                }
            }
        }
        // Release whatever this pad was holding, then forget it so a replug is picked up again.
        val releases = ArrayList<LogicalEvent>()
        pad.mapper.reset { releases.add(it) }
        if (releases.isNotEmpty() && pad.active) sink(pad.id, releases)
        synchronized(lock) {
            if (pads[pad.path] === pad) pads.remove(pad.path)
            pad.alive = false
            // Only this thread ever closes the fd, so the number can't be reused under a live reader.
            if (pad.grabbed) NativeInput.grab(pad.fd, false)
            NativeInput.close(pad.fd)
        }
        onChanged()
    }

    private companion object {
        const val SCAN_MS = 1000L
        const val FF_RUMBLE = 0x50
    }
}

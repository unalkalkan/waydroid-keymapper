package games.forgemaster.keymapper.helper

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import games.forgemaster.keymapper.core.engine.MotionPlanner
import games.forgemaster.keymapper.core.engine.MotionStep
import games.forgemaster.keymapper.core.engine.PointerChange
import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.engine.TouchAction
import games.forgemaster.keymapper.core.engine.TouchSink
import games.forgemaster.keymapper.core.input.DeviceCaps
import games.forgemaster.keymapper.core.input.Ev
import java.io.IOException
import java.lang.reflect.Method

/** A way of putting touches on screen. Called only from the engine thread. */
interface Emitter : TouchSink {
    val name: String
    fun releaseAll()
    fun close()

    /** When [tick] wants to run next ([System.nanoTime]), or null. */
    fun nextDeadline(): Long? = null
    fun tick(nowNs: Long) {}

    companion object {
        /** Pointer ids 0..9 belong to the engine; manual test touches use [MANUAL_BASE] + n. */
        const val MANUAL_BASE = 10
        const val SLOTS = 16

        fun create(backend: String, screen: () -> Screen): Emitter {
            if (backend == "inputmanager") return InputManagerEmitter()
            return try {
                UinputEmitter(screen)
            } catch (e: Exception) {
                if (backend == "uinput") throw e
                L.i("uinput unavailable (${e.message}); using InputManager injection")
                InputManagerEmitter()
            }
        }
    }
}

/**
 * Injects MotionEvents through InputManager. The shell user holds INJECT_EVENTS, and code started
 * through app_process is not subject to hidden-API enforcement, so no global setting is changed.
 */
class InputManagerEmitter : Emitter {
    override val name = "inputmanager"
    private val planner = MotionPlanner()
    private val manager: Any
    private val inject: Method
    private var downTime = 0L
    private var lastSentNs = 0L
    private var takeovers = 0L
    private var lastTakeoverLog = Long.MIN_VALUE / 2
    private var lastSlowLog = Long.MIN_VALUE / 2

    init {
        manager = try {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (_: ReflectiveOperationException) {
            Class.forName("android.hardware.input.InputManager").getDeclaredMethod("getInstance").invoke(null)!!
        }
        inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    override fun apply(changes: List<PointerChange>) {
        planner.plan(changes).forEach { send(it) }
    }

    override fun releaseAll() {
        planner.releaseAll().forEach { send(it) }
    }

    override fun close() = releaseAll()

    override fun nextDeadline(): Long? = if (planner.isTouching) lastSentNs + KEEPALIVE_NS else null

    /**
     * Repeats the fingers in place when nothing was sent for a while, so a takeover (see [regain])
     * is noticed and undone even while every control holds still, e.g. a stick held in one direction.
     */
    override fun tick(nowNs: Long) {
        if (nowNs - lastSentNs >= KEEPALIVE_NS) planner.repeat()?.let { send(it) }
    }

    private fun send(step: MotionStep) {
        if (!inject(step) && step.maskedAction != MotionEvent.ACTION_DOWN) regain(step)
    }

    /**
     * Android 13 and older keep one touch stream per display: a gesture or hover from another device
     * (on Waydroid, the host mouse moving over the window) takes it over, and every later event of
     * ours is dropped until we start a new gesture. Land the fingers [failed] carried again, then
     * retry it if it was a lift.
     */
    private fun regain(failed: MotionStep) {
        takeovers++
        val now = SystemClock.uptimeMillis()
        if (now - lastTakeoverLog >= LOG_INTERVAL_MS) {
            L.i("touch stream taken by another device; re-landed ${failed.pointers.size} finger(s), $takeovers so far")
            lastTakeoverLog = now
        }
        if (!MotionPlanner.landing(failed.pointers).all { inject(it) }) return
        val a = failed.maskedAction
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) inject(failed)
    }

    /** False when the input dispatcher rejected the event. */
    private fun inject(step: MotionStep): Boolean {
        lastSentNs = System.nanoTime()
        val now = SystemClock.uptimeMillis()
        if (step.maskedAction == MotionEvent.ACTION_DOWN) downTime = now
        val n = step.pointers.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = step.pointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = step.pointers[i].x
                y = step.pointers[i].y
                pressure = 1f
                size = 0.05f
                touchMajor = 12f
                touchMinor = 12f
            }
        }
        val event = MotionEvent.obtain(
            downTime, now, step.action, n, props, coords, 0, 0, 1f, 1f,
            DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return try {
            // WAIT_FOR_RESULT returns once the dispatcher has picked a target (not when the app is done).
            val ok = inject.invoke(manager, event, INJECT_WAIT_FOR_RESULT) as Boolean
            val took = SystemClock.uptimeMillis() - now
            if (took >= SLOW_INJECT_MS && now - lastSlowLog >= LOG_INTERVAL_MS) {
                L.w("injection blocked the engine for $took ms")
                lastSlowLog = now
            }
            ok
        } catch (e: Exception) {
            L.w("inject failed", e)
            true // not a dispatcher rejection; re-landing would fail the same way
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val DEVICE_ID = 0
        const val INJECT_WAIT_FOR_RESULT = 1
        const val KEEPALIVE_NS = 100_000_000L
        const val SLOW_INJECT_MS = 20L
        const val LOG_INTERVAL_MS = 5_000L
    }
}

/**
 * Emits through a virtual protocol-B touchscreen. Coordinates are converted from logical to the
 * panel's natural orientation; InputReader applies the display rotation itself.
 */
class UinputEmitter(private val screen: () -> Screen) : Emitter {
    override val name = "uinput"
    private val fd: Int = NativeInput.uinputCreate(DeviceCaps.VIRTUAL_TOUCH_NAME, Emitter.SLOTS)
    private val tracking = HashMap<Int, Int>()
    private val last = HashMap<Int, Pair<Float, Float>>()
    private var nextTracking = 1
    private val buf = IntArray(256 * 3)
    private var n = 0

    init {
        if (fd < 0) throw IOException("cannot create uinput device (errno ${-fd})")
    }

    override fun apply(changes: List<PointerChange>) {
        val s = screen()
        val wasTouching = tracking.isNotEmpty()
        n = 0
        for (c in changes) {
            if (n > 240) flush()
            val slot = c.id.coerceIn(0, Emitter.SLOTS - 1)
            when (c.action) {
                TouchAction.DOWN -> {
                    if (c.id in tracking) continue
                    val t = nextTracking
                    nextTracking = (nextTracking % 65535) + 1
                    tracking[c.id] = t
                    ev(Ev.ABS, Ev.ABS_MT_SLOT, slot)
                    ev(Ev.ABS, Ev.ABS_MT_TRACKING_ID, t)
                    position(s, c)
                    ev(Ev.ABS, Ev.ABS_MT_PRESSURE, 128)
                    ev(Ev.ABS, Ev.ABS_MT_TOUCH_MAJOR, 8)
                }
                TouchAction.MOVE -> {
                    if (c.id !in tracking) continue
                    ev(Ev.ABS, Ev.ABS_MT_SLOT, slot)
                    position(s, c)
                }
                TouchAction.UP -> {
                    if (tracking.remove(c.id) == null) continue
                    last.remove(c.id)
                    ev(Ev.ABS, Ev.ABS_MT_SLOT, slot)
                    ev(Ev.ABS, Ev.ABS_MT_TRACKING_ID, -1)
                }
            }
        }
        val touching = tracking.isNotEmpty()
        if (touching != wasTouching) ev(Ev.KEY, Ev.BTN_TOUCH, if (touching) 1 else 0)
        flush()
    }

    private fun position(s: Screen, c: PointerChange) {
        last[c.id] = c.x to c.y
        val (nx, ny) = s.toNatural(c.x, c.y)
        ev(Ev.ABS, Ev.ABS_MT_POSITION_X, scale(nx, s.naturalWidth))
        ev(Ev.ABS, Ev.ABS_MT_POSITION_Y, scale(ny, s.naturalHeight))
    }

    private fun scale(v: Float, size: Int): Int = ((v / size.coerceAtLeast(1)) * (MAX + 1)).toInt().coerceIn(0, MAX)

    private fun ev(type: Int, code: Int, value: Int) {
        buf[n * 3] = type
        buf[n * 3 + 1] = code
        buf[n * 3 + 2] = value
        n++
    }

    private fun flush() {
        ev(Ev.SYN, Ev.SYN_REPORT, 0)
        val r = NativeInput.uinputEmit(fd, buf, n)
        if (r < 0) L.w("uinput write failed (errno ${-r})")
        n = 0
    }

    override fun releaseAll() {
        val ups = last.entries.map { (id, p) -> PointerChange(id, TouchAction.UP, p.first, p.second) }
        if (ups.isNotEmpty()) apply(ups)
        tracking.clear()
    }

    override fun close() {
        releaseAll()
        NativeInput.uinputDestroy(fd)
    }

    private companion object {
        const val MAX = 32767
    }
}

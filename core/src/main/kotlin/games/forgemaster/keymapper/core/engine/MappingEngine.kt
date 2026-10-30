package games.forgemaster.keymapper.core.engine

import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.ButtonControl
import games.forgemaster.keymapper.core.model.ButtonMode
import games.forgemaster.keymapper.core.model.JoystickControl
import games.forgemaster.keymapper.core.model.LookControl
import games.forgemaster.keymapper.core.model.PhaseAction
import games.forgemaster.keymapper.core.model.PhaseSwitchControl
import games.forgemaster.keymapper.core.model.Profile
import games.forgemaster.keymapper.core.model.Stick
import games.forgemaster.keymapper.core.model.StickResponse
import java.util.EnumSet
import kotlin.math.abs

/**
 * Turns logical gamepad state into touches for the active profile phase.
 *
 * Not thread-safe: every call must come from one thread (the helper's engine thread). Time is passed
 * in by the caller (monotonic nanoseconds) so behaviour is deterministic under test. Calls that need
 * timed follow-ups (taps, rapid fire, look drag) are driven by [tick]; [nextDeadline] says when.
 */
class MappingEngine(
    private val sink: TouchSink,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun onPhaseChanged(index: Int, count: Int, name: String, byUser: Boolean) {}
    }

    var profile: Profile? = null
        private set
    var screen: Screen = Screen(1280, 720)
        private set
    var phase: Int = 0
        private set
    var enabled: Boolean = true
        private set

    private val held: EnumSet<Button> = EnumSet.noneOf(Button::class.java)
    private val axes = FloatArray(Axis.entries.size)
    private var runtimes: List<Runtime> = emptyList()
    /** Button lists of the current phase's button and phase-switch controls, for [shadowed]. */
    private var chords: List<List<Button>> = emptyList()
    private val usedPointers = BooleanArray(MAX_POINTERS)
    private val freedThisBatch = BooleanArray(MAX_POINTERS)
    private val out = ArrayList<PointerChange>()
    private var pendingPhase: Int? = null
    private var holdReturn: Pair<List<Button>, Int>? = null
    private var now = 0L

    val heldButtons: Set<Button> get() = held.clone()
    fun axis(a: Axis): Float = axes[a.ordinal]

    fun setProfile(p: Profile?, nowNs: Long) {
        now = nowNs
        val keepPhase = p != null && p.id == profile?.id && phase < p.phases.size
        releaseRuntimes()
        profile = p
        holdReturn = null
        pendingPhase = null
        if (!keepPhase) phase = 0
        rebuild()
        flush()
        if (p != null && !keepPhase) listener.onPhaseChanged(phase, p.phases.size, p.phases[phase].name, byUser = false)
    }

    fun setScreen(s: Screen, nowNs: Long) {
        if (s == screen) return
        now = nowNs
        releaseRuntimes()
        screen = s
        rebuild()
        flush()
    }

    fun setEnabled(on: Boolean, nowNs: Long) {
        if (on == enabled) return
        now = nowNs
        releaseRuntimes()
        enabled = on
        rebuild()
        flush()
    }

    /** Jumps to [index], or moves by [delta] (wrapping) when [index] is null. */
    fun selectPhase(index: Int?, delta: Int, nowNs: Long) {
        now = nowNs
        val count = profile?.phases?.size ?: return
        pendingPhase = index ?: Math.floorMod(phase + delta, count)
        applyPendingPhase()
        flush()
    }

    fun onButton(button: Button, pressed: Boolean, nowNs: Long) {
        now = nowNs
        if (pressed == (button in held)) return
        if (pressed) held.add(button) else held.remove(button)
        holdReturn?.let { (buttons, returnTo) ->
            if (!pressed && button in buttons) {
                holdReturn = null
                pendingPhase = returnTo
            }
        }
        runtimes.forEach { it.onButtons() }
        applyPendingPhase()
        flush()
    }

    fun onAxis(axis: Axis, value: Float, nowNs: Long) {
        now = nowNs
        axes[axis.ordinal] = value
        runtimes.forEach { it.onAxes() }
        flush()
    }

    /** Runs timed work. Returns the next deadline, or null when nothing is pending. */
    fun tick(nowNs: Long): Long? {
        now = nowNs
        runtimes.forEach { it.tick() }
        applyPendingPhase()
        flush()
        return nextDeadline()
    }

    fun nextDeadline(): Long? = runtimes.mapNotNull { it.deadline }.minOrNull()

    /** Lifts every finger. Input state is kept, so held buttons need a fresh press afterwards. */
    fun releaseAll(nowNs: Long) {
        now = nowNs
        releaseRuntimes()
        rebuild()
        flush()
    }

    /** Forgets all held buttons and axes (e.g. the controller went away) and lifts every finger. */
    fun resetInput(nowNs: Long) {
        now = nowNs
        held.clear()
        axes.fill(0f)
        holdReturn = null
        releaseRuntimes()
        rebuild()
        flush()
    }

    private fun applyPendingPhase() {
        val target = pendingPhase ?: return
        pendingPhase = null
        val p = profile ?: return
        if (target == phase || target !in p.phases.indices) return
        releaseRuntimes()
        phase = target
        rebuild()
        listener.onPhaseChanged(phase, p.phases.size, p.phases[phase].name, byUser = true)
    }

    private fun rebuild() {
        val controls = profile?.takeIf { enabled }?.phases?.getOrNull(phase)?.controls.orEmpty()
        chords = controls.mapNotNull { c ->
            when (c) {
                is ButtonControl -> c.buttons
                is PhaseSwitchControl -> c.buttons
                else -> null
            }
        }
        runtimes = controls.map { c ->
            when (c) {
                is ButtonControl -> ButtonRuntime(c)
                is JoystickControl -> JoystickRuntime(c)
                is LookControl -> LookRuntime(c)
                is PhaseSwitchControl -> PhaseSwitchRuntime(c)
            }
        }
        // Sticks are level-triggered: engage immediately if already deflected.
        runtimes.forEach { if (it !is ButtonRuntime && it !is PhaseSwitchRuntime) it.onAxes() }
    }

    private fun releaseRuntimes() {
        runtimes.forEach { it.release() }
    }

    private fun flush() {
        if (out.isEmpty()) return
        val batch = ArrayList(out)
        out.clear()
        freedThisBatch.fill(false)
        sink.apply(batch)
    }

    private fun allocPointer(): Int {
        for (i in 0 until MAX_POINTERS) {
            if (!usedPointers[i] && !freedThisBatch[i]) {
                usedPointers[i] = true
                return i
            }
        }
        return -1
    }

    private fun freePointer(id: Int) {
        usedPointers[id] = false
        freedThisBatch[id] = true
    }

    private fun chordHeld(buttons: List<Button>) = buttons.isNotEmpty() && held.containsAll(buttons)

    /**
     * True when a longer chord containing every one of [buttons] is held as well: the longer chord
     * wins, so with L1+X bound, completing L1+X doesn't also fire X. Checked only when [buttons]
     * become held, so releasing L1 first never fires X either.
     */
    private fun shadowed(buttons: List<Button>) =
        chords.any { it.size > buttons.size && it.containsAll(buttons) && held.containsAll(it) }

    private fun stickVector(stick: Stick): Vec {
        val pair = stick.axes
        if (pair != null) return Vec(axes[pair.first.ordinal], axes[pair.second.ordinal])
        val x = (if (Button.DPAD_RIGHT in held) 1f else 0f) - (if (Button.DPAD_LEFT in held) 1f else 0f)
        val y = (if (Button.DPAD_DOWN in held) 1f else 0f) - (if (Button.DPAD_UP in held) 1f else 0f)
        val v = Vec(x, y)
        return if (v.isZero) v else v * (1f / v.length)
    }

    private inner class Finger {
        var id = -1
        var x = 0f
        var y = 0f
        val down get() = id >= 0

        fun press(px: Float, py: Float) {
            if (down) return
            id = allocPointer()
            if (id < 0) return
            x = clampX(px)
            y = clampY(py)
            out.add(PointerChange(id, TouchAction.DOWN, x, y))
        }

        fun move(px: Float, py: Float) {
            if (!down) return
            val nx = clampX(px)
            val ny = clampY(py)
            if (abs(nx - x) < 0.25f && abs(ny - y) < 0.25f) return
            x = nx
            y = ny
            out.add(PointerChange(id, TouchAction.MOVE, x, y))
        }

        /** Repeats the current position so input resampling sees zero velocity (no overshoot). */
        fun settle() {
            if (down) out.add(PointerChange(id, TouchAction.MOVE, x, y))
        }

        fun lift() {
            if (!down) return
            out.add(PointerChange(id, TouchAction.UP, x, y))
            freePointer(id)
            id = -1
        }

        private fun clampX(v: Float) = v.coerceIn(0f, (screen.width - 1).toFloat())
        private fun clampY(v: Float) = v.coerceIn(0f, (screen.height - 1).toFloat())
    }

    private abstract inner class Runtime {
        open val deadline: Long? get() = null
        open fun onButtons() {}
        open fun onAxes() {}
        open fun tick() {}
        abstract fun release()
    }

    private inner class ButtonRuntime(private val c: ButtonControl) : Runtime() {
        private val px = c.x * screen.width
        private val py = c.y * screen.height
        private val finger = Finger()
        private var chord = chordHeld(c.buttons) // already-held buttons need a fresh press
        private val script = ArrayDeque<Pair<Long, Boolean>>() // (time, press?)
        private var rapidNext: Long? = null
        private val rapidHalfPeriod =
            (500_000_000.0 / c.rapidHz.coerceIn(ButtonControl.MIN_RAPID_HZ, ButtonControl.MAX_RAPID_HZ)).toLong()
        private val aimRadius = c.aimRadius * screen.shortSide
        private val aimMin = c.aimMin.coerceIn(0f, c.aimRadius) * screen.shortSide
        private var aimArmedUntil: Long? = null
        private var aimSettleAt: Long? = null

        override val deadline: Long?
            get() = listOfNotNull(script.firstOrNull()?.first, rapidNext, aimArmedUntil, aimSettleAt).minOrNull()

        override fun onButtons() {
            val h = chordHeld(c.buttons)
            if (h != chord) {
                chord = h
                if (!h) released() else if (!shadowed(c.buttons)) pressed()
            }
            if (c.aim == Stick.DPAD) aim()
        }

        override fun onAxes() {
            if (c.aim != null && c.aim != Stick.DPAD) aim()
        }

        /** Drags a held finger from the centre along the aim stick, as a thumb drags a skill to aim it. */
        private fun aim() {
            val stick = c.aim ?: return
            if (!finger.down || aimArmedUntil != null) return
            val raw = stickVector(stick)
            val v = if (stick == Stick.DPAD) raw else StickMath.apply(raw, AIM_RESPONSE)
            val len = v.length
            val reach = if (len > 0f) (aimMin + len * (aimRadius - aimMin)) / len else 0f
            val bx = finger.x
            val by = finger.y
            finger.move(px + v.x * reach, py + v.y * reach)
            if (finger.x != bx || finger.y != by) aimSettleAt = now + SETTLE_NS
        }

        private fun pressed() {
            pressMode()
            // Land on the centre first so the game takes it as a press on this button, then aim next frame.
            if (c.aim != null && finger.down) aimArmedUntil = now + ARM_NS
        }

        private fun pressMode() {
            when (c.mode) {
                ButtonMode.HOLD -> finger.press(px, py)
                ButtonMode.TOGGLE -> if (finger.down) finger.lift() else finger.press(px, py)
                ButtonMode.TAP -> {
                    restart()
                    script.addLast(now + TAP_NS to false)
                }
                ButtonMode.DOUBLE_TAP -> {
                    restart()
                    script.addLast(now + TAP_NS to false)
                    script.addLast(now + TAP_NS + DOUBLE_TAP_GAP_NS to true)
                    script.addLast(now + 2 * TAP_NS + DOUBLE_TAP_GAP_NS to false)
                }
                ButtonMode.RAPID -> {
                    finger.press(px, py)
                    rapidNext = now + rapidHalfPeriod
                }
            }
        }

        private fun released() {
            when (c.mode) {
                ButtonMode.HOLD -> finger.lift()
                ButtonMode.RAPID -> {
                    rapidNext = null
                    finger.lift()
                }
                else -> Unit
            }
        }

        private fun restart() {
            script.clear()
            finger.lift()
            finger.press(px, py)
        }

        override fun tick() {
            val armed = aimArmedUntil
            if (armed != null && now >= armed) {
                aimArmedUntil = null
                aim()
            }
            val settle = aimSettleAt
            if (settle != null && now >= settle) {
                aimSettleAt = null
                finger.settle()
            }
            while (script.isNotEmpty() && script.first().first <= now) {
                if (script.removeFirst().second) finger.press(px, py) else finger.lift()
            }
            val t = rapidNext ?: return
            if (t > now) return
            if (finger.down) finger.lift() else finger.press(px, py)
            rapidNext = if (now - t > rapidHalfPeriod) now + rapidHalfPeriod else t + rapidHalfPeriod
        }

        override fun release() {
            script.clear()
            rapidNext = null
            aimArmedUntil = null
            aimSettleAt = null
            finger.lift()
        }
    }

    private inner class JoystickRuntime(private val c: JoystickControl) : Runtime() {
        private val center = Vec(c.x * screen.width, c.y * screen.height)
        private val radius = c.radius * screen.shortSide
        private val finger = Finger()
        private var armedUntil: Long? = null
        private var settleAt: Long? = null
        private var target = center

        override val deadline: Long? get() = listOfNotNull(armedUntil, settleAt).minOrNull()

        private fun moveTo(v: Vec) {
            val bx = finger.x
            val by = finger.y
            finger.move(v.x, v.y)
            if (finger.x != bx || finger.y != by) settleAt = now + SETTLE_NS
        }

        override fun onAxes() {
            if (c.stick != Stick.DPAD) update()
        }

        override fun onButtons() {
            if (c.stick == Stick.DPAD) update()
        }

        private fun update() {
            val raw = stickVector(c.stick)
            var v = if (c.stick == Stick.DPAD) raw else StickMath.apply(raw, c.response)
            if (c.eightWay) v = StickMath.eightWay(v)
            if (v.isZero) {
                armedUntil = null
                settleAt = null
                finger.lift()
                return
            }
            target = center + v * radius
            if (!finger.down) {
                // Land on the centre first so the game registers the joystick, then move next frame.
                finger.press(center.x, center.y)
                if (finger.down) armedUntil = now + ARM_NS
                return
            }
            if (armedUntil == null) moveTo(target)
        }

        override fun tick() {
            val a = armedUntil
            if (a != null && now >= a) {
                armedUntil = null
                moveTo(target)
                return
            }
            val st = settleAt
            if (st != null && now >= st) {
                settleAt = null
                finger.settle()
            }
        }

        override fun release() {
            armedUntil = null
            settleAt = null
            finger.lift()
        }
    }

    private inner class LookRuntime(private val c: LookControl) : Runtime() {
        private val center = Vec(c.x * screen.width, c.y * screen.height)
        private val radius = c.radius * screen.shortSide
        private val speed = c.sensitivity * screen.shortSide
        private val finger = Finger()
        private var v = Vec.ZERO
        private var pos = center
        private var lastTick = 0L
        private var idleSince: Long? = null
        private var recenterAt: Long? = null
        private var settled = false

        override val deadline: Long?
            get() = when {
                recenterAt != null -> recenterAt
                !finger.down -> null
                v.isZero -> (idleSince ?: now) + LOOK_RELEASE_NS
                else -> lastTick + TICK_NS
            }

        override fun onAxes() {
            if (c.stick != Stick.DPAD) update()
        }

        override fun onButtons() {
            if (c.stick == Stick.DPAD) update()
        }

        private fun update() {
            val raw = stickVector(c.stick)
            var nv = if (c.stick == Stick.DPAD) raw else StickMath.apply(raw, c.response)
            if (c.invertY) nv = Vec(nv.x, -nv.y)
            val wasZero = v.isZero
            v = nv
            if (v.isZero) {
                if (!wasZero) idleSince = now
                return
            }
            idleSince = null
            if (!finger.down && recenterAt == null) touchCenter()
        }

        private fun touchCenter() {
            pos = center
            finger.press(center.x, center.y)
            lastTick = now
        }

        override fun tick() {
            val r = recenterAt
            if (r != null) {
                if (now < r) return
                recenterAt = null
                if (!v.isZero) touchCenter()
                return
            }
            if (!finger.down) return
            if (v.isZero) {
                val since = idleSince ?: now.also { idleSince = it }
                if (now - since >= LOOK_RELEASE_NS) {
                    finger.lift()
                } else if (!settled) {
                    finger.settle()
                    settled = true
                }
                lastTick = now
                return
            }
            settled = false
            val dt = (now - lastTick).coerceIn(0L, MAX_DT_NS) / 1e9f
            lastTick = now
            pos += v * (speed * dt)
            val offset = pos - center
            val distance = offset.length
            if (distance >= radius) {
                val edge = center + offset * (radius / distance)
                finger.move(edge.x, edge.y)
                finger.lift()
                recenterAt = now + RECENTER_NS
            } else {
                finger.move(pos.x, pos.y)
            }
        }

        override fun release() {
            recenterAt = null
            idleSince = null
            v = Vec.ZERO
            finger.lift()
        }
    }

    private inner class PhaseSwitchRuntime(private val c: PhaseSwitchControl) : Runtime() {
        private var chord = chordHeld(c.buttons)

        override fun onButtons() {
            val h = chordHeld(c.buttons)
            if (h == chord) return
            chord = h
            if (!h || shadowed(c.buttons)) return
            val count = profile?.phases?.size ?: return
            pendingPhase = when (c.action) {
                PhaseAction.NEXT -> (phase + 1) % count
                PhaseAction.PREVIOUS -> (phase - 1 + count) % count
                PhaseAction.GOTO -> c.target
                PhaseAction.HOLD -> {
                    holdReturn = c.buttons to phase
                    c.target
                }
            }
        }

        override fun release() = Unit
    }

    companion object {
        /** Engine-owned pointer ids are 0 until MAX_POINTERS; manual (test) touches use the ids above. */
        const val MAX_POINTERS = 10
        const val TAP_NS = 40_000_000L
        const val DOUBLE_TAP_GAP_NS = 60_000_000L
        const val ARM_NS = 16_000_000L
        const val TICK_NS = 8_000_000L
        const val MAX_DT_NS = 50_000_000L
        const val LOOK_RELEASE_NS = 120_000_000L
        const val RECENTER_NS = 16_000_000L
        /** Delay of the repeat-position MOVE after a joystick jump (one 60 Hz frame). */
        const val SETTLE_NS = 16_000_000L
        /** Stick response of [ButtonControl.aim]: the joystick defaults. */
        private val AIM_RESPONSE = StickResponse()
    }
}

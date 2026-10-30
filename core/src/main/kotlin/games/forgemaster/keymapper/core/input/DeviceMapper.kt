package games.forgemaster.keymapper.core.input

import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.AxisCalibration
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.ControllerConfig
import java.util.EnumSet
import kotlin.math.abs

sealed class LogicalEvent {
    data class ButtonChange(val button: Button, val pressed: Boolean) : LogicalEvent()
    data class AxisChange(val axis: Axis, val value: Float) : LogicalEvent()
}

/**
 * Turns one controller's raw evdev events into logical buttons and axes.
 *
 * Default roles follow the Linux gamepad conventions; [ControllerConfig] can override axis roles,
 * button roles and calibration. Trigger axes also drive L2/R2 (with hysteresis), OR-ed with any
 * digital L2/R2: some pads advertise BTN_TL2/BTN_TR2 and never send them (scrcpy's virtual pad).
 * A hat switch becomes the four D-pad buttons.
 *
 * [onEvent] and [reset] are synchronized: the helper's reader thread feeds events while policy
 * changes (deselecting a pad, new calibration) reset the mapper from other threads.
 */
class DeviceMapper(caps: DeviceCaps, config: ControllerConfig? = null) {
    private val axisByCode: Map<Int, Axis>
    private val buttonByCode: Map<Int, Button>
    private val ranges: Map<Int, AbsRange> = caps.abs
    private val calibration: Map<Int, AxisCalibration> = config?.calibration ?: emptyMap()

    private val digital = EnumSet.noneOf(Button::class.java) // from KEY events
    private val synthesized = EnumSet.noneOf(Button::class.java) // from hats and triggers
    private val reported = EnumSet.noneOf(Button::class.java)
    private val axisValues = FloatArray(Axis.entries.size)

    init {
        val roles = LinkedHashMap<Axis, Int>()
        val abs = caps.abs.keys
        if (Ev.ABS_X in abs) roles[Axis.LX] = Ev.ABS_X
        if (Ev.ABS_Y in abs) roles[Axis.LY] = Ev.ABS_Y
        if (Ev.ABS_RX in abs && Ev.ABS_RY in abs) {
            roles[Axis.RX] = Ev.ABS_RX
            roles[Axis.RY] = Ev.ABS_RY
            if (Ev.ABS_Z in abs) roles[Axis.LT] = Ev.ABS_Z
            if (Ev.ABS_RZ in abs) roles[Axis.RT] = Ev.ABS_RZ
        } else if (Ev.ABS_Z in abs && Ev.ABS_RZ in abs) {
            // HID "Android-style" pads put the right stick on Z/RZ and triggers on brake/gas.
            roles[Axis.RX] = Ev.ABS_Z
            roles[Axis.RY] = Ev.ABS_RZ
        }
        if (Axis.LT !in roles && Ev.ABS_BRAKE in abs) roles[Axis.LT] = Ev.ABS_BRAKE
        if (Axis.RT !in roles && Ev.ABS_GAS in abs) roles[Axis.RT] = Ev.ABS_GAS
        config?.axisMap?.forEach { (axis, code) ->
            roles.entries.removeAll { it.value == code }
            roles[axis] = code
        }
        axisByCode = roles.entries.associate { (axis, code) -> code to axis }

        val buttons = HashMap(DEFAULT_BUTTONS)
        config?.buttonMap?.let { buttons.putAll(it) }
        buttonByCode = buttons
    }

    @Synchronized
    fun onEvent(type: Int, code: Int, value: Int, emit: (LogicalEvent) -> Unit) {
        when (type) {
            Ev.KEY -> {
                val button = buttonByCode[code] ?: return
                if (value != 0) digital.add(button) else digital.remove(button)
                publishButton(button, emit)
            }
            Ev.ABS -> when (code) {
                Ev.ABS_HAT0X -> hat(value, Button.DPAD_LEFT, Button.DPAD_RIGHT, emit)
                Ev.ABS_HAT0Y -> hat(value, Button.DPAD_UP, Button.DPAD_DOWN, emit)
                else -> {
                    val axis = axisByCode[code] ?: return
                    val v = normalize(axis, code, value)
                    if (abs(v - axisValues[axis.ordinal]) < 0.0005f) return
                    axisValues[axis.ordinal] = v
                    emit(LogicalEvent.AxisChange(axis, v))
                    if (axis == Axis.LT) triggerButton(Button.L2, v, emit)
                    if (axis == Axis.RT) triggerButton(Button.R2, v, emit)
                }
            }
        }
    }

    /** Releases everything this device holds (on unplug or when it stops being the active pad). */
    @Synchronized
    fun reset(emit: (LogicalEvent) -> Unit) {
        digital.clear()
        synthesized.clear()
        reported.toList().forEach { publishButton(it, emit) }
        Axis.entries.forEach { axis ->
            if (axisValues[axis.ordinal] != 0f) {
                axisValues[axis.ordinal] = 0f
                emit(LogicalEvent.AxisChange(axis, 0f))
            }
        }
    }

    private fun hat(value: Int, negative: Button, positive: Button, emit: (LogicalEvent) -> Unit) {
        setSynth(negative, value < 0)
        setSynth(positive, value > 0)
        publishButton(negative, emit)
        publishButton(positive, emit)
    }

    private fun triggerButton(button: Button, v: Float, emit: (LogicalEvent) -> Unit) {
        val down = button in synthesized
        if (!down && v >= TRIGGER_PRESS) setSynth(button, true)
        if (down && v <= TRIGGER_RELEASE) setSynth(button, false)
        publishButton(button, emit)
    }

    private fun setSynth(button: Button, on: Boolean) {
        if (on) synthesized.add(button) else synthesized.remove(button)
    }

    private fun publishButton(button: Button, emit: (LogicalEvent) -> Unit) {
        val now = button in digital || button in synthesized
        val was = button in reported
        if (now == was) return
        if (now) reported.add(button) else reported.remove(button)
        emit(LogicalEvent.ButtonChange(button, now))
    }

    private fun normalize(axis: Axis, code: Int, raw: Int): Float {
        val cal = calibration[code]
        val range = ranges[code]
        if (axis.isTrigger) {
            val lo = cal?.center?.toFloat() ?: range?.min?.toFloat() ?: 0f
            val hi = cal?.max?.toFloat() ?: range?.max?.toFloat() ?: 1f
            if (hi <= lo) return 0f
            val v = ((raw - lo) / (hi - lo)).coerceIn(0f, 1f)
            return v
        }
        val v = if (cal != null) {
            val c = cal.center.toFloat()
            if (raw >= c) (raw - c) / (cal.max - c).coerceAtLeast(1f) else (raw - c) / (c - cal.min).coerceAtLeast(1f)
        } else if (range != null) {
            val half = ((range.max - range.min) / 2f).coerceAtLeast(1f)
            (raw - range.center) / half
        } else {
            0f
        }
        val clamped = v.coerceIn(-1f, 1f)
        return if (cal?.invert == true) -clamped else clamped
    }

    companion object {
        const val TRIGGER_PRESS = 0.5f
        const val TRIGGER_RELEASE = 0.4f

        /** Linux KEY code -> logical button, using the code names as Android's Generic.kl does. */
        val DEFAULT_BUTTONS: Map<Int, Button> = mapOf(
            Ev.BTN_SOUTH to Button.A,
            Ev.BTN_EAST to Button.B,
            Ev.BTN_NORTH to Button.X,
            Ev.BTN_WEST to Button.Y,
            Ev.BTN_TL to Button.L1,
            Ev.BTN_TR to Button.R1,
            Ev.BTN_TL2 to Button.L2,
            Ev.BTN_TR2 to Button.R2,
            Ev.BTN_SELECT to Button.SELECT,
            Ev.BTN_START to Button.START,
            Ev.BTN_MODE to Button.MODE,
            Ev.BTN_THUMBL to Button.L3,
            Ev.BTN_THUMBR to Button.R3,
            Ev.BTN_DPAD_UP to Button.DPAD_UP,
            Ev.BTN_DPAD_DOWN to Button.DPAD_DOWN,
            Ev.BTN_DPAD_LEFT to Button.DPAD_LEFT,
            Ev.BTN_DPAD_RIGHT to Button.DPAD_RIGHT,
            Ev.KEY_BACK to Button.SELECT,
            Ev.KEY_HOMEPAGE to Button.MODE,
            Ev.KEY_MENU to Button.START,
            // Generic joysticks (BTN_TRIGGER..): positional guess, remappable per controller.
            0x120 to Button.A, 0x121 to Button.B, 0x122 to Button.X, 0x123 to Button.Y,
            0x124 to Button.L1, 0x125 to Button.R1, 0x126 to Button.L2, 0x127 to Button.R2,
            0x128 to Button.SELECT, 0x129 to Button.START, 0x12a to Button.L3, 0x12b to Button.R3,
        )
    }
}

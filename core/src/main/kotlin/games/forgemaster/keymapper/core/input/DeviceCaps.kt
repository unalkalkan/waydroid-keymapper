package games.forgemaster.keymapper.core.input

import kotlinx.serialization.Serializable

@Serializable
data class AbsRange(val min: Int, val max: Int, val flat: Int = 0, val fuzz: Int = 0) {
    val center: Float get() = (min + max) / 2f
}

/** What an evdev node can do, as read through EVIOCGNAME/EVIOCGID/EVIOCGBIT/EVIOCGABS. */
data class DeviceCaps(
    val name: String,
    val bus: Int,
    val vendor: Int,
    val product: Int,
    val keys: Set<Int>,
    val abs: Map<Int, AbsRange>,
    val direct: Boolean = false,
    val hasRumble: Boolean = false,
) {
    val isTouchscreen: Boolean get() = direct || Ev.ABS_MT_POSITION_X in abs

    /**
     * A gamepad has gamepad buttons (BTN_SOUTH..BTN_THUMBR), or joystick buttons together with X/Y
     * axes. Touchscreens and our own virtual device are excluded.
     */
    val isGamepad: Boolean
        get() {
            if (isTouchscreen || name == VIRTUAL_TOUCH_NAME) return false
            val gamepadButtons = keys.any { it in Ev.BTN_SOUTH..Ev.BTN_THUMBR }
            val joystickButtons = keys.any { it in Ev.BTN_JOYSTICK until Ev.BTN_SOUTH }
            val sticks = Ev.ABS_X in abs && Ev.ABS_Y in abs
            return gamepadButtons || (joystickButtons && sticks)
        }

    companion object {
        const val VIRTUAL_TOUCH_NAME = "keymapper-touch"
    }
}

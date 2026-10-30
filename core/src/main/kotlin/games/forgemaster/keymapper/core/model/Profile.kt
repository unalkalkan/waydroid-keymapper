package games.forgemaster.keymapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A complete mapping for one or more games. Positions are fractions of the logical (rotated) screen,
 * so a profile survives resolution changes; sizes are fractions of the shorter screen side.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val phases: List<Phase> = listOf(Phase("Default")),
    /** Grab the controller (EVIOCGRAB) while this profile is mapping, so the game doesn't also see it. */
    val grab: Boolean = true,
    /** Short controller rumble when the phase changes, if the controller supports force feedback. */
    val rumbleOnPhaseSwitch: Boolean = true,
    val schema: Int = SCHEMA,
) {
    companion object {
        const val SCHEMA = 1
        const val MAX_PHASES = 16
        const val MAX_CONTROLS = 64
    }
}

@Serializable
data class Phase(val name: String, val controls: List<Control> = emptyList())

@Serializable
sealed class Control {
    abstract val id: String
    /** Centre, as a fraction of the logical screen width. */
    abstract val x: Float
    /** Centre, as a fraction of the logical screen height. */
    abstract val y: Float

    abstract fun at(x: Float, y: Float): Control
}

enum class ButtonMode(val label: String) {
    HOLD("Hold"), TAP("Tap"), TOGGLE("Toggle"), DOUBLE_TAP("Double tap"), RAPID("Rapid fire"),
}

/** Touches a point while its button (or every button of a chord) is held. */
@Serializable
@SerialName("button")
data class ButtonControl(
    override val id: String,
    override val x: Float,
    override val y: Float,
    /** One button, or a chord: every listed button must be held. */
    val buttons: List<Button>,
    val mode: ButtonMode = ButtonMode.HOLD,
    /** Taps per second in [ButtonMode.RAPID]; clamped to [MIN_RAPID_HZ, MAX_RAPID_HZ]. */
    val rapidHz: Float = 10f,
    val label: String = "",
    /** Visual radius in the editor/HUD, fraction of the shorter side. */
    val size: Float = 0.05f,
    /** While held, this stick drags the finger away from the centre, e.g. to aim a skill before release. */
    val aim: Stick? = null,
    /** Drag distance at full [aim] deflection, fraction of the shorter side. */
    val aimRadius: Float = 0.15f,
) : Control() {
    override fun at(x: Float, y: Float) = copy(x = x, y = y)

    companion object {
        const val MIN_RAPID_HZ = 1f
        const val MAX_RAPID_HZ = 30f
    }
}

/** On-screen movement joystick: the finger lands on the centre and follows the stick within [radius]. */
@Serializable
@SerialName("joystick")
data class JoystickControl(
    override val id: String,
    override val x: Float,
    override val y: Float,
    val stick: Stick = Stick.LEFT,
    /** Fraction of the shorter screen side. */
    val radius: Float = 0.12f,
    val response: StickResponse = StickResponse(),
    /** Snap the direction to the nearest of 8 directions at full deflection (digital movement). */
    val eightWay: Boolean = false,
) : Control() {
    override fun at(x: Float, y: Float) = copy(x = x, y = y)
}

/** Camera/aim drag: the stick moves a finger continuously; it lifts and recentres at [radius]. */
@Serializable
@SerialName("look")
data class LookControl(
    override val id: String,
    override val x: Float,
    override val y: Float,
    val stick: Stick = Stick.RIGHT,
    /** Travel from the centre before recentring, fraction of the shorter side. */
    val radius: Float = 0.2f,
    /** At full deflection the finger moves this many shorter-sides per second. */
    val sensitivity: Float = 1.0f,
    val response: StickResponse = StickResponse(deadzone = 0.1f, curve = 1.6f),
    val invertY: Boolean = false,
) : Control() {
    override fun at(x: Float, y: Float) = copy(x = x, y = y)
}

enum class PhaseAction(val label: String) {
    NEXT("Next phase"), PREVIOUS("Previous phase"), GOTO("Go to phase"), HOLD("Phase while held"),
}

/** Switches the active phase (control set). Its position is only used to draw it. */
@Serializable
@SerialName("phase")
data class PhaseSwitchControl(
    override val id: String,
    override val x: Float,
    override val y: Float,
    val buttons: List<Button>,
    val action: PhaseAction = PhaseAction.NEXT,
    val target: Int = 0,
    val size: Float = 0.04f,
) : Control() {
    override fun at(x: Float, y: Float) = copy(x = x, y = y)
}

/** How raw stick deflection becomes output deflection. */
@Serializable
data class StickResponse(
    /** Radial inner deadzone, fraction of full deflection. */
    val deadzone: Float = 0.12f,
    /** Deflection at or beyond this counts as full. */
    val outer: Float = 0.95f,
    /** Response exponent: 1 = linear, >1 = finer control near the centre, <1 = twitchier. */
    val curve: Float = 1.0f,
    /** Map the square-ish range many pads report onto a circle before the deadzone. */
    val squareToCircle: Boolean = true,
)

/** Per-axis calibration in raw evdev units. For triggers [center] is the resting value. */
@Serializable
data class AxisCalibration(val min: Int, val center: Int, val max: Int, val invert: Boolean = false)

/** Per-controller settings, remembered by vendor/product id. */
@Serializable
data class ControllerConfig(
    val vendor: Int,
    val product: Int,
    val name: String = "",
    /** Keyed by evdev ABS code. */
    val calibration: Map<Int, AxisCalibration> = emptyMap(),
    /** Overrides which ABS code feeds a logical axis. */
    val axisMap: Map<Axis, Int> = emptyMap(),
    /** Overrides which logical button a KEY code produces. */
    val buttonMap: Map<Int, Button> = emptyMap(),
) {
    val key: String get() = controllerKey(vendor, product)
}

fun controllerKey(vendor: Int, product: Int): String = "%04x:%04x".format(vendor, product)

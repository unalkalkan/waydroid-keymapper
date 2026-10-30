package games.forgemaster.keymapper.core.model

import kotlinx.serialization.Serializable

/** Logical gamepad buttons, named after the common Xbox-style layout. */
@Serializable
enum class Button(val label: String) {
    A("A"), B("B"), X("X"), Y("Y"),
    L1("LB"), R1("RB"), L2("LT"), R2("RT"),
    SELECT("Select"), START("Start"), MODE("Home"),
    L3("L3"), R3("R3"),
    DPAD_UP("D-pad up"), DPAD_DOWN("D-pad down"), DPAD_LEFT("D-pad left"), DPAD_RIGHT("D-pad right"),
}

/** Logical analog axes. Stick axes are in [-1, 1] (right/down positive); triggers are in [0, 1]. */
@Serializable
enum class Axis {
    LX, LY, RX, RY, LT, RT;

    val isTrigger: Boolean get() = this == LT || this == RT
}

/** A two-dimensional input source for joystick and look controls. */
@Serializable
enum class Stick(val label: String) {
    LEFT("Left stick"), RIGHT("Right stick"), DPAD("D-pad");

    val axes: Pair<Axis, Axis>?
        get() = when (this) {
            LEFT -> Axis.LX to Axis.LY
            RIGHT -> Axis.RX to Axis.RY
            DPAD -> null
        }
}

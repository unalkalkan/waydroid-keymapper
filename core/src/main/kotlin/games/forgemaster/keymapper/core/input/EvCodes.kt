package games.forgemaster.keymapper.core.input

/** Linux input-event constants (include/uapi/linux/input-event-codes.h). Public kernel ABI. */
object Ev {
    const val SYN = 0x00
    const val KEY = 0x01
    const val REL = 0x02
    const val ABS = 0x03
    const val MSC = 0x04
    const val FF = 0x15

    const val SYN_REPORT = 0
    const val SYN_DROPPED = 3

    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_Z = 0x02
    const val ABS_RX = 0x03
    const val ABS_RY = 0x04
    const val ABS_RZ = 0x05
    const val ABS_THROTTLE = 0x06
    const val ABS_RUDDER = 0x07
    const val ABS_GAS = 0x09
    const val ABS_BRAKE = 0x0a
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11
    const val ABS_MT_SLOT = 0x2f
    const val ABS_MT_TOUCH_MAJOR = 0x30
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36
    const val ABS_MT_TRACKING_ID = 0x39
    const val ABS_MT_PRESSURE = 0x3a

    const val BTN_JOYSTICK = 0x120
    const val BTN_SOUTH = 0x130
    const val BTN_EAST = 0x131
    const val BTN_C = 0x132
    const val BTN_NORTH = 0x133 // a.k.a. BTN_X
    const val BTN_WEST = 0x134 // a.k.a. BTN_Y
    const val BTN_Z = 0x135
    const val BTN_TL = 0x136
    const val BTN_TR = 0x137
    const val BTN_TL2 = 0x138
    const val BTN_TR2 = 0x139
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_MODE = 0x13c
    const val BTN_THUMBL = 0x13d
    const val BTN_THUMBR = 0x13e
    const val BTN_TOUCH = 0x14a
    const val BTN_DPAD_UP = 0x220
    const val BTN_DPAD_DOWN = 0x221
    const val BTN_DPAD_LEFT = 0x222
    const val BTN_DPAD_RIGHT = 0x223

    const val KEY_MENU = 139
    const val KEY_BACK = 158
    const val KEY_HOMEPAGE = 172

    const val INPUT_PROP_DIRECT = 0x01
    const val BUS_VIRTUAL = 0x06

    private val absNames = mapOf(
        ABS_X to "ABS_X", ABS_Y to "ABS_Y", ABS_Z to "ABS_Z", ABS_RX to "ABS_RX", ABS_RY to "ABS_RY",
        ABS_RZ to "ABS_RZ", ABS_THROTTLE to "ABS_THROTTLE", ABS_RUDDER to "ABS_RUDDER", ABS_GAS to "ABS_GAS",
        ABS_BRAKE to "ABS_BRAKE", ABS_HAT0X to "ABS_HAT0X", ABS_HAT0Y to "ABS_HAT0Y",
    )
    private val keyNames = mapOf(
        BTN_SOUTH to "BTN_SOUTH", BTN_EAST to "BTN_EAST", BTN_C to "BTN_C", BTN_NORTH to "BTN_NORTH",
        BTN_WEST to "BTN_WEST", BTN_Z to "BTN_Z", BTN_TL to "BTN_TL", BTN_TR to "BTN_TR", BTN_TL2 to "BTN_TL2",
        BTN_TR2 to "BTN_TR2", BTN_SELECT to "BTN_SELECT", BTN_START to "BTN_START", BTN_MODE to "BTN_MODE",
        BTN_THUMBL to "BTN_THUMBL", BTN_THUMBR to "BTN_THUMBR", BTN_DPAD_UP to "BTN_DPAD_UP",
        BTN_DPAD_DOWN to "BTN_DPAD_DOWN", BTN_DPAD_LEFT to "BTN_DPAD_LEFT", BTN_DPAD_RIGHT to "BTN_DPAD_RIGHT",
        KEY_BACK to "KEY_BACK", KEY_HOMEPAGE to "KEY_HOMEPAGE", KEY_MENU to "KEY_MENU",
    )

    fun absName(code: Int): String = absNames[code] ?: "ABS_0x%02x".format(code)
    fun keyName(code: Int): String = keyNames[code] ?: "KEY_0x%03x".format(code)
}

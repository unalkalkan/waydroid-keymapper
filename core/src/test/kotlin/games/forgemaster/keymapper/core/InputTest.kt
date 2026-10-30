package games.forgemaster.keymapper.core

import games.forgemaster.keymapper.core.input.AbsRange
import games.forgemaster.keymapper.core.input.DeviceCaps
import games.forgemaster.keymapper.core.input.DeviceMapper
import games.forgemaster.keymapper.core.input.Ev
import games.forgemaster.keymapper.core.input.LogicalEvent
import games.forgemaster.keymapper.core.input.LogicalEvent.AxisChange
import games.forgemaster.keymapper.core.input.LogicalEvent.ButtonChange
import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.AxisCalibration
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.ControllerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTest {
    private val stick = AbsRange(-32768, 32767, flat = 128)
    private val trigger = AbsRange(0, 255)

    private val xpad = DeviceCaps(
        name = "Xbox pad", bus = 3, vendor = 0x045e, product = 0x028e,
        keys = setOf(Ev.BTN_SOUTH, Ev.BTN_EAST, Ev.BTN_NORTH, Ev.BTN_WEST, Ev.BTN_TL, Ev.BTN_TR, Ev.BTN_SELECT, Ev.BTN_START, Ev.BTN_MODE),
        abs = mapOf(Ev.ABS_X to stick, Ev.ABS_Y to stick, Ev.ABS_RX to stick, Ev.ABS_RY to stick,
            Ev.ABS_Z to trigger, Ev.ABS_RZ to trigger, Ev.ABS_HAT0X to AbsRange(-1, 1), Ev.ABS_HAT0Y to AbsRange(-1, 1)),
        hasRumble = true,
    )

    private fun events(mapper: DeviceMapper, vararg raw: Triple<Int, Int, Int>): List<LogicalEvent> {
        val out = ArrayList<LogicalEvent>()
        raw.forEach { (t, c, v) -> mapper.onEvent(t, c, v) { out.add(it) } }
        return out
    }

    @Test
    fun classifiesGamepadsOnly() {
        assertTrue(xpad.isGamepad)
        val touch = DeviceCaps("ts", 0, 0, 0, setOf(Ev.BTN_TOUCH), mapOf(Ev.ABS_MT_POSITION_X to stick), direct = true)
        assertFalse(touch.isGamepad)
        val keyboard = DeviceCaps("kbd", 3, 1, 1, (1..120).toSet(), emptyMap())
        assertFalse(keyboard.isGamepad)
        val joystick = DeviceCaps("stick", 3, 1, 2, setOf(0x120, 0x121), mapOf(Ev.ABS_X to stick, Ev.ABS_Y to stick))
        assertTrue(joystick.isGamepad)
        assertFalse(xpad.copy(name = DeviceCaps.VIRTUAL_TOUCH_NAME).isGamepad)
    }

    @Test
    fun xpadLayout() {
        val m = DeviceMapper(xpad)
        assertEquals(listOf(ButtonChange(Button.A, true)), events(m, Triple(Ev.KEY, Ev.BTN_SOUTH, 1)))
        val rx = events(m, Triple(Ev.ABS, Ev.ABS_RX, 32767)).single() as AxisChange
        assertEquals(Axis.RX, rx.axis)
        assertEquals(1f, rx.value, 1e-3f)
        val lt = events(m, Triple(Ev.ABS, Ev.ABS_Z, 255))
        assertEquals(AxisChange(Axis.LT, 1f), lt[0])
        assertEquals(ButtonChange(Button.L2, true), lt[1]) // synthesized: no BTN_TL2
        assertEquals(listOf(ButtonChange(Button.DPAD_LEFT, true)), events(m, Triple(Ev.ABS, Ev.ABS_HAT0X, -1)))
        assertEquals(listOf(ButtonChange(Button.DPAD_LEFT, false), ButtonChange(Button.DPAD_RIGHT, true)),
            events(m, Triple(Ev.ABS, Ev.ABS_HAT0X, 1)))
    }

    @Test
    fun triggerHysteresis() {
        val m = DeviceMapper(xpad)
        assertTrue(events(m, Triple(Ev.ABS, Ev.ABS_Z, 140)).contains(ButtonChange(Button.L2, true)))
        assertFalse(events(m, Triple(Ev.ABS, Ev.ABS_Z, 115)).any { it is ButtonChange }) // 0.45: still held
        assertTrue(events(m, Triple(Ev.ABS, Ev.ABS_Z, 90)).contains(ButtonChange(Button.L2, false)))
    }

    @Test
    fun hidStylePadUsesZRzAsRightStick() {
        val caps = xpad.copy(abs = mapOf(Ev.ABS_X to trigger, Ev.ABS_Y to trigger, Ev.ABS_Z to trigger,
            Ev.ABS_RZ to trigger, Ev.ABS_GAS to trigger, Ev.ABS_BRAKE to trigger))
        val m = DeviceMapper(caps)
        assertEquals(Axis.RX, (events(m, Triple(Ev.ABS, Ev.ABS_Z, 255)).single() as AxisChange).axis)
        assertEquals(Axis.RT, (events(m, Triple(Ev.ABS, Ev.ABS_GAS, 255)).first() as AxisChange).axis)
        val centre = events(m, Triple(Ev.ABS, Ev.ABS_X, 128))
        assertEquals(0f, (centre.single() as AxisChange).value, 0.01f)
    }

    @Test
    fun calibrationAndRemapOverrideDefaults() {
        val config = ControllerConfig(
            0x045e, 0x028e,
            calibration = mapOf(Ev.ABS_X to AxisCalibration(min = -20000, center = 1000, max = 21000, invert = true)),
            buttonMap = mapOf(Ev.BTN_NORTH to Button.Y),
        )
        val m = DeviceMapper(xpad, config)
        assertEquals(-1f, (events(m, Triple(Ev.ABS, Ev.ABS_X, 21000)).single() as AxisChange).value, 1e-4f)
        assertEquals(0.5f, (events(m, Triple(Ev.ABS, Ev.ABS_X, -9500)).single() as AxisChange).value, 1e-3f)
        assertEquals(listOf(ButtonChange(Button.Y, true)), events(m, Triple(Ev.KEY, Ev.BTN_NORTH, 1)))
    }

    @Test
    fun resetReleasesEverything() {
        val m = DeviceMapper(xpad)
        events(m, Triple(Ev.KEY, Ev.BTN_SOUTH, 1), Triple(Ev.ABS, Ev.ABS_X, 32767))
        val out = ArrayList<LogicalEvent>()
        m.reset { out.add(it) }
        assertTrue(out.contains(ButtonChange(Button.A, false)))
        assertTrue(out.contains(AxisChange(Axis.LX, 0f)))
    }
}

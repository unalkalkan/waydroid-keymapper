package games.forgemaster.keymapper.core

import games.forgemaster.keymapper.core.engine.MappingEngine
import games.forgemaster.keymapper.core.engine.PointerChange
import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.engine.TouchAction.DOWN
import games.forgemaster.keymapper.core.engine.TouchAction.MOVE
import games.forgemaster.keymapper.core.engine.TouchAction.UP
import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.ButtonControl
import games.forgemaster.keymapper.core.model.ButtonMode
import games.forgemaster.keymapper.core.model.Control
import games.forgemaster.keymapper.core.model.JoystickControl
import games.forgemaster.keymapper.core.model.LookControl
import games.forgemaster.keymapper.core.model.Phase
import games.forgemaster.keymapper.core.model.PhaseAction
import games.forgemaster.keymapper.core.model.PhaseSwitchControl
import games.forgemaster.keymapper.core.model.Profile
import games.forgemaster.keymapper.core.model.Stick
import games.forgemaster.keymapper.core.model.StickResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val ms = 1_000_000L
    private val out = ArrayList<PointerChange>()
    private val phases = ArrayList<Pair<Int, Boolean>>()
    private val engine = MappingEngine({ out.addAll(it) }, object : MappingEngine.Listener {
        override fun onPhaseChanged(index: Int, count: Int, name: String, byUser: Boolean) {
            phases.add(index to byUser)
        }
    })

    private fun load(vararg controls: Control, more: List<Phase> = emptyList()) {
        engine.setScreen(Screen(1000, 500), 0)
        engine.setProfile(Profile("p", "P", listOf(Phase("main", controls.toList())) + more), 0)
        out.clear()
    }

    private fun take(): List<PointerChange> = ArrayList(out).also { out.clear() }

    private fun button(mode: ButtonMode = ButtonMode.HOLD, vararg b: Button = arrayOf(Button.A), id: String = "b") =
        ButtonControl(id, 0.5f, 0.5f, b.toList(), mode, rapidHz = 10f)

    @Test
    fun holdPressesWhileHeld() {
        load(button())
        engine.onButton(Button.A, true, 0)
        assertEquals(listOf(PointerChange(0, DOWN, 500f, 250f)), take())
        engine.onButton(Button.A, false, 10 * ms)
        assertEquals(listOf(PointerChange(0, UP, 500f, 250f)), take())
    }

    @Test
    fun tapLiftsAfterFortyMilliseconds() {
        load(button(ButtonMode.TAP))
        engine.onButton(Button.A, true, 0)
        assertEquals(DOWN, take().single().action)
        assertEquals(40 * ms, engine.nextDeadline())
        engine.tick(39 * ms)
        assertTrue(take().isEmpty())
        engine.tick(40 * ms)
        assertEquals(UP, take().single().action)
        assertNull(engine.nextDeadline())
    }

    @Test
    fun toggleAlternates() {
        load(button(ButtonMode.TOGGLE))
        engine.onButton(Button.A, true, 0); engine.onButton(Button.A, false, 1)
        assertEquals(listOf(DOWN), take().map { it.action })
        engine.onButton(Button.A, true, 2); engine.onButton(Button.A, false, 3)
        assertEquals(listOf(UP), take().map { it.action })
    }

    @Test
    fun doubleTapRunsItsScript() {
        load(button(ButtonMode.DOUBLE_TAP))
        engine.onButton(Button.A, true, 0)
        var t = 0L
        while (true) {
            t = engine.tick(t) ?: break
        }
        assertEquals(listOf(DOWN, UP, DOWN, UP), take().map { it.action })
    }

    @Test
    fun rapidFireTogglesAtTheConfiguredRate() {
        load(button(ButtonMode.RAPID))
        engine.onButton(Button.A, true, 0)
        for (t in 1..400) engine.tick(t * ms)
        val actions = take().map { it.action }
        // 10 Hz -> a state change every 50 ms: down at 0, then 8 changes in 400 ms.
        assertEquals(9, actions.size)
        assertEquals(DOWN, actions.first())
        engine.onButton(Button.A, false, 401 * ms)
        assertNull(engine.nextDeadline())
    }

    @Test
    fun chordNeedsEveryButton() {
        load(button(ButtonMode.HOLD, Button.L1, Button.Y))
        engine.onButton(Button.L1, true, 0)
        assertTrue(take().isEmpty())
        engine.onButton(Button.Y, true, 1)
        assertEquals(DOWN, take().single().action)
        engine.onButton(Button.Y, false, 2)
        assertEquals(UP, take().single().action)
    }

    @Test
    fun longerChordShadowsItsSubset() {
        val skill = ButtonControl("skill", 0.2f, 0.5f, listOf(Button.X))
        val upgrade = ButtonControl("up", 0.8f, 0.5f, listOf(Button.L1, Button.X))
        load(skill, upgrade)
        engine.onButton(Button.L1, true, 0)
        engine.onButton(Button.X, true, 1)
        assertEquals(listOf(PointerChange(0, DOWN, 800f, 250f)), take())
        // Letting go of the modifier first must not fire the plain binding.
        engine.onButton(Button.L1, false, 2)
        assertEquals(listOf(UP), take().map { it.action })
        engine.onButton(Button.X, false, 3)
        assertTrue(take().isEmpty())
        // Without the modifier the plain binding works as before.
        engine.onButton(Button.X, true, 4)
        assertEquals(listOf(PointerChange(0, DOWN, 200f, 250f)), take())
        // Completing the chord while the plain binding is held fires the chord too.
        engine.onButton(Button.L1, true, 5)
        assertEquals(listOf(PointerChange(1, DOWN, 800f, 250f)), take())
    }

    @Test
    fun heldButtonAimsWithItsStickAndReleasesWhereAimed() {
        load(ButtonControl("skill", 0.5f, 0.5f, listOf(Button.X), aim = Stick.RIGHT, aimRadius = 0.2f))
        engine.onAxis(Axis.RX, 1f, 0)
        assertTrue(take().isEmpty()) // not held: the stick does nothing
        engine.onButton(Button.X, true, 1 * ms)
        assertEquals(listOf(PointerChange(0, DOWN, 500f, 250f)), take()) // lands on the button
        engine.tick(17 * ms)
        assertEquals(listOf(PointerChange(0, MOVE, 600f, 250f)), take()) // 0.2 * 500 px, one frame later
        engine.tick(33 * ms)
        assertEquals(listOf(PointerChange(0, MOVE, 600f, 250f)), take()) // settle
        engine.onAxis(Axis.RX, 0f, 40 * ms)
        engine.onAxis(Axis.RY, -1f, 41 * ms)
        assertEquals(listOf(PointerChange(0, MOVE, 500f, 250f), PointerChange(0, MOVE, 500f, 150f)), take())
        engine.onButton(Button.X, false, 50 * ms)
        assertEquals(listOf(PointerChange(0, UP, 500f, 150f)), take())
        engine.tick(80 * ms)
        assertTrue(take().isEmpty())
        assertNull(engine.nextDeadline())
    }

    @Test
    fun simultaneousButtonsGetDistinctPointers() {
        load(button(id = "a"), ButtonControl("b", 0.1f, 0.1f, listOf(Button.B)))
        engine.onButton(Button.A, true, 0)
        engine.onButton(Button.B, true, 0)
        assertEquals(listOf(0, 1), take().map { it.id })
    }

    @Test
    fun pointerIdIsNotReusedWithinOneBatch() {
        // A phase switch lifts held fingers and, in the same batch, an already-deflected joystick in
        // the new phase lands. Reusing the lifted id there would merge an UP and a DOWN in one uinput frame.
        load(
            button(), PhaseSwitchControl("s", 0.9f, 0.1f, listOf(Button.SELECT), PhaseAction.NEXT),
            more = listOf(Phase("drive", listOf(JoystickControl("j", 0.3f, 0.3f, Stick.LEFT)))),
        )
        engine.onButton(Button.A, true, 0)
        engine.onAxis(Axis.LX, 1f, 0)
        take()
        engine.onButton(Button.SELECT, true, 1)
        val batch = take()
        assertEquals(listOf(UP, DOWN), batch.map { it.action })
        assertTrue("new finger got a different id", batch[0].id != batch[1].id)
    }

    @Test
    fun joystickLandsOnCentreThenMoves() {
        load(JoystickControl("j", 0.2f, 0.6f, Stick.LEFT, radius = 0.2f, response = StickResponse(deadzone = 0.1f, outer = 1f)))
        engine.onAxis(Axis.LX, 1f, 0)
        assertEquals(listOf(PointerChange(0, DOWN, 200f, 300f)), take())
        engine.tick(MappingEngine.ARM_NS)
        val move = take().single()
        assertEquals(MOVE, move.action)
        assertEquals(300f, move.x, 0.5f) // centre + radius (0.2 * 500)
        assertEquals(300f, move.y, 0.5f)
        engine.tick(MappingEngine.ARM_NS + MappingEngine.SETTLE_NS)
        val settle = take().single()
        assertEquals("settle repeats the position", PointerChange(0, MOVE, move.x, move.y), settle)
        assertNull("nothing pending while the stick is held still", engine.nextDeadline())
        engine.onAxis(Axis.LX, 0f, 40 * ms)
        assertEquals(UP, take().single().action)
    }

    @Test
    fun joystickIgnoresDeadzone() {
        load(JoystickControl("j", 0.2f, 0.6f, Stick.LEFT, response = StickResponse(deadzone = 0.2f)))
        engine.onAxis(Axis.LX, 0.15f, 0)
        assertTrue(take().isEmpty())
    }

    @Test
    fun dpadJoystickEightWay() {
        load(JoystickControl("j", 0.5f, 0.5f, Stick.DPAD, radius = 0.1f, eightWay = true))
        engine.onButton(Button.DPAD_UP, true, 0)
        engine.onButton(Button.DPAD_RIGHT, true, 0)
        engine.tick(MappingEngine.ARM_NS)
        val last = take().last()
        assertEquals(500f + 50f * 0.7071f, last.x, 0.5f)
        assertEquals(250f - 50f * 0.7071f, last.y, 0.5f)
    }

    @Test
    fun lookDragsAndRecentres() {
        load(LookControl("l", 0.5f, 0.5f, Stick.RIGHT, radius = 0.1f, sensitivity = 1f,
            response = StickResponse(deadzone = 0f, curve = 1f, outer = 1f, squareToCircle = false)))
        engine.onAxis(Axis.RX, 1f, 0)
        assertEquals(listOf(PointerChange(0, DOWN, 500f, 250f)), take())
        // 500 px/s; radius 50 px is reached after 100 ms of ticks.
        var t = 0L
        repeat(20) { t += 8 * ms; engine.tick(t) }
        val changes = take()
        val up = changes.indexOfFirst { it.action == UP }
        assertTrue("finger lifted at the edge", up > 0)
        assertEquals(550f, changes[up].x, 1f)
        assertTrue("and pressed again at the centre", changes.drop(up).any { it.action == DOWN && it.x == 500f })
    }

    @Test
    fun lookLiftsAfterIdle() {
        load(LookControl("l", 0.5f, 0.5f, Stick.RIGHT))
        engine.onAxis(Axis.RX, 1f, 0)
        engine.tick(8 * ms)
        engine.onAxis(Axis.RX, 0f, 10 * ms)
        take()
        engine.tick(10 * ms + MappingEngine.LOOK_RELEASE_NS - 1)
        assertTrue(take().none { it.action == UP })
        engine.tick(10 * ms + MappingEngine.LOOK_RELEASE_NS)
        assertEquals(UP, take().single().action)
    }

    @Test
    fun phaseSwitchReleasesAndNeedsFreshPress() {
        load(
            button(), PhaseSwitchControl("s", 0.9f, 0.1f, listOf(Button.SELECT), PhaseAction.NEXT),
            more = listOf(Phase("menu", listOf(ButtonControl("m", 0.1f, 0.1f, listOf(Button.A))))),
        )
        engine.onButton(Button.A, true, 0)
        engine.onButton(Button.SELECT, true, 1)
        assertEquals(1, engine.phase)
        assertEquals(1 to true, phases.last())
        assertEquals(listOf(DOWN, UP), take().map { it.action }) // A's finger lifted on switch
        engine.onButton(Button.SELECT, false, 2)
        assertTrue("A still held: no new touch until pressed again", take().isEmpty())
        engine.onButton(Button.A, false, 3)
        engine.onButton(Button.A, true, 4)
        assertEquals(PointerChange(0, DOWN, 100f, 50f), take().single())
    }

    @Test
    fun holdPhaseReturnsOnRelease() {
        load(
            PhaseSwitchControl("s", 0.9f, 0.1f, listOf(Button.L1), PhaseAction.HOLD, target = 1),
            more = listOf(Phase("alt")),
        )
        engine.onButton(Button.L1, true, 0)
        assertEquals(1, engine.phase)
        engine.onButton(Button.L1, false, 1)
        assertEquals(0, engine.phase)
    }

    @Test
    fun disabledEngineDoesNothingButKeepsState() {
        load(button())
        engine.setEnabled(false, 0)
        engine.onButton(Button.A, true, 0)
        assertTrue(take().isEmpty())
        engine.setEnabled(true, 1)
        assertTrue("held button needs a fresh press after re-enable", take().isEmpty())
    }

    @Test
    fun screenChangeRescalesPositions() {
        load(button())
        engine.setScreen(Screen(2000, 1000), 0)
        engine.onButton(Button.A, true, 0)
        assertEquals(PointerChange(0, DOWN, 1000f, 500f), take().single())
    }
}

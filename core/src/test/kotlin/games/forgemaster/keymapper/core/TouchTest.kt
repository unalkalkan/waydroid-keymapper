package games.forgemaster.keymapper.core

import games.forgemaster.keymapper.core.engine.MotionActions
import games.forgemaster.keymapper.core.engine.MotionPlanner
import games.forgemaster.keymapper.core.engine.PointerChange
import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.engine.StickMath
import games.forgemaster.keymapper.core.engine.TouchAction.DOWN
import games.forgemaster.keymapper.core.engine.TouchAction.MOVE
import games.forgemaster.keymapper.core.engine.TouchAction.UP
import games.forgemaster.keymapper.core.engine.Vec
import games.forgemaster.keymapper.core.model.StickResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTest {
    @Test
    fun plannerSequencesMultitouch() {
        val p = MotionPlanner()
        val steps = p.plan(listOf(PointerChange(3, DOWN, 1f, 1f))) +
            p.plan(listOf(PointerChange(1, DOWN, 2f, 2f))) +
            p.plan(listOf(PointerChange(1, MOVE, 5f, 5f), PointerChange(3, MOVE, 6f, 6f))) +
            p.plan(listOf(PointerChange(3, UP, 6f, 6f))) +
            p.plan(listOf(PointerChange(1, UP, 5f, 5f)))
        assertEquals(
            listOf(
                MotionActions.DOWN,
                MotionActions.POINTER_DOWN or (0 shl 8), // id 1 sorts before id 3
                MotionActions.MOVE,
                MotionActions.POINTER_UP or (1 shl 8),
                MotionActions.UP,
            ),
            steps.map { it.action },
        )
        assertEquals(listOf(1, 3), steps[1].pointers.map { it.id })
        assertEquals(2, steps[2].pointers.size)
        assertEquals(6f, steps[3].pointers[1].x)
        assertTrue(!p.isTouching)
    }

    @Test
    fun plannerCoalescesMovesAndCarriesFinalPositionIntoUp() {
        val p = MotionPlanner()
        p.plan(listOf(PointerChange(0, DOWN, 0f, 0f)))
        val steps = p.plan(listOf(PointerChange(0, MOVE, 1f, 1f), PointerChange(0, MOVE, 2f, 2f), PointerChange(0, UP, 3f, 3f)))
        assertEquals(listOf(MotionActions.MOVE, MotionActions.UP), steps.map { it.action })
        assertEquals(3f, steps[1].pointers.single().x)
    }

    @Test
    fun releaseAllLiftsEverything() {
        val p = MotionPlanner()
        p.plan(listOf(PointerChange(0, DOWN, 0f, 0f), PointerChange(1, DOWN, 1f, 1f)))
        val steps = p.releaseAll()
        assertEquals(listOf(MotionActions.POINTER_UP or (1 shl 8), MotionActions.UP), steps.map { it.action })
    }

    @Test
    fun landingStartsANewGestureWithEveryPointer() {
        val p = MotionPlanner()
        p.plan(listOf(PointerChange(4, DOWN, 4f, 4f), PointerChange(2, DOWN, 2f, 2f)))
        val move = p.plan(listOf(PointerChange(4, MOVE, 9f, 9f))).single()
        val steps = MotionPlanner.landing(move.pointers)
        assertEquals(listOf(MotionActions.DOWN, MotionActions.POINTER_DOWN or (1 shl 8)), steps.map { it.action })
        assertEquals(listOf(2), steps[0].pointers.map { it.id })
        assertEquals(listOf(2, 4), steps[1].pointers.map { it.id })
        assertEquals(9f, steps[1].pointers[1].x)
        assertTrue(MotionPlanner.landing(emptyList()).isEmpty())
        val again = p.repeat()!!
        assertEquals(MotionActions.MOVE, again.action)
        assertEquals(move.pointers, again.pointers)
        p.releaseAll()
        assertEquals(null, p.repeat())
    }

    @Test
    fun naturalCoordinatesMatchInputReaderRotation() {
        // Portrait panel 1080x2400 rotated 90: logical landscape 2400x1080.
        val s = Screen(2400, 1080, 1)
        assertEquals(1080, s.naturalWidth)
        assertEquals(2400, s.naturalHeight)
        assertEquals(1080f to 0f, s.toNatural(0f, 0f)) // logical top-left = panel top-right
        assertEquals(0f to 2400f, s.toNatural(2400f, 1080f))
        assertEquals((1080f - 100f) to 50f, s.toNatural(50f, 100f))
        val r2 = Screen(1080, 2400, 2)
        assertEquals(1080f to 2400f, r2.toNatural(0f, 0f))
        val r3 = Screen(2400, 1080, 3)
        assertEquals(0f to 2400f, r3.toNatural(0f, 0f))
        assertEquals(100f to (2400f - 50f), r3.toNatural(50f, 100f))
    }

    @Test
    fun stickResponse() {
        val r = StickResponse(deadzone = 0.1f, outer = 0.9f, curve = 2f, squareToCircle = false)
        assertEquals(Vec.ZERO, StickMath.apply(Vec(0.05f, 0.05f), r))
        assertEquals(1f, StickMath.apply(Vec(0.95f, 0f), r).length, 1e-4f)
        assertEquals(0.25f, StickMath.shape(0.5f, r), 1e-4f) // ((0.5-0.1)/0.8)^2
        val corner = StickMath.squareToCircle(Vec(1f, 1f))
        assertEquals(1f, corner.length, 1e-4f)
        assertEquals(Vec(1f, 0f), StickMath.eightWay(Vec(0.9f, 0.2f)))
        val diag = StickMath.eightWay(Vec(0.5f, -0.6f))
        assertEquals(0.7071f, diag.x, 1e-3f)
        assertEquals(-0.7071f, diag.y, 1e-3f)
    }
}

package games.forgemaster.keymapper.core.engine

import kotlinx.serialization.Serializable
import kotlin.math.min

/** Logical (rotated) display size in pixels and its rotation (0..3, Android Surface.ROTATION_*). */
@Serializable
data class Screen(val width: Int, val height: Int, val rotation: Int = 0) {
    val shortSide: Int get() = min(width, height)
    /** Size of the display in its natural (rotation 0) orientation. */
    val naturalWidth: Int get() = if (rotation % 2 == 1) height else width
    val naturalHeight: Int get() = if (rotation % 2 == 1) width else height

    /**
     * Logical -> natural panel coordinates, the inverse of the rotation InputReader applies to an
     * orientation-aware touchscreen. Needed when emitting through a virtual (uinput) touchscreen.
     */
    fun toNatural(x: Float, y: Float): Pair<Float, Float> = when (rotation and 3) {
        1 -> (height - y) to x
        2 -> (width - x) to (height - y)
        3 -> y to (width - x)
        else -> x to y
    }
}

@Serializable
enum class TouchAction { DOWN, MOVE, UP }

/** One finger change in logical pixels. A batch of changes is one atomic input frame. */
data class PointerChange(val id: Int, val action: TouchAction, val x: Float, val y: Float)

/** Receives batches of pointer changes on the engine thread. */
fun interface TouchSink {
    fun apply(changes: List<PointerChange>)
}

/** Android MotionEvent action values (public API constants, duplicated so core stays JVM-only). */
object MotionActions {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3
    const val POINTER_DOWN = 5
    const val POINTER_UP = 6
    const val POINTER_INDEX_SHIFT = 8
}

data class PointerState(val id: Int, val x: Float, val y: Float)

/** One MotionEvent to inject: [action] is already combined with the pointer index. */
data class MotionStep(val action: Int, val pointers: List<PointerState>) {
    val maskedAction: Int get() = action and 0xff
    val actionIndex: Int get() = action shr MotionActions.POINTER_INDEX_SHIFT
}

/**
 * Converts pointer-change batches into the MotionEvent sequence Android expects: one DOWN or
 * POINTER_DOWN per new finger, one UP or POINTER_UP per lifted finger, and moves coalesced into a
 * single MOVE carrying every active pointer. Pointers are kept sorted by id.
 */
class MotionPlanner {
    private val active = java.util.TreeMap<Int, PointerState>()

    val activeCount: Int get() = active.size
    val isTouching: Boolean get() = active.isNotEmpty()

    fun plan(changes: List<PointerChange>): List<MotionStep> {
        val steps = ArrayList<MotionStep>()
        var movePending = false
        fun flushMove() {
            if (movePending && active.isNotEmpty()) steps.add(MotionStep(MotionActions.MOVE, snapshot()))
            movePending = false
        }
        for (c in changes) {
            when (c.action) {
                TouchAction.MOVE -> {
                    if (c.id !in active) continue
                    // Same-position moves are kept: the engine sends them to stop resampling overshoot.
                    active[c.id] = PointerState(c.id, c.x, c.y)
                    movePending = true
                }
                TouchAction.DOWN -> {
                    flushMove()
                    if (c.id in active) continue
                    active[c.id] = PointerState(c.id, c.x, c.y)
                    val pointers = snapshot()
                    val index = pointers.indexOfFirst { it.id == c.id }
                    val action = if (pointers.size == 1) MotionActions.DOWN
                    else MotionActions.POINTER_DOWN or (index shl MotionActions.POINTER_INDEX_SHIFT)
                    steps.add(MotionStep(action, pointers))
                }
                TouchAction.UP -> {
                    if (c.id !in active) continue
                    if (active[c.id]!!.let { it.x != c.x || it.y != c.y }) {
                        active[c.id] = PointerState(c.id, c.x, c.y)
                        movePending = true
                    }
                    flushMove()
                    val pointers = snapshot()
                    val index = pointers.indexOfFirst { it.id == c.id }
                    val action = if (pointers.size == 1) MotionActions.UP
                    else MotionActions.POINTER_UP or (index shl MotionActions.POINTER_INDEX_SHIFT)
                    steps.add(MotionStep(action, pointers))
                    active.remove(c.id)
                }
            }
        }
        flushMove()
        return steps
    }

    /** A MOVE that repeats every active pointer where it is, or null when nothing is touching. */
    fun repeat(): MotionStep? = if (active.isEmpty()) null else MotionStep(MotionActions.MOVE, snapshot())

    /** Lifts every active pointer (one UP/POINTER_UP each). */
    fun releaseAll(): List<MotionStep> =
        plan(active.values.toList().asReversed().map { PointerChange(it.id, TouchAction.UP, it.x, it.y) })

    private fun snapshot(): List<PointerState> = active.values.toList()

    companion object {
        /** The DOWN and POINTER_DOWNs that put [pointers] (sorted by id) on screen as a new gesture. */
        fun landing(pointers: List<PointerState>): List<MotionStep> = List(pointers.size) { i ->
            val action = if (i == 0) MotionActions.DOWN
            else MotionActions.POINTER_DOWN or (i shl MotionActions.POINTER_INDEX_SHIFT)
            MotionStep(action, pointers.subList(0, i + 1))
        }
    }
}

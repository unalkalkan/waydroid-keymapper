package games.forgemaster.keymapper.core.engine

import games.forgemaster.keymapper.core.model.StickResponse
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec(val x: Float, val y: Float) {
    val length: Float get() = hypot(x, y)
    val isZero: Boolean get() = x == 0f && y == 0f

    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec(x * k, y * k)

    companion object {
        val ZERO = Vec(0f, 0f)
    }
}

object StickMath {
    /** Maps the unit square onto the unit disc (corners of a square-gated pad land on the circle). */
    fun squareToCircle(v: Vec): Vec {
        val x = v.x.coerceIn(-1f, 1f)
        val y = v.y.coerceIn(-1f, 1f)
        return Vec(x * sqrt(1f - y * y / 2f), y * sqrt(1f - x * x / 2f))
    }

    /** Applies the radial deadzone, outer limit and response curve. Output length is in [0, 1]. */
    fun apply(raw: Vec, response: StickResponse): Vec {
        val v = if (response.squareToCircle) squareToCircle(raw) else raw
        val m = v.length
        if (m <= response.deadzone || m == 0f) return Vec.ZERO
        val magnitude = shape(m, response)
        return Vec(v.x / m * magnitude, v.y / m * magnitude)
    }

    /** One-dimensional response: raw deflection in [0, 1] -> output in [0, 1]. Used by the curve editor. */
    fun shape(m: Float, response: StickResponse): Float {
        if (m <= response.deadzone) return 0f
        val outer = response.outer.coerceAtLeast(response.deadzone + 0.01f)
        val n = ((m - response.deadzone) / (outer - response.deadzone)).coerceIn(0f, 1f)
        return n.pow(response.curve)
    }

    /** Snaps a non-zero vector to the nearest of 8 directions at full length. */
    fun eightWay(v: Vec): Vec {
        if (v.isZero) return Vec.ZERO
        val step = PI / 4
        val angle = (atan2(v.y.toDouble(), v.x.toDouble()) / step).roundToInt() * step
        return Vec(cos(angle).toFloat().snap(), sin(angle).toFloat().snap())
    }

    private fun Float.snap() = if (kotlin.math.abs(this) < 1e-6f) 0f else this
}

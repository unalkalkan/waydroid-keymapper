package games.forgemaster.keymapper.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Shows every active pointer and logs each MotionEvent (tag `KmTouchTest`) as
 * `action=<name> idx=<actionIndex> n=<pointerCount> p=<id:x,y;...> dev=<deviceId>`.
 * Coordinates are screen pixels (raw), so they compare directly with injected positions.
 */
class TouchTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TouchView(this))
    }

    private class TouchView(context: Context) : View(context) {
        private val pointers = LinkedHashMap<Int, PointF>()
        private val location = IntArray(2)
        private var last = "no touches yet"
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(127, 209, 174) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            getLocationOnScreen(location)
            val line = format(e)
            Log.i(TAG, line)
            last = line
            when (e.actionMasked) {
                MotionEvent.ACTION_CANCEL -> pointers.clear()
                else -> {
                    pointers.clear()
                    for (i in 0 until e.pointerCount) {
                        val lifted = (e.actionMasked == MotionEvent.ACTION_UP ||
                            e.actionMasked == MotionEvent.ACTION_POINTER_UP) && i == e.actionIndex
                        if (!lifted) pointers[e.getPointerId(i)] = PointF(rawX(e, i), rawY(e, i))
                    }
                }
            }
            invalidate()
            return true
        }

        private fun rawX(e: MotionEvent, i: Int) =
            if (Build.VERSION.SDK_INT >= 29) e.getRawX(i) else e.getX(i) + location[0]

        private fun rawY(e: MotionEvent, i: Int) =
            if (Build.VERSION.SDK_INT >= 29) e.getRawY(i) else e.getY(i) + location[1]

        private fun format(e: MotionEvent): String {
            val action = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                else -> "OTHER_${e.actionMasked}"
            }
            val points = (0 until e.pointerCount).joinToString(";") { i ->
                "${e.getPointerId(i)}:${rawX(e, i).roundToInt()},${rawY(e, i).roundToInt()}"
            }
            return "action=$action idx=${e.actionIndex} n=${e.pointerCount} p=$points dev=${e.deviceId}"
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(18, 24, 22))
            canvas.drawText("Touch test · ${pointers.size} active", 32f, 56f, text)
            canvas.drawText(last, 32f, 100f, text)
            getLocationOnScreen(location)
            for ((id, p) in pointers) {
                val x = p.x - location[0]
                val y = p.y - location[1]
                canvas.drawCircle(x, y, 44f, ring)
                canvas.drawCircle(x, y, 12f, dot)
                canvas.drawText("#$id (${p.x.roundToInt()}, ${p.y.roundToInt()})", x + 52f, y - 12f, text)
            }
        }
    }

    companion object {
        const val TAG = "KmTouchTest"
    }
}

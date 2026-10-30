package games.forgemaster.keymapper.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import games.forgemaster.keymapper.app.ui.LabeledSlider
import games.forgemaster.keymapper.core.engine.StickMath
import games.forgemaster.keymapper.core.model.StickResponse
import kotlin.math.roundToInt

/** Deadzone / outer / curve editor with a live plot of the engine's own response function. */
@Composable
fun ResponseEditor(response: StickResponse, onChange: (StickResponse) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Response curve", style = MaterialTheme.typography.titleSmall)
        CurvePlot(response, Modifier.fillMaxWidth().height(140.dp))
        LabeledSlider("Deadzone", response.deadzone, 0f..0.5f, { "${(it * 100).roundToInt()}%" }) {
            onChange(response.copy(deadzone = it, outer = maxOf(response.outer, it + 0.05f)))
        }
        LabeledSlider("Full speed at", response.outer, 0.5f..1f, { "${(it * 100).roundToInt()}%" }) {
            onChange(response.copy(outer = maxOf(it, response.deadzone + 0.05f)))
        }
        LabeledSlider("Curve", response.curve, 0.3f..4f, { "%.2f".format(it) }) {
            onChange(response.copy(curve = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Square to circle")
                Text("Fixes pads that report square corners", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = response.squareToCircle, onCheckedChange = { onChange(response.copy(squareToCircle = it)) })
        }
    }
}

@Composable
fun CurvePlot(response: StickResponse, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val shade = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Deadzone and saturated regions.
        drawRect(shade, Offset.Zero, Size(w * response.deadzone, h))
        drawRect(shade.copy(alpha = 0.08f), Offset(w * response.outer, 0f), Size(w * (1 - response.outer), h))
        for (i in 0..4) {
            val x = w * i / 4f
            val y = h * i / 4f
            drawLine(grid, Offset(x, 0f), Offset(x, h))
            drawLine(grid, Offset(0f, y), Offset(w, y))
        }
        drawLine(grid, Offset(0f, h), Offset(w, 0f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val path = Path()
        val steps = 100
        for (i in 0..steps) {
            val m = i / steps.toFloat()
            val out = StickMath.shape(m, response)
            val p = Offset(w * m, h * (1 - out))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, line, style = Stroke(3.dp.toPx()))
    }
}

package games.forgemaster.keymapper.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import games.forgemaster.keymapper.app.KeymapperApp
import games.forgemaster.keymapper.app.data.ControllerEntity
import games.forgemaster.keymapper.core.input.Ev
import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.AxisCalibration
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.protocol.ControllerInfo
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private data class ControllerRow(val key: String, val entity: ControllerEntity?, val info: ControllerInfo?)

@Composable
fun ControllersScreen(modifier: Modifier, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val graph = KeymapperApp.graph(context)
    val status by graph.helper.status.collectAsState()
    val entities by graph.db.controllers().observe().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf<Int?>(null) }
    var calibrating by remember { mutableStateOf<ControllerRow?>(null) }

    val connected = status?.controllers.orEmpty()
    val rows = (connected.map { it.key } + entities.map { it.key }).distinct().map { key ->
        ControllerRow(key, entities.firstOrNull { it.key == key }, connected.firstOrNull { it.key == key })
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Controllers", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (status == null) "Helper offline — connected controllers show up once it runs."
                else "Tick the controllers to use. With none ticked, every connected gamepad is used.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (rows.isEmpty()) item { Text("No controllers seen yet. Plug one in or pair it over Bluetooth.") }
        items(rows, key = { it.key }) { row ->
            ControllerCard(
                row = row,
                testing = row.info != null && testing == row.info.id,
                onToggleUse = { use ->
                    scope.launch {
                        val e = row.entity ?: row.info?.let { ControllerEntity.new(it.vendor, it.product, it.name) } ?: return@launch
                        graph.db.controllers().upsert(e.copy(selected = use))
                    }
                },
                onTest = { testing = if (testing == row.info?.id) null else row.info?.id },
                onCalibrate = { calibrating = row },
                onResetCalibration = {
                    scope.launch {
                        val e = row.entity ?: return@launch
                        graph.db.controllers().upsert(e.withConfig(e.config().copy(calibration = emptyMap())))
                        snackbar.showSnackbar("Calibration reset")
                    }
                },
                onForget = { scope.launch { graph.db.controllers().delete(row.key) } },
            )
        }
    }

    calibrating?.let { row ->
        val info = row.info
        if (info == null) {
            calibrating = null
        } else {
            CalibrationDialog(
                info = info,
                onDismiss = { calibrating = null },
                onSave = { calibration ->
                    calibrating = null
                    scope.launch {
                        val e = row.entity ?: ControllerEntity.new(info.vendor, info.product, info.name)
                        graph.db.controllers().upsert(e.withConfig(e.config().copy(calibration = calibration)))
                        snackbar.showSnackbar("Calibration saved for ${info.name}")
                    }
                },
            )
        }
    }
}

@Composable
private fun ControllerCard(
    row: ControllerRow,
    testing: Boolean,
    onToggleUse: (Boolean) -> Unit,
    onTest: () -> Unit,
    onCalibrate: () -> Unit,
    onResetCalibration: () -> Unit,
    onForget: () -> Unit,
) {
    val info = row.info
    val name = info?.name ?: row.entity?.name ?: row.key
    val calibrated = row.entity?.config()?.calibration?.isNotEmpty() == true
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.entity?.selected == true, onCheckedChange = onToggleUse)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    val bits = buildList {
                        add(row.key)
                        add(if (info != null) "connected" else "not connected")
                        if (info?.grabbed == true) add("grabbed")
                        if (info?.active == true) add("in use")
                        if (info?.hasRumble == true) add("rumble")
                        if (calibrated) add("calibrated")
                    }
                    Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info != null) {
                    OutlinedButton(onClick = onTest) { Text(if (testing) "Stop test" else "Test") }
                    OutlinedButton(onClick = onCalibrate) { Text("Calibrate") }
                }
                if (calibrated) TextButton(onClick = onResetCalibration) { Text("Reset calibration") }
                if (info == null) TextButton(onClick = onForget) { Text("Forget") }
            }
            if (testing && info != null) LiveTestPanel(info.id)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveTestPanel(deviceId: Int) {
    val graph = KeymapperApp.graph(LocalContext.current)
    val axes = remember { mutableStateMapOf<Axis, Float>() }
    val buttons = remember { mutableStateMapOf<Button, Boolean>() }
    DisposableEffect(deviceId) {
        graph.helper.acquireMonitor()
        onDispose { graph.helper.releaseMonitor() }
    }
    LaunchedEffect(deviceId) {
        graph.helper.logicalInputs.filter { it.device == deviceId }.collect { e ->
            e.axis?.let { axes[it] = e.value }
            e.button?.let { buttons[it] = e.value > 0.5f }
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Inputs are grabbed while testing.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Axis.LX to Axis.LY, Axis.RX to Axis.RY).forEach { (ax, ay) ->
                Canvas(Modifier.size(96.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(outline, r, style = Stroke(2.dp.toPx()))
                    val x = (axes[ax] ?: 0f).coerceIn(-1f, 1f)
                    val y = (axes[ay] ?: 0f).coerceIn(-1f, 1f)
                    drawCircle(primary, 8.dp.toPx(), center + Offset(x * r, y * r))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Axis.LT, Axis.RT).forEach { a ->
                    Text(a.name, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { (axes[a] ?: 0f).coerceIn(0f, 1f) }, modifier = Modifier.width(96.dp))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button.entries.forEach { b ->
                FilterChip(selected = buttons[b] == true, onClick = {}, label = { Text(b.label) })
            }
        }
    }
}

private fun isCalibratableAxis(code: Int) = code < Ev.ABS_HAT0X || (code in 0x18 until Ev.ABS_MT_SLOT)

@Composable
private fun CalibrationDialog(info: ControllerInfo, onDismiss: () -> Unit, onSave: (Map<Int, AxisCalibration>) -> Unit) {
    val graph = KeymapperApp.graph(LocalContext.current)
    var step by remember { mutableIntStateOf(0) }
    val codes = remember(info) { info.axes.keys.filter(::isCalibratableAxis).sorted() }
    val restSum = remember { mutableStateMapOf<Int, Long>() }
    val restCount = remember { mutableStateMapOf<Int, Int>() }
    val last = remember { mutableStateMapOf<Int, Int>() }
    val seenMin = remember { mutableStateMapOf<Int, Int>() }
    val seenMax = remember { mutableStateMapOf<Int, Int>() }

    DisposableEffect(info.id) {
        graph.helper.acquireMonitor()
        onDispose { graph.helper.releaseMonitor() }
    }
    LaunchedEffect(info.id, step) {
        val events = graph.helper.rawEvents.filter { it.device == info.id && it.type == Ev.ABS && it.code in codes }
        when (step) {
            1 -> {
                withTimeoutOrNull(1500) {
                    events.collect { e ->
                        last[e.code] = e.value
                        restSum[e.code] = (restSum[e.code] ?: 0L) + e.value
                        restCount[e.code] = (restCount[e.code] ?: 0) + 1
                    }
                }
                step = 2
            }
            2 -> events.collect { e ->
                last[e.code] = e.value
                seenMin[e.code] = minOf(seenMin[e.code] ?: e.value, e.value)
                seenMax[e.code] = maxOf(seenMax[e.code] ?: e.value, e.value)
            }
        }
    }

    fun result(): Map<Int, AxisCalibration> = codes.associateWith { code ->
        val range = info.axes.getValue(code)
        val rest = restCount[code]?.takeIf { it > 0 }?.let { (restSum.getValue(code) / it).toInt() }
            ?: last[code]
            ?: range.center.roundToInt()
        val lo = minOf(seenMin[code] ?: range.min, rest)
        val hi = maxOf(seenMax[code] ?: range.max, rest)
        AxisCalibration(lo, rest, hi)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate ${info.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step) {
                    0 -> Text("Step 1: put the controller down and leave the sticks and triggers alone, then press Start.")
                    1 -> {
                        Text("Measuring the resting position…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> Text("Step 2: roll both sticks around their full range a few times and press both triggers all the way. Then press Save.")
                }
                codes.forEach { code ->
                    val range = info.axes.getValue(code)
                    Text(
                        "%-12s now %6s  min %6s  max %6s  (hw %d..%d)".format(
                            Ev.absName(code),
                            last[code]?.toString() ?: "-",
                            seenMin[code]?.toString() ?: "-",
                            seenMax[code]?.toString() ?: "-",
                            range.min,
                            range.max,
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (codes.isEmpty()) Text("This controller reports no analog axes.")
                AssistChip(onClick = {}, label = { Text("Inputs are grabbed during calibration") }, modifier = Modifier.height(32.dp))
            }
        },
        confirmButton = {
            when (step) {
                0 -> TextButton(enabled = codes.isNotEmpty(), onClick = { step = 1 }) { Text("Start") }
                1 -> TextButton(enabled = false, onClick = {}) { Text("Wait…") }
                else -> TextButton(onClick = { onSave(result()) }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

package games.forgemaster.keymapper.app.ui.editor

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import games.forgemaster.keymapper.app.KeymapperApp
import games.forgemaster.keymapper.app.data.ProfileEntity
import games.forgemaster.keymapper.app.ui.findActivity
import games.forgemaster.keymapper.app.ui.newId
import games.forgemaster.keymapper.app.ui.newProfile
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
import games.forgemaster.keymapper.core.model.sanitized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Mutable editing session over one profile. */
class EditorState(initial: Profile) {
    var profile by mutableStateOf(initial)
        private set
    var phase by mutableIntStateOf(0)
    var selectedId by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)

    val controls: List<Control> get() = profile.phases.getOrNull(phase)?.controls.orEmpty()
    val selected: Control? get() = controls.firstOrNull { it.id == selectedId }

    fun update(transform: (Profile) -> Profile) {
        profile = transform(profile)
        dirty = true
    }

    fun markSaved(p: Profile) {
        profile = p
        dirty = false
    }

    private fun updatePhase(f: (Phase) -> Phase) = update { p ->
        p.copy(phases = p.phases.mapIndexed { i, ph -> if (i == phase) f(ph) else ph })
    }

    fun updateControl(id: String, f: (Control) -> Control) =
        updatePhase { ph -> ph.copy(controls = ph.controls.map { if (it.id == id) f(it) else it }) }

    fun add(control: Control) {
        updatePhase { ph -> ph.copy(controls = ph.controls + control) }
        selectedId = control.id
    }

    fun remove(id: String) {
        updatePhase { ph -> ph.copy(controls = ph.controls.filterNot { it.id == id }) }
        if (selectedId == id) selectedId = null
    }

    fun addPhase() {
        update { p -> p.copy(phases = p.phases + Phase("Phase ${p.phases.size + 1}")) }
        phase = profile.phases.lastIndex
        selectedId = null
    }

    fun renamePhase(index: Int, name: String) = update { p ->
        p.copy(phases = p.phases.mapIndexed { i, ph -> if (i == index) ph.copy(name = name) else ph })
    }

    fun deletePhase(index: Int) {
        if (profile.phases.size <= 1) return
        update { p ->
            val remaining = p.phases.filterIndexed { i, _ -> i != index }.map { ph ->
                ph.copy(controls = ph.controls.map { c ->
                    if (c is PhaseSwitchControl) {
                        c.copy(target = when {
                            c.target == index -> 0
                            c.target > index -> c.target - 1
                            else -> c.target
                        })
                    } else {
                        c
                    }
                })
            }
            p.copy(phases = remaining)
        }
        phase = phase.coerceAtMost(profile.phases.lastIndex)
        selectedId = null
    }
}

private fun displayAspect(context: Context): Float {
    val wm = context.getSystemService(WindowManager::class.java)
    val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
        wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        dm.widthPixels to dm.heightPixels
    }
    return max(w, h).toFloat() / min(w, h).coerceAtLeast(1)
}

private fun backgroundFile(context: Context, profileId: String) = File(File(context.filesDir, "backgrounds"), profileId)

private fun decodeBackground(file: File): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
fun EditorScreen(profileId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = KeymapperApp.graph(context)
    var state by remember { mutableStateOf<EditorState?>(null) }

    LaunchedEffect(profileId) {
        val entity = graph.db.profiles().get(profileId)
        state = EditorState(entity?.toProfile() ?: newProfile("Profile").copy(id = profileId))
    }

    // Landscape, edge to edge, system bars hidden while editing.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous
        }
    }

    val s = state
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    EditorContent(s, onClose)
}

@Composable
private fun EditorContent(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = KeymapperApp.graph(context)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val aspect = remember { displayAspect(context) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var confirmLeave by remember { mutableStateOf(false) }
    var editingPhase by remember { mutableStateOf<Int?>(null) }
    var profileSettings by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf<String?>(null) }
    var bgVersion by remember { mutableIntStateOf(0) }
    val profileId = state.profile.id

    val background by produceState<ImageBitmap?>(null, bgVersion) {
        value = withContext(Dispatchers.IO) { runCatching { decodeBackground(backgroundFile(context, profileId)) }.getOrNull() }
    }

    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val file = backgroundFile(context, profileId)
                    file.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                }.isSuccess
            }
            if (ok) bgVersion++ else snackbar.showSnackbar("Couldn't read that image")
        }
    }

    fun save() {
        scope.launch {
            val clean = state.profile.sanitized()
            graph.db.profiles().upsert(ProfileEntity.of(clean))
            state.markSaved(clean)
            state.phase = state.phase.coerceAtMost(clean.phases.lastIndex)
            snackbar.showSnackbar("Saved")
        }
    }

    fun learnButtons(id: String) {
        scope.launch {
            learning = "Press a button on the controller…"
            val b = graph.helper.learnButton()
            learning = null
            if (b == null) {
                snackbar.showSnackbar(if (graph.helper.isConnected) "No button pressed" else "Helper offline — can't learn")
                return@launch
            }
            state.updateControl(id) { c ->
                when (c) {
                    is ButtonControl -> c.copy(buttons = listOf(b))
                    is PhaseSwitchControl -> c.copy(buttons = listOf(b))
                    else -> c
                }
            }
        }
    }

    fun learnStick(id: String) {
        scope.launch {
            learning = "Push a stick (or the D-pad)…"
            val stick = graph.helper.learnStick()
            learning = null
            if (stick == null) {
                snackbar.showSnackbar(if (graph.helper.isConnected) "No stick movement" else "Helper offline — can't learn")
                return@launch
            }
            state.updateControl(id) { c ->
                when (c) {
                    is JoystickControl -> c.copy(stick = stick)
                    is LookControl -> c.copy(stick = stick)
                    else -> c
                }
            }
        }
    }

    BackHandler {
        if (state.dirty) confirmLeave = true else onClose()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val boxAspect = maxWidth / maxHeight
            val (w, h) = if (boxAspect > aspect) (maxHeight * aspect) to maxHeight else maxWidth to (maxWidth / aspect)
            Box(Modifier.size(w, h)) {
                val bg = background
                if (bg != null) {
                    Image(bg, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                }
                ControlCanvas(state, showGrid = bg == null)
            }
        }

        if (toolbarVisible) {
            Toolbar(
                state = state,
                onBack = { if (state.dirty) confirmLeave = true else onClose() },
                onHide = { toolbarVisible = false },
                onSave = ::save,
                onEditPhase = { editingPhase = it },
                onAdd = { state.add(it) },
                onPickBackground = { pickBackground.launch(arrayOf("image/*")) },
                onClearBackground = {
                    backgroundFile(context, profileId).delete()
                    bgVersion++
                },
                hasBackground = background != null,
                onProfileSettings = { profileSettings = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        } else {
            SmallFloatingActionButton(
                onClick = { toolbarVisible = true },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            ) { Icon(Icons.Filled.Visibility, contentDescription = "Show toolbar") }
        }

        val selected = state.selected
        if (selected != null) {
            val onLeft = selected.x > 0.55f
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(340.dp),
            ) {
                ControlPanel(
                    control = selected,
                    phases = state.profile.phases,
                    learning = learning,
                    onChange = { updated -> state.updateControl(selected.id) { updated } },
                    onLearnButton = { learnButtons(selected.id) },
                    onLearnStick = { learnStick(selected.id) },
                    onDuplicate = {
                        state.add(duplicate(selected))
                    },
                    onDelete = { state.remove(selected.id) },
                    onClose = { state.selectedId = null },
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save this layout before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        val clean = state.profile.sanitized()
                        graph.db.profiles().upsert(ProfileEntity.of(clean))
                        state.markSaved(clean)
                        onClose()
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmLeave = false
                        onClose()
                    }) { Text("Discard") }
                    TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                }
            },
        )
    }

    editingPhase?.let { index ->
        var name by remember(index) { mutableStateOf(state.profile.phases.getOrNull(index)?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingPhase = null },
            title = { Text("Phase") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    state.renamePhase(index, name.trim())
                    editingPhase = null
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (state.profile.phases.size > 1) {
                        TextButton(onClick = {
                            state.deletePhase(index)
                            editingPhase = null
                        }) { Text("Delete phase") }
                    }
                    TextButton(onClick = { editingPhase = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (profileSettings) {
        var name by remember { mutableStateOf(state.profile.name) }
        AlertDialog(
            onDismissRequest = { profileSettings = false },
            title = { Text("Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Grab controller")
                            Text("The game won't see the pad's own input", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.profile.grab, onCheckedChange = { on -> state.update { it.copy(grab = on) } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rumble on phase switch", modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.profile.rumbleOnPhaseSwitch,
                            onCheckedChange = { on -> state.update { it.copy(rumbleOnPhaseSwitch = on) } },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && name.trim() != state.profile.name) state.update { it.copy(name = name.trim()) }
                    profileSettings = false
                }) { Text("Done") }
            },
        )
    }
}

private fun duplicate(c: Control): Control {
    val nx = (c.x + 0.04f).coerceAtMost(1f)
    val ny = (c.y + 0.04f).coerceAtMost(1f)
    return when (c) {
        is ButtonControl -> c.copy(id = newId(), x = nx, y = ny)
        is JoystickControl -> c.copy(id = newId(), x = nx, y = ny)
        is LookControl -> c.copy(id = newId(), x = nx, y = ny)
        is PhaseSwitchControl -> c.copy(id = newId(), x = nx, y = ny)
    }
}

@Composable
private fun Toolbar(
    state: EditorState,
    onBack: () -> Unit,
    onHide: () -> Unit,
    onSave: () -> Unit,
    onEditPhase: (Int) -> Unit,
    onAdd: (Control) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    hasBackground: Boolean,
    onProfileSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addMenu by remember { mutableStateOf(false) }
    var bgMenu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        // A translucent colour doesn't match the scheme, so contentColorFor() can't pick onSurface.
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                state.profile.name + if (state.dirty) " •" else "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(state.profile.phases) { i, p ->
                    FilterChip(
                        selected = i == state.phase,
                        onClick = {
                            if (i == state.phase) onEditPhase(i) else {
                                state.phase = i
                                state.selectedId = null
                            }
                        },
                        label = { Text(p.name) },
                        trailingIcon = if (i == state.phase) {
                            { Icon(Icons.Filled.Edit, contentDescription = "Edit phase", modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
            IconButton(onClick = { state.addPhase() }) { Icon(Icons.Filled.Add, contentDescription = "Add phase") }
            Box {
                IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.AddCircle, contentDescription = "Add control") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Button") }, onClick = {
                        addMenu = false
                        onAdd(ButtonControl(newId(), 0.5f, 0.5f, listOf(Button.A), ButtonMode.HOLD))
                    })
                    DropdownMenuItem(text = { Text("Joystick (movement)") }, onClick = {
                        addMenu = false
                        onAdd(JoystickControl(newId(), 0.18f, 0.7f, Stick.LEFT))
                    })
                    DropdownMenuItem(text = { Text("Look / aim") }, onClick = {
                        addMenu = false
                        onAdd(LookControl(newId(), 0.7f, 0.45f, Stick.RIGHT))
                    })
                    DropdownMenuItem(text = { Text("Phase switch") }, onClick = {
                        addMenu = false
                        onAdd(PhaseSwitchControl(newId(), 0.5f, 0.1f, listOf(Button.SELECT), PhaseAction.NEXT))
                    })
                }
            }
            Box {
                IconButton(onClick = { bgMenu = true }) { Icon(Icons.Filled.Image, contentDescription = "Background") }
                DropdownMenu(expanded = bgMenu, onDismissRequest = { bgMenu = false }) {
                    DropdownMenuItem(text = { Text("Choose screenshot…") }, onClick = {
                        bgMenu = false
                        onPickBackground()
                    })
                    if (hasBackground) {
                        DropdownMenuItem(text = { Text("Remove screenshot") }, onClick = {
                            bgMenu = false
                            onClearBackground()
                        })
                    }
                }
            }
            IconButton(onClick = onProfileSettings) { Icon(Icons.Filled.Settings, contentDescription = "Profile settings") }
            IconButton(onClick = onHide) { Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide toolbar") }
            IconButton(onClick = onSave, enabled = state.dirty) { Icon(Icons.Filled.Save, contentDescription = "Save") }
        }
    }
}

/** Radius used for hit testing and for the drawn handle, in pixels. */
private fun handleRadius(c: Control, shortSide: Float, minPx: Float): Float = max(
    minPx,
    when (c) {
        is ButtonControl -> c.size * shortSide
        is PhaseSwitchControl -> c.size * shortSide
        is JoystickControl -> c.radius * shortSide * 0.35f
        is LookControl -> c.radius * shortSide * 0.2f
    },
)

@Composable
private fun ControlCanvas(state: EditorState, showGrid: Boolean) {
    val textMeasurer = rememberTextMeasurer()
    val controls by rememberUpdatedState(state.controls)
    val primary = MaterialTheme.colorScheme.primary
    val highlight = Color(0xFFFFC107)
    val minHandle = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }

    fun hitTest(pos: Offset, width: Float, height: Float): Control? {
        val short = min(width, height)
        return controls
            .map { c -> c to hypot(pos.x - c.x * width, pos.y - c.y * height) }
            .filter { (c, d) -> d <= handleRadius(c, short, minHandle) }
            .minByOrNull { it.second }
            ?.first
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    state.selectedId = hitTest(pos, size.width.toFloat(), size.height.toFloat())?.id
                }
            }
            .pointerInput(Unit) {
                var dragging: String? = null
                detectDragGestures(
                    onDragStart = { pos ->
                        dragging = hitTest(pos, size.width.toFloat(), size.height.toFloat())?.id
                        dragging?.let { state.selectedId = it }
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, amount ->
                        val id = dragging ?: return@detectDragGestures
                        change.consume()
                        val dx = amount.x / size.width
                        val dy = amount.y / size.height
                        state.updateControl(id) { c ->
                            c.at((c.x + dx).coerceIn(0f, 1f), (c.y + dy).coerceIn(0f, 1f))
                        }
                    },
                )
            },
    ) {
        if (showGrid) drawGrid()
        val short = min(size.width, size.height)
        controls.forEach { c ->
            drawControl(c, short, c.id == state.selectedId, primary, highlight, textMeasurer, minHandle)
        }
    }
}

private fun DrawScope.drawGrid() {
    drawRect(Color(0xFF15201C))
    val step = size.height / 8
    var x = 0f
    while (x < size.width) {
        drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height))
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(size.width, y))
        y += step
    }
}

private fun DrawScope.label(textMeasurer: TextMeasurer, text: String, center: Offset, sizeSp: Float = 13f) {
    val layout = textMeasurer.measure(
        text,
        TextStyle(color = Color.White, fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
    )
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

private fun DrawScope.drawControl(
    c: Control,
    short: Float,
    selected: Boolean,
    primary: Color,
    highlight: Color,
    textMeasurer: TextMeasurer,
    minHandle: Float,
) {
    val center = Offset(c.x * size.width, c.y * size.height)
    val edge = if (selected) highlight else Color.White.copy(alpha = 0.85f)
    val strokeWidth = if (selected) 4f else 2.5f
    when (c) {
        is ButtonControl -> {
            val r = max(c.size * short, minHandle * 0.8f)
            drawCircle(primary.copy(alpha = 0.45f), r, center)
            drawCircle(edge, r, center, style = Stroke(strokeWidth))
            val name = c.label.ifBlank { c.buttons.joinToString("+") { it.label }.ifBlank { "?" } }
            val text = if (c.mode == ButtonMode.HOLD) name else "$name\n${c.mode.label.lowercase()}"
            label(textMeasurer, text, center)
        }
        is JoystickControl -> {
            val r = c.radius * short
            drawCircle(primary.copy(alpha = 0.18f), r, center)
            drawCircle(edge, r, center, style = Stroke(strokeWidth))
            drawCircle(primary.copy(alpha = 0.6f), max(r * 0.35f, minHandle), center)
            val name = when (c.stick) {
                Stick.LEFT -> "LS"
                Stick.RIGHT -> "RS"
                Stick.DPAD -> "D-pad"
            }
            label(textMeasurer, if (c.eightWay) "$name\n8-way" else name, center)
        }
        is LookControl -> {
            val r = c.radius * short
            drawCircle(
                edge,
                r,
                center,
                style = Stroke(strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
            drawLine(edge, center - Offset(18f, 0f), center + Offset(18f, 0f), strokeWidth)
            drawLine(edge, center - Offset(0f, 18f), center + Offset(0f, 18f), strokeWidth)
            val name = when (c.stick) {
                Stick.LEFT -> "LS"
                Stick.RIGHT -> "RS"
                Stick.DPAD -> "D-pad"
            }
            label(textMeasurer, "Look $name", center + Offset(0f, 34f))
        }
        is PhaseSwitchControl -> {
            val r = max(c.size * short, minHandle * 0.8f)
            drawRoundRect(
                Color(0xCC5E35B1),
                topLeft = center - Offset(r * 1.4f, r),
                size = Size(r * 2.8f, r * 2f),
                cornerRadius = CornerRadius(r * 0.4f),
            )
            drawRoundRect(
                edge,
                topLeft = center - Offset(r * 1.4f, r),
                size = Size(r * 2.8f, r * 2f),
                cornerRadius = CornerRadius(r * 0.4f),
                style = Stroke(strokeWidth),
            )
            val action = when (c.action) {
                PhaseAction.NEXT -> "next"
                PhaseAction.PREVIOUS -> "prev"
                PhaseAction.GOTO -> "→ ${c.target + 1}"
                PhaseAction.HOLD -> "hold ${c.target + 1}"
            }
            label(textMeasurer, "${c.buttons.joinToString("+") { it.label }}\n$action", center, 11f)
        }
    }
}

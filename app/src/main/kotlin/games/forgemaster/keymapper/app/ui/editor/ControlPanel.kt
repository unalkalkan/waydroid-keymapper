package games.forgemaster.keymapper.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import games.forgemaster.keymapper.app.ui.LabeledSlider
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.ButtonControl
import games.forgemaster.keymapper.core.model.ButtonMode
import games.forgemaster.keymapper.core.model.Control
import games.forgemaster.keymapper.core.model.JoystickControl
import games.forgemaster.keymapper.core.model.LookControl
import games.forgemaster.keymapper.core.model.Phase
import games.forgemaster.keymapper.core.model.PhaseAction
import games.forgemaster.keymapper.core.model.PhaseSwitchControl
import games.forgemaster.keymapper.core.model.Stick
import kotlin.math.roundToInt

fun Control.kindLabel(): String = when (this) {
    is ButtonControl -> "Button"
    is JoystickControl -> "Joystick"
    is LookControl -> "Look / aim"
    is PhaseSwitchControl -> "Phase switch"
}

/** Property sheet for the selected control. */
@Composable
fun ControlPanel(
    control: Control,
    phases: List<Phase>,
    learning: String?,
    onChange: (Control) -> Unit,
    onLearnButton: () -> Unit,
    onLearnStick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(control.kindLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        if (learning != null) {
            Text(learning, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        when (control) {
            is ButtonControl -> ButtonFields(control, onChange, onLearnButton)
            is JoystickControl -> JoystickFields(control, onChange, onLearnStick)
            is LookControl -> LookFields(control, onChange, onLearnStick)
            is PhaseSwitchControl -> PhaseFields(control, phases, onChange, onLearnButton)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonPicker(selected: List<Button>, onChange: (List<Button>) -> Unit, onLearn: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Buttons", style = MaterialTheme.typography.titleSmall)
            Text(
                if (selected.size > 1) "Chord: hold all of them" else "Pick up to 3 for a chord",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FilledTonalButton(onClick = onLearn) { Text("Learn") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button.entries.forEach { b ->
            val on = b in selected
            FilterChip(
                selected = on,
                onClick = {
                    when {
                        on -> onChange(selected - b)
                        selected.size < 3 -> onChange(selected + b)
                    }
                },
                label = { Text(b.label) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickPicker(stick: Stick, onChange: (Stick) -> Unit, onLearn: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Input", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onLearn) { Text("Learn") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Stick.entries.forEach { s ->
            FilterChip(selected = s == stick, onClick = { onChange(s) }, label = { Text(s.label) })
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonFields(c: ButtonControl, onChange: (Control) -> Unit, onLearn: () -> Unit) {
    ButtonPicker(c.buttons, { onChange(c.copy(buttons = it)) }, onLearn)
    HorizontalDivider()
    Text("Mode", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ButtonMode.entries.forEach { m ->
            FilterChip(selected = c.mode == m, onClick = { onChange(c.copy(mode = m)) }, label = { Text(m.label) })
        }
    }
    if (c.mode == ButtonMode.RAPID) {
        LabeledSlider("Rate", c.rapidHz, ButtonControl.MIN_RAPID_HZ..ButtonControl.MAX_RAPID_HZ, { "${it.roundToInt()} taps/s" }) {
            onChange(c.copy(rapidHz = it.roundToInt().toFloat()))
        }
    }
    if (c.mode == ButtonMode.HOLD) {
        Text("Aim while held", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = c.aim == null, onClick = { onChange(c.copy(aim = null)) }, label = { Text("Off") })
            Stick.entries.forEach { s ->
                FilterChip(selected = c.aim == s, onClick = { onChange(c.copy(aim = s)) }, label = { Text(s.label) })
            }
        }
        if (c.aim != null) {
            LabeledSlider("Aim distance", c.aimRadius, 0.02f..0.5f, { "${(it * 100).roundToInt()}%" }) {
                onChange(c.copy(aimRadius = it, aimMin = c.aimMin.coerceAtMost(it)))
            }
            LabeledSlider("Aim start", c.aimMin, 0f..c.aimRadius, { "${(it * 100).roundToInt()}%" }) {
                onChange(c.copy(aimMin = it))
            }
        }
    }
    OutlinedTextField(
        value = c.label,
        onValueChange = { onChange(c.copy(label = it.take(12))) },
        label = { Text("Label (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    LabeledSlider("Size", c.size, 0.015f..0.2f, { "${(it * 100).roundToInt()}" }) { onChange(c.copy(size = it)) }
}

@Composable
private fun JoystickFields(c: JoystickControl, onChange: (Control) -> Unit, onLearn: () -> Unit) {
    StickPicker(c.stick, { onChange(c.copy(stick = it)) }, onLearn)
    LabeledSlider("Radius", c.radius, 0.02f..0.5f, { "${(it * 100).roundToInt()}%" }) { onChange(c.copy(radius = it)) }
    SwitchRow("8-way (digital movement)", c.eightWay) { onChange(c.copy(eightWay = it)) }
    if (c.stick != Stick.DPAD) {
        HorizontalDivider()
        ResponseEditor(c.response) { onChange(c.copy(response = it)) }
    }
}

@Composable
private fun LookFields(c: LookControl, onChange: (Control) -> Unit, onLearn: () -> Unit) {
    StickPicker(c.stick, { onChange(c.copy(stick = it)) }, onLearn)
    LabeledSlider("Drag area", c.radius, 0.02f..0.6f, { "${(it * 100).roundToInt()}%" }) { onChange(c.copy(radius = it)) }
    LabeledSlider("Sensitivity", c.sensitivity, 0.05f..5f, { "%.2f".format(it) }) { onChange(c.copy(sensitivity = it)) }
    SwitchRow("Invert vertical", c.invertY) { onChange(c.copy(invertY = it)) }
    if (c.stick != Stick.DPAD) {
        HorizontalDivider()
        ResponseEditor(c.response) { onChange(c.copy(response = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhaseFields(c: PhaseSwitchControl, phases: List<Phase>, onChange: (Control) -> Unit, onLearn: () -> Unit) {
    ButtonPicker(c.buttons, { onChange(c.copy(buttons = it)) }, onLearn)
    HorizontalDivider()
    Text("Action", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PhaseAction.entries.forEach { a ->
            FilterChip(selected = c.action == a, onClick = { onChange(c.copy(action = a)) }, label = { Text(a.label) })
        }
    }
    if (c.action == PhaseAction.GOTO || c.action == PhaseAction.HOLD) {
        Text("Target phase", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            phases.forEachIndexed { i, p ->
                FilterChip(selected = c.target == i, onClick = { onChange(c.copy(target = i)) }, label = { Text(p.name) })
            }
        }
    }
    LabeledSlider("Size", c.size, 0.015f..0.2f, { "${(it * 100).roundToInt()}" }) { onChange(c.copy(size = it)) }
}

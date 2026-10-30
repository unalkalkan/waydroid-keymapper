package games.forgemaster.keymapper.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import games.forgemaster.keymapper.core.model.Phase
import games.forgemaster.keymapper.core.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

fun newProfile(name: String) = Profile(id = newId(), name = name, phases = listOf(Phase("Default")))

val Profile.controlCount: Int get() = phases.sumOf { it.controls.size }

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("keymapper", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun appLabel(context: Context, pkg: String): String? = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrNull()

private val iconCache = LruCache<String, ImageBitmap>(64)

@Composable
fun rememberAppIcon(pkg: String): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState(iconCache.get(pkg), pkg) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()?.also { iconCache.put(pkg, it) }
        }
    }
    return icon
}

fun isInstalled(context: Context, pkg: String): Boolean = try {
    context.packageManager.getApplicationInfo(pkg, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A monospace command with a copy button. */
@Composable
fun CommandLine(command: String) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { copyToClipboard(context, command) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { "%.2f".format(it) },
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

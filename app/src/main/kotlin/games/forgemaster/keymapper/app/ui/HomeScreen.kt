package games.forgemaster.keymapper.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import games.forgemaster.keymapper.BuildConfig
import games.forgemaster.keymapper.app.KeymapperApp
import games.forgemaster.keymapper.app.OverlayService
import games.forgemaster.keymapper.app.RootActivator
import games.forgemaster.keymapper.app.TouchTestActivity
import games.forgemaster.keymapper.app.data.HelperRepository.Connection
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val graph = KeymapperApp.graph(context)
    val connection by graph.helper.connection.collectAsState()
    val status by graph.helper.status.collectAsState()
    val enabled by graph.settings.mappingEnabled.collectAsState()
    val forced by graph.settings.forcedProfileId.collectAsState()
    val hud by graph.settings.hudEnabled.collectAsState()
    val profiles by graph.db.profiles().observe().collectAsState(initial = emptyList())
    val pairing by graph.pairing.pairing.collectAsState()
    val scope = rememberCoroutineScope()

    var rootOutput by remember { mutableStateOf<String?>(null) }
    var rootRunning by remember { mutableStateOf(false) }
    var confirmRepair by remember { mutableStateOf(false) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Re-check the overlay permission when returning from system settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canDrawOverlays = Settings.canDrawOverlays(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun setHud(on: Boolean) {
        if (on) {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
                return
            }
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            graph.settings.setHudEnabled(true)
            OverlayService.start(context)
        } else {
            graph.settings.setHudEnabled(false)
            OverlayService.stop(context)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Keymapper", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Helper") {
            when (val c = connection) {
                is Connection.Online -> {
                    StatusLine(Color(0xFF2E9E6B), "Connected")
                    val who = when (c.hello.uid) {
                        0 -> "root"
                        2000 -> "shell"
                        else -> "uid ${c.hello.uid}"
                    }
                    Text("Helper ${c.hello.helperVersion} · running as $who · pid ${c.hello.pid}")
                    status?.let { Text("Touch output: ${it.backend}") }
                    if (c.hello.helperVersion != BuildConfig.VERSION_NAME) {
                        Text(
                            "The helper comes from a different build (app ${BuildConfig.VERSION_NAME}). Re-run deploy.sh.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is Connection.Offline -> {
                    StatusLine(MaterialTheme.colorScheme.error, "Not connected")
                    Text(c.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (connection is Connection.Offline) {
            SectionCard("Start the helper") {
                Text("From your PC, in the keymapper repo (installs the app, pairs, pushes and starts the helper):")
                CommandLine("./scripts/deploy.sh")
                Text("Later restarts only need:")
                CommandLine("adb shell sh /data/local/tmp/keymapper/ctl.sh start")
                if (RootActivator.hasSu()) {
                    Button(
                        enabled = !rootRunning,
                        onClick = {
                            rootRunning = true
                            scope.launch {
                                val result = RootActivator.activate(context, graph.pairing.get())
                                rootRunning = false
                                rootOutput = result.output
                                graph.helper.reconnect()
                            }
                        },
                    ) { Text(if (rootRunning) "Starting…" else "Start with root") }
                } else {
                    Text("No su found, so root start isn't available.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionCard("Mapping") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mapping enabled", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { graph.settings.setMappingEnabled(it) })
            }
            var menu by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Profile", modifier = Modifier.weight(1f))
                Box {
                    val name = forced?.let { id -> profiles.firstOrNull { it.id == id }?.name } ?: "Auto (by game)"
                    TextButton(onClick = { menu = true }) { Text(name) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Auto (by game)") }, onClick = {
                            graph.settings.setForcedProfileId(null)
                            menu = false
                        })
                        profiles.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                graph.settings.setForcedProfileId(p.id)
                                menu = false
                            })
                        }
                    }
                }
            }
            val s = status
            if (s != null) {
                val fg = s.foreground
                Text("Foreground: " + (fg?.let { appLabel(context, it)?.let { l -> "$l ($it)" } ?: it } ?: "unknown"))
                Text(
                    if (s.profileName != null) {
                        "Active: ${s.profileName} · ${s.phaseName ?: "phase ${s.phase + 1}"}" +
                            (if (s.phaseCount > 1) " (${s.phase + 1}/${s.phaseCount})" else "") +
                            (if (s.mapping) "" else " · not mapping")
                    } else {
                        "Active: none"
                    },
                )
                if (s.phaseCount > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { graph.helper.nextPhase(-1) }) { Text("Previous phase") }
                        OutlinedButton(onClick = { graph.helper.nextPhase(1) }) { Text("Next phase") }
                    }
                }
                Text(
                    if (s.controllers.isEmpty()) "No controllers connected"
                    else "Controllers: " + s.controllers.joinToString { it.name },
                )
            }
        }

        SectionCard("HUD overlay") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show HUD over games")
                    Text(
                        "Tap the pill to switch phase, long-press to pause.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = hud && canDrawOverlays, onCheckedChange = { setHud(it) })
            }
            if (!canDrawOverlays) {
                Text("Needs the “display over other apps” permission.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Tools") {
            OutlinedButton(onClick = { context.startActivity(Intent(context, TouchTestActivity::class.java)) }) {
                Text("Touch test")
            }
            Text("Pairing", style = MaterialTheme.typography.titleSmall)
            Text(
                "Socket ${pairing?.socketName ?: graph.pairing.get().socketName}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { confirmRepair = true }) { Text("New pairing key") }
            Text("App ${BuildConfig.VERSION_NAME} · no network access", style = MaterialTheme.typography.bodySmall)
        }
    }

    rootOutput?.let { out ->
        AlertDialog(
            onDismissRequest = { rootOutput = null },
            confirmButton = { TextButton(onClick = { rootOutput = null }) { Text("OK") } },
            title = { Text("Root start") },
            text = { Text(out, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
        )
    }

    if (confirmRepair) {
        AlertDialog(
            onDismissRequest = { confirmRepair = false },
            confirmButton = {
                TextButton(onClick = {
                    graph.pairing.regenerate()
                    graph.helper.reconnect()
                    confirmRepair = false
                    scope.launch { snackbar.showSnackbar("New key made. Re-run deploy.sh (or Start with root).") }
                }) { Text("Make new key") }
            },
            dismissButton = { TextButton(onClick = { confirmRepair = false }) { Text("Cancel") } },
            title = { Text("New pairing key?") },
            text = { Text("The running helper keeps the old key, so the app disconnects until you re-run deploy.sh.") },
        )
    }
}

@Composable
private fun StatusLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

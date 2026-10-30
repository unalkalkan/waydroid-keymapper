package games.forgemaster.keymapper.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import games.forgemaster.keymapper.app.KeymapperApp
import games.forgemaster.keymapper.app.data.ProfileEntity
import games.forgemaster.keymapper.core.model.KmJson
import games.forgemaster.keymapper.core.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface NameDialog {
    data object Create : NameDialog
    data class Rename(val profile: Profile) : NameDialog
}

@Composable
fun ProfilesScreen(modifier: Modifier, snackbar: SnackbarHostState, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val graph = KeymapperApp.graph(context)
    val entities by graph.db.profiles().observe().collectAsState(initial = emptyList())
    val games by graph.db.games().observe().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    fun exportText(p: Profile) = KmJson.exportProfile(p, games.filter { it.profileId == p.id }.map { it.packageName })

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "Exported" else "Export failed")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    val export = KmJson.importProfile(text)
                    val existing = graph.db.profiles().get(export.profile.id)
                    val profile = if (existing != null) export.profile.copy(id = newId()) else export.profile
                    graph.db.profiles().upsert(ProfileEntity.of(profile))
                    // Re-attach to listed games that are in the library and have no profile yet.
                    export.games.forEach { pkg ->
                        val game = graph.db.games().get(pkg)
                        if (game != null && game.profileId == null) graph.db.games().assign(pkg, profile.id)
                    }
                    profile.name
                }
            }
            snackbar.showSnackbar(result.fold({ "Imported “$it”" }, { "Import failed: ${it.message}" }))
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Profiles", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Text(" Import")
                    }
                }
            }
            if (entities.isEmpty()) {
                item { Text("No profiles yet. Create one, or add a game and edit its layout.") }
            }
            items(entities, key = { it.id }) { entity ->
                val profile = entity.toProfile()
                if (profile == null) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${entity.name} (unreadable)", modifier = Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { graph.db.profiles().delete(entity.id) } }) { Text("Delete") }
                        }
                    }
                    return@items
                }
                val usedBy = games.filter { it.profileId == profile.id }
                ProfileRow(
                    profile = profile,
                    usedBy = usedBy.joinToString { it.label },
                    onEdit = { onEdit(profile.id) },
                    onRename = { nameDialog = NameDialog.Rename(profile) },
                    onDuplicate = {
                        scope.launch {
                            graph.db.profiles().upsert(ProfileEntity.of(profile.copy(id = newId(), name = "${profile.name} copy")))
                        }
                    },
                    onExport = {
                        pendingExport = exportText(profile)
                        exportLauncher.launch("${profile.name}.keymapper.json")
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, exportText(profile))
                        context.startActivity(Intent.createChooser(send, "Share profile"))
                    },
                    onDelete = { deleting = profile },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { nameDialog = NameDialog.Create },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New profile") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    nameDialog?.let { dialog ->
        var name by remember(dialog) { mutableStateOf(if (dialog is NameDialog.Rename) dialog.profile.name else "") }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (dialog is NameDialog.Rename) "Rename profile" else "New profile") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val n = name.trim()
                    nameDialog = null
                    scope.launch {
                        when (dialog) {
                            NameDialog.Create -> {
                                val p = newProfile(n)
                                graph.db.profiles().upsert(ProfileEntity.of(p))
                                onEdit(p.id)
                            }
                            is NameDialog.Rename -> graph.db.profiles().upsert(ProfileEntity.of(dialog.profile.copy(name = n)))
                        }
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("Games using it will have no profile.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        graph.db.games().clearProfile(p.id)
                        graph.db.profiles().delete(p.id)
                        if (graph.settings.forcedProfileId.value == p.id) graph.settings.setForcedProfileId(null)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    usedBy: String,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                val phases = profile.phases.size
                Text(
                    "${profile.controlCount} controls · $phases phase${if (phases == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (usedBy.isNotEmpty()) Text("Used by $usedBy", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onEdit) { Text("Edit") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(
                        "Rename" to onRename,
                        "Duplicate" to onDuplicate,
                        "Export to file" to onExport,
                        "Share" to onShare,
                        "Delete" to onDelete,
                    ).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            action()
                        })
                    }
                }
            }
        }
    }
}

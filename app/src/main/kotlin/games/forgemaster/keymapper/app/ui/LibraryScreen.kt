package games.forgemaster.keymapper.app.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import games.forgemaster.keymapper.app.KeymapperApp
import games.forgemaster.keymapper.app.data.GameEntity
import games.forgemaster.keymapper.app.data.ProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LaunchableApp(val pkg: String, val label: String)

@Composable
fun LibraryScreen(modifier: Modifier, snackbar: SnackbarHostState, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val graph = KeymapperApp.graph(context)
    val games by graph.db.games().observe().collectAsState(initial = emptyList())
    val profiles by graph.db.profiles().observe().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    fun editLayout(game: GameEntity) {
        scope.launch {
            val id = game.profileId?.takeIf { pid -> profiles.any { it.id == pid } } ?: run {
                val p = newProfile(game.label)
                graph.db.profiles().upsert(ProfileEntity.of(p))
                graph.db.games().assign(game.packageName, p.id)
                p.id
            }
            onEdit(id)
        }
    }

    Box(modifier.fillMaxSize()) {
        if (games.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No games yet", style = MaterialTheme.typography.titleMedium)
                Text("Add a game, then give it a profile.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(games, key = { it.packageName }) { game ->
                GameRow(
                    game = game,
                    profiles = profiles,
                    onAssign = { pid -> scope.launch { graph.db.games().assign(game.packageName, pid) } },
                    onLaunch = {
                        val intent = context.packageManager.getLaunchIntentForPackage(game.packageName)
                        if (intent == null) {
                            scope.launch { snackbar.showSnackbar("${game.label} can't be launched") }
                        } else {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    onEdit = { editLayout(game) },
                    onRemove = { scope.launch { graph.db.games().delete(game.packageName) } },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picking = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add game") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (picking) {
        AppPicker(
            exclude = games.map { it.packageName }.toSet() + context.packageName,
            onDismiss = { picking = false },
            onPick = { app ->
                picking = false
                scope.launch {
                    graph.db.games().upsert(GameEntity(app.pkg, app.label, null, System.currentTimeMillis()))
                }
            },
        )
    }
}

@Composable
private fun GameRow(
    game: GameEntity,
    profiles: List<ProfileEntity>,
    onAssign: (String?) -> Unit,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(game.packageName)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    val current = profiles.firstOrNull { it.id == game.profileId }?.name ?: "No profile"
                    TextButton(onClick = { menu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Profile: $current")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("No profile") }, onClick = {
                            onAssign(null)
                            menu = false
                        })
                        profiles.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                onAssign(p.id)
                                menu = false
                            })
                        }
                    }
                }
            }
            IconButton(onClick = onLaunch) { Icon(Icons.Filled.PlayArrow, contentDescription = "Launch") }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit layout") }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val icon = rememberAppIcon(pkg)
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun AppPicker(exclude: Set<String>, onDismiss: () -> Unit, onPick: (LaunchableApp) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<LaunchableApp>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Add game") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    Text("Loading…", modifier = Modifier.padding(16.dp))
                } else {
                    val shown = list.filter {
                        it.pkg !in exclude && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.pkg }) { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.pkg)
                                Spacer(Modifier.size(12.dp))
                                Column {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

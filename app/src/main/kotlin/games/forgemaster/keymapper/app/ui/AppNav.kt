package games.forgemaster.keymapper.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import games.forgemaster.keymapper.app.KeymapperApp
import games.forgemaster.keymapper.app.ui.editor.EditorScreen

sealed interface Dest {
    data object Home : Dest
    data object Library : Dest
    data object Profiles : Dest
    data object Controllers : Dest
    data class Editor(val profileId: String) : Dest
}

class NavState {
    val stack = mutableStateListOf<Dest>(Dest.Home)
    val current: Dest get() = stack.last()

    fun go(dest: Dest) {
        stack.add(dest)
    }

    fun tab(dest: Dest) {
        stack.clear()
        stack.add(dest)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

private data class Tab(val dest: Dest, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Dest.Home, "Status", Icons.Filled.Home),
    Tab(Dest.Library, "Games", Icons.Filled.VideogameAsset),
    Tab(Dest.Profiles, "Profiles", Icons.Filled.Layers),
    Tab(Dest.Controllers, "Controllers", Icons.Filled.SportsEsports),
)

@Composable
fun AppRoot() {
    val nav = remember { NavState() }
    val graph = KeymapperApp.graph(LocalContext.current)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        graph.helper.errors.collect { snackbar.showSnackbar("Helper: $it") }
    }

    when (val dest = nav.current) {
        is Dest.Editor -> EditorScreen(profileId = dest.profileId, onClose = { nav.back() })
        else -> {
            BackHandler(enabled = nav.stack.size > 1) { nav.back() }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = nav.stack.firstOrNull() == tab.dest,
                                onClick = { nav.tab(tab.dest) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                val modifier = Modifier.padding(padding)
                when (dest) {
                    Dest.Home -> HomeScreen(modifier, snackbar)
                    Dest.Library -> LibraryScreen(modifier, snackbar, onEdit = { nav.go(Dest.Editor(it)) })
                    Dest.Profiles -> ProfilesScreen(modifier, snackbar, onEdit = { nav.go(Dest.Editor(it)) })
                    Dest.Controllers -> ControllersScreen(modifier, snackbar)
                    is Dest.Editor -> Unit
                }
            }
        }
    }
}

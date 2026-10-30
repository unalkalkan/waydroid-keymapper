package games.forgemaster.keymapper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import games.forgemaster.keymapper.app.ui.AppRoot
import games.forgemaster.keymapper.app.ui.KeymapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeymapperTheme {
                AppRoot()
            }
        }
    }
}

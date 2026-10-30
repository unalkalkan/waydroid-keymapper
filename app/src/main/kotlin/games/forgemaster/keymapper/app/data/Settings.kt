package games.forgemaster.keymapper.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small user settings, mirrored into StateFlows. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _mappingEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val mappingEnabled: StateFlow<Boolean> = _mappingEnabled

    private val _forcedProfileId = MutableStateFlow(prefs.getString(KEY_FORCED, null))
    /** null = pick the profile by the foreground game. */
    val forcedProfileId: StateFlow<String?> = _forcedProfileId

    private val _hudEnabled = MutableStateFlow(prefs.getBoolean(KEY_HUD, false))
    val hudEnabled: StateFlow<Boolean> = _hudEnabled

    fun setMappingEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _mappingEnabled.value = on
    }

    fun setForcedProfileId(id: String?) {
        prefs.edit().putString(KEY_FORCED, id).apply()
        _forcedProfileId.value = id
    }

    fun setHudEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_HUD, on).apply()
        _hudEnabled.value = on
    }

    var hudX: Int
        get() = prefs.getInt(KEY_HUD_X, 24)
        set(v) = prefs.edit().putInt(KEY_HUD_X, v).apply()

    var hudY: Int
        get() = prefs.getInt(KEY_HUD_Y, 120)
        set(v) = prefs.edit().putInt(KEY_HUD_Y, v).apply()

    private companion object {
        const val KEY_ENABLED = "mapping_enabled"
        const val KEY_FORCED = "forced_profile"
        const val KEY_HUD = "hud_enabled"
        const val KEY_HUD_X = "hud_x"
        const val KEY_HUD_Y = "hud_y"
    }
}

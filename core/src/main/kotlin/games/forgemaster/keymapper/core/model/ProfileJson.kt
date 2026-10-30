package games.forgemaster.keymapper.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** File format for exported profiles. */
@Serializable
data class ProfileExport(
    val format: String = FORMAT,
    val profile: Profile,
    /** Package names the profile was assigned to on the exporting device. */
    val games: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT = "keymapper-profile"
    }
}

object KmJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    private val pretty: Json = Json(json) { prettyPrint = true }

    fun encodeProfile(profile: Profile): String = json.encodeToString(Profile.serializer(), profile)

    fun decodeProfile(text: String): Profile = json.decodeFromString(Profile.serializer(), text).sanitized()

    fun exportProfile(profile: Profile, games: List<String>): String =
        pretty.encodeToString(ProfileExport.serializer(), ProfileExport(profile = profile.sanitized(), games = games))

    /** Accepts an export envelope or a bare profile object. */
    fun importProfile(text: String): ProfileExport {
        val element = json.parseToJsonElement(text)
        val obj = element as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalArgumentException("Not a JSON object")
        return if ("profile" in obj) {
            val export = json.decodeFromJsonElement(ProfileExport.serializer(), obj)
            require(export.format == ProfileExport.FORMAT) { "Unknown format '${export.format}'" }
            export.copy(profile = export.profile.sanitized())
        } else {
            ProfileExport(profile = json.decodeFromJsonElement(Profile.serializer(), obj).sanitized())
        }
    }
}

private fun Float.clamp(lo: Float, hi: Float) = if (isNaN()) lo else coerceIn(lo, hi)

private fun StickResponse.sanitized(): StickResponse {
    val dz = deadzone.clamp(0f, 0.9f)
    return copy(deadzone = dz, outer = outer.clamp(dz + 0.05f, 1f), curve = curve.clamp(0.2f, 5f))
}

private fun List<Button>.sanitizedChord() = distinct().take(3)

/** Clamps every value into its valid range and drops anything that can't be mapped. */
fun Profile.sanitized(): Profile {
    val cleanPhases = phases.take(Profile.MAX_PHASES).map { phase ->
        val seen = HashSet<String>()
        phase.copy(
            name = phase.name.take(40),
            controls = phase.controls.take(Profile.MAX_CONTROLS).mapNotNull { c ->
                if (!seen.add(c.id)) return@mapNotNull null
                val x = c.x.clamp(0f, 1f)
                val y = c.y.clamp(0f, 1f)
                when (c) {
                    is ButtonControl -> c.copy(
                        x = x, y = y, buttons = c.buttons.sanitizedChord(), label = c.label.take(12),
                        rapidHz = c.rapidHz.clamp(ButtonControl.MIN_RAPID_HZ, ButtonControl.MAX_RAPID_HZ),
                        size = c.size.clamp(0.015f, 0.2f), aimRadius = c.aimRadius.clamp(0.02f, 0.5f),
                    )
                    is JoystickControl -> c.copy(
                        x = x, y = y, radius = c.radius.clamp(0.02f, 0.5f), response = c.response.sanitized(),
                    )
                    is LookControl -> c.copy(
                        x = x, y = y, radius = c.radius.clamp(0.02f, 0.6f),
                        sensitivity = c.sensitivity.clamp(0.05f, 8f), response = c.response.sanitized(),
                    )
                    is PhaseSwitchControl -> c.copy(
                        x = x, y = y, buttons = c.buttons.sanitizedChord(), size = c.size.clamp(0.015f, 0.2f),
                    )
                }
            },
        )
    }.ifEmpty { listOf(Phase("Default")) }
    val phaseCount = cleanPhases.size
    val fixedPhases = cleanPhases.map { phase ->
        phase.copy(controls = phase.controls.map { c ->
            if (c is PhaseSwitchControl) c.copy(target = c.target.coerceIn(0, phaseCount - 1)) else c
        })
    }
    return copy(name = name.take(60).ifBlank { "Profile" }, phases = fixedPhases, schema = Profile.SCHEMA)
}


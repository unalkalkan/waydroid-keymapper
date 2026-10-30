package games.forgemaster.keymapper.core.protocol

import games.forgemaster.keymapper.core.engine.Screen
import games.forgemaster.keymapper.core.engine.TouchAction
import games.forgemaster.keymapper.core.input.AbsRange
import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.ControllerConfig
import games.forgemaster.keymapper.core.model.KmJson
import games.forgemaster.keymapper.core.model.Profile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1

/** helper -> client, first frame on every connection. */
@Serializable
data class Hello(
    val proto: Int,
    val helperVersion: String,
    val nonce: String,
    val pid: Int,
    val uid: Int,
)

/** client -> helper. [client] is "app" or "cli". */
@Serializable
data class Auth(val nonce: String, val mac: String, val client: String, val proto: Int = PROTOCOL_VERSION)

/** helper -> client: proves the helper knows the key too. */
@Serializable
data class AuthOk(val mac: String)

/**
 * All profiles plus the game assignments. The helper switches by foreground package unless
 * [forcedProfileId] is set. Nothing is persisted by the helper.
 */
@Serializable
data class SetProfiles(
    val profiles: List<Profile>,
    /** package name -> profile id */
    val assignments: Map<String, String> = emptyMap(),
    val forcedProfileId: String? = null,
)

@Serializable
data class SetPhase(val index: Int? = null, val delta: Int = 0)

@Serializable
data class SetEnabled(val enabled: Boolean)

/** Overrides the display size the helper detected; null returns to auto-detection. */
@Serializable
data class SetScreen(val screen: Screen? = null)

/** Manual finger for tests: logical pixels, pointer in 0..5. */
@Serializable
data class TouchCmd(val pointer: Int, val action: TouchAction, val x: Float, val y: Float)

/** Synthetic controller input, fed to the engine as if it came from a pad. */
@Serializable
data class KeyCmd(val button: Button? = null, val axis: Axis? = null, val value: Float = 1f)

/** [selected] holds controller keys ("vvvv:pppp"); empty means every connected gamepad. */
@Serializable
data class SetControllers(val selected: List<String> = emptyList(), val configs: List<ControllerConfig> = emptyList())

/** Raw/logical event streaming for learn mode and calibration. Grabs the pads while on. */
@Serializable
data class SetMonitor(val enabled: Boolean)

@Serializable
data class ControllerInfo(
    val id: Int,
    val path: String,
    val name: String,
    val vendor: Int,
    val product: Int,
    val key: String,
    val active: Boolean,
    val grabbed: Boolean,
    val hasRumble: Boolean,
    val axes: Map<Int, AbsRange> = emptyMap(),
)

@Serializable
data class Status(
    val helperVersion: String,
    val uid: Int,
    val backend: String,
    val enabled: Boolean,
    val mapping: Boolean,
    val monitor: Boolean,
    val profileId: String? = null,
    val profileName: String? = null,
    val phase: Int = 0,
    val phaseCount: Int = 0,
    val phaseName: String? = null,
    val foreground: String? = null,
    val screen: Screen? = null,
    val controllers: List<ControllerInfo> = emptyList(),
    val clients: Int = 0,
)

@Serializable
data class RawEvent(val device: Int, val type: Int, val code: Int, val value: Int)

@Serializable
data class LogicalInput(val device: Int, val button: Button? = null, val axis: Axis? = null, val value: Float)

@Serializable
data class ErrorMsg(val message: String)

@Serializable
data class Shutdown(val reason: String = "")

/** JSON payload helpers shared by both ends. */
object Wire {
    val json = KmJson.json

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    fun <T> decode(serializer: KSerializer<T>, frame: Frame): T =
        json.decodeFromString(serializer, frame.text)
}

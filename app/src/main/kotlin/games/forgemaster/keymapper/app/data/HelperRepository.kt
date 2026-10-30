package games.forgemaster.keymapper.app.data

import games.forgemaster.keymapper.core.model.Axis
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.Stick
import games.forgemaster.keymapper.core.protocol.Hello
import games.forgemaster.keymapper.core.protocol.LogicalInput
import games.forgemaster.keymapper.core.protocol.RawEvent
import games.forgemaster.keymapper.core.protocol.SetControllers
import games.forgemaster.keymapper.core.protocol.SetProfiles
import games.forgemaster.keymapper.core.protocol.Status
import games.forgemaster.keymapper.helper.client.HelperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * App-wide view of the helper: connection state, the latest [Status], and the event streams used by
 * learn mode and calibration. Pushes the full desired state (profiles, assignments, controllers,
 * enabled) after every connect and whenever it changes.
 */
class HelperRepository(
    private val db: KmDatabase,
    private val settings: Settings,
    private val pairingStore: PairingStore,
    private val scope: CoroutineScope,
) {
    sealed interface Connection {
        data class Offline(val reason: String) : Connection
        data class Online(val hello: Hello) : Connection
    }

    private val _connection = MutableStateFlow<Connection>(Connection.Offline("Connecting…"))
    val connection: StateFlow<Connection> = _connection

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    private val _raw = MutableSharedFlow<RawEvent>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val rawEvents: SharedFlow<RawEvent> = _raw

    private val _logical = MutableSharedFlow<LogicalInput>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val logicalInputs: SharedFlow<LogicalInput> = _logical

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = _errors

    @Volatile private var profilesMsg = SetProfiles(emptyList())
    @Volatile private var controllersMsg = SetControllers()
    private val monitorUsers = AtomicInteger(0)
    private val knownControllers = Collections.synchronizedSet(HashSet<String>())

    /** Every send goes through one thread so messages keep their order and never block the UI. */
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "km-helper-send").apply { isDaemon = true } }

    private val client = HelperClient("app", { pairingStore.get() }, object : HelperClient.Listener {
        override fun onConnected(hello: Hello) {
            _connection.value = Connection.Online(hello)
            sender.execute { pushAll() }
        }

        override fun onDisconnected(reason: String) {
            _connection.value = Connection.Offline(reason)
            _status.value = null
        }

        override fun onStatus(status: Status) {
            _status.value = status
            rememberControllers(status)
        }

        override fun onRawEvent(event: RawEvent) {
            _raw.tryEmit(event)
        }

        override fun onLogicalInput(input: LogicalInput) {
            _logical.tryEmit(input)
        }

        override fun onHelperError(message: String) {
            _errors.tryEmit(message)
        }
    })

    val isConnected: Boolean get() = client.isConnected

    fun start() {
        client.start()
        scope.launch {
            combine(db.profiles().observe(), db.games().observe(), settings.forcedProfileId) { profiles, games, forced ->
                val decoded = profiles.mapNotNull { it.toProfile() }
                val ids = decoded.map { it.id }.toSet()
                SetProfiles(
                    profiles = decoded,
                    assignments = games.mapNotNull { g -> g.profileId?.takeIf { it in ids }?.let { g.packageName to it } }.toMap(),
                    forcedProfileId = forced?.takeIf { it in ids },
                )
            }.collect { msg ->
                profilesMsg = msg
                sender.execute { client.setProfiles(msg) }
            }
        }
        scope.launch {
            db.controllers().observe().map { list ->
                list.forEach { knownControllers.add(it.key) }
                SetControllers(list.filter { it.selected }.map { it.key }, list.map { it.config() })
            }.collect { msg ->
                controllersMsg = msg
                sender.execute { client.setControllers(msg) }
            }
        }
        scope.launch {
            settings.mappingEnabled.collect { on -> sender.execute { client.setEnabled(on) } }
        }
    }

    private fun pushAll() {
        client.setControllers(controllersMsg)
        client.setProfiles(profilesMsg)
        client.setEnabled(settings.mappingEnabled.value)
        if (monitorUsers.get() > 0) client.setMonitor(true)
    }

    private fun rememberControllers(status: Status) {
        val fresh = status.controllers.filter { knownControllers.add(it.key) }
        if (fresh.isEmpty()) return
        scope.launch {
            fresh.forEach { db.controllers().insertIfAbsent(ControllerEntity.new(it.vendor, it.product, it.name)) }
        }
    }

    /** Drop and redo the connection, e.g. after the pairing key changed. */
    fun reconnect() = client.reconnect()

    fun nextPhase(delta: Int = 1) = sender.execute { client.setPhase(delta = delta) }

    fun selectPhase(index: Int) = sender.execute { client.setPhase(index = index) }

    fun shutdownHelper() = sender.execute { client.shutdown("app") }

    /** Raw/logical event streaming (also makes the helper grab pads). Reference counted. */
    fun acquireMonitor() {
        if (monitorUsers.incrementAndGet() == 1) sender.execute { client.setMonitor(true) }
    }

    fun releaseMonitor() {
        if (monitorUsers.decrementAndGet() <= 0) {
            monitorUsers.set(0)
            sender.execute { client.setMonitor(false) }
        }
    }

    /** Waits for the next button press on any controller. Null on timeout or when offline. */
    suspend fun learnButton(timeoutMs: Long = 10_000): Button? {
        if (!client.isConnected) return null
        acquireMonitor()
        try {
            return withTimeoutOrNull(timeoutMs) {
                logicalInputs.mapNotNull { e -> e.button?.takeIf { e.value > 0.5f } }.first()
            }
        } finally {
            releaseMonitor()
        }
    }

    /** Waits for a stick (or the D-pad) to be pushed far enough. Null on timeout or when offline. */
    suspend fun learnStick(timeoutMs: Long = 10_000): Stick? {
        if (!client.isConnected) return null
        acquireMonitor()
        try {
            return withTimeoutOrNull(timeoutMs) {
                logicalInputs.mapNotNull { e ->
                    val axis = e.axis
                    val button = e.button
                    when {
                        axis != null && abs(e.value) > 0.6f && (axis == Axis.LX || axis == Axis.LY) -> Stick.LEFT
                        axis != null && abs(e.value) > 0.6f && (axis == Axis.RX || axis == Axis.RY) -> Stick.RIGHT
                        button != null && e.value > 0.5f && button.name.startsWith("DPAD_") -> Stick.DPAD
                        else -> null
                    }
                }.first()
            }
        } finally {
            releaseMonitor()
        }
    }
}

package games.forgemaster.keymapper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import games.forgemaster.keymapper.R
import games.forgemaster.keymapper.app.data.HelperRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * The HUD: a small draggable pill over the game showing profile and phase. Tap cycles the phase,
 * long-press pauses/resumes mapping. Runs as a foreground service so it survives the app UI.
 */
class OverlayService : LifecycleService() {
    private lateinit var wm: WindowManager
    private var pill: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val graph by lazy { KeymapperApp.graph(this) }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        createChannel()
        startInForeground(buildNotification("Starting…", graph.settings.mappingEnabled.value))
        if (!Settings.canDrawOverlays(this)) {
            graph.settings.setHudEnabled(false)
            stopSelf()
            return
        }
        addPill()
        lifecycleScope.launch {
            combine(graph.helper.connection, graph.helper.status, graph.settings.mappingEnabled) { c, s, enabled ->
                Triple(c, s, enabled)
            }.collect { (c, s, enabled) ->
                val (text, state) = when {
                    c is HelperRepository.Connection.Offline -> "Keymapper · helper offline" to State.OFFLINE
                    s == null -> "Keymapper · connecting" to State.OFFLINE
                    !enabled -> "${s.profileName ?: "Keymapper"} · paused" to State.IDLE
                    s.profileName == null -> "Keymapper · idle" to State.IDLE
                    else -> {
                        val phase = s.phaseName ?: "Phase ${s.phase + 1}"
                        val counter = if (s.phaseCount > 1) " (${s.phase + 1}/${s.phaseCount})" else ""
                        "${s.profileName} · $phase$counter" to State.MAPPING
                    }
                }
                // Hide over our own screens (the editor canvas especially); the HUD is for games.
                render(text, state, visible = s?.foreground != packageName)
                notify(buildNotification(text, enabled))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_HIDE -> {
                graph.settings.setHudEnabled(false)
                stopSelf()
            }
            ACTION_TOGGLE -> graph.settings.setMappingEnabled(!graph.settings.mappingEnabled.value)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null
        super.onDestroy()
    }

    private enum class State { MAPPING, IDLE, OFFLINE }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun addPill() {
        val view = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val h = dp(12f).toInt()
            val v = dp(6f).toInt()
            setPadding(h, v, h, v)
            text = "Keymapper"
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(0xCC37474F.toInt())
            }
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = graph.settings.hudX
            y = graph.settings.hudY
        }
        view.setOnTouchListener(PillTouch(view))
        wm.addView(view, params)
        pill = view
    }

    private fun render(text: String, state: State, visible: Boolean) {
        val view = pill ?: return
        view.visibility = if (visible) View.VISIBLE else View.GONE
        view.text = text
        (view.background as? GradientDrawable)?.setColor(
            when (state) {
                State.MAPPING -> 0xCC1B7A55.toInt()
                State.IDLE -> 0xCC37474F.toInt()
                State.OFFLINE -> 0xCC9A3B3B.toInt()
            },
        )
    }

    private inner class PillTouch(private val view: View) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            graph.settings.setMappingEnabled(!graph.settings.mappingEnabled.value)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && hypot(dx, dy) > slop) {
                        dragging = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (dragging) {
                        graph.settings.hudX = params.x
                        graph.settings.hudY = params.y
                    } else if (!longPressed) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        graph.helper.nextPhase(1)
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            return true
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mapping HUD", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String, enabled: Boolean): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val hide = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_HIDE), flags)
        val toggle = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_keymapper)
            .setContentTitle("Keymapper")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Hide HUD", hide)
            .addAction(0, if (enabled) "Pause mapping" else "Resume mapping", toggle)
            .build()
    }

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    companion object {
        private const val CHANNEL = "hud"
        private const val NOTIFICATION_ID = 1
        const val ACTION_HIDE = "games.forgemaster.keymapper.action.HUD_HIDE"
        const val ACTION_TOGGLE = "games.forgemaster.keymapper.action.TOGGLE_MAPPING"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

package dev.gamecontrols.link.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import dev.gamecontrols.link.LinkApp
import dev.gamecontrols.link.R
import dev.gamecontrols.link.media.ScreenEncoder
import dev.gamecontrols.link.ui.MainActivity

class CaptureService : Service() {
    private val app get() = application as LinkApp
    private val main = Handler(Looper.getMainLooper())
    private var encoder: ScreenEncoder? = null
    private var sessionId = 0L
    private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_SESSION, 0) ?: 0
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_REVOKE) {
            if (app.host.matchesSession(id)) {
                if (intent.action == ACTION_STOP) app.host.stop("Stopped from the screen-sharing notification.")
                else app.host.revokeControl("Control revoked from the notification.")
            } else Log.w("GameLink", "Ignored stale notification action.")
            if (encoder == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null || intent.action != ACTION_START || encoder != null ||
            !app.host.attachCapture(id, this)) {
            Log.w("GameLink", "Rejected missing, repeated, or stale capture start.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        sessionId = id
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            else legacyConsent(intent)
        if (data == null || intent.getIntExtra(EXTRA_RESULT, 0) != android.app.Activity.RESULT_OK) {
            app.host.stop("No valid screen-sharing consent was supplied.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            ensureChannel(this)
            check(notificationsVisible(this)) { "Enable this app's capture notifications before sharing." }
            startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            encoder = ScreenEncoder(this, android.app.Activity.RESULT_OK, data,
                { packet -> app.host.sendCapture(sessionId, packet) },
                { geometry, controlGeometry -> main.post { app.host.captureConfigured(sessionId, geometry, controlGeometry) } },
                { main.post { app.host.captureRunning(sessionId) } },
                { reason -> main.post { app.host.stopIfSession(sessionId, reason) } },
                { error -> main.post {
                    encoder = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    app.host.captureClosed(sessionId, this, error)
                    stopSelf()
                } }).also { it.start() }
        } catch (error: IllegalStateException) {
            failStart(error)
        } catch (error: SecurityException) {
            failStart(error)
        } catch (error: IllegalArgumentException) {
            failStart(error)
        }
        return START_NOT_STICKY
    }

    private fun failStart(error: Exception) {
        Log.e("GameLink", "Capture service could not start", error)
        app.host.stopIfSession(sessionId, "Capture service could not start: ${error.javaClass.simpleName}.")
        stopOwnedCapture()
    }

    fun updateControlNotification(target: String?) {
        if (!closing && encoder != null) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(target))
        }
    }

    fun stopOwnedCapture() {
        if (closing) return
        closing = true
        val owned = encoder
        if (owned != null) owned.stop() else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        app.host.stopIfSession(sessionId, "Host task was removed.")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        app.host.stopIfSession(sessionId, "Host foreground service ended.")
        val owned = encoder
        if (owned != null) owned.stop() else app.host.captureClosed(sessionId, this, null)
        super.onDestroy()
    }

    private fun notification(target: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String) = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).setAction(name)
                .setData("gamecontrols-internal://session/$sessionId/$name".toUri())
                .putExtra(EXTRA_SESSION, sessionId), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(if (target == null) getString(R.string.notification_view_only)
                else getString(R.string.notification_control, target))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.stop_all), action(ACTION_STOP))
            .addAction(0, getString(R.string.revoke_control), action(ACTION_REVOKE))
            .build()
    }

    @Suppress("DEPRECATION")
    private fun legacyConsent(intent: Intent): Intent? = intent.getParcelableExtra(EXTRA_CONSENT)

    companion object {
        const val ACTION_START = "dev.gamecontrols.link.START_CAPTURE"
        const val ACTION_STOP = "dev.gamecontrols.link.STOP_CAPTURE"
        const val ACTION_REVOKE = "dev.gamecontrols.link.REVOKE_CONTROL"
        const val EXTRA_SESSION = "session"
        const val EXTRA_RESULT = "projection-result"
        const val EXTRA_CONSENT = "projection-consent"
        private const val CHANNEL = "game-controls-link-capture"
        private const val NOTIFICATION_ID = 7401

        fun ensureChannel(context: android.content.Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        }

        fun notificationsVisible(context: android.content.Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(CHANNEL) ?: return false
            return manager.areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}

package app.opencall.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCall Bridge foreground service (LITE flavor).
 *
 * Lightweight loop: heartbeat only. The server sees the device as an
 * internet-bridge participant; when a call's WebRTC room is opened by the
 * web app, the browser publishes its offer to `calls_events` and this phone
 * answers with mic audio via [WebRtcBridge].
 *
 * No telephony, no SMS — nothing for Play Protect's fraud scan to flag.
 */
class BridgeService : Service() {

    companion object {
        const val TAG = "OpenCall/Bridge"
        const val CHANNEL_ID = "opencall_bridge"
        const val NOTIF_ID = 43
        const val ACTION_START = "app.opencall.bridge.START"
        const val ACTION_STOP = "app.opencall.bridge.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BridgeService::class.java))
        }
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var bridge: WebRtcBridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "OpenCall Bridge", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps the internet bridge connected"
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenCall bridge")
                .setContentText("Internet calling bridge online — microphone ready")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("OpenCall bridge")
                .setContentText("Internet calling bridge online — microphone ready")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (running.compareAndSet(false, true)) {
            BridgeRunning.isRunning = true
            worker = Thread { loop() }.also { it.name = "oc-bridge"; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        BridgeRunning.isRunning = false
        try { bridge?.close() } catch (_: Exception) {}
        bridge = null
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    private fun loop() {
        while (running.get()) {
            val devId = DeviceStore.deviceId(this)
            val secret = DeviceStore.secret(this)
            if (devId == null || secret == null) { sleep(5000); continue }
            try {
                Rpc.rpc(
                    "gateway_heartbeat",
                    JSONObject()
                        .put("p_device_id", devId)
                        .put("p_secret", secret)
                        .putOpt("p_battery", readBattery())
                        .put("p_app_version", appVersion()),
                )
            } catch (_: Exception) { /* retried next tick */ }
            sleep(15000)
        }
    }

    private fun readBattery(): Int? {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager ?: return null
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 1..100) pct else null
        } catch (_: Exception) { null }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}

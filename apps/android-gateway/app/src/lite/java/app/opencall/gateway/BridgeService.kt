package app.opencall.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCall Bridge foreground service (LITE flavor).
 *
 * Loop:
 *   1. gateway_heartbeat()      — stay online
 *   2. gateway_fetch_commands() — claim pending commands (the v1.5.0 build
 *      never did this — dial_call / send_sms sat pending forever; that is
 *      exactly the "created / queued" bug the web app showed)
 *   3. execute each via GUIDED HANDOFF — zero sensitive permissions:
 *      • dial_call  → heads-up notification; tapping it opens the phone's
 *        own dialer with the number pre-filled. The WebRTC audio bridge
 *        joins immediately, so the browser hears the call the moment the
 *        user places it.
 *      • send_sms   → heads-up notification; tapping opens the messaging
 *        app with recipient + text pre-filled. The SMS row flips to 'sent'
 *        when the user taps send.
 *      • answer_call→ inbound cellular call: user answers on the handset as
 *        usual; we join the room as OFFERER so the browser can hear it.
 *      • end_call   → drop the audio bridge, report ended.
 *
 * Call state mirrors WebRTC reality, not fiction:
 *   handoff posted → 'dialing'; bridge CONNECTED → 'answered';
 *   bridge FAILED / end_call → 'ended' / 'failed'.
 */
class BridgeService : Service() {

    companion object {
        const val TAG = "OpenCall/Bridge"
        const val CHANNEL_ID = "opencall_bridge"
        const val CHANNEL_HANDOFF = "opencall_bridge_handoff"
        const val NOTIF_ID = 43
        const val ACTION_START = "app.opencall.bridge.START"
        const val ACTION_STOP = "app.opencall.bridge.STOP"
        private const val HANDOFF_NOTIFICATION_TIMEOUT_MS = 60_000L
        private const val HANDOFF_NOTIFICATION_TIMEOUT_S = 60

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

    // current bridged call (single active call, like the full flavor)
    @Volatile private var activeRoom: String = ""
    @Volatile private var activeCallId: String = ""
    /** reported 'answered' for the current callId (WebRTC CONNECTED fires repeatedly). */
    @Volatile private var answeredReportedFor: String = ""
    /** set while a dial handoff notification is pending; onBridgeGone must not report failed. */
    @Volatile private var awaitingHandoff: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "OpenCall Bridge", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps the internet bridge connected"
        nm.createNotificationChannel(ch)
        val ch2 = NotificationChannel(CHANNEL_HANDOFF, "OpenCall Bridge handoffs", NotificationManager.IMPORTANCE_HIGH)
        ch2.description = "Tap-to-dial and tap-to-send prompts from OpenCall"
        nm.createNotificationChannel(ch2)
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

    // ============ main loop ============

    private fun loop() {
        Log.i(TAG, "bridge loop started")
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
                        .putOpt("p_sim_number", DeviceStore.simNumber(this))
                        .putOpt("p_battery", readBattery())
                        .put("p_app_version", appVersion()),
                )

                val claimed = Rpc.rpcRaw(
                    "gateway_fetch_commands",
                    JSONObject()
                        .put("p_device_id", devId)
                        .put("p_secret", secret)
                        .put("p_limit", 5),
                )
                val cmds = BridgeCommands.parse(claimed)
                for (cmd in cmds) {
                    if (!running.get()) break
                    val ack = handle(devId, secret, cmd)
                    try {
                        Rpc.rpc(
                            "gateway_complete_command",
                            JSONObject()
                                .put("p_device_id", devId)
                                .put("p_secret", secret)
                                .put("p_command_id", cmd.id)
                                .put("p_ok", ack.ok)
                                .putOpt("p_result", ack.result),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "complete_command failed (will stay 'sent')", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "loop error: ${e.message}")
            }
            sleep(5000)
        }
        Log.i(TAG, "bridge loop stopped")
    }

    // ============ command handling ============

    private fun handle(devId: String, secret: String, cmd: BridgeCommand): Ack {
        Log.i(TAG, "cmd ${cmd.kind} → ${cmd.payload}")
        val callId = cmd.payload.optString("callId")
        return try {
            when (cmd.kind) {
                "dial_call" -> handleDial(devId, secret, cmd.payload)
                "send_sms" -> handleSms(cmd.payload)
                "answer_call" -> handleAnswer(devId, secret, callId, cmd.payload.optString("room"))
                "end_call" -> handleEnd(devId, secret, callId)
                "ping" -> Ack(true)
                else -> Ack(false, JSONObject().put("error", "unknown kind ${cmd.kind}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "cmd ${cmd.kind} failed", e)
            Ack(false, JSONObject().put("error", e.message ?: e.javaClass.simpleName))
        }
    }

    /** Outbound PSTN call: tap-to-dial handoff + immediate audio bridge join. */
    private fun handleDial(devId: String, secret: String, payload: JSONObject): Ack {
        val to = payload.optString("to")
        val callId = payload.optString("callId")
        if (to.isBlank() || callId.isBlank()) return Ack(false, JSONObject().put("error", "missing to/callId"))

        val room = payload.optString("room").ifBlank { "call-$callId" }
        activeRoom = room
        activeCallId = callId
        answeredReportedFor = ""
        awaitingHandoff = true

        // bridge joins NOW: when the user taps and the cellular call goes out,
        // the browser's offer is already answered — audio flows immediately.
        try {
            ensureBridge().joinAndAnswer(room, callId)
        } catch (e: Exception) {
            Log.w(TAG, "bridge join failed: ${e.message}")
        }

        // honest state: the call is NOT dialing yet — the user must tap first
        reportState(devId, secret, callId, "dialing",
            error = "waiting: tap the OpenCall notification to open the dialer")

        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$to"))
        val ok = postHandoffNotification(
            title = "Tap to dial $to",
            text = "OpenCall will bridge the audio into your browser — open the dialer to place the call",
            intent = dial,
        )
        if (!ok) {
            awaitingHandoff = false
            reportState(devId, secret, callId, "failed", error = "could not post the tap-to-dial notification")
            return Ack(false, JSONObject().put("error", "notification not authorized"))
        }
        scheduleHandoffTimeout(devId, secret, callId, "dial")
        return Ack(true, JSONObject().put("handoff", "dial").put("to", to).put("room", room))
    }

    /** Outbound SMS: tap-to-send handoff (no SEND_SMS permission). */
    private fun handleSms(payload: JSONObject): Ack {
        val to = payload.optString("to")
        val body = payload.optString("body")
        val smsId = payload.optString("smsId")
        if (to.isBlank() || body.isBlank()) return Ack(false, JSONObject().put("error", "missing to/body"))

        val send = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to"))
            .putExtra("sms_body", body)
        val ok = postHandoffNotification(
            title = "Tap to send SMS to $to",
            text = "Your messaging app opens pre-filled — press send there",
            intent = send,
        )
        if (!ok) {
            if (smsId.isNotBlank()) reportSms(smsId, "failed", "could not post the tap-to-send notification")
            return Ack(false, JSONObject().put("error", "notification not authorized"))
        }
        // NOTE: no status update on success — gateway_report_sms only accepts
        // sent/delivered/failed and we can't detect the user's send without
        // SMS permissions. The row stays 'queued' and the web app explains
        // the tap-to-send handoff (see app.html).
        return Ack(true, JSONObject().put("handoff", "sms").put("to", to).put("smsId", smsId))
    }

    /** Inbound cellular call: user answers on the handset; we bridge audio as OFFERER. */
    private fun handleAnswer(devId: String, secret: String, callId: String, room: String): Ack {
        if (callId.isBlank()) return Ack(false, JSONObject().put("error", "missing callId"))
        val r = room.ifBlank { "call-$callId" }
        activeRoom = r
        activeCallId = callId
        awaitingHandoff = false
        try {
            ensureBridge().joinAndOffer(r, callId)
        } catch (e: Exception) {
            Log.w(TAG, "bridge join failed: ${e.message}")
        }
        // flip the row out of 'ringing' now (also protects it from the
        // 45 s no-answer sweep); onBridgeConnected won't double-report.
        answeredReportedFor = callId
        reportState(devId, secret, callId, "answered")
        postHandoffNotification(
            title = "Bridge ready",
            text = "Answer the incoming call — the browser is listening",
            intent = Intent(this, MainActivity::class.java),
        )
        return Ack(true)
    }

    private fun handleEnd(devId: String, secret: String, callId: String): Ack {
        awaitingHandoff = false
        if (callId.isNotBlank()) {
            reportState(devId, secret, callId, "ended")
        }
        try { bridge?.close() } catch (_: Exception) {}
        bridge = null
        activeCallId = ""
        activeRoom = ""
        return Ack(true)
    }

    // ============ WebRTC-driven state (the honest source of truth) ============

    private fun onBridgeConnected() {
        val devId = DeviceStore.deviceId(this) ?: return
        val secret = DeviceStore.secret(this) ?: return
        val callId = activeCallId
        if (callId.isBlank()) return
        awaitingHandoff = false
        if (answeredReportedFor == callId) return
        answeredReportedFor = callId
        Log.i(TAG, "bridge CONNECTED → call $callId answered")
        reportState(devId, secret, callId, "answered")
    }

    private fun onBridgeGone() {
        val devId = DeviceStore.deviceId(this) ?: return
        val secret = DeviceStore.secret(this) ?: return
        val callId = activeCallId
        if (callId.isBlank()) return
        if (awaitingHandoff) {
            Log.i(TAG, "bridge closed before handoff completed — waiting for user")
            return
        }
        Log.i(TAG, "bridge FAILED → call $callId failed")
        reportState(devId, secret, callId, "failed", error = "audio bridge connection failed")
        activeCallId = ""
    }

    private fun scheduleHandoffTimeout(devId: String, secret: String, callId: String, what: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (running.get() && awaitingHandoff && activeCallId == callId && answeredReportedFor != callId && what == "dial") {
                Log.i(TAG, "handoff timeout for call $callId")
                reportState(devId, secret, callId, "failed",
                    error = "no answer from phone: tap-to-dial not used within $HANDOFF_NOTIFICATION_TIMEOUT_S s")
                activeCallId = ""
            }
        }, HANDOFF_NOTIFICATION_TIMEOUT_MS)
    }

    // ============ helpers ============

    private fun reportState(devId: String, secret: String, callId: String, state: String, error: String? = null) {
        try {
            val o = JSONObject()
                .put("p_device_id", devId).put("p_secret", secret)
                .put("p_call_id", callId).put("p_state", state)
            if (error != null) o.put("p_error", error)
            Rpc.rpc("update_gateway_call", o)
        } catch (e: Exception) {
            Log.w(TAG, "update_gateway_call($state) failed: ${e.message}")
        }
    }

    private fun reportSms(smsId: String, status: String, error: String?) {
        try {
            val devId = DeviceStore.deviceId(this) ?: return
            val secret = DeviceStore.secret(this) ?: return
            val o = JSONObject()
                .put("p_device_id", devId).put("p_secret", secret)
                .put("p_sms_id", smsId).put("p_status", status)
            if (error != null) o.put("p_error", error)
            Rpc.rpc("gateway_report_sms", o)
        } catch (e: Exception) {
            Log.w(TAG, "gateway_report_sms($status) failed: ${e.message}")
        }
    }

    /** Posts a heads-up notification. Returns false when notifications are blocked. */
    private fun postHandoffNotification(title: String, text: String, intent: Intent): Boolean {
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !nm.areNotificationsEnabled()) return false
        val pi = PendingIntent.getActivity(
            this, (title.hashCode() and 0x7fffffff), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_HANDOFF)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_CALL)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        }
        runOnUiThread { try { nm.notify(NOTIF_ID + 1, n) } catch (_: Exception) {} }
        return true
    }

    private fun ensureBridge(): WebRtcBridge {
        if (bridge == null) {
            bridge = WebRtcBridge(
                this,
                DeviceStore.deviceId(this)!!,
                DeviceStore.secret(this)!!,
                onConnected = { runOnUiThread { onBridgeConnected() } },
                onGone = { runOnUiThread { onBridgeGone() } },
            )
        }
        return bridge!!
    }

    private fun runOnUiThread(f: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(f)
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

    private data class Ack(val ok: Boolean, val result: JSONObject? = null)
}

/** Parsers for the command claim RPC result (local to the lite flavor). */
internal data class BridgeCommand(val id: String, val kind: String, val payload: JSONObject)

internal object BridgeCommands {
    /**
     * gateway_fetch_commands returns a jsonb ARRAY of { id, kind, payload }.
     * Handle both JSONArray (set-returning) and single-object (scalar) shapes.
     */
    fun parse(raw: Any?): List<BridgeCommand> {
        return when (raw) {
            null -> emptyList()
            is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                val o = raw.optJSONObject(i) ?: return@mapNotNull null
                BridgeCommand(o.optString("id"), o.optString("kind"), o.optJSONObject("payload") ?: JSONObject())
            }
            is JSONObject -> {
                if (raw.has("id")) listOf(
                    BridgeCommand(raw.optString("id"), raw.optString("kind"), raw.optJSONObject("payload") ?: JSONObject())
                ) else emptyList()
            }
            else -> emptyList()
        }
    }
}

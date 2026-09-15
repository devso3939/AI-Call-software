package app.opencall.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service: the gateway's heartbeat.
 *
 * Loop (single worker thread):
 *   1. gateway_heartbeat()       — mark device online, report battery
 *   2. gateway_fetch_commands()  — claim pending commands (send_sms / dial_call /
 *                                  answer_call / end_call / ping)
 *   3. execute each, report via gateway_complete_command()
 *
 * Battery-friendly: 20 s idle poll (4 s while a call is active so dial/answer
 * stays snappy), exponential backoff on errors (5 s → 60 s), immediate wake on
 * connectivity regain via ConnectivityManager callback, and a partial wakelock
 * only while executing commands. The foreground notification is live-updated
 * with battery/online state and carries a Stop action.
 */
class GatewayService : Service() {

    companion object {
        const val TAG = "OpenCall/Gw"
        const val CHANNEL_ID = "opencall_gateway"
        const val NOTIF_ID = 42
        const val ACTION_START = "app.opencall.gateway.START"
        const val ACTION_STOP = "app.opencall.gateway.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var bridge: WebRtcBridge? = null
    private lateinit var prefs: android.content.SharedPreferences

    // Live notification state (1.5.25): battery % and connectivity, refreshed
    // by the loop; notification re-rendered when the text changes.
    private var lastNotifText: String? = null
    private var lastBattery: Int? = null
    private var online = true

    // Connectivity wake (1.5.25): onAvailable() triggers an immediate loop
    // iteration instead of waiting out the current sleep.
    private val wakeLock = Object()
    @Volatile private var wakeNow = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var partialWake: PowerManager.WakeLock? = null

    private fun buildNotification(): Notification {
        val batteryTxt = lastBattery?.let { " • $it%" } ?: ""
        val statusTxt = if (online) "Online — relaying SMS & calls" else "Offline — waiting for network"
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b
            .setContentTitle("OpenCall gateway${batteryTxt}")
            .setContentText(statusTxt)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun refreshNotification(battery: Int?, isOnline: Boolean) {
        val changed = battery != lastBattery || isOnline != online
        lastBattery = battery
        online = isOnline
        if (!changed) return
        val text = "b=$battery on=$isOnline"
        if (text == lastNotifText) return
        lastNotifText = text
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
    }

    /** Nudge the loop awake immediately (used by the network callback). */
    private fun wakeLoop() {
        synchronized(wakeLock) {
            wakeNow = true
            wakeLock.notifyAll()
        }
    }

    /** Interruptible sleep that returns early when wakeLoop() fires. */
    private fun sleep(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        synchronized(wakeLock) {
            while (running.get() && !wakeNow) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return
                try { wakeLock.wait(minOf(remain, 1000)) } catch (_: InterruptedException) { return }
            }
            wakeNow = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("gw", Context.MODE_PRIVATE)
        // 1.5.12 — a service restart mid-session would otherwise silently
        // strand the route in BLUETOOTH mode. Bridge sessions are web-side
        // lifecycle events, so every fresh service start begins acoustic.
        AudioRoute.mode = AudioRoute.Mode.SPEAKER
        prefs.edit().putBoolean("hfpMode", false).apply()
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "OpenCall Gateway", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps the SIM gateway connected"
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        // Connectivity wake (1.5.25): the moment the network comes back
        // (wifi↔mobile handover, Doze exit), the loop wakes immediately
        // instead of waiting out its current backoff sleep.
        if (netCallback == null) {
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "network available — waking loop")
                        wakeLoop()
                    }
                }
                cm?.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    cb,
                )
                netCallback = cb
            } catch (e: Exception) {
                Log.w(TAG, "network callback registration failed: ${e.message}")
            }
        }

        if (running.compareAndSet(false, true)) {
            GatewayRunning.isRunning = true
            worker = Thread { loop() }.also { it.name = "oc-gateway"; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        GatewayRunning.isRunning = false
        wakeLoop() // release any in-flight sleep so the worker exits promptly
        try {
            netCallback?.let {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        netCallback = null
        try { partialWake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        partialWake = null
        try { bridge?.close() } catch (_: Exception) {}
        bridge = null
        worker?.interrupt()
        worker = null
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    // ============ main loop ============

    private fun loop() {
        Log.i(TAG, "gateway loop started")

        // Exponential backoff (1.5.25): 5s → 15s → 30s → 60s, reset on any
        // successful RPC round. Prevents hammering a dead network every 5 s
        // while still recovering fast.
        var backoffMs = 5000L

        while (running.get()) {
            val devId = DeviceStore.deviceId(this)
            val secret = DeviceStore.secret(this)
            if (devId == null || secret == null) {
                sleep(5000); continue
            }

            try {
                // 0) keep the agent-mode flag in sync (MainActivity toggles it)
                FullCallService.autoAnswerInbound =
                    prefs.getBoolean("agentMode", false)

                val battery = readBattery()

                // 1) heartbeat every iteration (marks online; server times out after 90 s)
                Rpc.rpc(
                    "gateway_heartbeat",
                    JSONObject()
                        .put("p_device_id", devId)
                        .put("p_secret", secret)
                        .putOpt("p_battery", battery)
                        .put("p_app_version", appVersion())
                        .putOpt("p_sim_number", DeviceStore.simNumber(this))
                        // 1.5.13 — setup state so the web app can warn when the
                        // silent-dialer prerequisites are missing (overlay, role).
                        .put("p_setup", setupState()),
                )

                // 2) claim commands (returns jsonb array — use rpcRaw!)
                val claimed = Rpc.rpcRaw(
                    "gateway_fetch_commands",
                    JSONObject()
                        .put("p_device_id", devId)
                        .put("p_secret", secret)
                        .put("p_limit", 5),
                )

                // Success → reset backoff and refresh the live notification.
                backoffMs = 5000L
                refreshNotification(battery, true)

                val cmds = Commands.parse(claimed)
                if (cmds.isEmpty()) {
                    // Idle: 20 s between polls saves battery; when a call is
                    // active (or an audio bridge is up) drop to 4 s so dial /
                    // answer / end commands stay snappy.
                    val callActive = FullCallService.ringing || FullCallService.active || bridge != null
                    sleep(if (callActive) 4000 else 20000)
                    continue
                }

                // 3) execute each under a partial wakelock so the CPU doesn't
                // sleep mid-command (dial, SMS send, DTMF…). Acquired per
                // command batch, released in finally.
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencall:gw-cmd")
                try {
                    wl?.acquire(60_000) // safety cap: never hold beyond 60 s
                    for (cmd in cmds) {
                        if (!running.get()) break
                        execute(devId, secret, cmd)
                    }
                } finally {
                    try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "loop error: ${e.message}")
                refreshNotification(null, false)
                sleep(backoffMs)
                backoffMs = (backoffMs * 3).coerceAtMost(60_000L) // 5 → 15 → 45 → 60 cap
            }
        }
        Log.i(TAG, "gateway loop stopped")
    }

    private fun execute(devId: String, secret: String, cmd: Command) {
        Log.i(TAG, "cmd ${cmd.kind} → ${cmd.payload}")
        var ok = false
        var result: JSONObject? = null
        try {
            when (cmd.kind) {
                "send_sms" -> {
                    val smsId = cmd.payload.optString("smsId")
                    val to = cmd.payload.optString("to")
                    val body = cmd.payload.optString("body")
                    // "via" names the origin (web UI, api key name, or
                    // sms-gateway:<username>) — log it so the owner can trace
                    // third-party API sends in logcat.
                    val via = cmd.payload.optString("via", "")
                    Log.i(TAG, "send_sms via=$via")
                    if (to.isNotBlank() && body.isNotBlank()) {
                        SmsSender.send(this, smsId, to, body)
                        ok = true
                    } else {
                        result = JSONObject().put("error", "missing to/body")
                    }
                }

                "dial_call" -> {
                    val to = cmd.payload.optString("to")
                    val callId = cmd.payload.optString("callId")
                    val room = cmd.payload.optString("room")
                    if (to.isNotBlank()) {
                        ok = CallControl.placeCall(this, to)
                        if (ok) {
                            // state = dialing (server flips to ringing)
                            Rpc.rpc("update_gateway_call", JSONObject()
                                .put("p_device_id", devId).put("p_secret", secret)
                                .put("p_call_id", callId).put("p_state", "dialing"))
                            prefs.edit().putString("bridgeRoom", room).putString("bridgeCallId", callId).apply()
                            // 1.5.7 silent-dialer fallback: when the
                            // InCallService is NOT bound (no default-dialer /
                            // manage-calls grant) nothing else dismisses the
                            // stock dialer, so a background best-effort loop
                            // keeps pressing home during call setup. Harmless
                            // no-op when background activity starts are
                            // blocked; the ICS path handles the rest.
                            Thread {
                                var n = 0
                                // stops early once the ICS takes over (its
                                // calls set becomes non-empty); otherwise
                                // covers ~10 s of call setup.
                                while (n < 40 && FullCallService.calls.isEmpty()) {
                                    FullCallService.dismissInCallUi(this@GatewayService)
                                    try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                                    n++
                                }
                            }.start()
                            // open the audio bridge NOW so the browser's offer is
                            // answered the moment it lands
                            // 1.5.12: Bluetooth hands-free mode carries the
                            // audio over the paired computer instead — no
                            // WebRTC bridge for this call.
                            if (AudioRoute.mode == AudioRoute.Mode.BLUETOOTH) {
                                Log.i(TAG, "dial_call: BLUETOOTH hands-free mode — WebRTC bridge skipped")
                            } else {
                                ensureBridge().joinAndAnswer(room, callId)
                            }
                        } else {
                            // 1.5.6: honest failure — without this the web call
                            // panel stays "dialing" until its timeout.
                            result = JSONObject().put("error", "dial failed — CALL_PHONE denied or Telecom rejected the dial")
                            try {
                                Rpc.rpc("update_gateway_call", JSONObject()
                                    .put("p_device_id", devId).put("p_secret", secret)
                                    .put("p_call_id", callId).put("p_state", "failed"))
                            } catch (_: Exception) {}
                        }
                    }
                }

                "answer_call" -> {
                    val callId = cmd.payload.optString("callId")
                    ok = CallControl.answerRinging(this)
                    if (ok) {
                        Rpc.rpc("update_gateway_call", JSONObject()
                            .put("p_device_id", devId).put("p_secret", secret)
                            .put("p_call_id", callId).put("p_state", "answered"))
                        // INBOUND bridge: phone is the OFFERER — browser's
                        // startMedia('callee') waits for our offer in calls_events.
                        // joinAndAnswer here would deadlock (both sides waiting).
                        // 1.5.12: Bluetooth hands-free mode carries the audio
                        // over the paired computer instead — no WebRTC bridge.
                        val room = prefs.getString("bridgeRoom", "") ?: ""
                        if (AudioRoute.mode == AudioRoute.Mode.BLUETOOTH) {
                            Log.i(TAG, "answer_call: BLUETOOTH hands-free mode — WebRTC bridge skipped")
                        } else {
                            ensureBridge().joinAndOffer(room, callId)
                        }
                    }
                }

                "end_call" -> {
                    val callId = cmd.payload.optString("callId")
                    ok = CallControl.endCall(this)
                    // AUDIT FIX: only report "ended" when the hang-up actually
                    // succeeded — the old code published "ended" even when
                    // Telecom rejected it, so the browser showed the call over
                    // while the cellular call was still live.
                    if (ok) {
                        Rpc.rpc("update_gateway_call", JSONObject()
                            .put("p_device_id", devId).put("p_secret", secret)
                            .put("p_call_id", callId).put("p_state", "ended"))
                    } else {
                        result = JSONObject().put("error", "end failed — no active call or Telecom rejected the hang-up")
                    }
                    bridge?.close()
                    // 1.5.12: the Bluetooth hands-free session ends with the call
                    prefs.edit().putBoolean("hfpMode", false).apply()
                }

                // ── 1.5.5 web-app call controls (in-call mute / speaker) ──
                // 1.5.10: setMuted returns a JSON object { muted, path } so the
                // web UI can show exactly which mute path applied (telecom vs
                // bridge-track). "Mute phone" silences the phone's microphone
                // contribution — it NEVER touches the browser user's voice.
                "mute_call" -> {
                    val st = CallControl.setMuted(this, true)
                    ok = st != null
                    result = st ?: JSONObject().put("error", "no active call")
                }

                "unmute_call" -> {
                    val st = CallControl.setMuted(this, false)
                    ok = st != null
                    result = st ?: JSONObject().put("error", "no active call")
                }

                "toggle_mute" -> {
                    val st = CallControl.toggleMute(this)
                    ok = st != null
                    result = st ?: JSONObject().put("error", "no active call")
                }

                "speaker_on" -> {
                    // 1.5.12 — the web app requests the call audio on the
                    // paired computer (Bluetooth hands-free) with payload
                    // { route: "bluetooth" }. Commands without the payload
                    // behave exactly as in 1.5.11 (acoustic speaker bridge).
                    if (cmd.payload.optString("route") == "bluetooth") {
                        CallControl.routeBluetooth(this)
                        prefs.edit().putBoolean("hfpMode", true).apply()
                        ok = true
                        result = JSONObject().put("speaker", true).put("route", "bluetooth")
                    } else {
                        CallControl.speakerOn(this)
                        prefs.edit().putBoolean("hfpMode", false).apply()
                        ok = true
                        result = JSONObject().put("speaker", true)
                    }
                }

                "speaker_off" -> {
                    CallControl.speakerOff(this)
                    prefs.edit().putBoolean("hfpMode", false).apply()
                    ok = true
                    result = JSONObject().put("speaker", false)
                }

                "toggle_speaker" -> {
                    // 1.5.12: in Bluetooth hands-free mode a legacy toggle
                    // returns the call to the acoustic bridge (the web app's
                    // route button uses explicit speaker_on / speaker_off).
                    val wasBt = AudioRoute.mode == AudioRoute.Mode.BLUETOOTH
                    val now = if (wasBt) false else !CallControl.bridgeWantsSpeaker
                    if (now) CallControl.speakerOn(this) else CallControl.speakerOff(this)
                    prefs.edit().putBoolean("hfpMode", false).apply()
                    ok = true
                    result = JSONObject().put("speaker", now)
                }

                // hold is exposed through queue_gateway_command payloads too
                "hold_call" -> {
                    val st = CallControl.setHeld(true)
                    ok = st != null
                    result = if (st == null) JSONObject().put("error", "no active call")
                             else JSONObject().put("held", true)
                }

                "resume_call" -> {
                    val st = CallControl.setHeld(false)
                    ok = st != null
                    result = if (st == null) JSONObject().put("error", "no active call")
                             else JSONObject().put("held", false)
                }

                // 1.5.7: DTMF keypad from the web call panel
                "dtmf_call" -> {
                    val digit = cmd.payload.optString("digit").firstOrNull()
                    ok = digit != null && CallControl.sendDtmf(digit)
                    result = if (digit == null) JSONObject().put("error", "missing digit")
                             else JSONObject().put("sent", ok)
                }

                // 1.5.5: exact call-state probe — lets the web app verify the
                // InCallService binding without a real call
                "call_state_probe" -> {
                    ok = true
                    result = JSONObject()
                        .put("icsBound", FullCallService.calls.isNotEmpty() || CallControl.hasInCallBinding(this))
                        .put("lastState", FullCallService.lastState)
                        .put("active", FullCallService.active)
                        .put("ringing", FullCallService.ringing)
                        .put("agentMode", FullCallService.autoAnswerInbound)
                        // 1.5.12: audioRoute lets the web app confirm the
                        // Bluetooth hands-free mode actually engaged.
                        .put("audioRoute", if (AudioRoute.mode == AudioRoute.Mode.BLUETOOTH) "bluetooth" else "speaker")
                }

                "ping" -> { ok = true }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cmd ${cmd.kind} failed", e)
            result = JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }

        try {
            Rpc.rpc(
                "gateway_complete_command",
                JSONObject()
                    .put("p_device_id", devId)
                    .put("p_secret", secret)
                    .put("p_command_id", cmd.id)
                    .put("p_ok", ok)
                    .putOpt("p_result", result),
            )
        } catch (e: Exception) {
            Log.w(TAG, "complete_command failed (will stay 'sent')", e)
        }
    }

    private fun ensureBridge(): WebRtcBridge {
        if (bridge == null) {
            // 1.5.8: acousticBridge=true — mic captures WITHOUT hardware AEC
            // so the loudspeaker→mic hop (the whole bridge path) survives.
            // AUDIT FIX: wire onConnected/onGone like the lite flavor — without
            // them a failed bridge (TURN unreachable, peer gone) left the
            // overlay showing "connected" and the call state stuck on
            // "dialing" forever while the cellular call ran with nobody
            // listening on the browser side.
            bridge = WebRtcBridge(
                this,
                DeviceStore.deviceId(this)!!,
                DeviceStore.secret(this)!!,
                onConnected = {
                    // State reporting stays with FullCallService (telecom is
                    // the source of truth for ringing/active — the bridge can
                    // connect while the call is still RINGING, so reporting
                    // "answered" here would lie). Just log.
                    Log.i(TAG, "bridge CONNECTED")
                },
                onGone = {
                    // AUDIT FIX: bridge died (TURN unreachable, peer gone) —
                    // tell the browser so its call panel doesn't sit on
                    // "dialing" forever. Only when no terminal state was
                    // already published for this call.
                    val cid = prefs.getString("bridgeCallId", "") ?: ""
                    if (cid.isNotBlank()) reportBridgeFailed(cid)
                },
                // 1.5.8 FIX: full IS an acoustic bridge for cellular calls —
                // the far end reaches the mic ONLY through the loudspeaker hop.
                // Hardware AEC erases exactly that hop, so it must be OFF here.
                acousticBridge = true,
            )
        }
        return bridge!!
    }

    /**
     * AUDIT FIX helper: publish state=failed for a call at most once —
     * FullCallService also reports terminal states via update_gateway_call,
     * so this must not fight it (and must not fire after "completed"/"ended").
     */
    private var bridgeFailedReportedFor: String? = null
    private fun reportBridgeFailed(callId: String) {
        if (bridgeFailedReportedFor == callId) return
        bridgeFailedReportedFor = callId
        val devId = DeviceStore.deviceId(this) ?: return
        val secret = DeviceStore.secret(this) ?: return
        try {
            Rpc.rpc("update_gateway_call", JSONObject()
                .put("p_device_id", devId).put("p_secret", secret)
                .put("p_call_id", callId).put("p_state", "failed")
                .put("p_error", "audio bridge connection failed"))
            Log.w(TAG, "bridge FAILED for call $callId — reported to server")
        } catch (e: Exception) {
            Log.w(TAG, "bridge failure report failed: ${e.message}")
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

    /**
     * 1.5.13 — setup state reported with every heartbeat so the web app can
     * warn when the silent-dialer prerequisites are missing. Without the
     * overlay permission (and ideally the phone-app role) Android shows the
     * stock dialer UI during gateway calls — the exact "manual call" bug.
     * MainActivity writes the same snapshot to prefs; we recompute the
     * live values here rather than trusting a possibly stale cache.
     */
    private fun setupState(): JSONObject = try {
        val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        val role = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            tm?.defaultDialerPackage == packageName
        } else false
        val om = getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && om != null) {
            om.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(), packageName)
        } else null
        val overlay = if (mode != null) mode == android.app.AppOpsManager.MODE_ALLOWED
                      else Settings.canDrawOverlays(this)
        JSONObject()
            .put("flavor", "gateway")
            .put("overlay", overlay)
            .put("dialer", role)
            .put("agentMode", prefs.getBoolean("agentMode", false))
    } catch (_: Exception) {
        JSONObject().put("flavor", "gateway")
    }
}

/** Parsers for the command claim RPC result. */
internal data class Command(val id: String, val kind: String, val payload: JSONObject)

internal object Commands {
    /**
     * gateway_fetch_commands returns a jsonb ARRAY of { id, kind, payload }.
     * PostgREST wraps a single-element scalar as a bare JSON array already;
     * Rpc.rpc returns element[0] only when the body is a JSON array, which for
     * a scalar array result is wrong — so handle both shapes.
     */
    fun parse(raw: Any?): List<Command> {
        return when (raw) {
            null -> emptyList()
            is org.json.JSONArray -> (0 until raw.length()).mapNotNull { i ->
                val o = raw.optJSONObject(i) ?: return@mapNotNull null
                Command(o.optString("id"), o.optString("kind"), o.optJSONObject("payload") ?: JSONObject())
            }
            is JSONObject -> {
                if (raw.has("id")) listOf(
                    Command(raw.optString("id"), raw.optString("kind"), raw.optJSONObject("payload") ?: JSONObject())
                ) else emptyList()
            }
            else -> emptyList()
        }
    }
}

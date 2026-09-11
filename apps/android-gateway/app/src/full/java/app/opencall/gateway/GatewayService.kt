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
import android.os.PowerManager
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
 * Battery-friendly: 5 s idle poll, immediate wake on connectivity, Doze-safe
 * via a partial wakelock only while executing a command.
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
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenCall gateway")
                .setContentText("SIM gateway online — SMS & calls relay")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("OpenCall gateway")
                .setContentText("SIM gateway online — SMS & calls relay")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
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
        var iteration = 0L

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

                // 1) heartbeat every iteration (marks online; server times out after 90 s)
                val battery = readBattery()
                Rpc.rpc(
                    "gateway_heartbeat",
                    JSONObject()
                        .put("p_device_id", devId)
                        .put("p_secret", secret)
                        .putOpt("p_battery", battery)
                        .put("p_app_version", appVersion())
                        .putOpt("p_sim_number", DeviceStore.simNumber(this)),
                )

                // 2) claim commands (returns jsonb array — use rpcRaw!)
                val claimed = Rpc.rpcRaw(
                    "gateway_fetch_commands",
                    JSONObject()
                        .put("p_device_id", devId)
                        .put("p_secret", secret)
                        .put("p_limit", 5),
                )

                val cmds = Commands.parse(claimed)
                if (cmds.isEmpty()) {
                    sleep(if (iteration % 6 == 0L) 5000 else 4000)
                    iteration++
                    continue
                }

                // 3) execute each
                for (cmd in cmds) {
                    if (!running.get()) break
                    execute(devId, secret, cmd)
                }
            } catch (e: Exception) {
                Log.w(TAG, "loop error: ${e.message}")
                sleep(5000)
            }
            iteration++
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
                    Rpc.rpc("update_gateway_call", JSONObject()
                        .put("p_device_id", devId).put("p_secret", secret)
                        .put("p_call_id", callId).put("p_state", "ended"))
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
            bridge = WebRtcBridge(
                this,
                DeviceStore.deviceId(this)!!,
                DeviceStore.secret(this)!!,
                acousticBridge = true,
            )
        }
        return bridge!!
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

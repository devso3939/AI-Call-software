package app.opencall.gateway

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The ONLY hook Android gives a non-default dialer to observe and control
 * real cellular calls:
 *
 *   • RINGING / DIALING / ACTIVE / DISCONNECTED state — exact, no polling.
 *     (PHONE_STATE broadcasts give "offhook/idle" only — useless for "is the
 *     far side actually ringing".)
 *   • Full call control: answer / reject / hold / mute / speaker / DTMF /
 *     disconnect — via android.telecom.Call itself.
 *
 *   • API 26–32:  MANAGE_OWN_CALLS + user grants "Phone → Other apps" or the
 *                 app is the default dialer. ANSWER_PHONE_CALLS covers answer.
 *   • API 33+:    a CALL_COMPANION app (the "Allow managing calls" consent
 *                 toggle in Settings → Apps → Special access) is served this
 *                 service too; putCallOnHold-style control + state come along.
 *                 ROLE_CALL_SCREENING / ROLE_DIALER are other valid grants.
 *
 * Everything the service learns is mirrored to the web app:
 *   state      → update_gateway_call (dialing|ringing|answered|ended)
 *   direction  → prefs "lastCallDir" (out=inbound) so the UI can label it
 *   number     → prefs "lastCallNum"
 *
 * The service also dismisses the stock InCallUI every time it reappears
 * (see minimizeCallScreen) so the phone's dialer never covers the user's
 * screen — the gateway runs in the background and the web app is the UI.
 */
class FullCallService : InCallService() {

    companion object {
        const val TAG = "OpenCall/ICS"

        // live call objects (cellular calls only — one at a time is enough)
        val calls = CopyOnWriteArraySet<Call>()

        /** TRUE while at least one cellular call is DIALING or RINGING. */
        @Volatile var ringing: Boolean = false
            private set

        /** TRUE while at least one cellular call is ACTIVE. */
        @Volatile var active: Boolean = false
            private set

        /** Latest known state of the primary call, for the status screen. */
        @Volatile var lastState: String = "idle"
        @Volatile var lastNumber: String? = null
        @Volatile var lastDirection: String? = null   // "outbound" | "inbound" | null

        /** Mic-mute mirror (Call.isMuted doesn't exist; setMuted is API 34+). */
        @Volatile var micMuted: Boolean = false

        /** Live service instance — Call.setMuted doesn't exist; the API 34+
            mute entry point is InCallService.setMuted(Boolean). */
        @Volatile var instance: FullCallService? = null

        /** Set by CallControl.placeCall so onCallAdded knows this is ours. */
        @Volatile var pendingOutbound: Boolean = false

        /**
         * 1.5.6 — server-driven inbound handling. The GatewayService polls
         * gateway_commands; when the user taps Answer/Decline in the web app
         * it queues answer_call / end_call and we act through the Call object
         * here. autoAnswerInbound makes the phone pick up EVERY inbound
         * cellular call automatically (fully background agent mode).
         */
        @Volatile var autoAnswerInbound: Boolean = false

        /** Server call id for the currently-ringing INBOUND call (bridgeRoom companion). */
        @Volatile var inboundCallId: String? = null

        fun stateLabel(c: Call): String = when (c.state) {
            Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING -> "dialing"
            Call.STATE_RINGING -> "ringing"
            Call.STATE_ACTIVE -> "active"
            if (Build.VERSION.SDK_INT >= 34) 1076111470 else -1 -> "active" // STATE_SIMULATED_RINGING (API 34), inlined to compile on older SDKs
            Call.STATE_HOLDING -> "holding"
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "ended"
            else -> "other:" + c.state
        }

        /**
         * 1.5.7 — the supported speaker entry point. AudioManager flips lose
         * to telephony on modern Android; routing through the InCallService
         * (CallAudioState) is what actually sticks. Returns true when the
         * route was applied through this service (i.e. the ICS is bound).
         *
         * @JvmStatic is REQUIRED: AudioRoute.kt (shared src/main, also in the
         * lite flavor) reaches this via reflection on the OUTER class, and
         * companion members only land as outer-class statics with @JvmStatic.
         */
        @JvmStatic
        fun setSpeakerRoute(on: Boolean): Boolean = try {
            val svc = instance ?: return false
            svc.setAudioRoute(
                if (on) CallAudioState.ROUTE_SPEAKER
                else CallAudioState.ROUTE_EARPIECE)
            true
        } catch (_: Throwable) { false }

        /**
         * 1.5.7 — fire-and-forget "go home". Used by the ICS itself and by
         * GatewayService as a best-effort fallback when the ICS is not
         * bound (background activity launch may be blocked on Android 10+
         * without a system binding — the attempt is harmless either way).
         */
        fun dismissInCallUi(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "home intent failed: ${e.message}")
            }
        }
    }

    // ─────────────────────── lifecycle ───────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        calls.add(call)
        call.registerCallback(cb)
        val dir = try { "${call.details.callDirection}" } catch (_: Throwable) { "?" }
        Log.i(TAG, "call added: ${stateLabel(call)} ${safeNumber(call)} dir=$dir")
        pushState(call)
        // The stock dialer UI always pops when a call is added — push it to
        // the background immediately. Android re-shows it on each state
        // change, so we also dismiss in the callback.
        minimizeCallScreen()

        // 1.5.6: INBOUND call ringing → tell the server (replaces the
        // never-shipped IncomingCallReceiver). The web app shows the
        // incoming banner + Answer/Decline buttons for this call id.
        if (stateLabel(call) == "ringing" && lastDirection == "inbound") {
            val from = safeNumber(call)
            inboundCallId = reportIncoming(from)
            if (autoAnswerInbound) {
                Log.i(TAG, "auto-answer inbound call (agent mode)")
                Thread {
                    try { Thread.sleep(600) } catch (_: InterruptedException) {}
                    try { call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) } catch (e: Exception) {
                        Log.w(TAG, "auto-answer failed: ${e.message}")
                    }
                }.start()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        calls.remove(call)
        call.unregisterCallback(cb)
        Log.i(TAG, "call removed")
        pushState(call)
        if (calls.isEmpty()) {
            ringing = false
            active = false
            lastState = "idle"
            reportState("ended", lastNumber)
            inboundCallId = null
            // restore normal audio mode
            try { setAudioRoute(CallAudioState.ROUTE_SPEAKER or CallAudioState.ROUTE_EARPIECE) } catch (_: Exception) {}
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        // The dialer may flip the route back to earpiece — re-assert speaker
        // while the acoustic bridge is supposed to be listening.
        if (CallControl.bridgeWantsSpeaker && audioState != null &&
            (audioState.route and CallAudioState.ROUTE_SPEAKER) == 0) {
            try { setAudioRoute(CallAudioState.ROUTE_SPEAKER); Log.i(TAG, "re-asserted speaker route") } catch (_: Exception) {}
        }
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) { /* single-call design */ }

    override fun onDestroy() {
        for (c in calls) { try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        calls.clear()
        ringing = false
        active = false
        lastState = "idle"
        instance = null
        super.onDestroy()
    }

    // ─────────────────────── call callbacks ───────────────────────

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.i(TAG, "state → ${stateLabel(call)}")
            pushState(call)
            if (state == Call.STATE_ACTIVE) {
                minimizeCallScreen()   // dialer re-shows itself
                // 1.5.7 audio fix: telecom IGNORES route changes while the
                // call is dialing/ringing — the AudioManager flip from
                // WebRtcBridge.onOffer/onAnswer gets reverted. Re-assert the
                // speaker through the supported InCallService path a moment
                // after the call goes ACTIVE (and again after 1.5 s for slow
                // OEM audio stacks), so the far end actually reaches the mic
                // and the browser hears the customer.
                if (CallControl.bridgeWantsSpeaker) {
                    Thread {
                        try { Thread.sleep(350); setAudioRoute(CallAudioState.ROUTE_SPEAKER) } catch (_: Exception) {}
                        try { Thread.sleep(1150); setAudioRoute(CallAudioState.ROUTE_SPEAKER) } catch (_: Exception) {}
                    }.start()
                }
            }
            if (state == Call.STATE_RINGING) minimizeCallScreen()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            // number can resolve late (contact lookup) — refresh the mirror
            lastNumber = safeNumber(call)
        }
    }

    // ─────────────────────── state → backend ───────────────────────

    private fun pushState(call: Call) {
        val s = stateLabel(call)
        lastState = s
        lastNumber = safeNumber(call) ?: lastNumber
        // callDirection is API 29+; minSdk is 26 — read it reflectively-guarded
        lastDirection = try {
            when (call.details.callDirection) {
                Call.Details.DIRECTION_OUTGOING -> "outbound"
                Call.Details.DIRECTION_INCOMING -> "inbound"
                else -> lastDirection
            }
        } catch (_: Throwable) { lastDirection }
        ringing = calls.any { stateLabel(it) == "ringing" || stateLabel(it) == "dialing" }
        active = calls.any { stateLabel(it) == "active" || stateLabel(it) == "holding" }

        val state = when (s) {
            "dialing", "ringing" -> if (lastDirection == "inbound") null else s  // inbound ringing is reported by report_incoming_call
            "active" -> "answered"
            "holding" -> "answered"
            "ended" -> "ended"
            else -> null
        }
        if (state != null) reportState(state, lastNumber)
        minimizeCallScreen()
    }

    private fun reportState(state: String, number: String?) {
        val devId = DeviceStore.deviceId(this) ?: return
        val secret = DeviceStore.secret(this) ?: return
        val callId = getSharedPreferences("gw", MODE_PRIVATE).getString("bridgeCallId", null) ?: return
        Thread {
            try {
                Rpc.rpc("update_gateway_call", JSONObject()
                    .put("p_device_id", devId)
                    .put("p_secret", secret)
                    .put("p_call_id", callId)
                    .put("p_state", state))
                Log.i(TAG, "reported $state → web")
            } catch (e: Exception) { Log.w(TAG, "report $state failed: ${e.message}") }
        }.start()
    }

    /**
     * 1.5.6: inbound ringing → report_incoming_call. Returns the server
     * call id (the web app's Answer/Decline buttons target it). Runs on a
     * worker thread — onCallAdded must stay quick or Telecom ANRs us.
     */
    private fun reportIncoming(from: String?): String? {
        val devId = DeviceStore.deviceId(this) ?: return null
        val secret = DeviceStore.secret(this) ?: return null
        val r = java.util.concurrent.atomic.AtomicReference<String?>()
        val t = Thread {
            try {
                val res = Rpc.rpc("report_incoming_call", JSONObject()
                    .put("p_device_id", devId)
                    .put("p_secret", secret)
                    .put("p_from", from ?: "unknown"))
                val id = res?.optString("callId")?.takeIf { it.isNotBlank() }
                r.set(id)
                Log.i(TAG, "reported inbound call from $from → callId=$id")
            } catch (e: Exception) { Log.w(TAG, "report_incoming_call failed: ${e.message}") }
        }
        t.start()
        t.join(5000)   // generous, but bounded — Telecom is waiting on us
        return r.get()
    }

    private fun safeNumber(call: Call): String? = try {
        call.details.handle?.schemeSpecificPart
    } catch (_: Exception) { null }

    // ─────────────────────── UI suppression ───────────────────────

    /**
     * Throws the stock dialer's full-screen activity to the background so the
     * cellular call runs with NO phone-side UI. There is no public API to
     * suppress InCallUI, so we do the honest thing: go home. The user sees a
     * small "OpenCall gateway — call in progress" notification instead and
     * every control lives in the web app.
     */
    private fun minimizeCallScreen() {
        dismissInCallUi(this)
        suppressLoop()
    }

    /**
     * 1.5.7 — silent dialer. The stock dialer re-shows itself right after we
     * go home, and on many devices it launches AFTER onCallAdded fires, so a
     * single dismissal always loses the race and the dialer stays on screen.
     * Fix: keep dismissing — a 100 ms re-assert loop for ~12 s, restarted by
     * every state change (dialing → ringing → active), so the whole
     * call-setup window stays covered. Bounded so it can never spin forever.
     */
    @Volatile private var suppressGen: Int = 0

    private fun suppressLoop() {
        val gen = ++suppressGen
        Thread {
            var fired = 0
            var alive = true
            // 120 × 100 ms ≈ 12 s of coverage per state-change window —
            // enough to cover dialing → ringing → answered on a normal
            // network, and each state change restarts the clock anyway.
            while (alive && gen == suppressGen && fired < 120 && calls.isNotEmpty()) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { alive = false }
                if (alive) {
                    dismissInCallUi(this)
                    fired++
                }
            }
        }.start()
    }
}

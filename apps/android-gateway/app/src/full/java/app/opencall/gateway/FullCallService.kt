package app.opencall.gateway

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

        fun stateLabel(c: Call): String = when (c.state) {
            Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING -> "dialing"
            Call.STATE_RINGING -> "ringing"
            Call.STATE_ACTIVE -> "active"
            if (Build.VERSION.SDK_INT >= 34) 1076111470 else -1 -> "active" // STATE_SIMULATED_RINGING (API 34), inlined to compile on older SDKs
            Call.STATE_HOLDING -> "holding"
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "ended"
            else -> "other:" + c.state
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
            if (state == Call.STATE_ACTIVE) minimizeCallScreen()   // dialer re-shows itself
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
        try {
            val it = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        } catch (e: Exception) { Log.w(TAG, "home intent failed: ${e.message}") }
    }
}

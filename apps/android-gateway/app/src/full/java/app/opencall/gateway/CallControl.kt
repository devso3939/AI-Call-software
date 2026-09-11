package app.opencall.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject

/**
 * Places / answers / ends real cellular calls, and reports the exact state
 * machine to the backend.
 *
 * Since 1.5.5 the exact per-call state (dialing / ringing / active / ended)
 * comes from [FullCallService] — the InCallService binding Android serves to
 * phone-capable apps — instead of the coarse PHONE_STATE broadcast. Control
 * (mute / hold / speaker / DTMF / disconnect) also goes through the Call
 * object that service holds.
 *
 * Audio bridging is acoustic: the cellular call is forced to SPEAKERPHONE and
 * the WebRTC bridge picks up mic + speaker with hardware AEC.
 */
object CallControl {
    private const val TAG = "OpenCall/Call"

    /**
     * 1.5.8: single source of truth for "the bridge wants the loudspeaker"
     * lives in AudioRoute (shared code) — WebRtcBridge's auto-speaker path
     * sets it there directly, so this delegated property always reflects the
     * real state (used by GatewayService's toggle_speaker).
     */
    var bridgeWantsSpeaker: Boolean
        get() = AudioRoute.bridgeWantsSpeaker
        private set(value) { AudioRoute.bridgeWantsSpeaker = value }

    fun hasPermissions(ctx: Context): Boolean {
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val canAnswer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        } else false
        return canCall && canAnswer
    }

    /**
     * True when the OS will actually serve us InCallService callbacks:
     * default dialer, MANAGE_OWN_CALLS "Other apps" grant, or (API 33+)
     * the CALL_COMPANION consent toggle. Without it we still can dial and
     * report coarse state via PHONE_STATE — just not exact per-call state.
     */
    fun hasInCallBinding(ctx: Context): Boolean {
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && tm.defaultDialerPackage == ctx.packageName) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ctx.getSystemService(android.telecom.TelecomManager::class.java)
                    ?.let { true } == true) {
                // API 33+: READ_PHONE_NUMBERS-style check is not it — the
                // honest test is whether Telecom grants us the role. There is
                // no public query for CALL_COMPANION, so approximate:
                // MANAGE_OWN_CALLS is granted to companion apps.
                return ctx.checkSelfPermission("android.permission.MANAGE_OWN_CALLS") == PackageManager.PERMISSION_GRANTED
            }
            false
        } catch (_: Exception) { false }
    }

    /** Place a cellular call to E.164. Returns true if the dial command went out. */
    fun placeCall(ctx: Context, e164: String): Boolean {
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
            val uri = android.net.Uri.fromParts("tel", e164, null)
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "CALL_PHONE not granted — cannot dial")
                return false
            }
            FullCallService.pendingOutbound = true
            // 1.5.7 silent dialer: get home in front BEFORE the dialer's
            // InCallUI activity launches — shrinks the visible pop to a
            // brief flash at worst, and FullCallService keeps dismissing
            // from onCallAdded onward.
            FullCallService.dismissInCallUi(ctx)
            tm.placeCall(uri, null)
            Log.i(TAG, "placeCall $e164")
            true
        } catch (e: Exception) {
            Log.e(TAG, "placeCall failed", e)
            false
        }
    }

    /** Accept the currently-ringing cellular call (web app "Accept"). */
    fun answerRinging(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "ANSWER_PHONE_CALLS not granted — cannot answer")
                return false
            }
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
            tm.acceptRingingCall()
            Log.i(TAG, "answerRingingCall issued")
            true
        } catch (e: Exception) {
            Log.e(TAG, "answer failed", e)
            false
        }
    }

    /** End every call we can see (or the telecom-level endCall fallback). */
    fun endCall(ctx: Context): Boolean {
        return try {
            // Preferred: end via the Call object — precise and API-26+.
            val ours = FullCallService.calls.toList()
            if (ours.isNotEmpty()) {
                var any = false
                for (c in ours) {
                    try { c.disconnect(); any = true } catch (_: Exception) {}
                }
                if (any) { Log.i(TAG, "endCall via InCallService Call objects"); return true }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
                val ok = try { tm.endCall() } catch (_: SecurityException) { false }
                Log.i(TAG, "endCall (telecom) → $ok")
                ok
            } else {
                // API 26/27: no public endCall. Best-effort: broadcast media-button
                // headset hook (works on many builds, not guaranteed).
                val am = ctx.getSystemService(AudioManager::class.java)
                am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
                am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
                Log.w(TAG, "endCall on API < 28: headset-hook best effort")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed", e)
            false
        }
    }

    /** Answer the ringing call through the InCallService Call object (best path). */
    fun answerViaCallObject(): Boolean = try {
        val ringing = FullCallService.calls.firstOrNull { FullCallService.stateLabel(it) == "ringing" }
        if (ringing != null) {
            ringing.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
            true
        } else false
    } catch (e: Exception) { false }

    // ─────────── web-app call controls (routed via gateway_commands) ───────────

    /**
     * MUTE DIRECTIONS (1.5.10) — two different mutes, two different goals:
     *
     *  • MUTE ME (web side): the browser mutes its own mic track. Nothing
     *    leaves the laptop → nothing reaches the cellular far end. This is
     *    the primary mute and needs NO phone command at all.
     *
     *  • MUTE PHONE MIC (secondary): silences the phone's own mic so room
     *    noise around the phone can't leak into the call. Implementation:
     *      - API 34+: InCallService.setMuted — the real telecom mute.
     *      - API <34: WebRtcBridge.setBridgeMicMuted ONLY. That track is the
     *        phone's contribution to the bridge. CRITICAL: we must NOT touch
     *        AudioManager.isMicrophoneMute — the phone mic is what carries
     *        the BROWSER USER'S VOICE acoustically into the cellular call
     *        (laptop speaker → phone mic → network). Hardware-muting it
     *        would make the web user silent to the far end. (Bug in ≤1.5.9:
     *        "Mute phone" muted the laptop user's voice instead.)
     *
     *  The returned JSON records which path applied so the web UI can be
     *  honest about what happened.
     */

    /** Toggle mute on the active cellular call. Returns {muted, path} or null. */
    fun toggleMute(ctx: Context): JSONObject? = setMuted(ctx, !FullCallService.micMuted)

    /** Mute explicitly. Returns {muted, path} JSON, or null when no call live. */
    fun setMuted(ctx: Context, muted: Boolean): JSONObject? {
        return try {
            val hasLiveCall = FullCallService.calls.any {
                FullCallService.stateLabel(it) == "active" || FullCallService.stateLabel(it) == "holding"
            }
            if (!hasLiveCall) return null
            var path = "bridge"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: the mute entry point lives on the InCallService, not
                // the Call object. Requires the phone to have default-dialer
                // privileges on some OEM builds; bridge-mic fallback covers the rest.
                try { FullCallService.instance?.setMuted(muted); path = "telecom" } catch (_: Exception) {}
            }
            // API < 34 (or telecom failed): mute ONLY the bridge's mic track —
            // never the hardware mic (see the mute-directions note above).
            if (path != "telecom") WebRtcBridge.setBridgeMicMuted(muted)
            FullCallService.micMuted = muted
            JSONObject().put("muted", muted).put("path", path)
        } catch (e: Exception) { null }
    }

    /** Hold / unhold the active call. */
    fun setHeld(held: Boolean): Boolean? = try {
        val active = FullCallService.calls.firstOrNull { FullCallService.stateLabel(it) == "active" } ?: return null
        if (held) active.hold() else active.unhold()
        held
    } catch (e: Exception) { null }

    /** Send a DTMF digit (0-9, *, #, A-D) on the active call. */
    fun sendDtmf(digit: Char): Boolean = try {
        val active = FullCallService.calls.firstOrNull { FullCallService.stateLabel(it) == "active" } ?: return false
        active.playDtmfTone(digit)
        Thread.sleep(120)
        active.stopDtmfTone()
        true
    } catch (e: Exception) { false }

    /** Force the active call onto the loudspeaker (acoustic bridge needs it). */
    fun speakerOn(ctx: Context) {
        // 1.5.8: AudioRoute.speakerOn sets the shared bridgeWantsSpeaker flag
        // (single source of truth), so the InCallService re-assert engages
        // no matter who requested the speaker.
        AudioRoute.speakerOn(ctx)
    }

    fun speakerOff(ctx: Context) {
        AudioRoute.speakerOff(ctx)
    }

    /** True when a cellular call is currently ringing on this device. */
    fun isRinging(): Boolean = FullCallService.ringing

    /** True when a cellular call is currently active (or held). */
    fun isActive(): Boolean = FullCallService.active
}

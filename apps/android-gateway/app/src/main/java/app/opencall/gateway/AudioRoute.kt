package app.opencall.gateway

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Speaker routing for the WebRTC audio bridge (shared by BOTH flavors).
 *
 * The acoustic path works like this: the phone's speaker plays the far end,
 * the microphone picks it up (hardware AEC removes the echo), which is how a
 * "bridge" device relays audio without touching the telephony stack — so the
 * lite flavor needs exactly the same logic as the full gateway.
 *
 * 1.5.7 — the AudioManager-only flip often LOST to telephony on modern
 * Android: the telecom stack owns the audio session and reverts the route,
 * so the call stayed on the earpiece and the browser heard nothing. Fix:
 * when the full InCallService is bound (default-dialer / companion grant),
 * route through CallAudioState — the supported, reliable path — and only
 * fall back to AudioManager otherwise. ALSO: delay the flip until the call
 * is actually ACTIVE (telecom ignores route changes pre-CONNECT).
 */
object AudioRoute {

    private const val TAG = "OpenCall/Audio"

    /** Speaker ON so the WebRTC bridge can hear the call audio. */
    fun speakerOn(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            am.mode = AudioManager.MODE_IN_CALL
            am.isMicrophoneMute = false
            // 1.5.7: prefer the telecom-controlled route when we can. This is
            // the ONLY route that reliably sticks on API 34+ where telephony
            // owns the audio session.
            tryInCallSpeaker(true)
            if (!inCallRouteApplied) {
                // No InCallService binding (lite flavor or pre-grant) →
                // AudioManager is the only lever we have.
                am.isSpeakerphoneOn = true
            }
            Log.i(TAG, "speaker ON (inCallRouteApplied=$inCallRouteApplied)")
        } catch (e: Exception) { Log.e(TAG, "speakerOn failed", e) }
    }

    fun speakerOff(ctx: Context) {
        try {
            tryInCallSpeaker(false)
            if (!inCallRouteApplied) {
                val am = ctx.getSystemService(AudioManager::class.java) ?: return
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) { /* fine */ }
    }

    /** True when the last tryInCallSpeaker actually routed via InCallService. */
    @Volatile private var inCallRouteApplied: Boolean = false

    /**
     * Best-effort telecom route flip (works only when the ICS is bound).
     *
     * 1.5.7 CI fix — this file lives in src/main (BOTH flavors compile it),
     * but FullCallService exists only in src/full, so a direct reference
     * broke the lite build ("Unresolved reference 'FullCallService'").
     * Reflection keeps the same behavior: no-op → false when the class or
     * method is absent (lite), real call when the full ICS is in.
     */
    private fun tryInCallSpeaker(on: Boolean) {
        inCallRouteApplied = try {
            val cls = Class.forName("app.opencall.gateway.FullCallService")
            val m = cls.getMethod("setSpeakerRoute", Boolean::class.javaPrimitiveType)
            m.invoke(null, on) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }
}

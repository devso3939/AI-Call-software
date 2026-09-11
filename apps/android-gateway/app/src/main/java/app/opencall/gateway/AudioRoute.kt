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
 *
 * 1.5.8 — THREE more holes closed for "no voice on the computer":
 *
 *  1. The WebRTC bridge flipped the speaker but never told FullCallService,
 *     so the "re-assert speaker after ACTIVE" safety net never ran (the
 *     bridgeWantsSpeaker flag stayed false). Result: telecom reverted the
 *     route to earpiece a second later and the call went silent again.
 *     The flag is now set HERE via reflection, so every speakerOn/speakerOff
 *     caller (bridge included) gets the re-assert protection.
 *
 *  2. The first ACTIVE-state re-assert fired only ONCE (+350 ms) and the
 *     AudioManager fallback was never re-applied on the lite path. Both
 *     re-asserts now retry the FULL route application.
 *
 *  3. Hardware AEC on many devices cancels the phone's OWN speaker output
 *     out of its own microphone — which is exactly the acoustic path the
 *     bridge depends on. When a cellular call is active, the mic is captured
 *     with echoCancellation DISABLED (see WebRtcBridge), letting the
 *     loudspeaker→mic hop work while the browser handles echo on its side.
 */
object AudioRoute {

    private const val TAG = "OpenCall/Audio"

    /** Mirrors CallControl.bridgeWantsSpeaker (full flavor) so the InCallService re-assert engages. */
    @Volatile var bridgeWantsSpeaker: Boolean = false

    /**
     * 1.5.10 — the phone's in-call (STREAM_VOICE_CALL) volume is the volume
     * the BROWSER USER'S voice plays at through the loudspeaker. If the user
     * keeps their phone at 30% in-call volume, the laptop user is inaudible
     * to the cellular far end even though everything else works — and it
     * looks like "the laptop microphone doesn't reach the call". During a
     * bridged call we raise the stream to max (saving the user's value) and
     * restore it when the bridge closes.
     */
    @Volatile private var savedVoiceVolume: Int? = null

    private fun am(ctx: Context): AudioManager? =
        ctx.getSystemService(AudioManager::class.java)

    private fun raiseVoiceVolume(ctx: Context) {
        val a = am(ctx) ?: return
        try {
            if (savedVoiceVolume == null) savedVoiceVolume = a.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val max = a.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (a.getStreamVolume(AudioManager.STREAM_VOICE_CALL) < max) {
                a.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
                Log.i(TAG, "voice-call volume → max (was ${savedVoiceVolume})")
            }
        } catch (_: Exception) {}
    }

    private fun restoreVoiceVolume(ctx: Context) {
        val v = savedVoiceVolume ?: return
        savedVoiceVolume = null
        try {
            am(ctx)?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, v, 0)
        } catch (_: Exception) {}
    }

    /** Speaker ON so the WebRTC bridge can hear the call audio. */
    fun speakerOn(ctx: Context) {
        bridgeWantsSpeaker = true
        try {
            val a = am(ctx) ?: return
            a.mode = AudioManager.MODE_IN_CALL
            a.isMicrophoneMute = false
            // 1.5.10: the bridge is acoustic — the loudspeaker has to be loud
            // enough for the mic to pick the browser user's voice up.
            raiseVoiceVolume(ctx)
            // 1.5.7: prefer the telecom-controlled route when we can. This is
            // the ONLY route that reliably sticks on API 34+ where telephony
            // owns the audio session.
            tryInCallSpeaker(true)
            if (!inCallRouteApplied) {
                // No InCallService binding (lite flavor or pre-grant) →
                // AudioManager is the only lever we have.
                a.isSpeakerphoneOn = true
            }
            Log.i(TAG, "speaker ON (inCallRouteApplied=$inCallRouteApplied)")
        } catch (e: Exception) { Log.e(TAG, "speakerOn failed", e) }
    }

    fun speakerOff(ctx: Context) {
        bridgeWantsSpeaker = false
        try {
            tryInCallSpeaker(false)
            if (!inCallRouteApplied) {
                val a = am(ctx) ?: return
                a.isSpeakerphoneOn = false
                a.mode = AudioManager.MODE_NORMAL
            }
            // 1.5.10: give the phone its volume back
            restoreVoiceVolume(ctx)
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

    /**
     * 1.5.8 — complete re-assertion pass, safe to call repeatedly: the
     * telecom path (when bound) AND the AudioManager fallback together.
     * Used by the delayed re-asserts after the call goes ACTIVE, because a
     * single flip right after connect is still often reverted by the OEM
     * audio stack a beat later.
     */
    fun reassertSpeaker(ctx: Context) {
        if (!bridgeWantsSpeaker) return
        try {
            val a = am(ctx) ?: return
            a.mode = AudioManager.MODE_IN_CALL
            a.isMicrophoneMute = false
            // 1.5.10: keep the bridge loud — OEM stacks sometimes reset the
            // stream volume mid-call along with the route.
            raiseVoiceVolume(ctx)
            tryInCallSpeaker(true)
            if (!inCallRouteApplied) a.isSpeakerphoneOn = true
            Log.i(TAG, "speaker re-asserted (inCallRouteApplied=$inCallRouteApplied)")
        } catch (_: Exception) {}
    }
}

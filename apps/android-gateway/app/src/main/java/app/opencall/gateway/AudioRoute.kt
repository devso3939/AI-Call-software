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
 */
object AudioRoute {
    private const val TAG = "OpenCall/Audio"

    /** Speaker ON so the WebRTC bridge can hear the call audio. */
    fun speakerOn(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            am.mode = AudioManager.MODE_IN_CALL
            am.isSpeakerphoneOn = true
            am.isMicrophoneMute = false
            Log.i(TAG, "speaker ON")
        } catch (e: Exception) { Log.e(TAG, "speakerOn failed", e) }
    }

    fun speakerOff(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) { /* fine */ }
    }
}

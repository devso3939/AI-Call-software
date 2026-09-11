package app.opencall.gateway

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 1.5.14 — transparent pass-through dialer activity.
 *
 * Its ONLY job is to make the app ELIGIBLE for the system "Default phone
 * app" role (ROLE_DIALER): Android requires a manifest-level ACTION_DIAL
 * handler before it will ever offer the role. Without it, the role screen
 * launched from "Set as phone app" never listed OpenCall, the role stayed
 * ungranted, telecom never bound our InCallService, and the cellular call's
 * audio stayed pinned to the earpiece — which is why the far end's voice
 * never reached the loudspeaker, the mic, the browser, or the recording.
 *
 * Behavior when it actually fires (user taps a dial: link and this app is
 * the default dialer): forward to the stock dialer and finish immediately.
 * Theme.NoDisplay means nothing flashes on screen.
 */
class DialDuckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uri: Uri? = intent?.data
            val forward = Intent(Intent.ACTION_DIAL)
            if (uri != null) forward.data = uri
            forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // AUDIT FIX: when OpenCall IS the default dialer, resolution can
            // pick THIS activity again → launch loop. Exclude ourselves and
            // only forward when a DIFFERENT dialer actually resolves.
            val target = forward.resolveActivity(packageManager)
            if (target != null && target.packageName != packageName) {
                startActivity(forward)
            }
        } catch (_: Exception) {
            // No other dialer on the device (we'd be the only one) — nothing
            // sensible to forward to; just exit silently.
        }
        finish()
    }
}

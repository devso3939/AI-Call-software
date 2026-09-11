package app.opencall.gateway

import android.util.Log

/**
 * 1.5.10a — src/main → src/full bridge for the CallMask honesty signals.
 *
 * WebRtcBridge (src/main, compiled into BOTH flavors) now tells the CallMask
 * overlay (src/full only) when a WebRTC bridge starts/stops, so the overlay
 * copy stays truthful:
 *
 *   bridge up   → "This call is running from your computer"
 *   bridge gone → back to the handset copy while the cellular call lives on
 *
 * A direct CallMask reference from src/main would break the lite build
 * (same reason AudioRoute reflects into FullCallService) — so this shim
 * reflects and silently no-ops when the class is absent (lite flavor).
 */
object CallMaskCompat {
    private const val TAG = "OpenCall/MaskCompat"

    fun bridgeStarted() {
        try {
            Class.forName("app.opencall.gateway.CallMask")
                .getMethod("onBridgeStarted")
                .invoke(null)
        } catch (_: Throwable) {
            // lite flavor — no CallMask class, nothing to update
        }
    }

    fun bridgeEnded() {
        try {
            Class.forName("app.opencall.gateway.CallMask")
                .getMethod("onBridgeEnded")
                .invoke(null)
        } catch (_: Throwable) {
            // lite flavor — no CallMask class, nothing to update
        }
    }
}

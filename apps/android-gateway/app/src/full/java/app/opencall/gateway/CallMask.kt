package app.opencall.gateway

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/**
 * 1.5.10 — the REAL answer to "the phone dialer keeps popping up".
 *
 * History of the losing battle: TelecomManager.placeCall() ALWAYS launches the
 * stock InCallUI, and everything we tried to dismiss it afterwards loses the
 * race on modern Android:
 *
 *   • ACTION_MAIN/CATEGORY_HOME ("press Home") — on Android 10+ a background
 *     app's startActivity is silently dropped (background-activity-start ban).
 *     The SYSTEM_ALERT_WINDOW permission grants an exemption, but OEM builds
 *     (Samsung, Xiaomi) still throttle the launches, so the dialer wins many
 *     rounds of our 100 ms re-assert loop.
 *   • Even when Home wins, the stock dialer RE-SHOWS itself on every call
 *     state change (dialing → ringing → active) — 12 s of suppression is not
 *     enough, and an endless loop would burn battery forever.
 *
 * The robust move is to stop racing entirely: draw a full-screen overlay ON TOP
 * of the dialer. The dialer can launch all it wants — the user sees the mask.
 * The overlay lives for the WHOLE call (added on first call, removed when the
 * last call ends), so there is no per-state race to lose.
 *
 * The mask is deliberately a real screen, not black glass: it says what is
 * happening, and it is TAP-THROUGH so it can never trap the user.
 *
 * 1.5.10a — the copy is now call-shape-aware (honesty regression fix):
 *   • Shown initially as "Waiting for the computer to take this call…" — at
 *     onCallAdded time we don't yet know whether the web UI will bridge.
 *   • When a WebRTC bridge actually starts (WebRtcBridge → CallMaskCompat →
 *     onBridgeStarted) the body swaps to "This call is running from your
 *     computer".
 *   • If the call goes ACTIVE with NO bridge (user answered/talks on the
 *     handset), the body swaps to "Call active on this phone" instead of
 *     wrongly claiming the call runs from the computer.
 *   • If the bridge drops mid-call (browser closed) the body reverts to the
 *     handset copy while the call continues.
 *   • hide() resets the bridged flag so the next call starts clean.
 *
 * Requires SYSTEM_ALERT_WINDOW ("Display over other apps") — declared in the
 * manifest since 1.5.8 and granted from MainActivity's Background-calls card.
 */
object CallMask {

    private const val TAG = "OpenCall/Mask"

    /** View mutations must happen on the thread that created the view. */
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var view: LinearLayout? = null
    @Volatile private var wm: WindowManager? = null
    @Volatile private var body: TextView? = null
    @Volatile private var number: String? = null

    /** 1.5.10a — true once a WebRTC bridge is up for this call. */
    @Volatile private var bridged: Boolean = false

    private fun waitingBody(n: String?): String =
        "Waiting for the computer to take this call…\n\n" +
        (n?.let { "→ $it\n" } ?: "") +
        "You can leave the phone face down."

    private fun bridgedBody(n: String?): String =
        "This call is running from your computer.\n" +
        "The phone is just the SIM gateway — talk and control everything in the web app.\n\n" +
        (n?.let { "→ $it\n" } ?: "") +
        "You can leave the phone face down."

    private fun handsetBody(n: String?): String =
        "Call active on this phone.\n" +
        "You can mute or hang up from the web app — or just talk normally.\n\n" +
        (n?.let { "→ $it\n" } ?: "")

    private fun allowed(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Exception) { false }

    /** Swap the body text on the main thread (the overlay view is attached there). */
    private fun swapTo(text: String) {
        main.post {
            try { body?.text = text } catch (_: Exception) {}
        }
    }

    /**
     * 1.5.10a — a WebRTC bridge started for this call. Called from WebRtcBridge
     * (src/main, any flavor) via CallMaskCompat reflection.
     */
    @JvmStatic
    @Synchronized
    fun onBridgeStarted() {
        bridged = true
        if (view != null) swapTo(bridgedBody(number))
    }

    /**
     * 1.5.10a — the bridge went away (browser closed, ICE failed, call ended).
     * If the cellular call is still active the honest copy is the handset one;
     * if the call is ending the mask will be hidden by onCallRemoved anyway.
     */
    @JvmStatic
    @Synchronized
    fun onBridgeEnded() {
        bridged = false
        if (view != null && FullCallService.active) swapTo(handsetBody(number))
    }

    /**
     * 1.5.10a — the call went ACTIVE and no bridge ever started: the user is
     * talking on the handset. FullCallService calls this directly (same flavor).
     */
    @JvmStatic
    @Synchronized
    fun setHandsetActive() {
        if (bridged) return          // the bridge owns the copy — don't clobber it
        if (view != null) swapTo(handsetBody(number))
    }

    /** True while a bridge is up (FullCallService gates the handset copy on this). */
    @JvmStatic
    fun isBridged(): Boolean = bridged

    /**
     * Show the mask. Safe to call repeatedly — keeps the existing mask.
     * Returns true when a mask is on screen afterwards.
     */
    @Synchronized
    fun show(ctx: Context, number: String?): Boolean {
        if (view != null) {
            // AUDIT FIX: refresh the number when the mask is already up —
            // show() is called on every onCallAdded, and a second call in
            // the same mask lifetime (e.g. a call-waiting swap) used to
            // leave the OLD number on screen for the NEW call.
            if (number != this.number) {
                this.number = number
                swapTo(if (bridged) bridgedBody(number) else waitingBody(number))
            }
            return true
        }
        if (!allowed(ctx)) return false        // no overlay grant — silent skip
        try {
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            this.number = number

            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xF2101800.toInt())   // deep green-black, matches the app
                isClickable = false
                isFocusable = false
            }
            val title = TextView(ctx).apply {
                text = "📞  OpenCall"
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad / 2)
            }
            val bodyTv = TextView(ctx).apply {
                // 1.5.10a — start honest: we don't know yet whether the
                // computer will take this call or the user will.
                text = if (bridged) bridgedBody(number) else waitingBody(number)
                setTextColor(0xFFB8C4B0.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(pad, 0, pad, pad)
            }
            val unhide = TextView(ctx).apply {
                text = "OpenCall hides the dialer during calls — the screen returns when the call ends."
                setTextColor(0xFF6B7A66.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(pad, pad / 2, pad, pad * 2)
            }
            box.addView(title)
            box.addView(bodyTv)
            box.addView(unhide)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // NOT_TOUCHABLE → taps pass through to whatever is underneath
                // (the launcher / dialer), so the phone can never be trapped.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }

            manager.addView(box, lp)
            view = box
            wm = manager
            body = bodyTv
            Log.i(TAG, "call mask shown${if (bridged) " (bridged)" else ""}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "show failed: ${e.message}")
            return false
        }
    }

    /** Remove the mask. Safe to call when nothing is shown. */
    @Synchronized
    fun hide(ctx: Context) {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null
        wm = null
        body = null
        number = null
        bridged = false          // 1.5.10a — next call starts with a clean slate
        Log.i(TAG, "call mask hidden")
    }

    /** True while the mask is on screen. */
    fun isShowing(): Boolean = view != null
}

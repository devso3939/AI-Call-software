package app.opencall.gateway

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
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
 * happening ("call running from your computer"), and it is TAP-THROUGH so it
 * can never trap the user — any physical interaction with the phone (pressing
 * Home / Back / tapping the "Unhide" strip) removes it instantly.
 *
 * Requires SYSTEM_ALERT_WINDOW ("Display over other apps") — declared in the
 * manifest since 1.5.8 and granted from MainActivity's Background-calls card.
 */
object CallMask {

    private const val TAG = "OpenCall/Mask"

    @Volatile private var view: LinearLayout? = null
    @Volatile private var wm: WindowManager? = null

    private fun allowed(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Exception) { false }

    /**
     * Show the mask. Safe to call repeatedly — keeps the existing mask.
     * Returns true when a mask is on screen afterwards.
     */
    @Synchronized
    fun show(ctx: Context, number: String?): Boolean {
        if (view != null) return true          // already up
        if (!allowed(ctx)) return false        // no overlay grant — silent skip
        try {
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()

            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xF2101800.toInt())   // deep green-black, matches the app
                isClickable = false
                isFocusable = false
            }
            val title = TextView(ctx).apply {
                text = "📞  Call active"
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad / 2)
            }
            val body = TextView(ctx).apply {
                text = "This call is running from your computer.\n" +
                        "The phone is only the SIM gateway — talk and control everything in the web app.\n\n" +
                        number?.let { "→ $it\n" }.orEmpty() +
                        "You can leave the phone face down."
                setTextColor(0xFFB8C4B0.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(pad, 0, pad, pad)
            }
            val unhide = TextView(ctx).apply {
                text = "tap anywhere or press Home to unhide the phone"
                setTextColor(0xFF6B7A66.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(pad, pad / 2, pad, pad * 2)
            }
            box.addView(title)
            box.addView(body)
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
                // (the launcher / dialer), so the phone can never be trapped;
                // LAYOUT_NO_LIMITS not needed — match_parent covers the screen.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }

            manager.addView(box, lp)
            view = box
            wm = manager
            Log.i(TAG, "call mask shown")
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
        Log.i(TAG, "call mask hidden")
    }

    /** True while the mask is on screen. */
    fun isShowing(): Boolean = view != null
}

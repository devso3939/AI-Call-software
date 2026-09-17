package app.opencall.gateway

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 1.5.29 — shared programmatic design system, v2.
 *
 * The whole UI is built in code (no XML layouts), so this file is the
 * equivalent of a theme + component library. v2 ports the WEB APP's design
 * language 1:1 (same pattern, same feel):
 *   • deep-navy canvas with a soft mint/cyan radial glow at the top
 *   • translucent blurred-feel cards (18dp radius, hairline stroke, soft shadow)
 *   • mint→cyan GRADIENT primary buttons with a glow shadow (web .btn-primary)
 *   • outlined ghost buttons (web .btn-ghost), soft danger buttons
 *   • 12dp rounded inputs with a mint focus ring (web .input:focus)
 *   • springy press feedback everywhere (cubic-bezier(.22,1.2,.36,1) feel
 *     via OvershootInterpolator — the web --spring curve)
 *   • staggered cardin entrances, tinted toasts with a glowing edge,
 *     breathing status pill, sliding gradient underline on section tabs
 *
 * Both flavors (GATEWAY + BRIDGE) pull from here so the two APKs look like
 * one product. No Jetpack Compose, no extra dependencies — plain views +
 * ViewPropertyAnimator, graceful on API 26+.
 */
object Ui {

    // ---- palette (1:1 with the web app :root) ----
    const val BG = 0xFF070B14.toInt()          // web --ink950 canvas
    const val CARD = 0xE6111827.toInt()        // web --ink900 rgba(17,24,39,.72)→~90% opaque
    const val CARD_HI = 0xFF16233B.toInt()     // hovered/elevated card
    const val STROKE = 0xFF243043.toInt()      // web --ink700 hairline
    const val INK = 0xFFE5EAF3.toInt()         // web body text
    const val INK_DIM = 0xFF94A3B8.toInt()     // web .muted
    const val INK_FAINT = 0xFF64748B.toInt()   // web .tiny
    const val MINT = 0xFF34D399.toInt()        // web --mint400
    const val MINT_DEEP = 0xFF10B981.toInt()   // web --mint
    const val CYAN = 0xFF22D3EE.toInt()        // web --cyan
    const val RED = 0xFFF87171.toInt()         // web .pill.failed color
    const val AMBER = 0xFFFBBF24.toInt()       // web .pill.queued color

    fun dp(c: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    /**
     * Web-app canvas: deep navy base + soft mint/cyan radial glows at the
     * top and a faint violet one at the bottom (the exact background stack
     * from the web app's body, ported as a LayerDrawable of radials).
     */
    fun canvas(c: Context): android.graphics.drawable.Drawable {
        val w = c.resources.displayMetrics.widthPixels
        fun radial(size: Int, color: Int): GradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(color, 0x00000000),
        ).apply {
            shape = GradientDrawable.RADIAL
            gradientRadius = dp(c, size).toFloat()
            setColors(intArrayOf(color, 0x00000000))
        }
        val mintGlow = radial(dp(c, 420), 0x2410B981.toInt()) // rgba(16,185,129,.14)
        val cyanGlow = radial(dp(c, 420), 0x1A22D3EE.toInt()) // rgba(34,211,238,.10)
        val violetGlow = radial(dp(c, 380), 0x14A78BFA.toInt()) // rgba(167,139,250,.08)
        return LayerDrawable(arrayOf(mintGlow, cyanGlow, violetGlow)).apply {
            setId(0, 1); setId(1, 2); setId(2, 3)
            // mint glow: top-left
            setLayerInset(0, -dp(c, 90), -dp(c, 160), dp(c, w) - dp(c, 240), dp(c, 300))
            // cyan glow: top-right
            setLayerInset(1, dp(c, w) - dp(c, 240), -dp(c, 160), -dp(c, 90), dp(c, 300))
            // violet glow: bottom-center
            setLayerInset(2, dp(c, 40), dp(c, 900), dp(c, 40), -dp(c, 60))
        }
    }

    // ---- spring motion (the web --spring curve, as an interpolator) ----
    fun spring(): OvershootInterpolator = OvershootInterpolator(1.6f)

    // ---- drawables ----

    /** Web-app card: translucent navy surface + hairline stroke, 18dp radius. */
    fun card(c: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(CARD)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, 18).toFloat()
            setStroke(dp(c, 1), STROKE)
        }

    /** Mint→cyan gradient pill for primary buttons (web .btn-primary). */
    fun gradPill(c: Context, colors: IntArray, radiusDp: Int = 999): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, radiusDp).toFloat()
        }

    /** Outlined pill for ghost buttons (web .btn-ghost). */
    fun pill(c: Context, fill: Int, stroke: Int, radiusDp: Int = 999): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, radiusDp).toFloat()
            setStroke(dp(c, 1), stroke)
        }

    /** Card / pill wrapped in a ripple so every touch feels alive. */
    fun ripple(content: android.graphics.drawable.Drawable, tint: Int): RippleDrawable =
        RippleDrawable(
            android.content.res.ColorStateList.valueOf(tint),
            content,
            null,
        )

    /** The soft glow shadow under primary buttons (web box-shadow). */
    fun glowElevation(v: View, c: Context, color: Int = 0x4D10B981.toInt()) {
        try {
            val shadow = GradientDrawable().apply {
                setColor(0x01000000) // nearly invisible; only carries the shadow tint
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(c, 14).toFloat()
            }
            v.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            v.elevation = dp(c, 4).toFloat()
            // tint the shadow via a layered outline drawable
            v.background = ripple(
                LayerDrawable(arrayOf(shadow, (v.background as? GradientDrawable) ?: gradPill(c, intArrayOf(MINT_DEEP, CYAN)))),
                0x33FFFFFF,
            )
        } catch (_: Exception) { /* elevation tint is cosmetic — never crash */ }
    }

    // ---- components ----

    /**
     * The ONE button style, matching the web:
     *  primary → mint→cyan gradient pill + glow shadow + dark text
     *  ghost   → outlined pill, light text
     *  danger  → soft red-tinted pill (not a flat alarm-red block)
     * All get ripple + springy press (scale .96 → overshoot back).
     */
    fun button(
        c: Context,
        label: String,
        primary: Boolean = true,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): Button = Button(c).apply {
        text = label
        isAllCaps = false
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = -0.01f
        setPadding(dp(c, 22), dp(c, 13), dp(c, 22), dp(c, 13))
        // Strip the MaterialComponents default tinted background so our
        // custom ripple shows through.
        backgroundTintList = null
        includeFontPadding = false
        when {
            danger -> {
                setTextColor(0xFFFCA5A5.toInt())
                background = ripple(pill(c, 0x3EDC2626.toInt(), 0x66F87171), 0x33FFFFFF)
            }
            primary -> {
                setTextColor(0xFF04110C.toInt())
                background = ripple(gradPill(c, intArrayOf(MINT_DEEP, CYAN)), 0x40FFFFFF)
                // web .btn-primary glow: elevation carries the depth
                stateListAnimator = null
                elevation = dp(c, 5).toFloat()
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            else -> {
                setTextColor(0xFFCBD5E1.toInt())
                background = ripple(pill(c, 0x14FFFFFF, STROKE), 0x33FFFFFF)
            }
        }
        // springy press: scale down on touch, spring back with overshoot
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(spring()).start()
            }
            false // let the click handler still fire
        }
        setOnClickListener { onClick() }
    }

    /** Rounded single-line input with a mint focus ring (web .input:focus). */
    fun input(c: Context, hint: String, inputType: Int): EditText =
        EditText(c).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 15f
            setTextColor(INK)
            setHintTextColor(INK_FAINT)
            setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
            background = card(c).apply {
                cornerRadius = dp(c, 12).toFloat()
                setColor(0xCC141B29.toInt()) // web rgba(20,27,41,.8)
            }
            backgroundTintList = null
            // mint focus ring: border brightens on focus, like the web
            setOnFocusChangeListener { v, hasFocus ->
                (v.background as? GradientDrawable)?.setStroke(
                    dp(c, 1),
                    if (hasFocus) 0xFF10B981.toInt() else STROKE,
                )
            }
        }

    /** Section title inside a card. */
    fun cardTitle(c: Context, text: String, pad: Int, big: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            textSize = if (big) 16.5f else 12.5f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = if (big) -0.01f else 0.06f
            isAllCaps = !big
            setTextColor(if (big) INK else INK_FAINT)
            isSingleLine = false
            setPadding(pad, pad, pad, dp(c, 8))
        }

    /** Body text. */
    fun body(c: Context, text: String = "", size: Float = 13.5f, dim: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            textSize = size
            setTextColor(if (dim) INK_DIM else INK)
            setLineSpacing(dp(c, 3).toFloat(), 1f)
        }

    /** Horizontal divider between card sections. */
    fun divider(c: Context, pad: Int): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1),
        ).apply { setMargins(pad, dp(c, 8), pad, dp(c, 8)) }
        background = GradientDrawable().apply {
            setColor(STROKE)
            shape = GradientDrawable.RECTANGLE
        }
    }

    /** Web-style step chip: "✓ permanent — never expires" etc. */
    fun chip(c: Context, text: String, mark: String = "✓"): TextView = TextView(c).apply {
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        text = "$mark  $text"
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        background = pill(
            c,
            0x1A10B981.toInt(), // rgba(16,185,129,.10)
            0x4710B981.toInt(), // rgba(16,185,129,.28)
        )
        setTextColor(MINT)
    }

    /**
     * Live status pill: colored dot + text, breathing while running.
     * Matches the web .healthdot + .pill styling.
     */
    fun statusDot(c: Context, running: Boolean): TextView = TextView(c).apply {
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        background = pill(
            c,
            if (running) 0xFF0B2B21.toInt() else 0xFF2B1414.toInt(),
            if (running) MINT else RED,
        ).apply { cornerRadius = dp(c, 999).toFloat() }
    }

    /** Copy chip: dashed border + mono value (web .copychip). */
    fun copyChip(c: Context, label: String, value: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9))
            background = ripple(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(c, 10).toFloat()
                    setColor(0xCC141B29.toInt())
                    setStroke(dp(c, 1), 0x5994A3B8.toInt())
                },
                0x33FFFFFF,
            )
            setOnClickListener { onClick() }
            // springy press like buttons
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(spring()).start()
                }
                false
            }
        }
        val lbl = TextView(c).apply {
            text = label
            textSize = 11f
            setTextColor(INK_FAINT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, dp(c, 8), 0)
        }
        val val_ = TextView(c).apply {
            text = value
            textSize = 13f
            setTextColor(INK)
            setTypeface(Typeface.MONOSPACE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val icon = TextView(c).apply {
            text = "⧉"
            textSize = 13f
            setTextColor(MINT)
            setPadding(dp(c, 8), 0, 0, 0)
        }
        row.addView(lbl)
        row.addView(val_)
        row.addView(icon)
        return row
    }

    // ---- animations ----

    /**
     * Entrance animation: slide up + fade + slight scale, staggered by index
     * (the web cardin keyframe, ported).
     */
    fun animateIn(v: View, index: Int = 0) {
        v.alpha = 0f
        v.translationY = dp(v.context, 14).toFloat()
        v.scaleX = 0.985f
        v.scaleY = 0.985f
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L + (index * 30L))
            .setStartDelay((index * 55L))
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** Cross-fade a section in/out (used when pairing state flips). */
    fun crossfade(show: View, hide: View) {
        hide.animate().alpha(0f).setDuration(160).withEndAction {
            hide.visibility = View.GONE
            show.alpha = 0f
            show.translationY = dp(show.context, 8).toFloat()
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        }.start()
    }

    /**
     * Pulsing "live" dot: 1s scale pulse, infinite. Attach to a small View
     * (or a TextView with a bullet) to make "RUNNING" feel alive.
     */
    fun pulse(v: View) {
        v.animate().setDuration(0).start() // reset
        val anim = ScaleAnimation(
            1f, 1.25f, 1f, 1.25f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = 900
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = DecelerateInterpolator()
        }
        v.startAnimation(anim)
    }

    fun stopPulse(v: View) { v.clearAnimation() }

    /** New log line: quick slide-in from the left so the log feels live. */
    fun slideInLog(v: View) {
        v.alpha = 0f
        v.translationX = dp(v.context, -16).toFloat()
        v.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Success flash: green glow ring expanding outward (web .testok). */
    fun successFlash(v: View) {
        val grow = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.02f, 1f)
        val growY = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.02f, 1f)
        AnimatorSet().apply {
            playTogether(grow, growY)
            duration = 500
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    // ---- toasts (web-style tinted toasts with a glowing edge) ----

    /**
     * Tinted toast matching the web .toast-ok / .toast-err: dark tinted
     * surface, colored border, mint/red text. Falls back to a plain toast
     * if view inflation fails on some OEM skin.
     */
    fun toast(c: Context, msg: String, ok: Boolean = true) {
        try {
            val t = Toast.makeText(c, msg, Toast.LENGTH_LONG)
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11))
                background = pill(
                    c,
                    if (ok) 0xEB061A13.toInt() else 0xEB1E0808.toInt(),
                    if (ok) 0x8034D399.toInt() else 0x80F87171.toInt(),
                    radiusDp = 14,
                )
            }
            val mark = TextView(c).apply {
                text = if (ok) "✓" else "✕"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (ok) MINT else RED)
                setPadding(0, 0, dp(c, 10), 0)
            }
            val txt = TextView(c).apply {
                text = msg
                textSize = 13f
                setTextColor(if (ok) 0xFFA7F3D0.toInt() else 0xFFFECACA.toInt())
            }
            box.addView(mark)
            box.addView(txt)
            t.view = box
            t.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, dp(c, 48))
            t.show()
        } catch (_: Exception) {
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
        }
    }
}

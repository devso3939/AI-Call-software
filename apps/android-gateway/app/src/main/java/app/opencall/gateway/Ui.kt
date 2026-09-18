package app.opencall.gateway

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1.5.26 — shared programmatic design system.
 *
 * The whole UI is built in code (no XML layouts), so this file is the
 * equivalent of a theme + component library: one palette, one card style,
 * one button style, one entrance animation. Both flavors (GATEWAY + BRIDGE)
 * pull from here so the two APKs look like the same product.
 *
 * Everything degrades gracefully on old devices: minSdk 26 means we always
 * have RippleDrawable / GradientDrawable; the entrance animations are plain
 * ViewPropertyAnimator (API 12+) and View.animate (API 12+), no Jetpack
 * Compose, no extra dependencies.
 */
object Ui {

    // ---- palette (matches the web app: deep navy + mint accent) ----
    const val BG = 0xFF0B1220.toInt()          // window background
    const val CARD = 0xFF111C2E.toInt()        // card surface
    const val CARD_HI = 0xFF16233B.toInt()     // card surface, hovered/elevated
    const val STROKE = 0xFF24344F.toInt()      // card border
    const val INK = 0xFFE6EDF5.toInt()         // primary text
    const val INK_DIM = 0xFF94A3B8.toInt()     // secondary text
    const val MINT = 0xFF34D399.toInt()        // accent (buttons, live dot)
    const val MINT_DIM = 0xFF0E5A42.toInt()    // accent pressed
    const val RED = 0xFFF87171.toInt()         // danger / stop
    const val RED_DIM = 0xFF5A1414.toInt()     // danger pressed
    const val AMBER = 0xFFFBBF24.toInt()       // warnings

    fun dp(c: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    // ---- drawables ----

    /** Rounded card background with a subtle 1px stroke. */
    fun card(c: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(CARD)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, 16).toFloat()
            setStroke(dp(c, 1), STROKE)
        }

    /** Rounded pill background for buttons. */
    fun pill(c: Context, fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, 999).toFloat()
            setStroke(dp(c, 1), stroke)
        }

    /** Card / pill wrapped in a ripple so every touch feels alive. */
    fun ripple(content: android.graphics.drawable.Drawable, tint: Int): RippleDrawable =
        RippleDrawable(
            android.content.res.ColorStateList.valueOf(tint),
            content,
            null,
        )

    // ---- components ----

    /**
     * The ONE button style: mint pill (primary) or outlined pill (secondary),
     * ripple feedback, springy press animation.
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
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12))
        // Strip the MaterialComponents default tinted background so our
        // custom ripple shows through (MaterialButtons ignore setBackground
        // unless you null the MaterialButton background first).
        backgroundTintList = null
        val fill = when {
            danger -> RED_DIM
            primary -> MINT_DIM
            else -> Color.TRANSPARENT
        }
        val stroke = when {
            danger -> 0x66F87171
            primary -> 0xFF34D399.toInt()
            else -> STROKE
        }
        val textColor = when {
            danger -> 0xFFFCA5A5.toInt()
            primary -> 0xFFB7F5DF.toInt()
            else -> INK
        }
        setTextColor(textColor)
        background = ripple(pill(c, fill, stroke), 0x33FFFFFF)
        // springy press: scale down on touch, spring back on release
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
            }
            false // let the click handler still fire
        }
        setOnClickListener { onClick() }
    }

    /** Rounded single-line input. */
    fun input(c: Context, hint: String, inputType: Int): EditText =
        EditText(c).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 14f
            setTextColor(INK)
            setHintTextColor(INK_DIM)
            setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
            background = card(c).apply { cornerRadius = dp(c, 12).toFloat() }
            // The default EditText draws its own underline; disable it by
            // giving our background full control.
            backgroundTintList = null
        }

    /** Section title inside a card. */
    fun cardTitle(c: Context, text: String, pad: Int, big: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            textSize = if (big) 16f else 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (big) INK else INK_DIM)
            isSingleLine = false
            setPadding(pad, pad, pad, dp(c, 6))
        }

    /** Body text. */
    fun body(c: Context, text: String = "", size: Float = 13f, dim: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text
            textSize = size
            setTextColor(if (dim) INK_DIM else INK)
            setLineSpacing(dp(c, 2).toFloat(), 1f)
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

    /**
     * Live status pill: a colored dot + text, used for "RUNNING"/"stopped".
     * The dot breathes (scale pulse) when the service is running.
     */
    fun statusDot(c: Context, running: Boolean): TextView = TextView(c).apply {
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5))
        background = pill(
            c,
            if (running) 0xFF0B2B21.toInt() else 0xFF2B1414.toInt(),
            if (running) 0xFF34D399.toInt() else 0xFFF87171.toInt(),
        ).apply { cornerRadius = dp(c, 999).toFloat() }
    }

    /**
     * Entrance animation: slide up + fade in, staggered by index.
     * Call after adding a view to its parent, passing its child index.
     */
    fun animateIn(v: View, index: Int = 0) {
        v.alpha = 0f
        v.translationY = dp(v.context, 12).toFloat()
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L + (index * 40L))
            .setStartDelay((index * 50L))
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** Cross-fade a section in/out (used when pairing state flips). */
    fun crossfade(show: View, hide: View) {
        hide.animate().alpha(0f).setDuration(160).withEndAction {
            hide.visibility = View.GONE
            show.alpha = 0f
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    /**
     * Pulsing "live" dot: 1s scale pulse, infinite. Attach to a small View
     * (or a TextView with a bullet) to make "RUNNING" feel alive.
     */
    fun pulse(v: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) return
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
}

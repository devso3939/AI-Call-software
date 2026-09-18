package app.opencall.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * OpenCall Bridge — LITE flavor (zero sensitive permissions).
 *
 * Pairs the phone as an "internet bridge" device: it joins the call's WebRTC
 * room and streams microphone audio, exactly like the web app's microphone
 * tab. It does NOT touch SMS or the telephony stack — that's why this APK
 * installs without any Play Protect sensitive-permission warning.
 *
 * UI: pair code → pair → Start bridge → keep this screen open (or let the
 * foreground service run) while a call routes audio through this phone.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusPill: TextView
    private lateinit var infoBody: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairBtn: Button
    private lateinit var simInput: EditText
    private lateinit var simBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var unpairBtn: Button
    private lateinit var logBox: LinearLayout

    private val neededPerms: Array<String> get() {
        val base = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return base.toTypedArray()
    }

    private fun requestAllPermissions() {
        val missing = neededPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.5.26 — deep navy canvas behind everything (matches the web app).
        window.decorView.setBackgroundColor(Ui.BG)
        buildUi()
        requestAllPermissions()
    }

    // v1.5.33 FIX (audit RANK 2): start/stop are ASYNC — the refresh() right
    // after a tap still saw the OLD running state, so "Stop bridge" looked
    // dead right after starting (and vice versa). A 1-second ticker keeps
    // every button/pill in sync with reality while the screen is open.
    private val refresher = object : android.os.Handler(android.os.Looper.getMainLooper()) {
        override fun handleMessage(m: android.os.Message) {
            if (lifecycleActive) { refresh(); sendEmptyMessageDelayed(0, 1000) }
        }
    }
    private var lifecycleActive = false

    override fun onResume() {
        super.onResume()
        lifecycleActive = true
        refresher.sendEmptyMessage(0)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        lifecycleActive = false
        refresher.removeMessages(0)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val denied = perms.filterIndexed { i, _ -> results[i] != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) {
            toast("Denied: ${denied.joinToString { it.substringAfterLast('.') }} — mic bridge needs it")
        }
        refresh()
    }

    // ============ UI ============

    private fun buildUi() {
        val pad = Ui.dp(this, 16)

        // 1.5.26 — header + live status pill, same design language as the
        // GATEWAY flavor (shared Ui.kt).
        statusText = TextView(this).apply {
            setPadding(pad, pad, pad, Ui.dp(this@MainActivity, 8))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.INK)
            text = "OpenCall Bridge"
        }
        statusSub = TextView(this).apply {
            setPadding(pad, 0, pad, Ui.dp(this@MainActivity, 12))
            textSize = 13f
            setTextColor(Ui.INK_DIM)
        }
        statusPill = Ui.statusDot(this, false)
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, Ui.dp(this@MainActivity, 12))
        }
        pillRow.addView(statusPill)

        codeInput = Ui.input(this, "6-digit pairing code (from Devices tab)", android.text.InputType.TYPE_CLASS_NUMBER)
        pairBtn = Ui.button(this, "Pair this phone", primary = true) { doPair() }
        simInput = Ui.input(this, "Your SIM number (e.g. +995599123456)", android.text.InputType.TYPE_CLASS_PHONE)
        simBtn = Ui.button(this, "Save SIM number", primary = false) {
            val n = simInput.text.toString().trim()
            if (!n.matches(Regex("^\\+[1-9][0-9]{3,15}$"))) {
                toast("Enter the number in international format, e.g. +995599123456"); return@button
            }
            DeviceStore.saveSimNumber(this@MainActivity, n)
            log("✔ SIM number saved — shown on your Devices page")
            refresh()
        }
        startBtn = Ui.button(this, "Start bridge", primary = true) {
            if (!DeviceStore.isPaired(this@MainActivity)) { toast("Pair first"); return@button }
            // v1.5.33 FIX (audit RANK 3): Start used to silently run without
            // the mic — the bridge connected but recorded silence. Now it
            // routes to the permission flow instead of failing invisibly.
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                toast("Microphone permission is required to bridge audio — granting…")
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return@button
            }
            BridgeService.start(this@MainActivity)
            refresh()
        }
        stopBtn = Ui.button(this, "Stop bridge", primary = false, danger = true) {
            BridgeService.stop(this@MainActivity)
            refresh()
        }
        unpairBtn = Ui.button(this, "Unpair this device", primary = false, danger = true) {
            DeviceStore.clear(this@MainActivity)
            refresh()
        }
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1.5.26 — compact status body + the BRIDGE-only explainer.
        infoBody = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.INK_DIM)
            setPadding(pad, 4, pad, 4)
        }
        val bridgeNote = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.INK_DIM)
            setPadding(pad, Ui.dp(this@MainActivity, 4), pad, 0)
            text = "⚠ This is BRIDGE — tap mode only. For FULLY AUTOMATIC calls + SMS from your SIM (nothing to tap), install the separate OpenCall GATEWAY apk: github.com/devso3939/AI-Call-software"
        }

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, Ui.dp(this@MainActivity, 24))
                addView(statusText)
                addView(statusSub)
                addView(pillRow)
                addView(cardOf("Setup", codeInput, pairBtn, simInput, simBtn))
                addView(spacer())
                addView(cardOf("Bridge", startBtn, stopBtn, infoBody, bridgeNote))
                addView(spacer())
                addView(cardOf("Danger zone", unpairBtn))
                addView(TextView(this@MainActivity).apply {
                    text = "Activity log"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ui.INK_DIM)
                    setPadding(pad, pad, pad, Ui.dp(this@MainActivity, 4))
                })
                addView(logBox)
            })
        }
        setContentView(root)
        if (!reducedMotion()) {
            val l = root.getChildAt(0) as LinearLayout
            (0 until l.childCount).forEach { i -> Ui.animateIn(l.getChildAt(i), i) }
        }
    }

    /** Wrap views in a rounded card with a title. */
    private fun cardOf(title: String, vararg views: View): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@MainActivity)
            val t = Ui.dp(this@MainActivity, 6)
            setPadding(t, t, t, t)
        }
        card.addView(Ui.cardTitle(this, title, Ui.dp(this, 8), big = true))
        for (v in views) {
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            card.addView(wrap)
        }
        return card
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, Ui.dp(this@MainActivity, 12))
    }

    /** Honor the system "remove animations" accessibility setting. */
    private fun reducedMotion(): Boolean = try {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f,
        ) == 0f
    } catch (_: Exception) { false }

    private fun log(line: String) {
        runOnUiThread {
            val t = TextView(this).apply {
                text = line
                textSize = 12f
                setTextColor(Ui.INK_DIM)
                setPadding(
                    Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 2),
                    Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 2),
                )
            }
            logBox.addView(t, 0)
            if (!reducedMotion()) Ui.slideInLog(t)
            if (logBox.childCount > 30) logBox.removeViewAt(logBox.childCount - 1)
        }
    }

    // v1.5.32 FIX (audit #1): toast() from the pairing background thread
    // crashed with "Can't create handler inside thread that has not called
    // Looper.prepare()" — any pairing failure killed the BRIDGE app.
    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ============ actions ============

    private fun doPair() {
        val code = codeInput.text.toString().trim()
        if (!code.matches(Regex("^[0-9]{6}$"))) { toast("Enter the 6-digit code"); return }

        pairBtn.isEnabled = false
        Thread {
            try {
                val res = Rpc.rpc(
                    "register_gateway_device",
                    JSONObject()
                        .put("p_code", code)
                        .put("p_name", "Bridge · " + (android.os.Build.MODEL ?: "Android phone"))
                        .putOpt("p_sim_number", DeviceStore.simNumber(this)),
                )
                val devId = res?.optString("deviceId").orEmpty()
                val secret = res?.optString("deviceSecret").orEmpty()
                if (devId.isBlank() || secret.isBlank()) throw Exception("no credentials returned")

                DeviceStore.save(this, devId, secret)
                log("✔ paired — deviceId=${devId.take(8)}…")
                runOnUiThread { codeInput.setText(""); refresh() }
            } catch (e: Exception) {
                log("✘ pairing failed: ${e.message}")
                toast("Pairing failed: ${e.message}")
            } finally {
                runOnUiThread { pairBtn.isEnabled = true }
            }
        }.start()
    }

    private fun refresh() {
        val paired = DeviceStore.isPaired(this)
        val svcRunning = BridgeRunning.isRunning

        // Surface exact version + package so a screenshot can prove which build is installed.
        val pkgInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val vName = pkgInfo?.versionName ?: "?"
        val vCode = pkgInfo?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: -1L

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // 1.5.26 — header: app name + version, then the live status pill.
        statusSub.text = "v$vName · package $packageName"
        val prevPillText = statusPill.text.toString()
        val pillText = if (svcRunning) "●  RUNNING" else "○  stopped"
        statusPill.text = pillText
        statusPill.background = Ui.statusDot(this, svcRunning).background
        statusPill.setTextColor(if (svcRunning) 0xFFB7F5DF.toInt() else 0xFFFCA5A5.toInt())
        if (prevPillText != pillText && !reducedMotion()) {
            val pop = ScaleAnimation(
                0.9f, 1f, 0.9f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f,
            ).apply { duration = 220 }
            statusPill.startAnimation(pop)
        }
        // v1.5.32 FIX (audit #8): pulse was restarted on EVERY refresh() —
        // each call created a fresh infinite ScaleAnimation, which the
        // user saw as unstoppable flickering. Pulse only on state change,
        // and keep the existing animation otherwise.
        if (prevPillText != pillText) {
            if (svcRunning) Ui.pulse(statusPill) else Ui.stopPulse(statusPill)
        } else if (svcRunning && statusPill.animation == null) {
            Ui.pulse(statusPill)
        }

        // compact status body (was the old multi-line dump)
        val sim = DeviceStore.simNumber(this)
        val info = buildString {
            append("Paired: ${if (paired) "yes" else "no"} · Microphone: ${if (micOk) "OK" else "permission missing"}")
            append("\nSIM number: ${sim ?: "not set (optional)"}")
            if (paired) append("\nDevice: ${DeviceStore.deviceId(this@MainActivity)?.take(8)}…")
        }
        infoBody.text = info

        codeInput.visibility = if (paired) View.GONE else View.VISIBLE
        pairBtn.visibility = if (paired) View.GONE else View.VISIBLE
        // v1.5.32 FIX (audit #11): only overwrite the SIM field when its
        // content actually differs — re-setting the same text on every
        // refresh() used to move the cursor and fight the user typing.
        if (simInput.text.toString() != (sim ?: "")) simInput.setText(sim ?: "")
        simInput.hint = if (paired) "Your SIM number (e.g. +995599123456)" else "Pair first, then set your SIM number"
        simBtn.isEnabled = paired
        startBtn.isEnabled = paired && !svcRunning
        stopBtn.isEnabled = svcRunning
        unpairBtn.isEnabled = paired
    }
}

/** Cheap running-flag shared between service and activity (single process). */
object BridgeRunning {
    @Volatile var isRunning: Boolean = false
}

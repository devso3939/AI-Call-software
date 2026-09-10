package app.opencall.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.View
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
        buildUi()
        requestAllPermissions()
    }

    override fun onResume() { super.onResume(); refresh() }

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
        val pad = (16 * resources.displayMetrics.density).toInt()
        statusText = TextView(this).apply {
            setPadding(pad, pad, pad, pad / 2)
            textSize = 15f
        }
        codeInput = EditText(this).apply {
            hint = "6-digit pairing code (from Devices tab)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        pairBtn = button("Pair this phone") { doPair() }
        simInput = EditText(this).apply {
            hint = "Your SIM number (e.g. +995599123456)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        simBtn = button("Save SIM number") {
            val n = simInput.text.toString().trim()
            if (!n.matches(Regex("^\\+[1-9][0-9]{3,15}$"))) {
                toast("Enter the number in international format, e.g. +995599123456"); return@button
            }
            DeviceStore.saveSimNumber(this@MainActivity, n)
            log("✔ SIM number saved — shown on your Devices page")
            refresh()
        }
        startBtn = button("Start bridge") {
            if (!DeviceStore.isPaired(this@MainActivity)) { toast("Pair first"); return@button }
            BridgeService.start(this@MainActivity)
            refresh()
        }
        stopBtn = button("Stop bridge") {
            BridgeService.stop(this@MainActivity)
            refresh()
        }
        unpairBtn = button("Unpair this device") {
            DeviceStore.clear(this@MainActivity)
            refresh()
        }
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(statusText)
                addView(codeInput)
                addView(pairBtn)
                addView(simInput)
                addView(simBtn)
                addView(startBtn)
                addView(stopBtn)
                addView(unpairBtn)
                addView(TextView(this@MainActivity).apply {
                    text = "Activity log"
                    setPadding(pad, pad, pad, 4)
                })
                addView(logBox)
            })
        }
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setPadding(16, 8, 16, 8)
            setOnClickListener { onClick() }
        }

    private fun log(line: String) {
        runOnUiThread {
            val t = TextView(this).apply {
                text = line
                textSize = 12f
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(), 2,
                    (16 * resources.displayMetrics.density).toInt(), 2,
                )
            }
            logBox.addView(t, 0)
            if (logBox.childCount > 30) logBox.removeViewAt(logBox.childCount - 1)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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
        val sb = StringBuilder()
        sb.append("OpenCall Bridge v$vName ($vCode)\n")
        sb.append("package: $packageName\n\n")
        sb.append("Paired: ${if (paired) "yes" else "no"}\n")
        sb.append("Bridge service: ${if (svcRunning) "RUNNING" else "stopped"}\n")
        sb.append("Microphone: ${if (micOk) "OK" else "permission missing"}\n")
        val sim = DeviceStore.simNumber(this)
        sb.append("SIM number: ${sim ?: "not set (optional)"}\n")
        if (paired) {
            sb.append("Device: ${DeviceStore.deviceId(this)?.take(8)}…\n")
        }
        sb.append("\n⚠ This is BRIDGE — tap mode only. Android forbids\n")
        sb.append("auto-dialing/SMS without SMS+Phone permissions.\n")
        sb.append("For FULLY AUTOMATIC calls + SMS from your SIM\n")
        sb.append("(nothing to tap), install the separate OpenCall\n")
        sb.append("GATEWAY apk: github.com/devso3939/AI-Call-software\n")
        statusText.text = sb.toString()

        codeInput.visibility = if (paired) View.GONE else View.VISIBLE
        pairBtn.visibility = if (paired) View.GONE else View.VISIBLE
        simInput.setText(DeviceStore.simNumber(this) ?: "")
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

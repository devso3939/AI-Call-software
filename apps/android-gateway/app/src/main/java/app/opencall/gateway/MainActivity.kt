package app.opencall.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * Pairing + status screen.
 *
 * 1. Ask for all dangerous permissions up front (SMS, phone, mic).
 * 2. If not paired: type the 6-digit code shown in the web app's Devices tab
 *    → register_gateway_device() → store deviceId+secret encrypted.
 * 3. If paired: show status + start/stop the gateway foreground service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var unpairBtn: Button
    private lateinit var logBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ============ permissions ============

    private val neededPerms: Array<String> get() {
        // READ_SMS / READ_PHONE_NUMBERS intentionally dropped (restricted
        // categories) — incoming SMS uses the SMS_RECEIVED PDU broadcast and
        // the SIM number lookup was non-essential.
        val base = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            base.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
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

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val denied = perms.filterIndexed { i, _ -> results[i] != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) {
            toast("Denied: ${denied.joinToString { it.substringAfterLast('.') }} — gateway features will fail")
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
        startBtn = button("Start gateway") {
            if (!DeviceStore.isPaired(this@MainActivity)) { toast("Pair first"); return@button }
            GatewayService.start(this@MainActivity)
            refresh()
        }
        stopBtn = button("Stop gateway") {
            GatewayService.stop(this@MainActivity)
            refresh()
        }
        unpairBtn = button("Unpair this device") {
            // Server-side removal happens from the web app; here we just forget credentials.
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
                        .put("p_name", android.os.Build.MODEL ?: "Android phone"),
                )
                val devId = res?.optString("deviceId").orEmpty()
                val secret = res?.optString("deviceSecret").orEmpty()
                if (devId.isBlank() || secret.isBlank()) throw Exception("no credentials returned")

                DeviceStore.save(this, devId, secret)
                log("✔ paired — deviceId=${devId.take(8)}…")
                runOnUiThread { codeInput.setText(""); refresh() }
                GatewayService.start(this)
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
        val svcRunning = GatewayRunning.isRunning

        // Surface exact version + package so a screenshot can prove which build is installed.
        val pkgInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val vName = pkgInfo?.versionName ?: "?"
        val vCode = pkgInfo?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: -1L

        val sb = StringBuilder()
        sb.append("OpenCall SIM Gateway v$vName ($vCode)\n")
        sb.append("package: $packageName\n\n")
        sb.append("Paired: ${if (paired) "yes" else "no"}\n")
        sb.append("Gateway service: ${if (svcRunning) "RUNNING" else "stopped"}\n")
        sb.append("Permissions: ${if (CallControl.hasPermissions(this)) "call OK" else "call perms missing"}\n")
        if (paired) {
            sb.append("Device: ${DeviceStore.deviceId(this)?.take(8)}…\n")
        }
        sb.append("\nWeb app: open the Devices tab → Create pairing code → type it here.\n")
        statusText.text = sb.toString()

        codeInput.visibility = if (paired) View.GONE else View.VISIBLE
        pairBtn.visibility = if (paired) View.GONE else View.VISIBLE
        startBtn.isEnabled = paired && !svcRunning
        stopBtn.isEnabled = svcRunning
        unpairBtn.isEnabled = paired
    }
}

/** Cheap running-flag shared between service and activity (single process). */
object GatewayRunning {
    @Volatile var isRunning: Boolean = false
}

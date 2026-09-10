package app.opencall.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Pairing + status + PERMISSION CENTER screen.
 *
 * 1.5.5 rework of the permission flow:
 *  - asks each dangerous permission SEQUENTIALLY (batched dialogs get
 *    auto-denied by some OEM builds, e.g. Samsung with Auto Blocker on);
 *  - detects "permanently denied" (dialog never shows again) and deep-links
 *    straight into the app's system Settings page instead of retrying;
 *  - renders one row per permission with granted/denied state + why it's
 *    needed, so nothing fails silently later;
 *  - ships an in-app unblock guide (Install unknown apps, Samsung Auto
 *    Blocker, Play Protect, MIUI) because those device switches are what
 *    actually blocks install + permission dialogs on the user's phones.
 */
class MainActivity : AppCompatActivity() {

    private data class PermSpec(
        val perm: String,
        val label: String,
        val why: String,
        val critical: Boolean,
    )

    private lateinit var statusText: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairBtn: Button
    private lateinit var simInput: EditText
    private lateinit var simBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var unpairBtn: Button
    private lateinit var grantBtn: Button
    private lateinit var installHelpBtn: Button
    private lateinit var permBox: LinearLayout
    private lateinit var logBox: LinearLayout

    // background-calls setup card (1.5.6)
    private lateinit var bgDialerRow: LinearLayout
    private lateinit var bgDialerText: TextView
    private lateinit var bgDialerBtn: Button
    private lateinit var bgManageBtn: Button
    private lateinit var agentToggle: Button

    private val askAttempts = mutableMapOf<String, Int>()
    private val permanentDenied = mutableSetOf<String>()
    private var permQueue: MutableList<String> = mutableListOf()
    private var permRequestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startGrantFlow() // first pass — sequential dialogs for whatever is missing
    }

    override fun onResume() {
        super.onResume()
        refresh()
        autoDetectSim() // re-try after the permission dialog closes
    }

    // ============ permission center ============

    private fun permSpecs(): List<PermSpec> {
        // READ_SMS / READ_PHONE_NUMBERS intentionally dropped (restricted
        // categories) — incoming SMS uses the SMS_RECEIVED PDU broadcast and
        // the SIM number lookup was non-essential.
        val l = mutableListOf(
            PermSpec(Manifest.permission.SEND_SMS, "Send SMS",
                "Sends your campaigns' SMS from this SIM.", true),
            PermSpec(Manifest.permission.RECEIVE_SMS, "Receive SMS",
                "Relays incoming replies to the web app.", true),
            PermSpec(Manifest.permission.READ_PHONE_STATE, "Phone state",
                "Live call status on the web app.", true),
            PermSpec(Manifest.permission.CALL_PHONE, "Place calls",
                "Dials from this SIM when you click Call.", true),
            PermSpec(Manifest.permission.RECORD_AUDIO, "Microphone",
                "Carries your voice into the call (audio bridge).", true),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            l.add(PermSpec(Manifest.permission.ANSWER_PHONE_CALLS, "Answer calls",
                "Answers incoming calls remotely.", true))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            l.add(PermSpec(Manifest.permission.POST_NOTIFICATIONS, "Notifications",
                "Keeps the 'gateway running' notification visible.", false))
        }
        return l
    }

    private fun granted(p: String): Boolean = try {
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun missingCriticalPerms(): List<PermSpec> =
        permSpecs().filter { it.critical && !granted(it.perm) }

    private fun startGrantFlow() {
        permQueue = permSpecs().map { it.perm }.filter { !granted(it) }.toMutableList()
        if (permQueue.isEmpty()) {
            log("✔ all permissions already granted")
            refresh()
            return
        }
        log("Asking for ${permQueue.size} missing permission(s)…")
        requestNextPerm()
    }

    private fun requestNextPerm() {
        val next = permQueue.firstOrNull()
        if (next == null) {
            permRequestInFlight = false
            summarizeGrantFlow()
            refresh()
            return
        }
        permRequestInFlight = true
        val spec = permSpecs().firstOrNull { it.perm == next }
        log("Asking: ${spec?.label ?: next.substringAfterLast('.')}")
        askAttempts[next] = (askAttempts[next] ?: 0) + 1
        ActivityCompat.requestPermissions(this, arrayOf(next), REQ_PERM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return

        val perm = permissions.firstOrNull()
        val result = grantResults.firstOrNull()
        permRequestInFlight = false

        if (perm != null && result != PackageManager.PERMISSION_GRANTED) {
            val askedBefore = (askAttempts[perm] ?: 1) > 1
            val willAskAgain = try {
                ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
            } catch (_: Exception) { false }
            // Dialog answered but will never show again → the only path left
            // is the system Settings page for this app.
            if (askedBefore && !willAskAgain && !permanentDenied.contains(perm)) {
                permanentDenied.add(perm)
                log("✘ ${perm.substringAfterLast('.')} permanently denied — use Open settings")
            }
        }
        if (perm != null && permQueue.firstOrNull() == perm) permQueue.removeAt(0)
        refresh()
        requestNextPerm() // advances the queue; on empty queue it summarizes + refreshes
    }

    private fun summarizeGrantFlow() {
        val missing = permSpecs().filter { !granted(it.perm) }
        if (missing.isEmpty()) {
            log("✔ all permissions granted — gateway can place calls, answer, and send SMS")
            return
        }
        val crit = missing.filter { it.critical }
        if (crit.isEmpty()) {
            log("Optional skipped: ${missing.joinToString { it.label }}")
            return
        }
        log("✘ still missing: ${crit.joinToString { it.label }}")
        if (crit.any { permanentDenied.contains(it.perm) }) {
            toast("A dialog won't appear for some permissions — tap 'Open settings' next to them")
        } else {
            toast("If a permission dialog never appears, see 'Blocked at install?' help")
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } catch (_: Exception) {
            toast("Open Settings → Apps → OpenCall Gateway manually")
        }
    }

    private fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: Exception) {
            toast("Open Settings → Security and privacy manually")
        }
    }

    // ============ background-calls setup (1.5.6) ============

    /** True when the OS treats us as a dialer (role or default-dialer pkg). */
    private fun isDefaultDialer(): Boolean = try {
        val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            tm?.defaultDialerPackage == packageName
        } else false
    } catch (_: Exception) { false }

    /** The system screen that grants the "default phone app" role. */
    private fun openDefaultDialerScreen() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER) &&
                    !rm.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)) {
                    startActivity(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
                } else {
                    toast("Already the phone app, or role unavailable — check Settings → Default apps")
                }
            } else {
                // Pre-Android-10: change-default-dialer dialog (TelecomManager
                // .createRegisterPhoneAccountIntent is not public API).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    startActivity(Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName))
                } else {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        } catch (_: Exception) {
            toast("Open Settings → Apps → Default apps → Phone app → OpenCall Gateway")
        }
    }

    /** The API 33+ "Allow managing calls" consent toggle lives here. */
    private fun openManageCallsScreen() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
        } catch (_: Exception) {
            toast("Open Settings → Apps → OpenCall Gateway → Allow managing calls")
        }
    }

    /**
     * Device-side blockers are the reason installs fail and permission
     * dialogs never show. Every path here is a system setting — nothing the
     * app can change itself, so the fix is guiding the user to the exact
     * screen.
     */
    private fun showInstallHelp() {
        val help = """
            If the APK won't install, or permission dialogs never appear, one of these device switches is blocking it. Fix the relevant one, then reopen this app and tap "Grant missing permissions".

            1) Install unknown apps (most common block)
            Settings → Apps → Special app access → Install unknown apps → choose the app you installed from (Chrome, Files, Telegram…) → Allow from this source.

            2) Samsung Auto Blocker (One UI 6+)
            Settings → Security and privacy → Auto Blocker → turn it OFF (or add OpenCall Gateway to its allowed apps). Auto Blocker silently blocks sideloaded APKs that use SMS/call permissions and can suppress the permission dialogs entirely — that's why nothing pops up.

            3) Google Play Protect
            If install says "Blocked by Play Protect": tap ⋮ More details → Install anyway.
            To stop repeat scans: Play Store → tap your profile → Play Protect → gear icon → turn off "Scan apps with Play Protect".

            4) Xiaomi / MIUI, Oppo/Realme, Huawei
            Settings → Apps → Manage apps → OpenCall Gateway → Permissions → allow everything. If install fails silently also enable "Install via USB" in Developer options.

            5) Permissions permanently denied
            If a permission row above shows ✘ and no dialog ever appears: Settings → Apps → OpenCall Gateway → Permissions → enable them manually (or tap "Open settings" on that row).

            Full illustrated guide: devso3939.github.io/AI-Call-software/install.html
        """.trimIndent()

        val d = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = help
            textSize = 13f
            setPadding(d, d / 2, d, 0)
            setTextColor(0xFFDDDDDD.toInt())
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("Blocked at install / permissions?")
            .setView(scroll)
            .setPositiveButton("Open security settings") { _, _ -> openSecuritySettings() }
            .setNeutralButton("Open app settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Close", null)
            .show()
    }

    // ============ UI ============

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        statusText = TextView(this).apply {
            setPadding(pad, pad, pad, pad / 2)
            textSize = 15f
        }

        // --- permission center rows ---
        grantBtn = button("Grant missing permissions") {
            if (permRequestInFlight) { toast("A permission dialog is already open"); return@button }
            startGrantFlow()
        }
        installHelpBtn = button("Blocked at install? Open unblock guide") { showInstallHelp() }
        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- background-calls setup card (1.5.6) ---
        val bgCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bgTitle = TextView(this).apply {
            text = "Background calls"
            textSize = 15f
            setPadding(pad, pad / 2, pad, 4)
        }
        bgDialerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bgDialerText = TextView(this).apply {
            textSize = 12f
            setPadding(pad, 0, 8, 0)
        }
        bgDialerRow.addView(bgDialerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bgDialerBtn = button("Set as phone app") { openDefaultDialerScreen() }
        bgDialerRow.addView(bgDialerBtn)

        val bgManageText = TextView(this).apply {
            textSize = 12f
            setPadding(pad, 4, pad, 0)
            text = "Allow managing calls (Android 13+): Settings → Apps → OpenCall Gateway → ⋮ → Allow managing calls. Lets the phone answer/decline/mute while the screen is off."
        }
        bgManageBtn = button("Open app info") { openManageCallsScreen() }

        agentToggle = Button(this).apply {
            setPadding(16, 8, 16, 8)
            setOnClickListener { toggleAgentMode() }
        }
        bgCard.addView(bgTitle)
        bgCard.addView(bgDialerRow)
        bgCard.addView(bgManageText)
        bgCard.addView(bgManageBtn)
        bgCard.addView(agentToggle)

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
            log("✔ SIM number saved — sent with every heartbeat")
            refresh()
        }
        autoDetectSim()
        startBtn = button("Start gateway") {
            if (!DeviceStore.isPaired(this@MainActivity)) { toast("Pair first"); return@button }
            val missing = missingCriticalPerms()
            if (missing.isNotEmpty()) {
                toast("Grant these first: ${missing.joinToString { it.label }}")
                startGrantFlow()
                return@button
            }
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
                addView(grantBtn)
                addView(installHelpBtn)
                addView(permBox)
                addView(bgCard)
                addView(codeInput)
                addView(simInput)
                addView(simBtn)
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

    private fun renderPermRows() {
        permBox.removeAllViews()
        val pad = (16 * resources.displayMetrics.density).toInt()
        for (spec in permSpecs()) {
            val isGranted = granted(spec.perm)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 2, pad, 2)
            }
            val mark = when {
                isGranted -> "✔"
                permanentDenied.contains(spec.perm) -> "✘"
                else -> "□"
            }
            val tv = TextView(this).apply {
                text = "$mark ${spec.label}\n${spec.why}"
                textSize = 12f
                setPadding(0, 0, 8, 0)
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (isGranted) {
                val ok = TextView(this).apply { text = "granted" ; textSize = 12f }
                row.addView(ok)
            } else {
                val b = if (permanentDenied.contains(spec.perm)) {
                    button("Open settings") { openAppSettings() }
                } else {
                    button("Grant") {
                        if (permRequestInFlight) { toast("A permission dialog is already open"); return@button }
                        permQueue = mutableListOf(spec.perm)
                        requestNextPerm()
                    }
                }
                row.addView(b)
            }
            permBox.addView(row)
        }
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

    /**
     * Gateway holds READ_PHONE_STATE, so we can usually read the SIM's own
     * number straight off the card and pre-fill the field — the user only
     * types anything if the carrier left line1Number blank (common on some
     * prepaid SIMs).
     */
    private fun autoDetectSim() {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return
        Thread {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                val n = tm?.line1Number?.trim().orEmpty()
                if (n.matches(Regex("^\\+[1-9][0-9]{3,15}$")) && DeviceStore.simNumber(this) == null) {
                    DeviceStore.saveSimNumber(this, n)
                    log("✔ SIM number auto-detected: $n")
                    runOnUiThread { simInput.setText(n); refresh() }
                }
            } catch (_: Exception) { /* carrier didn't expose it — manual entry still works */ }
        }.start()
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
        renderPermRows()
        renderBackgroundCard()

        val paired = DeviceStore.isPaired(this)
        val svcRunning = GatewayRunning.isRunning
        val total = permSpecs().size
        val got = permSpecs().count { granted(it.perm) }

        // Surface exact version + package so a screenshot can prove which build is installed.
        val pkgInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val vName = pkgInfo?.versionName ?: "?"
        val vCode = pkgInfo?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: -1L

        val sb = StringBuilder()
        sb.append("OpenCall SIM Gateway v$vName ($vCode)\n")
        sb.append("package: $packageName\n\n")
        sb.append("Paired: ${if (paired) "yes" else "no"}\n")
        sb.append("Gateway service: ${if (svcRunning) "RUNNING" else "stopped"}\n")
        sb.append("Permissions: $got/$total granted")
        if (got < total) sb.append(" — tap 'Grant missing permissions'")
        sb.append("\n")
        sb.append("Call controls: ${if (CallControl.hasPermissions(this)) "OK" else "call perms missing"}\n")
        sb.append("SIM number: ${DeviceStore.simNumber(this) ?: "not set"}\n")
        if (paired) {
            sb.append("Device: ${DeviceStore.deviceId(this)?.take(8)}…\n")
        }
        sb.append("\nWeb app: open the Devices tab → Create pairing code → type it here.\n")
        statusText.text = sb.toString()

        codeInput.visibility = if (paired) View.GONE else View.VISIBLE
        pairBtn.visibility = if (paired) View.GONE else View.VISIBLE
        simInput.setText(DeviceStore.simNumber(this) ?: "")
        startBtn.isEnabled = paired && !svcRunning
        stopBtn.isEnabled = svcRunning
        unpairBtn.isEnabled = paired
    }

    private companion object {
        const val REQ_PERM = 41
        const val PREF_AGENT_MODE = "agentMode"
    }

    /** Agent mode: auto-answer every inbound call, zero phone-side UI. */
    private fun agentModeOn(): Boolean =
        getSharedPreferences("gw", Context.MODE_PRIVATE).getBoolean(PREF_AGENT_MODE, false)

    private fun toggleAgentMode() {
        val now = !agentModeOn()
        getSharedPreferences("gw", Context.MODE_PRIVATE).edit().putBoolean(PREF_AGENT_MODE, now).apply()
        FullCallService.autoAnswerInbound = now
        log(if (now) "✔ agent mode ON — inbound calls are answered automatically"
            else "agent mode OFF — inbound calls wait for your Answer tap in the web app")
        renderBackgroundCard()
    }

    private fun renderBackgroundCard() {
        val dialer = isDefaultDialer()
        bgDialerText.text = if (dialer)
            "✔ Set as phone app — full background control: answer, decline, mute, hold while the screen stays off."
        else
            "Optional but recommended: make OpenCall Gateway the phone app (Settings → Default apps → Phone). All call control then runs in the background — no dialer UI, screen can stay off."
        bgDialerBtn.visibility = if (dialer) View.GONE else View.VISIBLE

        val on = agentModeOn()
        FullCallService.autoAnswerInbound = on
        agentToggle.text = if (on) "Agent mode: ON (tap to disable auto-answer)"
                           else "Agent mode: OFF (tap to auto-answer all calls)"
    }
}

/** Cheap running-flag shared between service and activity (single process). */
object GatewayRunning {
    @Volatile var isRunning: Boolean = false
}

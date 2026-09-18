package app.opencall.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var statusSub: TextView
    private lateinit var statusPill: TextView
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

    // 1.5.17 — SMS Gateway credentials card: the phone itself creates the
    // username/password that third-party services (e.g. SmartBookly) use
    // to send SMS through this SIM via the cloud API.
    private lateinit var gwText: TextView
    private lateinit var gwChips: LinearLayout
    // 1.5.29 — rebuilds the copy-chips only when credentials actually change,
    // so refresh() doesn't flicker the chips on every onResume().
    private var gwChipKey: String = ""
    private lateinit var gwUserInput: EditText
    private lateinit var gwCreateBtn: Button
    private lateinit var gwRotateBtn: Button
    private lateinit var gwStatusBtn: Button
    private lateinit var gwTestBtn: Button

    // 1.5.26 — compact device status body (paired/permissions/SIM/device id).
    private lateinit var infoCardBody: TextView

    // 1.5.8 — "Display over other apps" row: grants SYSTEM_ALERT_WINDOW,
    // which EXEMPTS the app from the Android 10+ background-activity-start
    // ban. Without it the silent-dialer fallback (pressing Home over the
    // stock dialer while the gateway is backgrounded) is silently blocked.
    private lateinit var bgOverlayText: TextView
    private lateinit var bgOverlayBtn: Button

    private val askAttempts = mutableMapOf<String, Int>()
    private val permanentDenied = mutableSetOf<String>()
    private var permQueue: MutableList<String> = mutableListOf()
    private var permRequestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.5.29 — web-app canvas: deep navy + soft mint/cyan radial glows.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.background = Ui.canvas(this)
        buildUi()
        // 1.5.25 — don't fire a stack of permission dialogs before the user
        // has read a single word about what this app does. First launch shows
        // a short explainer; "Continue" starts the sequential grant flow,
        // "Later" defers (the rows stay tappable, onResume re-triggers).
        val p = getSharedPreferences("gw", Context.MODE_PRIVATE)
        if (p.getBoolean("permExplainerShown", false)) {
            startGrantFlow()
        } else {
            p.edit().putBoolean("permExplainerShown", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Why these permissions?")
                .setMessage(
                    "OpenCall Gateway turns this phone into an SMS + call relay controlled from your web app:\n\n" +
                        "• SMS — send campaigns and relay replies from your SIM\n" +
                        "• Phone — place calls and report live call status\n" +
                        "• Notifications — alert you when a call comes in\n\n" +
                        "Permissions are requested ONE at a time (some phones auto-deny batched dialogs). " +
                        "Nothing leaves your gateway except your own traffic."
                )
                .setPositiveButton("Continue") { _, _ -> startGrantFlow() }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 1.5.20 AUTO-START: whenever the app is opened and the phone is
        // paired, make sure the gateway service is running. This is what
        // went wrong today: the service had died (reboot / Android kill),
        // the phone stopped heartbeating, and third-party sends sat
        // "queued" forever. Opening the app now ALWAYS revives the gateway.
        if (DeviceStore.isPaired(this)) GatewayService.start(this)
        refresh()
        autoDetectSim() // re-try after the permission dialog closes
        // 1.5.13 — after the user returns from the "Display over other
        // apps" screen (launched by Start gateway), the grant flow has
        // already run in onCreate; nothing to resume here. But if the
        // app was freshly opened straight into that screen, ensure the
        // sequential dialogs fire — startGrantFlow() is idempotent when
        // everything is already granted.
        if (permQueue.isEmpty() && !permRequestInFlight && missingCriticalPerms().isNotEmpty()) {
            startGrantFlow()
        }
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
     * 1.5.8 — the system screen for "Display over other apps"
     * (SYSTEM_ALERT_WINDOW). Holding this permission exempts the app from
     * the Android 10+ background-activity-start ban, which is what lets the
     * silent-dialer fallback keep pressing Home over the stock dialer while
     * the gateway runs in the background.
     */
    private fun hasOverlayPermission(): Boolean = try {
        Settings.canDrawOverlays(this)
    } catch (_: Exception) { false }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                toast("Open Settings → Apps → Special app access → Display over other apps")
            }
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

    private lateinit var setupSection: LinearLayout
    private lateinit var pairedSection: LinearLayout

    /** Wrap views in a rounded card with a title and vertical padding. */
    private fun cardOf(title: String?, vararg views: View): LinearLayout {
        val pad = Ui.dp(this, 16)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@MainActivity)
            val t = Ui.dp(this@MainActivity, 14)
            setPadding(t, t, t, t)
        }
        if (title != null) card.addView(Ui.cardTitle(this, title, pad / 2, big = true))
        for (v in views) {
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            card.addView(wrap)
        }
        return card
    }

    private fun buildUi() {
        val pad = Ui.dp(this, 16)

        // 1.5.29 — HERO header, modeled on the web app's brand block:
        // gradient logo tile + product name + version line, then the live
        // breathing status pill on its own row.
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, Ui.dp(this@MainActivity, 20), pad, 0)
        }
        val logo = TextView(this).apply {
            text = "📞"
            textSize = 17f
            gravity = Gravity.CENTER
            val tile = Ui.gradPill(this@MainActivity, intArrayOf(Ui.MINT_DEEP, Ui.CYAN), radiusDp = 12)
            background = tile
            val ts = Ui.dp(this@MainActivity, 42)
            layoutParams = LinearLayout.LayoutParams(ts, ts)
            elevation = Ui.dp(this@MainActivity, 6).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val heroTitles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 12), 0, 0, 0)
        }
        statusText = TextView(this).apply {
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
            setTextColor(Ui.INK)
            text = "OpenCall Gateway"
            setPadding(0, 0, 0, Ui.dp(this@MainActivity, 2))
        }
        statusSub = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.INK_FAINT)
        }
        heroTitles.addView(statusText)
        heroTitles.addView(statusSub)
        heroRow.addView(logo)
        heroRow.addView(heroTitles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        statusPill = Ui.statusDot(this, false)
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, Ui.dp(this@MainActivity, 14), pad, Ui.dp(this@MainActivity, 16))
        }
        pillRow.addView(statusPill)

        // --- permission center rows ---
        grantBtn = Ui.button(this, "Grant missing permissions", primary = true) {
            if (permRequestInFlight) { toast("A permission dialog is already open"); return@button }
            startGrantFlow()
        }
        installHelpBtn = Ui.button(this, "Blocked at install? Open unblock guide", primary = false) { showInstallHelp() }
        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- background-calls setup card (1.5.6) ---
        val bgCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bgDialerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bgDialerText = TextView(this).apply {
            textSize = 12f
            setPadding(pad, 0, 8, 0)
        }
        bgDialerRow.addView(bgDialerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bgDialerBtn = Ui.button(this, "Set as phone app", primary = false) { openDefaultDialerScreen() }
        bgDialerRow.addView(bgDialerBtn)

        val bgManageText = TextView(this).apply {
            textSize = 12f
            setPadding(pad, 4, pad, 0)
            text = "Allow managing calls (Android 13+): Settings → Apps → OpenCall Gateway → ⋮ → Allow managing calls. Lets the phone answer/decline/mute while the screen is off."
        }
        bgManageBtn = Ui.button(this, "Open app info", primary = false) { openManageCallsScreen() }

        // 1.5.8 — overlay grant row (needed for the silent-dialer fallback)
        bgOverlayText = TextView(this).apply {
            textSize = 12f
            setPadding(pad, 4, pad, 0)
        }
        bgOverlayBtn = Ui.button(this, "Allow display over other apps", primary = false) { openOverlaySettings() }

        agentToggle = Ui.button(this, "Agent mode", primary = false) { toggleAgentMode() }
        bgCard.addView(bgDialerRow)
        bgCard.addView(bgManageText)
        bgCard.addView(bgManageBtn)
        bgCard.addView(bgOverlayText)
        bgCard.addView(bgOverlayBtn)
        bgCard.addView(agentToggle)

        // 1.5.29 — gateway card, rebuilt to match the web SMS Gateway tab:
        // health dot + status line up top, one-tap COPY CHIPS for username
        // and password (tap → clipboard → toast), a "permanent" chip, then
        // the actions in a clear order: create → show → test → rotate.
        gwText = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.INK)
            setPadding(pad, 4, pad, 4)
        }
        infoCardBody = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.INK_DIM)
            setPadding(pad, 4, pad, 4)
        }
        gwChips = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        gwUserInput = Ui.input(this, "Choose a username (e.g. smartbookly)", android.text.InputType.TYPE_CLASS_TEXT)
        gwCreateBtn = Ui.button(this, "⚡ Create gateway credentials", primary = true) { doGatewaySetup() }
        // 1.5.27 — rotation is now a separate, explicit, confirmed action.
        // The create button itself NEVER breaks an existing connection
        // (server 024 KEEP mode: re-running it just re-verifies + re-binds).
        gwRotateBtn = Ui.button(this, "🔄 Rotate password (breaks current)", primary = false, danger = true) { doGatewaySetup(rotate = true) }
        gwStatusBtn = Ui.button(this, "📋 Show my credentials", primary = false) { doGatewayStatus() }
        // 1.5.28 — one-tap proof that the bond is alive: read-only server
        // check that the credentials still authenticate (sends nothing,
        // rotates nothing). Offline phone ≠ broken connection.
        gwTestBtn = Ui.button(this, "⚡ Test connection", primary = false) { doGatewayTest() }

        codeInput = Ui.input(this, "6-digit pairing code (from Devices tab)", android.text.InputType.TYPE_CLASS_NUMBER)
        pairBtn = Ui.button(this, "Pair this phone", primary = true) { doPair() }
        simInput = Ui.input(this, "Your SIM number (e.g. +995599123456)", android.text.InputType.TYPE_CLASS_PHONE)
        simBtn = Ui.button(this, "Save SIM number", primary = false) {
            val n = simInput.text.toString().trim()
            if (!n.matches(Regex("^\\+[1-9][0-9]{3,15}$"))) {
                toast("Enter the number in international format, e.g. +995599123456"); return@button
            }
            DeviceStore.saveSimNumber(this@MainActivity, n)
            log("✔ SIM number saved — sent with every heartbeat")
            refresh()
        }
        autoDetectSim()
        startBtn = Ui.button(this, "Start gateway", primary = true) {
            if (!DeviceStore.isPaired(this@MainActivity)) { toast("Pair first"); return@button }
            val missing = missingCriticalPerms()
            if (missing.isNotEmpty()) {
                toast("Grant these first: ${missing.joinToString { it.label }}")
                startGrantFlow()
                return@button
            }
            // 1.5.13 — the silent-dialer grants are NOT optional: without
            // "Display over other apps" the stock dialer pops up on the
            // phone during every web-placed call. Launch the overlay
            // settings FIRST and abort the start; the toast explains what
            // to do. After the user returns (onResume), the grant flow
            // auto-continues with the phone-app role, and a second tap on
            // "Start gateway" (now fully granted) goes straight through.
            if (!hasOverlayPermission()) {
                toast("One more thing: allow \"Display over other apps\" so calls run in the background (then tap Start again)")
                openOverlaySettings()
                return@button
            }
            // 1.5.13 — phone-app role: launches the system role dialog when
            // not held. The gateway still starts — CallControl works without
            // the role, only the ICS-bound silence path doesn't.
            if (!isDefaultDialer()) openDefaultDialerScreen()
            GatewayService.start(this@MainActivity)
            refresh()
        }
        stopBtn = Ui.button(this, "Stop gateway", primary = false, danger = true) {
            GatewayService.stop(this@MainActivity)
            refresh()
        }
        unpairBtn = Ui.button(this, "Unpair this device", primary = false, danger = true) {
            // Server-side removal happens from the web app; here we just forget credentials.
            DeviceStore.clear(this@MainActivity)
            refresh()
        }
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1.5.25 — two top-level sections, toggled by refresh():
        //  • setupSection (unpaired): the one-time pairing path.
        //  • pairedSection (paired): permissions, background calls, gateway
        //    credentials, SIM number, start/stop, unpair.
        // 1.5.26 — each section's content is grouped into rounded cards and
        // the whole section cross-fades when pairing state flips.
        setupSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 1.5.29 — scannable step chips instead of a dense paragraph: the
        // web app's stepchip pattern, so connecting takes one glance.
        val stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            "Open the web app → Devices tab",
            "Tap Create pairing code",
            "Type the 6 digits here",
        ).forEachIndexed { i, step ->
            stepsBox.addView(Ui.chip(this, step, mark = "${i + 1}"))
            if (i < 2) stepsBox.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, Ui.dp(this@MainActivity, 6))
            })
        }
        stepsBox.setPadding(pad, Ui.dp(this@MainActivity, 4), pad, Ui.dp(this@MainActivity, 8))
        setupSection.addView(
            cardOf(
                "Setup",
                codeInput,
                pairBtn,
                stepsBox,
                installHelpBtn,
            ),
        )

        pairedSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pairedSection.addView(cardOf(null, grantBtn, permBox))
        pairedSection.addView(spacer())
        pairedSection.addView(cardOf("Background calls", bgCard))
        pairedSection.addView(spacer())
        pairedSection.addView(
            cardOf(
                "📡 SMS Gateway (for other apps/services)",
                gwText,
                gwChips,
                gwUserInput,
                gwCreateBtn,
                gwStatusBtn,
                gwTestBtn,
                gwRotateBtn,
                simInput,
                simBtn,
                startBtn,
                stopBtn,
                infoCardBody,
            ),
        )
        pairedSection.addView(spacer())
        pairedSection.addView(cardOf("Danger zone", unpairBtn))

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, Ui.dp(this@MainActivity, 24))
                addView(heroRow)
                addView(pillRow)
                addView(setupSection)
                addView(pairedSection)
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
        // 1.5.26 — staggered entrance: header first, then each card slides
        // up + fades in with a small delay. Skipped when the OS asks for
        // reduced motion.
        if (!reducedMotion()) {
            val l = root.getChildAt(0) as LinearLayout
            (0 until l.childCount).forEach { i -> Ui.animateIn(l.getChildAt(i), i) }
        }
        refresh()
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

    private fun renderPermRows() {
        permBox.removeAllViews()
        val pad = Ui.dp(this, 16)
        for (spec in permSpecs()) {
            val isGranted = granted(spec.perm)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, Ui.dp(this@MainActivity, 6), pad, Ui.dp(this@MainActivity, 6))
            }
            // 1.5.26 — colored state mark: mint check, red cross, dim square.
            val mark = when {
                isGranted -> "✔"
                permanentDenied.contains(spec.perm) -> "✘"
                else -> "□"
            }
            val markColor = when {
                isGranted -> Ui.MINT
                permanentDenied.contains(spec.perm) -> Ui.RED
                else -> Ui.INK_DIM
            }
            val tv = TextView(this).apply {
                text = "$mark ${spec.label}\n${spec.why}"
                textSize = 12f
                setPadding(0, 0, 8, 0)
            }
            // color just the mark by using a two-spannable text
            val span = android.text.SpannableString(tv.text)
            span.setSpan(
                android.text.style.ForegroundColorSpan(markColor),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            tv.text = span
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (isGranted) {
                val ok = TextView(this).apply {
                    text = "granted"
                    textSize = 12f
                    setTextColor(Ui.MINT)
                }
                row.addView(ok)
            } else {
                val b = if (permanentDenied.contains(spec.perm)) {
                    Ui.button(this, "Open settings", primary = false) { openAppSettings() }
                } else {
                    Ui.button(this, "Grant", primary = false) {
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
            // 1.5.26 — new log entries slide in so the log feels live.
            if (!reducedMotion()) Ui.slideInLog(t)
            if (logBox.childCount > 30) logBox.removeViewAt(logBox.childCount - 1)
        }
    }

    // CRASH FIX (1.5.18): Toast.show() from a background thread (no Looper)
    // throws "Can't create handler inside thread that has not called
    // Looper.prepare()" and kills the whole app — this is what made the app
    // close when "Create / rotate gateway credentials" hit an error. Route
    // every toast through the UI thread so it is safe from anywhere.
    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // 1.5.29 — one-tap copy for the gateway credentials chips. Puts the value
    // on the clipboard and confirms with the tinted toast so the user knows
    // exactly which credential is now ready to paste.
    private fun copyToClipboard(label: String, value: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            Ui.toast(this, "✔ $label copied — paste it into the service", ok = true)
            log("✔ $label copied to clipboard")
        } catch (e: Exception) {
            log("✘ copy failed: ${e.message}")
            toast("Couldn't copy — long-press the value to copy manually")
        }
    }

    /** Visible error dialog on the UI thread — used for gateway actions so a
     *  failure can be screenshotted instead of silently killing the app. */
    private fun errorDialog(title: String, e: Exception) {
        log("✘ $title: ${e.message}")
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(e.message ?: "unknown error")
                .setPositiveButton("OK", null)
                .show()
        }
    }

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
                // 1.5.18: dialog instead of toast so the real error is visible
                errorDialog("Pairing failed", e)
            } finally {
                runOnUiThread { pairBtn.isEnabled = true }
            }
        }.start()
    }

    /**
     * 1.5.17 — create/rotate the SMS gateway credentials (username +
     * auto-generated password) for this phone's owner, authenticated with
     * the device pairing secret. The password is shown ONCE in a dialog
     * and stored encrypted on this phone so it can be re-shown.
     *
     * 1.5.27 PERSISTENT CONNECTION (server migration 024): setup now
     * defaults to KEEP mode — if credentials already exist, the server
     * keeps them (reused=true, password=null) and only re-binds this
     * phone. Rotation requires p_rotate=true, which we send ONLY from an
     * explicit "Rotate password" action. So re-opening this screen /
     * re-pairing the phone can never break the SmartBookly connection.
     */
    private fun doGatewaySetup(rotate: Boolean = false) {
        if (!DeviceStore.isPaired(this)) { toast("Pair first"); return }
        val user = gwUserInput.text.toString().trim()
        if (!user.matches(Regex("^[a-z0-9][a-z0-9._-]{2,39}$"))) {
            toast("Username: 3-40 chars, lowercase letters/digits/dot/dash/underscore"); return
        }
        if (rotate) {
            // rotation is destructive (existing password dies) — confirm first
            AlertDialog.Builder(this)
                .setTitle("Rotate gateway password?")
                .setMessage(
                    "A NEW password will be generated and the current one will STOP working.\n\n" +
                    "Every connected service (e.g. SmartBookly) must be updated with the new password."
                )
                .setPositiveButton("Rotate") { _, _ -> doGatewaySetupRpc(rotate = true, user = user) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        doGatewaySetupRpc(rotate = false, user = user)
    }

    private fun doGatewaySetupRpc(rotate: Boolean, user: String) {
        gwCreateBtn.isEnabled = false
        gwRotateBtn.isEnabled = false
        Thread {
            try {
                val res = Rpc.rpc(
                    "gateway_setup_from_device",
                    JSONObject()
                        .put("p_device_id", DeviceStore.deviceId(this))
                        .put("p_device_secret", DeviceStore.secret(this))
                        .put("p_username", user)
                        // 024: default (false) = keep existing credentials;
                        // true only from the explicit Rotate action
                        .put("p_rotate", rotate),
                ) ?: throw Exception("empty response")
                val reused = res.optBoolean("reused", false)
                val pw = if (reused) null else res.optString("password").takeIf { it.isNotBlank() }
                if (!reused && pw != null) {
                    // only persist a NEW password — KEEP mode must not
                    // overwrite the locally stored working credentials
                    DeviceStore.saveGatewayCreds(this, res.optString("username"), pw)
                }
                log(if (reused) "✔ gateway connection verified — existing credentials kept: ${res.optString("username")}"
                    else "✔ gateway credentials ${if (rotate) "rotated" else "created"}: ${res.optString("username")}")
                runOnUiThread {
                    if (reused) {
                        AlertDialog.Builder(this)
                            .setTitle("Connection verified — nothing changed")
                            .setMessage(
                                "Your gateway credentials are ACTIVE and were kept exactly as they are.\n\n" +
                                "Username: ${res.optString("username")}\n" +
                                "(password unchanged — check \"Gateway status\" to view it)\n\n" +
                                "No need to reconnect SmartBookly — the connection stays until you stop it."
                            )
                            .setPositiveButton("Done", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(if (rotate) "Gateway password rotated" else "Gateway credentials ready")
                            .setMessage(
                                "Username: ${res.optString("username")}\n" +
                                "Password: $pw\n\n" +
                                "⚠ Copy the password NOW — enter this username + " +
                                "password in the app/service that will send SMS " +
                                "(e.g. SmartBookly)."
                            )
                            .setPositiveButton("Done", null)
                            .show()
                    }
                    refresh()
                }
            } catch (e: Exception) {
                // 1.5.18: dialog instead of toast so the real error is visible
                errorDialog("Gateway setup failed", e)
            } finally {
                runOnUiThread { gwCreateBtn.isEnabled = true; gwRotateBtn.isEnabled = true }
            }
        }.start()
    }

    /** 1.5.17 — show whether the gateway account is enabled + usage. */
    private fun doGatewayStatus() {
        if (!DeviceStore.isPaired(this)) { toast("Pair first"); return }
        Thread {
            try {
                val res = Rpc.rpc(
                    "gateway_status_from_device",
                    JSONObject()
                        .put("p_device_id", DeviceStore.deviceId(this))
                        .put("p_device_secret", DeviceStore.secret(this)),
                ) ?: throw Exception("empty response")
                val enabled = res.optBoolean("enabled")
                // 1.5.19 SYNC: show the same honest online/battery the web
                // tab and third-party APIs see (server applies a 2-minute
                // freshness rule — battery is null when the heartbeat is stale).
                val online = res.optBoolean("online")
                val batt = if (res.isNull("battery")) null else res.optInt("battery")
                // 1.5.21 UI/UX: stored credentials shown directly — the user
                // no longer has to guess where the username/password live.
                // 1.5.23 VISIBLE CREDS: the password now comes from the server
                // (stored encrypted server-side, migration 022) so it always
                // matches what SmartBookly uses, even after a web-side rotation.
                val serverPw = res.optString("password", "").takeIf { it.isNotBlank() }
                val savedUser = DeviceStore.gatewayUsername(this) ?: res.optString("username", "")
                val savedPw = serverPw ?: DeviceStore.gatewayPassword(this)
                val msg = if (enabled) {
                    "✅ Gateway is ON\n\n" +
                    "Username: ${res.optString("username")}\n" +
                    (if (savedPw != null) "Password: $savedPw\n" else "") +
                    (if (serverPw == null) "\n(password not yet stored server-side — re-save credentials once to enable this)\n" else "") +
                    "\nThis phone: ${if (online) "🟢 online" else "🔴 offline (gateway service not running — tap Start gateway)"}" +
                    (if (batt != null) " · battery $batt%" else "") + "\n" +
                    "Sent: ${res.optLong("sentTotal")} · Failed: ${res.optLong("failedTotal")}\n\n" +
                    "Paste this username + password into the service that sends SMS (e.g. SmartBookly)."
                } else "❌ Gateway is OFF\n\nTap \"⚡ Create gateway credentials\" to enable it."
                log("gateway status: ${if (enabled) "on" else "off"}")
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("SMS Gateway")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                // 1.5.18: dialog instead of toast so the real error is visible
                errorDialog("Gateway status failed", e)
            }
        }.start()
    }

    /**
     * 1.5.28 — one-tap "is my connection still good?" check.
     * Calls the read-only gateway_api_test RPC (migration 025): it
     * authenticates exactly like a real send would, but delivers nothing.
     *  • ok=true + device.online=true  → credentials valid, phone reachable.
     *  • ok=true + device.online=false → credentials VALID, phone asleep —
     *    nothing is broken; the connection self-heals on the next heartbeat.
     *  • error → the credentials themselves no longer authenticate.
     */
    private fun doGatewayTest() {
        if (!DeviceStore.isPaired(this)) { toast("Pair first"); return }
        val user = DeviceStore.gatewayUsername(this)
        if (user.isNullOrBlank()) { toast("Create gateway credentials first"); return }
        gwTestBtn.isEnabled = false
        Thread {
            try {
                val res = Rpc.rpc(
                    "gateway_api_test",
                    JSONObject()
                        .put("p_username", user)
                        .put("p_password", DeviceStore.gatewayPassword(this) ?: JSONObject.NULL)
                        .put("p_token", JSONObject.NULL),
                ) ?: throw Exception("empty response")
                val dev = res.optJSONObject("device") ?: JSONObject()
                val usage = res.optJSONObject("usage") ?: JSONObject()
                val online = dev.optBoolean("online")
                val batt = if (dev.isNull("battery")) null else dev.optInt("battery")
                val lastSeen = dev.optString("lastSeenAt", "")
                val sent = usage.optLong("sent", 0)
                val failed = usage.optLong("failed", 0)
                val msg = "✅ Connection verified — credentials are valid and permanent.\n\n" +
                    "Phone: ${if (online) "🟢 online" else "🟡 offline (asleep — the connection self-heals on the next heartbeat; nothing is broken)"}" +
                    (if (batt != null) " · battery $batt%" else "") + "\n" +
                    (if (lastSeen.isNotBlank()) "Last seen: $lastSeen\n" else "") +
                    "Lifetime: $sent sent · $failed failed\n\n" +
                    "Nothing was sent and nothing was changed — this was a read-only check."
                log("connection test: ok · phone ${if (online) "online" else "offline"}")
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("⚡ Connection test")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                log("connection test: FAILED — ${e.message}")
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("❌ Connection test failed")
                        .setMessage("The credentials no longer authenticate.\n\n${e.message}\n\nIf the password was rotated elsewhere, re-create the credentials here (KEEP mode keeps the username and re-binds the device).")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                runOnUiThread { gwTestBtn.isEnabled = true }
            }
        }.start()
    }

    private fun refresh() {
        renderPermRows()
        renderBackgroundCard()

        val paired = DeviceStore.isPaired(this)
        // 1.5.25 — GatewayRunning.isRunning is set in-process by the service,
        // but Android can silently kill+restart the process (or the flag can
        // go stale after a service restart). Cross-check with the system's
        // list of active foreground services so the Start/Stop buttons always
        // reflect reality.
        val svcRunning = GatewayRunning.isRunning || gatewayServiceAlive()

        val total = permSpecs().size
        val got = permSpecs().count { granted(it.perm) }

        // Surface exact version + package so a screenshot can prove which build is installed.
        val pkgInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val vName = pkgInfo?.versionName ?: "?"
        val vCode = pkgInfo?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: -1L

        // 1.5.26 — header: app name + version, then the live status pill.
        // The pill breathes (pulse animation) while the service runs.
        statusSub.text = "v$vName · package $packageName"
        val prevPillText = statusPill.text.toString()
        val pillText = if (svcRunning) "●  RUNNING" else "○  stopped"
        statusPill.text = pillText
        statusPill.background = Ui.statusDot(this, svcRunning).background
        statusPill.setTextColor(if (svcRunning) 0xFFB7F5DF.toInt() else 0xFFFCA5A5.toInt())
        // v1.5.30 FIX — pulse only on a real state change, never on every
        // refresh (the infinite re-pulse was the "blinking").
        if (prevPillText != pillText) {
            // state change: pop the pill so the transition is noticeable
            if (!reducedMotion()) {
                val pop = ScaleAnimation(
                    0.9f, 1f, 0.9f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                ).apply { duration = 220 }
                statusPill.startAnimation(pop)
            }
            if (svcRunning) Ui.pulse(statusPill) else Ui.stopPulse(statusPill)
        }

        // 1.5.26 — the old multi-line dump becomes a compact status card body.
        val info = buildString {
            append("Paired: ${if (paired) "yes" else "no"} · Permissions: $got/$total")
            if (got < total) append(" — tap 'Grant missing permissions'")
            append("\nCall controls: ${if (CallControl.hasPermissions(this@MainActivity)) "OK" else "call perms missing"}")
            append("\nSIM number: ${DeviceStore.simNumber(this@MainActivity) ?: "not set"}")
            if (paired) append("\nDevice: ${DeviceStore.deviceId(this@MainActivity)?.take(8)}…")
        }

        // 1.5.25 — whole-section visibility (replaces per-view toggles).
        // 1.5.26 — cross-fade when the pairing state flips.
        val targetSection = if (paired) pairedSection else setupSection
        val hiddenSection = if (paired) setupSection else pairedSection
        if (hiddenSection.visibility == View.VISIBLE && targetSection.visibility == View.GONE) {
            // state is about to flip — animate it
            if (reducedMotion()) {
                hiddenSection.visibility = View.GONE
                targetSection.visibility = View.VISIBLE
            } else {
                Ui.crossfade(targetSection, hiddenSection)
            }
        } else {
            hiddenSection.visibility = View.GONE
            targetSection.visibility = View.VISIBLE
        }
        simInput.setText(DeviceStore.simNumber(this) ?: "")
        startBtn.isEnabled = paired && !svcRunning
        stopBtn.isEnabled = svcRunning
        unpairBtn.isEnabled = paired

        // 1.5.17 — gateway card only makes sense once paired (1.5.21: clearer copy)
        // 1.5.29 — web-style health line + one-tap copy chips (web .copychip):
        // the whole connect flow is now "tap username chip → tap password chip
        // → paste into SmartBookly".
        val gwEnabled = paired && !DeviceStore.gatewayUsername(this).isNullOrBlank()
        gwText.text = if (!paired) {
            "1️⃣ Pair this phone first (type the 6-digit code below).\n2️⃣ Then create gateway credentials here."
        } else if (gwEnabled) {
            "✅ Gateway ON — paste the credentials below into the service that sends SMS (e.g. SmartBookly)."
        } else {
            "Create a username here → we generate a strong password → paste both into the service that will send SMS (e.g. SmartBookly)."
        }

        // rebuild copy chips only when the username changed (cheap guard so
        // refresh() can run on every resume without flicker)
        val chipKey = "${DeviceStore.gatewayUsername(this) ?: ""}|${DeviceStore.gatewayPassword(this) != null}"
        if (chipKey != gwChipKey) {
            gwChipKey = chipKey
            gwChips.removeAllViews()
            val u = DeviceStore.gatewayUsername(this)
            if (paired && !u.isNullOrBlank()) {
                val p = DeviceStore.gatewayPassword(this)
                val pad16 = Ui.dp(this, 16)
                gwChips.addView(Ui.copyChip(this, "username", u) { copyToClipboard("gateway username", u) })
                if (p != null) {
                    gwChips.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(1, Ui.dp(this@MainActivity, 6))
                    })
                    gwChips.addView(Ui.copyChip(this, "password", p) { copyToClipboard("gateway password", p) })
                }
                gwChips.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(1, Ui.dp(this@MainActivity, 8))
                })
                val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chipRow.addView(Ui.chip(this, "permanent — never expires"))
                gwChips.addView(chipRow)
                gwChips.setPadding(pad16, Ui.dp(this@MainActivity, 8), pad16, 0)
                gwChips.visibility = View.VISIBLE
            } else {
                gwChips.visibility = View.GONE
            }
        }

        gwUserInput.visibility = if (paired) View.VISIBLE else View.GONE
        gwCreateBtn.isEnabled = paired
        gwRotateBtn.isEnabled = paired && gwEnabled // rotate only makes sense with existing creds
        gwStatusBtn.isEnabled = paired
        gwTestBtn.isEnabled = paired && gwEnabled // 1.5.28 — test only makes sense with creds

        // keep the compact info line at the bottom of the gateway card
        infoCardBody.text = info
    }

    /**
     * 1.5.25 — ground truth for "is the gateway actually running": ask
     * ActivityManager for this app's foreground services instead of trusting
     * the static flag. Cheap (one binder call, on resume only) and fixes the
     * stale "RUNNING" / dead-button state after process death.
     */
    private fun gatewayServiceAlive(): Boolean = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val cls = GatewayService::class.java.name
        am?.getRunningServices(200)?.any { it.service.className == cls } ?: false
    } catch (_: Exception) { false }

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

    /**
     * 1.5.13 — the "Background calls" card previously said "optional".
     * It is NOT optional: without these grants the stock dialer pops up
     * on every web-placed call (the exact "it still showed up on my
     * phone like I'm making a manual call" report). The wording now
     * matches reality.
     */
    private fun renderBackgroundCard() {
        val dialer = isDefaultDialer()
        bgDialerText.text = if (dialer)
            "✔ Set as phone app — full background control: answer, decline, mute, hold while the screen stays off."
        else
            "REQUIRED: tap \"Set as phone app\" and confirm. Without it Android shows the stock dialer on every web-placed call."
        bgDialerBtn.visibility = if (dialer) View.GONE else View.VISIBLE

        // 1.5.8 — overlay grant row
        val overlayOk = hasOverlayPermission()
        bgOverlayText.text = if (overlayOk)
            "✔ Display over other apps allowed — silent dialer can dismiss the stock call screen in the background."
        else
            "REQUIRED: allow \"Display over other apps\". This is what lets the gateway hide the stock call screen while it runs in the background."
        bgOverlayBtn.visibility = if (overlayOk) View.GONE else View.VISIBLE

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

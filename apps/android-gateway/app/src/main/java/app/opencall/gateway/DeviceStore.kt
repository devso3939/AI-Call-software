package app.opencall.gateway

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Device credentials + call-signaling state, encrypted at rest.
 *
 * deviceId + deviceSecret come from register_gateway_device() during pairing;
 * they're the only proof this phone is allowed to act as a gateway.
 */
object DeviceStore {
    private const val FILE = "opencall_gateway_secure"

    // Cache the EncryptedSharedPreferences instance: creating it on every access
    // re-derives the master key and is expensive (it was being done 2+ times per
    // poll iteration). One instance per process is safe — AndroidX encryption
    // handles concurrent access internally.
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            cachedPrefs = openPrefs(ctx)
            return cachedPrefs!!
        }
    }

    /**
     * v1.5.32 FIX (audit #3): EncryptedSharedPreferences throws
     * (AEADBadTagException / SecurityException / IllegalStateException)
     * when the Android keystore master key becomes unreadable — backup
     * restore, OEM phone-clone, or system update can corrupt it. That
     * crashed the app at EVERY launch with no recovery path. Recovery:
     * wipe the corrupted store and start fresh (the user re-pairs — a
     * one-time 6-digit code — instead of being locked out forever).
     */
    private fun openPrefs(ctx: Context): SharedPreferences {
        val appCtx = ctx.applicationContext
        try {
            return createEncryptedPrefs(appCtx)
        } catch (first: Exception) {
            // wipe + one retry
            try {
                appCtx.deleteSharedPreferences(FILE)
            } catch (_: Exception) {
                // API < 24 fallback: delete the underlying files directly
                val prefsDir = java.io.File(appCtx.applicationInfo.dataDir, "shared_prefs")
                listOf("$FILE.xml").forEach { name ->
                    try { java.io.File(prefsDir, name).delete() } catch (_: Exception) {}
                }
            }
            try {
                return createEncryptedPrefs(appCtx)
            } catch (second: Exception) {
                // last resort: plain (unencrypted) prefs so the app still
                // launches; better degraded security than a crash loop
                android.util.Log.e("DeviceStore", "encrypted prefs unrecoverable, falling back to plain prefs", second)
                return appCtx.getSharedPreferences("${FILE}_plain", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(appCtx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appCtx, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun deviceId(ctx: Context): String? = prefs(ctx).getString("deviceId", null)
    fun secret(ctx: Context): String? = prefs(ctx).getString("deviceSecret", null)

    fun save(ctx: Context, deviceId: String, secret: String) {
        prefs(ctx).edit()
            .putString("deviceId", deviceId)
            .putString("deviceSecret", secret)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun isPaired(ctx: Context): Boolean =
        !deviceId(ctx).isNullOrBlank() && !secret(ctx).isNullOrBlank()

    // ---- self-reported SIM number (display only; lite flavor has no telephony
    //      access, so the user types their own number once after pairing) ----
    fun simNumber(ctx: Context): String? = prefs(ctx).getString("simNumber", null)?.takeIf { it.isNotBlank() }
    fun saveSimNumber(ctx: Context, number: String) {
        prefs(ctx).edit().putString("simNumber", number.trim()).apply()
    }

    // ---- 1.5.17 SMS gateway credentials (created on this phone, shown to
    //      the owner so they can paste them into third-party services) ----
    fun gatewayUsername(ctx: Context): String? = prefs(ctx).getString("gwUsername", null)?.takeIf { it.isNotBlank() }
    fun gatewayPassword(ctx: Context): String? = prefs(ctx).getString("gwPassword", null)?.takeIf { it.isNotBlank() }
    fun saveGatewayCreds(ctx: Context, username: String, password: String) {
        prefs(ctx).edit()
            .putString("gwUsername", username.trim())
            .putString("gwPassword", password.trim())
            .apply()
    }

    // ---- last signaling seq per call (survives service restarts) ----
    fun sigSeq(ctx: Context, callId: String): Int = prefs(ctx).getInt("sig_$callId", 0)
    fun saveSigSeq(ctx: Context, callId: String, seq: Int) {
        prefs(ctx).edit().putInt("sig_$callId", seq).apply()
    }
    fun clearSigSeq(ctx: Context, callId: String) {
        prefs(ctx).edit().remove("sig_$callId").apply()
    }
}

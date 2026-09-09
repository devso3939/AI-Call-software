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

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, masterKey,
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

    // ---- last signaling seq per call (survives service restarts) ----
    fun sigSeq(ctx: Context, callId: String): Int = prefs(ctx).getInt("sig_$callId", 0)
    fun saveSigSeq(ctx: Context, callId: String, seq: Int) {
        prefs(ctx).edit().putInt("sig_$callId", seq).apply()
    }
    fun clearSigSeq(ctx: Context, callId: String) {
        prefs(ctx).edit().remove("sig_$callId").apply()
    }
}

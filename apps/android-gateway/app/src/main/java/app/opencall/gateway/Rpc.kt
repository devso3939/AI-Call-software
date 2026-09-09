package app.opencall.gateway

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal PostgREST RPC client for the Supabase backend.
 *
 * All device RPCs are anon-callable but take (deviceId, deviceSecret) — the
 * secret only ever lives in EncryptedSharedPreferences on this phone.
 *
 * Server: https://ijrfqjxdajgoaysazxle.supabase.co
 * Key:    sb_publishable_... (publishable key, safe to embed)
 */
object Rpc {
    private const val TAG = "OpenCall/Rpc"

    // ---- override via BuildConfig at build time if needed ----
    val SUPABASE_URL: String = "https://ijrfqjxdajgoaysazxle.supabase.co"
    val SUPABASE_ANON_KEY: String =
        "sb_publishable_Af8dsVLpjtO6Mpe3zpmI9Q_odky49Fh"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)   // long-poll friendly
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Calls a PostgREST RPC function. Returns the parsed body on HTTP 2xx.
     * Throws IOException / RpcException on failure.
     */
    fun rpc(function: String, args: JSONObject): JSONObject? = rpcRaw(function, args) as? JSONObject

    /**
     * Raw result: scalar functions (returns jsonb) come back as JSONObject;
     * set-returning functions (returns table / setof) come back as JSONArray.
     * Callers MUST use rpcRaw when the function returns a set — collapsing an
     * array to element[0] would silently drop rows.
     */
    fun rpcRaw(function: String, args: JSONObject): Any? {
        val url = "${SUPABASE_URL}/rest/v1/rpc/${function}"
        val body = args.toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url(url)
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "rpc $function → HTTP ${resp.code}: ${text.take(300)}")
                throw RpcException("rpc $function failed: HTTP ${resp.code} ${text.take(200)}")
            }
            if (text.isBlank()) return null   // void functions → 204 empty
            return when {
                text.startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
        }
    }

    class RpcException(message: String) : Exception(message)
}

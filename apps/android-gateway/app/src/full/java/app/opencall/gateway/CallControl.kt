package app.opencall.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject

/**
 * Places / answers / ends real cellular calls, and reports incoming cellular
 * calls to the backend so the web app can show a banner.
 *
 * Answering uses TelecomManager.acceptRingingCall (API 26+, needs
 * ANSWER_PHONE_CALLS or being the default dialer). Dialing uses
 * TelecomManager.placeCall (works with CALL_PHONE permission).
 *
 * Audio bridging is acoustic: the cellular call goes on SPEAKERPHONE and the
 * WebRTC bridge (WebRtcBridge) picks up mic + speaker with hardware AEC.
 */
object CallControl {
    private const val TAG = "OpenCall/Call"

    fun hasPermissions(ctx: Context): Boolean {
        val tel = ctx.getSystemService(TelephonyManager::class.java) ?: return false
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val canAnswer = ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        return canCall && canAnswer
    }

    /** Place a cellular call to E.164. Returns true if the dial command went out. */
    fun placeCall(ctx: Context, e164: String): Boolean {
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
                ?: return false
            val uri = android.net.Uri.fromParts("tel", e164, null)
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "CALL_PHONE not granted — cannot dial")
                return false
            }
            tm.placeCall(uri, null)
            Log.i(TAG, "placeCall $e164")
            true
        } catch (e: Exception) {
            Log.e(TAG, "placeCall failed", e)
            false
        }
    }

    /** Accept the currently-ringing cellular call. */
    fun answerRinging(ctx: Context): Boolean {
        return try {
            if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "ANSWER_PHONE_CALLS not granted — cannot answer")
                return false
            }
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
            tm.acceptRingingCall()
            Log.i(TAG, "answerRingingCall issued")
            true
        } catch (e: Exception) {
            Log.e(TAG, "answer failed", e)
            false
        }
    }

    /** End the active (or ringing) cellular call. */
    fun endCall(ctx: Context): Boolean {
        return try {
            if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val ok = tm.endCall()
                Log.i(TAG, "endCall → $ok")
                ok
            } else {
                // API 26/27: no public endCall. Best-effort: broadcast media-button
                // headset hook (works on many builds, not guaranteed).
                val am = ctx.getSystemService(AudioManager::class.java)
                am?.dispatchMediaKeyEvent(android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
                am?.dispatchMediaKeyEvent(android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
                Log.w(TAG, "endCall on API < 28: headset-hook best effort")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed", e)
            false
        }
    }

    /** Speaker routing lives in the shared AudioRoute object (used by both flavors). */
    fun speakerOn(ctx: Context) = AudioRoute.speakerOn(ctx)

    fun speakerOff(ctx: Context) = AudioRoute.speakerOff(ctx)
}

/**
 * Watches PHONE_STATE for real incoming cellular calls.
 *
 * RINGING → report_incoming_call() (creates the 'inbound-pstn' row + banner
 *           in the web app); also wakes GatewayService so it can offer a
 *           listening bridge room.
 * OFFHOOK → update_gateway_call(answered)
 * IDLE    → update_gateway_call(ended)
 */
class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        // battery-friendly: only act on transitions that matter
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val from = incoming ?: "unknown"
                Log.i("OpenCall/CallRx", "INCOMING $from")
                Thread {
                    try {
                        val devId = DeviceStore.deviceId(context) ?: return@Thread
                        val secret = DeviceStore.secret(context) ?: return@Thread
                        val res = Rpc.rpc(
                            "report_incoming_call",
                            JSONObject()
                                .put("p_device_id", devId)
                                .put("p_secret", secret)
                                .put("p_from", from),
                        )
                        Log.i("OpenCall/CallRx", "report_incoming_call → $res")
                        // remember the room so GatewayService can bridge on demand
                        res?.optString("room")?.takeIf { it.isNotBlank() }?.let {
                            context.getSharedPreferences("gw", Context.MODE_PRIVATE)
                                .edit().putString("bridgeRoom", it).apply()
                        }
                        res?.optString("callId")?.takeIf { it.isNotBlank() }?.let {
                            context.getSharedPreferences("gw", Context.MODE_PRIVATE)
                                .edit().putString("bridgeCallId", it).apply()
                        }
                    } catch (e: Exception) {
                        Log.e("OpenCall/CallRx", "report incoming failed", e)
                    }
                }.start()
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // The human answered (or we just accepted) the cellular call.
                Thread {
                    try {
                        val devId = DeviceStore.deviceId(context) ?: return@Thread
                        val secret = DeviceStore.secret(context) ?: return@Thread
                        val callId = context.getSharedPreferences("gw", Context.MODE_PRIVATE)
                            .getString("bridgeCallId", null) ?: return@Thread
                        Rpc.rpc(
                            "update_gateway_call",
                            JSONObject()
                                .put("p_device_id", devId)
                                .put("p_secret", secret)
                                .put("p_call_id", callId)
                                .put("p_state", "answered"),
                        )
                        // speaker on so the bridge (if opened) hears the call
                        CallControl.speakerOn(context)
                    } catch (e: Exception) { Log.w("OpenCall/CallRx", "offhook report failed", e) }
                }.start()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Thread {
                    try {
                        val devId = DeviceStore.deviceId(context) ?: return@Thread
                        val secret = DeviceStore.secret(context) ?: return@Thread
                        val prefs = context.getSharedPreferences("gw", Context.MODE_PRIVATE)
                        val callId = prefs.getString("bridgeCallId", null) ?: return@Thread
                        Rpc.rpc(
                            "update_gateway_call",
                            JSONObject()
                                .put("p_device_id", devId)
                                .put("p_secret", secret)
                                .put("p_call_id", callId)
                                .put("p_state", "ended"),
                        )
                        prefs.edit().remove("bridgeCallId").remove("bridgeRoom").apply()
                        CallControl.speakerOff(context)
                    } catch (e: Exception) { Log.w("OpenCall/CallRx", "idle report failed", e) }
                }.start()
            }
        }
    }
}

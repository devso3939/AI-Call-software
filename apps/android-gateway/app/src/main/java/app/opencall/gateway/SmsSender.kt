package app.opencall.gateway

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * Sends an SMS through the SIM using SmsManager, then reports the result back
 * to the backend via gateway_report_sms().
 *
 * Flow: command `send_sms` { smsId, to, body }
 *   → sendTextMessage (with PendingIntent for send status)
 *   → on broadcast: gateway_report_sms(ok / failed)
 */
object SmsSender {
    private const val TAG = "OpenCall/Sms"

    fun send(ctx: Context, smsId: String, to: String, body: String) {
        try {
            val sm = ctx.getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            if (sm == null) {
                report(ctx, smsId, false, "SmsManager unavailable on this device")
                return
            }

            // PendingIntent so we learn the real send outcome (radio-level).
            val sentIntent = Intent("app.opencall.gateway.SMS_SENT").apply {
                setPackage(ctx.packageName)
                putExtra("smsId", smsId)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, smsId.hashCode(),
                sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Long texts: let the radio split them.
            if (body.length > 160) {
                val parts = sm.divideMessage(body)
                sm.sendMultipartTextMessage(to, null, parts, ArrayList(listOf(pi)), null)
            } else {
                sm.sendTextMessage(to, null, body, pi, null)
            }
            Log.i(TAG, "SMS dispatched → $to (smsId=$smsId)")
            // Note: we don't report "sent" yet — the broadcast confirms delivery
            // to the radio; SMSStatusReceiver finalizes.
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
            report(ctx, smsId, false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun report(ctx: Context, smsId: String, ok: Boolean, error: String? = null, providerRef: String? = null) {
        try {
            val devId = DeviceStore.deviceId(ctx) ?: return
            val secret = DeviceStore.secret(ctx) ?: return
            Rpc.rpc(
                "gateway_report_sms",
                JSONObject()
                    .put("p_device_id", devId)
                    .put("p_secret", secret)
                    .put("p_sms_id", smsId)
                    .put("p_status", if (ok) "sent" else "failed")
                    .putOpt("p_error", error?.take(200))
                    .putOpt("p_provider_ref", providerRef),
            )
            Log.i(TAG, "report sms smsId=$smsId ok=$ok err=$error")
        } catch (e: Exception) {
            Log.e(TAG, "report sms failed (will stay 'queued' server-side)", e)
        }
    }
}

/** Receives the radio-level send confirmation for SMS we dispatched. */
class SmsStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "app.opencall.gateway.SMS_SENT") return
        val smsId = intent.getStringExtra("smsId") ?: return
        when (resultCode) {
            android.app.Activity.RESULT_OK -> SmsSender.report(context, smsId, true)
            else -> SmsSender.report(
                context, smsId, false,
                "radio error code $resultCode",
            )
        }
    }
}

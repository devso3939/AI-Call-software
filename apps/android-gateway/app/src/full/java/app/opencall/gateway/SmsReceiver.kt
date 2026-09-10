package app.opencall.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject

/**
 * Fires for every SMS arriving on this SIM. Posts it to the backend via
 * gateway_receive_sms() so it shows up in the owner's SMS tab in real time.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (msgs.isEmpty()) return

            // One PDUs blob can carry multiple segments — stitch them per sender.
            val bySender = LinkedHashMap<String, StringBuilder>()
            for (m in msgs) {
                val sb = bySender.getOrPut(m.originatingAddress ?: "unknown") { StringBuilder() }
                sb.append(m.messageBody ?: "")
            }

            val devId = DeviceStore.deviceId(context) ?: return   // not paired yet
            val secret = DeviceStore.secret(context) ?: return

            for ((from, body) in bySender) {
                val text = body.toString()
                Log.i("OpenCall/SmsRx", "SMS from $from: ${text.take(40)}")
                Thread {
                    try {
                        Rpc.rpc(
                            "gateway_receive_sms",
                            JSONObject()
                                .put("p_device_id", devId)
                                .put("p_secret", secret)
                                .put("p_from", from)
                                .put("p_body", text),
                        )
                    } catch (e: Exception) {
                        // Broadcast receivers have ~10 s; retry once after a beat.
                        Log.w("OpenCall/SmsRx", "upload failed, retrying once", e)
                        try { Thread.sleep(2000); Rpc.rpc(
                            "gateway_receive_sms",
                            JSONObject()
                                .put("p_device_id", devId)
                                .put("p_secret", secret)
                                .put("p_from", from)
                                .put("p_body", text),
                        ) } catch (e2: Exception) {
                            Log.e("OpenCall/SmsRx", "upload failed for $from — SMS lost from app view", e2)
                        }
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e("OpenCall/SmsRx", "parse failed", e)
        }
    }
}

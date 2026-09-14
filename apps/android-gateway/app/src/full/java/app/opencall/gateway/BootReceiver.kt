package app.opencall.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 1.5.20 — restart the gateway service after the phone reboots.
 *
 * Before this, the service only started when the user tapped
 * "Start gateway" (or paired) — after a reboot the gateway stayed dead,
 * the phone stopped heartbeating, and third-party SMS sends (SmartBookly)
 * queued forever with no honest error. Now a reboot revives the gateway
 * automatically as soon as the device is unlocked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!DeviceStore.isPaired(context)) return
        GatewayService.start(context)
    }
}

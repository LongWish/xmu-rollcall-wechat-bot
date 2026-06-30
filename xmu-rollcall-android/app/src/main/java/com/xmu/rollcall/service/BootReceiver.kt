package com.xmu.rollcall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val enabled = context
            .getSharedPreferences(WatchSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(WatchSettingsStore.KEY_WATCH_ENABLED, false)
        if (!enabled) return

        val serviceIntent = Intent(context, WatchService::class.java).apply {
            action = WatchService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Some ROMs block foreground-service startup from boot broadcasts.
        }
    }
}

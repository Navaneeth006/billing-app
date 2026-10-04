package com.foodtruck.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Restarts the server after reboot / app update when the user left it ON. */
public class ServerBootReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP_SERVER = "com.foodtruck.kiosk.STOP_SERVER";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            boolean enabled = context.getSharedPreferences(KioskServerService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KioskServerService.PREF_SERVER_ENABLED, false);
            if (enabled) {
                ContextCompat.startForegroundService(context, new Intent(context, KioskServerService.class));
            }
        }
    }
}

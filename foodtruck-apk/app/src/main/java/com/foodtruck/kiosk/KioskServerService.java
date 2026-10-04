package com.foodtruck.kiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Foreground service that keeps the kiosk HTTP server alive while the app is
 * closed or the screen is off. Survives reboot via ServerBootReceiver when the
 * user left the server switched ON.
 */
public class KioskServerService extends Service {
    public static final String PREFS = "kiosk_prefs";
    public static final String PREF_SERVER_ENABLED = "server_enabled";
    public static final String PREF_SERVER_RUNNING = "server_running";
    public static final String PREF_SERVER_ERROR = "server_error";

    private static final String CHANNEL_ID = "kiosk_server_status";
    private static final int NOTIFICATION_ID = 4242;

    private KioskHttpServer server;
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ServerBootReceiver.ACTION_STOP_SERVER.equals(intent.getAction())) {
            getPrefs().edit().putBoolean(PREF_SERVER_ENABLED, false).apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireNetworkLocks();

        if (!getPrefs().getBoolean(PREF_SERVER_ENABLED, true)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        try {
            if (server == null) {
                server = new KioskHttpServer(this);
                server.start(NanoTimeouts.SOCKET_READ_TIMEOUT, false);
            }
            getPrefs().edit()
                    .putBoolean(PREF_SERVER_RUNNING, true)
                    .putString(PREF_SERVER_ERROR, "")
                    .apply();
        } catch (IOException | RuntimeException exception) {
            getPrefs().edit()
                    .putBoolean(PREF_SERVER_RUNNING, false)
                    .putString(PREF_SERVER_ERROR,
                            exception.getMessage() == null ? exception.toString() : exception.getMessage())
                    .apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        getPrefs().edit().putBoolean(PREF_SERVER_RUNNING, false).apply();
        releaseNetworkLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static String getLocalIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void acquireNetworkLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && (cpuWakeLock == null || !cpuWakeLock.isHeld())) {
            cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoodTruckKiosk:Server");
            cpuWakeLock.setReferenceCounted(false);
            cpuWakeLock.acquire();
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null && (wifiLock == null || !wifiLock.isHeld())) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FoodTruckKiosk:Wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseNetworkLocks() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openAppIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopServer = new Intent(this, KioskServerService.class);
        stopServer.setAction(ServerBootReceiver.ACTION_STOP_SERVER);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stopServer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String address = getLocalIpv4Address();
        String status = address.isEmpty()
                ? "Waiting for Wi-Fi · port " + KioskHttpServer.SERVER_PORT
                : "http://" + address + ":" + KioskHttpServer.SERVER_PORT;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Food Truck Kiosk server is ON")
                .setContentText(status)
                .setContentIntent(openAppIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Stop server", stopIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Kiosk server", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when this phone is hosting the kiosk");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    /** NanoHTTPD socket read timeout constant, kept local to avoid extra imports. */
    private static final class NanoTimeouts {
        static final int SOCKET_READ_TIMEOUT = 5000;
    }
}

package com.foodtruck.kiosk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;

/** Phone-side control screen: server toggle, iPad URL + QR, printer picker, admin entry. */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMISSIONS = 42;

    private Switch serverSwitch;
    private TextView statusTitle;
    private TextView statusDetail;
    private View statusDot;
    private TextView urlText;
    private ImageView qrImage;
    private TextView printerText;
    private LinearLayout batteryCard;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> runOnUiThread(this::refreshUi);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverSwitch = findViewById(R.id.serverSwitch);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        statusDot = findViewById(R.id.statusDot);
        urlText = findViewById(R.id.urlText);
        qrImage = findViewById(R.id.qrImage);
        printerText = findViewById(R.id.printerText);
        batteryCard = findViewById(R.id.batteryCard);
        Button openAdmin = findViewById(R.id.openAdmin);
        Button choosePrinter = findViewById(R.id.choosePrinter);
        Button testPrint = findViewById(R.id.testPrint);

        serverSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startServer();
            } else {
                stopServer();
            }
        });
        openAdmin.setOnClickListener(v -> startActivity(new Intent(this, AdminActivity.class)));
        choosePrinter.setOnClickListener(v -> showPrinterPicker());
        testPrint.setOnClickListener(v -> testPrint());
        batteryCard.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        SharedPreferences prefs = getSharedPreferences(KioskServerService.PREFS, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        requestNeededPermissions();
        autoStartIfEnabled();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        getSharedPreferences(KioskServerService.PREFS, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onDestroy();
    }

    private void requestNeededPermissions() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private void autoStartIfEnabled() {
        SharedPreferences prefs = getSharedPreferences(KioskServerService.PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KioskServerService.PREF_SERVER_ENABLED)) {
            // First launch: server starts ON so the truck works out of the box.
            prefs.edit().putBoolean(KioskServerService.PREF_SERVER_ENABLED, true).apply();
        }
        if (prefs.getBoolean(KioskServerService.PREF_SERVER_ENABLED, false)
                && !prefs.getBoolean(KioskServerService.PREF_SERVER_RUNNING, false)) {
            startForegroundServiceSafely();
        }
    }

    private void startServer() {
        getSharedPreferences(KioskServerService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KioskServerService.PREF_SERVER_ENABLED, true)
                .putString(KioskServerService.PREF_SERVER_ERROR, "")
                .apply();
        startForegroundServiceSafely();
        refreshUi();
    }

    private void stopServer() {
        getSharedPreferences(KioskServerService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KioskServerService.PREF_SERVER_ENABLED, false).apply();
        stopService(new Intent(this, KioskServerService.class));
        refreshUi();
    }

    private void startForegroundServiceSafely() {
        try {
            ContextCompat.startForegroundService(this, new Intent(this, KioskServerService.class));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Android blocked the server: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("SetTextI18n")
    private void refreshUi() {
        SharedPreferences prefs = getSharedPreferences(KioskServerService.PREFS, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KioskServerService.PREF_SERVER_ENABLED, false);
        boolean running = prefs.getBoolean(KioskServerService.PREF_SERVER_RUNNING, false);
        String error = prefs.getString(KioskServerService.PREF_SERVER_ERROR, "");

        serverSwitch.setOnCheckedChangeListener(null);
        serverSwitch.setChecked(enabled);
        serverSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) startServer(); else stopServer();
        });

        boolean live = enabled && running;
        statusDot.setBackgroundResource(live ? R.drawable.dot_on : R.drawable.dot_off);
        statusTitle.setText(live ? R.string.server_on : R.string.server_off);
        if (!error.isEmpty()) {
            statusDetail.setText(error);
        } else if (live) {
            statusDetail.setText("Running in background — safe to close this app");
        } else if (enabled) {
            statusDetail.setText("Starting…");
        } else {
            statusDetail.setText("Toggle to start the kiosk server");
        }

        String ip = KioskServerService.getLocalIpv4Address();
        String url = ip.isEmpty() ? "" : "http://" + ip + ":" + KioskHttpServer.SERVER_PORT;
        urlText.setText(url.isEmpty() ? "Connect this phone to Wi-Fi" : url);
        if (!url.isEmpty()) {
            renderQr(url);
            qrImage.setVisibility(View.VISIBLE);
        } else {
            qrImage.setVisibility(View.GONE);
        }

        String printer = BluetoothPrinter.selectedAddress(this);
        printerText.setText(printer.isEmpty()
                ? getString(R.string.printer_none)
                : "Selected: " + printer);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryCard.setVisibility(ignoring ? View.GONE : View.VISIBLE);
    }

    private void renderQr(String content) {
        try {
            int size = 460;
            QRCodeWriter writer = new QRCodeWriter();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            com.google.zxing.common.BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            qrImage.setImageBitmap(bitmap);
        } catch (Exception e) {
            qrImage.setVisibility(View.GONE);
        }
    }

    private void showPrinterPicker() {
        final JSONArray[] devices = {null};
        try {
            devices[0] = BluetoothPrinter.pairedDevices(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            openBluetoothSettings();
            return;
        }
        if (devices[0] == null || devices[0].length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("No paired printers")
                    .setMessage("Pair your thermal printer in Android Bluetooth settings first.")
                    .setPositiveButton("Open Bluetooth settings", (d, w) -> openBluetoothSettings())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        final String[] names = new String[devices[0].length()];
        final String[] addresses = new String[devices[0].length()];
        for (int i = 0; i < devices[0].length(); i++) {
            org.json.JSONObject o = devices[0].optJSONObject(i);
            names[i] = o == null ? "device" : o.optString("name", "device");
            addresses[i] = o == null ? "" : o.optString("address", "");
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_printer)
                .setItems(names, (dialog, which) -> {
                    try {
                        BluetoothPrinter.select(this, addresses[which]);
                        printerText.setText("Selected: " + addresses[which]);
                        Toast.makeText(this, "Printer saved", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Bluetooth settings", (d, w) -> openBluetoothSettings())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Open Bluetooth settings manually", Toast.LENGTH_SHORT).show();
        }
    }

    private void testPrint() {
        if (BluetoothPrinter.selectedAddress(this).isEmpty()) {
            Toast.makeText(this, "Pick a printer first", Toast.LENGTH_SHORT).show();
            showPrinterPicker();
            return;
        }
        Toast.makeText(this, "Sending test print…", Toast.LENGTH_SHORT).show();
        ReceiptPrinter.enqueue(this, BluetoothPrinter.testReceipt(), "test");
    }

    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                @SuppressLint("BatteryLife")
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                openAppDetails();
            }
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Permissions are optional at first run; printer features re-check them on use.
    }
}

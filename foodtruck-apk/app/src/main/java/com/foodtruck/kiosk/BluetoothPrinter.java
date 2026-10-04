package com.foodtruck.kiosk;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.UUID;

/** ESC/POS over Bluetooth SPP — lists paired devices, prints raw bytes. */
public final class BluetoothPrinter {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PREFS = "kiosk_prefs";
    public static final String KEY_PRINTER = "printer_address";

    private BluetoothPrinter() {}

    public static JSONArray pairedDevices(Context context) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IllegalStateException("This phone has no Bluetooth.");
        requireConnectPermission(context);
        JSONArray out = new JSONArray();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            JSONObject o = new JSONObject();
            o.put("name", device.getName() == null ? "Bluetooth device" : device.getName());
            o.put("address", device.getAddress());
            out.put(o);
        }
        return out;
    }

    public static String selectedAddress(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PRINTER, "");
    }

    public static void select(Context context, String address) throws Exception {
        boolean found = false;
        JSONArray devices = pairedDevices(context);
        for (int i = 0; i < devices.length(); i++) {
            if (address.equalsIgnoreCase(devices.getJSONObject(i).optString("address"))) {
                found = true;
                break;
            }
        }
        if (!found) throw new IllegalArgumentException("Pair the printer in Android Bluetooth settings first.");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PRINTER, address).apply();
    }

    public static void print(Context context, byte[] bytes) throws Exception {
        String address = selectedAddress(context);
        if (address.isEmpty()) throw new IllegalStateException("No Bluetooth printer selected. Pick one in the app.");
        requireConnectPermission(context);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            throw new IllegalStateException("Bluetooth is off. Turn it on and try again.");
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        adapter.cancelDiscovery();
        try (BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID)) {
            socket.connect();
            OutputStream out = socket.getOutputStream();
            out.write(bytes);
            out.flush();
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            socket.close();
        }
    }

    static void requireConnectPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Allow the Nearby devices / Bluetooth permission for this app in Android settings.");
        }
    }

    public static byte[] testReceipt() {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0x1b); b.write(0x40);          // init
        b.write(0x1b); b.write(0x61); b.write(1); // center
        b.write(0x1b); b.write(0x45); b.write(1); // bold
        byte[] title = "FOOD TRUCK\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.write(title, 0, title.length);
        b.write(0x1b); b.write(0x45); b.write(0);
        byte[] body = "Bluetooth test print OK\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.write(body, 0, body.length);
        cut(b);
        return b.toByteArray();
    }

    static void cut(java.io.ByteArrayOutputStream b) {
        b.write(0x0a); b.write(0x0a); b.write(0x0a);
        b.write(0x1d); b.write(0x56); b.write(0x42); b.write(0x00); // partial cut
    }

    static void writeText(java.io.ByteArrayOutputStream b, String text) {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.write(data, 0, data.length);
    }
}

package com.foodtruck.kiosk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Builds ESC/POS bytes for customer receipts and kitchen slips from an order
 * JSON object, then hands them to a background retry queue that writes them
 * to the selected Bluetooth printer. Nothing blocks the HTTP thread.
 */
public final class ReceiptPrinter {
    private static final ConcurrentLinkedQueue<PrintTask> QUEUE = new ConcurrentLinkedQueue<>();
    private static Thread worker;

    private ReceiptPrinter() {}

    public static void enqueue(Context context, byte[] bytes, String label) {
        QUEUE.add(new PrintTask(context.getApplicationContext(), bytes, label));
        synchronized (ReceiptPrinter.class) {
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(ReceiptPrinter::drain, "kiosk-printer");
                worker.start();
            }
        }
    }

    private static void drain() {
        while (true) {
            PrintTask task = QUEUE.poll();
            if (task == null) return;
            boolean ok = false;
            String error = "";
            for (int attempt = 0; attempt < 3 && !ok; attempt++) {
                try {
                    BluetoothPrinter.print(task.context, task.bytes);
                    ok = true;
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                    try { Thread.sleep(1500L * (attempt + 1)); } catch (InterruptedException ignored) {}
                }
            }
            if (!ok) android.util.Log.w("ReceiptPrinter", "Print failed (" + task.label + "): " + error);
        }
    }

    private static final class PrintTask {
        final Context context;
        final byte[] bytes;
        final String label;
        PrintTask(Context context, byte[] bytes, String label) {
            this.context = context;
            this.bytes = bytes;
            this.label = label;
        }
    }

    // ------------------------------------------------------------ ESC/POS helpers

    private static void escInit(ByteArrayOutputStream b) { raw(b, 0x1b, 0x40); }
    private static void align(ByteArrayOutputStream b, int n) { raw(b, 0x1b, 0x61, n); }
    private static void bold(ByteArrayOutputStream b, boolean on) { raw(b, 0x1b, 0x45, on ? 1 : 0); }
    private static void doubleHeight(ByteArrayOutputStream b, boolean on) { raw(b, 0x1d, 0x21, on ? 0x11 : 0x00); }

    private static void raw(ByteArrayOutputStream b, int... bytes) {
        for (int x : bytes) b.write(x);
    }

    private static void text(ByteArrayOutputStream b, String s) {
        byte[] data = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.write(data, 0, data.length);
    }

    private static void cut(ByteArrayOutputStream b) {
        raw(b, 0x0a, 0x0a, 0x0a);
        raw(b, 0x1d, 0x56, 0x42, 0x00); // partial cut
    }

    private static String line(int chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars; i++) sb.append('-');
        return sb.toString();
    }

    private static int width(Context ctx) {
        try {
            return KioskDatabase.get(ctx).getIntSetting("paper_chars", 32);
        } catch (Exception e) {
            return 32;
        }
    }

    private static String money(long paise) {
        return String.format(java.util.Locale.US, "%.2f", paise / 100.0);
    }

    private static String twoCol(String left, String right, int chars) {
        if (right.length() >= chars) return right;
        StringBuilder sb = new StringBuilder(left);
        while (sb.length() + right.length() < chars) sb.append(' ');
        return sb.append(right).toString();
    }

    // ------------------------------------------------------------ receipt builder

    public static byte[] buildReceipt(Context context, JSONObject order) {
        KioskDatabase db = KioskDatabase.get(context);
        int chars = db.getIntSetting("paper_chars", 32);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            escInit(b);
            align(b, 1);
            bold(b, true);
            text(b, db.getSetting("business_name", "FOOD TRUCK") + "\n");
            bold(b, false);
            boolean cash = "cash".equalsIgnoreCase(order.optString("paymentMethod"));
            String paymentTitle = db.getSetting(
                    cash ? "cash_receipt_title" : "upi_receipt_title",
                    cash ? "CASH" : "PAID");
            align(b, 1);
            bold(b, true);
            doubleHeight(b, true);
            text(b, paymentTitle.toUpperCase(java.util.Locale.ROOT) + "\n");
            doubleHeight(b, false);
            bold(b, false);
            String tagline = db.getSetting("tagline", "");
            if (!tagline.isEmpty()) text(b, tagline + "\n");
            String address = db.getSetting("address", "");
            if (!address.isEmpty()) text(b, address + "\n");
            align(b, 0);
            text(b, line(chars) + "\n");

            text(b, "Order #" + order.optInt("orderNumber") + "   " + timeOf(order) + "\n");
            text(b, line(chars) + "\n");

            JSONArray items = order.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    int qty = it.optInt("qty", 1);
                    text(b, twoCol(qty + " x " + it.optString("name", "Item"), money(it.optLong("totalPaise")), chars) + "\n");
                    JSONArray extras = it.optJSONArray("extras");
                    if (extras != null) {
                        for (int e = 0; e < extras.length(); e++) {
                            JSONObject ex = extras.optJSONObject(e);
                            if (ex == null) continue;
                            text(b, twoCol("  + " + ex.optString("name", "Extra"), money(ex.optLong("qty", 1) * Math.round(ex.optDouble("price", 0) * 100)), chars) + "\n");
                        }
                    }
                }
            }
            text(b, line(chars) + "\n");

            long subtotal = order.optLong("subtotalPaise");
            long tax = order.optLong("taxPaise");
            text(b, twoCol("Subtotal", money(subtotal), chars) + "\n");
            if (tax > 0) text(b, twoCol("GST", money(tax), chars) + "\n");
            align(b, 0);
            bold(b, true);
            doubleHeight(b, true);
            text(b, twoCol("TOTAL", "Rs." + money(order.optLong("totalPaise")), chars) + "\n");
            doubleHeight(b, false);
            bold(b, false);
            text(b, line(chars) + "\n");
            text(b, cash ? "Payment: CASH\n" : "Payment: UPI\n");

            String vpa = db.getSetting("vpa", "");
            if (!vpa.isEmpty() && "upi".equalsIgnoreCase(order.optString("paymentMethod"))) {
                text(b, "UPI: " + vpa + "\n");
            }
            String thanks = db.getSetting("thank_you_line", "Thank you!");
            align(b, 1);
            text(b, "\n" + thanks + "\n");
            cut(b);
        } catch (Exception ignored) {
        }
        return b.toByteArray();
    }

    // ------------------------------------------------------------ kitchen slip builder

    public static byte[] buildKitchenSlip(Context context, JSONObject order) {
        KioskDatabase db = KioskDatabase.get(context);
        int chars = Math.max(24, db.getIntSetting("paper_chars", 32));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            escInit(b);
            bold(b, true);
            doubleHeight(b, true);
            align(b, 1);
            text(b, "KITCHEN\n");
            doubleHeight(b, false);
            align(b, 0);
            bold(b, false);
            text(b, "Order #" + order.optInt("orderNumber") + "  " + timeOf(order) + "\n");
            text(b, line(chars) + "\n");
            JSONArray items = order.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    bold(b, true);
                    doubleHeight(b, true);
                    text(b, it.optInt("qty", 1) + " x " + it.optString("name", "Item") + "\n");
                    doubleHeight(b, false);
                    bold(b, false);
                    JSONArray extras = it.optJSONArray("extras");
                    if (extras != null) {
                        for (int e = 0; e < extras.length(); e++) {
                            JSONObject ex = extras.optJSONObject(e);
                            if (ex == null) continue;
                            text(b, "   + " + ex.optString("name", "Extra") + "\n");
                        }
                    }
                }
            }
            text(b, line(chars) + "\n");
            String note = order.optString("note", "");
            if (!note.isEmpty()) text(b, "NOTE: " + note + "\n");
            text(b, "\n");
            cut(b);
        } catch (Exception ignored) {
        }
        return b.toByteArray();
    }

    private static String timeOf(JSONObject order) {
        String created = order.optString("createdAt", "");
        return created.isEmpty() ? "" : created.replace("T", " ");
    }
}

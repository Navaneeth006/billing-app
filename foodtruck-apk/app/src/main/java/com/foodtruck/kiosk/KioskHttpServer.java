package com.foodtruck.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

/**
 * The whole kiosk backend on the phone. Serves the web frontend from assets
 * and exposes the REST API used by the iPad kiosk, the kitchen screen and the
 * admin panel (on the iPad or inside the native app).
 */
public class KioskHttpServer extends NanoHTTPD {
    public static final int SERVER_PORT = 4242;
    private static final String PREFS = "kiosk_prefs";
    private static final String KEY_ADMIN_TOKEN = "admin_token";

    private final Context context;
    private final KioskDatabase db;

    public KioskHttpServer(Context context) throws IOException {
        super("0.0.0.0", SERVER_PORT);
        this.context = context.getApplicationContext();
        this.db = KioskDatabase.get(this.context);
    }

    // ------------------------------------------------------------------ routing

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri().length() > 1
                ? session.getUri().replaceAll("/+$", "")
                : session.getUri();
        Method method = session.getMethod();

        try {
            if (Method.OPTIONS.equals(method)) {
                return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""));
            }

            if (uri.equals("/api/health")) {
                return jsonOk(true);
            }
            if (uri.equals("/api/menu") && Method.GET.equals(method)) {
                JSONObject out = new JSONObject();
                out.put("categories", db.menuJson());
                out.put("extras", db.extrasJson());
                out.put("business", db.settingsJson());
                return cors(json(Response.Status.OK, out.toString()));
            }
            if (uri.equals("/api/orders") && Method.POST.equals(method)) {
                return createOrder(session);
            }
            if (uri.matches("^/api/orders/\\d+$") && Method.GET.equals(method)) {
                JSONObject order = db.getOrderJson(Long.parseLong(uri.substring(uri.lastIndexOf('/') + 1)));
                return order == null
                        ? jsonError(Response.Status.NOT_FOUND, "Order not found")
                        : cors(json(Response.Status.OK, new JSONObject().put("order", order).toString()));
            }
            // Kiosk-side payment confirm (placeholder until PhonePe goes live):
            // the customer pays by UPI QR or cash, then this marks the order paid
            // and triggers printing. Replace with gateway verification later.
            if (uri.matches("^/api/orders/\\d+/confirm-paid$") && Method.POST.equals(method)) {
                long id = Long.parseLong(uri.split("/")[3]);
                JSONObject body = readBody(session);
                JSONObject order = db.getOrderJson(id);
                if (order == null) return jsonError(Response.Status.NOT_FOUND, "Order not found");
                boolean newlyPaid = db.markOrderPaid(id, body.optString("method", "upi"));
                if (newlyPaid) printForOrder(db.getOrderJson(id));
                return cors(json(Response.Status.OK, new JSONObject()
                        .put("ok", true)
                        .put("order", db.getOrderJson(id)).toString()));
            }
            // UPI QR for the payment screen (uses the VPA set in admin settings).
            if (uri.equals("/api/qr/upi") && Method.GET.equals(method)) {
                String vpa = db.getSetting("vpa", "");
                String name = db.getSetting("business_name", "Food Truck");
                String amount = qp(session, "am", "0.00");
                String note = qp(session, "tn", "Food order");
                if (vpa.isEmpty()) vpa = "sample@upi";
                String link = "upi://pay?pa=" + vpa + "&pn=" + java.net.URLEncoder.encode(name, "UTF-8")
                        + "&am=" + amount + "&cu=INR&tn=" + java.net.URLEncoder.encode(note, "UTF-8");
                return png(qrPng(link, 520));
            }

            // ---------------- kitchen
            if (uri.equals("/api/kitchen/orders") && Method.GET.equals(method)) {
                JSONArray open = new JSONArray();
                JSONArray orders = db.ordersJson("paid", 100);
                for (int i = orders.length() - 1; i >= 0; i--) {
                    JSONObject o = orders.optJSONObject(i);
                    if (o != null && !"completed".equals(o.optString("status"))) open.put(o);
                }
                return cors(json(Response.Status.OK, new JSONObject().put("orders", open).toString()));
            }
            if (uri.matches("^/api/kitchen/orders/\\d+/status$") && Method.POST.equals(method)) {
                long id = Long.parseLong(uri.split("/")[4]);
                JSONObject body = readBody(session);
                String status = body.optString("status", "");
                if (!status.matches("^(preparing|ready|completed)$")) {
                    return jsonError(Response.Status.BAD_REQUEST, "Invalid status");
                }
                boolean updated = db.setOrderStatus(id, status);
                return cors(json(Response.Status.OK, new JSONObject().put("ok", updated).toString()));
            }

            // ---------------- admin
            if (uri.startsWith("/api/admin/")) {
                return routeAdmin(session, uri, method);
            }

            // ---------------- QR codes
            if (uri.equals("/api/qr/kiosk") && Method.GET.equals(method)) {
                return png(kioskUrlQr());
            }

            if (Method.GET.equals(method)) {
                return serveAsset(session.getUri());
            }
            return jsonError(Response.Status.NOT_FOUND, "Not found");
        } catch (JSONException e) {
            return jsonError(Response.Status.INTERNAL_ERROR, "Server JSON error: " + e.getMessage());
        } catch (Exception e) {
            return jsonError(Response.Status.INTERNAL_ERROR, "Server error: " + e.getMessage());
        }
    }

    private Response routeAdmin(IHTTPSession session, String uri, Method method) throws Exception {
        if (uri.equals("/api/admin/login") && Method.POST.equals(method)) {
            JSONObject body = readBody(session);
            String pin = body.optString("pin", "");
            String expected = db.getSetting("admin_pin", "1234");
            if (!expected.equals(pin)) {
                return cors(json(Response.Status.UNAUTHORIZED, "{\"ok\":false,\"message\":\"Wrong PIN\"}"));
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String token = prefs.getString(KEY_ADMIN_TOKEN, "");
            if (token.isEmpty()) {
                token = UUID.randomUUID().toString();
                prefs.edit().putString(KEY_ADMIN_TOKEN, token).apply();
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("token", token);
            Response response = cors(json(Response.Status.OK, out.toString()));
            response.addHeader("Set-Cookie", "kiosk_admin=" + token + "; Path=/; HttpOnly; Max-Age=604800");
            return response;
        }

        // Everything below requires either the session cookie or the PIN header.
        if (!isAuthed(session)) {
            return cors(json(Response.Status.UNAUTHORIZED, "{\"error\":\"Admin login required\"}"));
        }

        if (uri.equals("/api/admin/stats") && Method.GET.equals(method)) {
            JSONObject stats = db.statsJson();
            stats.put("pendingPayment", countPending());
            return cors(json(Response.Status.OK, stats.toString()));
        }
        if (uri.equals("/api/admin/orders") && Method.GET.equals(method)) {
            String status = qp(session, "status", "all");
            int limit = parseIntSafe(qp(session, "limit", "100"), 100);
            return cors(json(Response.Status.OK, new JSONObject().put("orders", db.ordersJson(status, limit)).toString()));
        }
        if (uri.matches("^/api/admin/orders/\\d+/mark-paid$") && Method.POST.equals(method)) {
            long id = Long.parseLong(uri.split("/")[4]);
            JSONObject body = readBody(session);
            String payMethod = body.optString("method", "cash");
            JSONObject order = db.getOrderJson(id);
            if (order == null) return jsonError(Response.Status.NOT_FOUND, "Order not found");
            boolean newlyPaid = db.markOrderPaid(id, payMethod);
            if (newlyPaid) {
                JSONObject fresh = db.getOrderJson(id);
                printForOrder(fresh);
                order = fresh;
            }
            return cors(json(Response.Status.OK, new JSONObject().put("ok", true).put("order", order).toString()));
        }
        if (uri.equals("/api/admin/menu") && Method.GET.equals(method)) {
            return cors(json(Response.Status.OK, db.adminMenuJson().toString()));
        }
        if (uri.equals("/api/admin/sync") && Method.POST.equals(method)) {
            JSONObject body = readBody(session);
            if (body.has("settings") && body.optJSONObject("settings") != null) {
                db.importSettings(body.getJSONObject("settings"));
            }
            if (body.has("categories") || body.has("extras")) {
                db.importMenu(body.optJSONArray("categories") == null ? new JSONArray() : body.getJSONArray("categories"),
                        body.optJSONArray("extras") == null ? new JSONArray() : body.getJSONArray("extras"));
            }
            return cors(json(Response.Status.OK, "{\"ok\":true}"));
        }
        if (uri.equals("/api/admin/export") && Method.GET.equals(method)) {
            JSONObject out = new JSONObject();
            out.put("settings", db.settingsJson());
            JSONObject menu = db.adminMenuJson();
            out.put("categories", menu.getJSONArray("categories"));
            out.put("extras", menu.getJSONArray("extras"));
            return cors(json(Response.Status.OK, out.toString()));
        }
        if (uri.equals("/api/admin/printer") && Method.GET.equals(method)) {
            String address = BluetoothPrinter.selectedAddress(context);
            JSONObject out = new JSONObject();
            out.put("driver", "bluetooth");
            out.put("configured", !address.isEmpty());
            out.put("address", address);
            out.put("hint", address.isEmpty()
                    ? "Pick a paired printer in the phone app."
                    : "Printer: " + address);
            return cors(json(Response.Status.OK, out.toString()));
        }
        if (uri.equals("/api/admin/printer/select") && Method.POST.equals(method)) {
            JSONObject body = readBody(session);
            try {
                BluetoothPrinter.select(context, body.optString("address", ""));
                return cors(json(Response.Status.OK, "{\"ok\":true}"));
            } catch (Exception e) {
                return jsonError(Response.Status.BAD_REQUEST, e.getMessage());
            }
        }
        if (uri.equals("/api/admin/printer/test") && Method.POST.equals(method)) {
            try {
                ReceiptPrinter.enqueue(context, BluetoothPrinter.testReceipt(), "test");
                return cors(json(Response.Status.OK, "{\"ok\":true}"));
            } catch (Exception e) {
                return jsonError(Response.Status.BAD_REQUEST, e.getMessage());
            }
        }
        return jsonError(Response.Status.NOT_FOUND, "Not found");
    }

    // ------------------------------------------------------------------ order creation

    private Response createOrder(IHTTPSession session) throws Exception {
        JSONObject body = readBody(session);
        KioskDatabase.CreateResult result = db.createOrder(body);
        if (result.error != null) {
            return cors(json(Response.Status.BAD_REQUEST, "{\"error\":\"" + escape(result.error) + "\"}"));
        }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("order", result.order);
        return cors(json(Response.Status.OK, out.toString()));
    }

    private void printForOrder(JSONObject order) {
        if (order == null) return;
        ReceiptPrinter.enqueue(context, ReceiptPrinter.buildReceipt(context, order), "receipt#" + order.optInt("orderNumber"));
        if ("1".equals(db.getSetting("show_kitchen_slip", "1"))) {
            ReceiptPrinter.enqueue(context, ReceiptPrinter.buildKitchenSlip(context, order), "kitchen#" + order.optInt("orderNumber"));
        }
    }

    private int countPending() {
        JSONArray orders = db.ordersJson("pending", 500);
        return orders.length();
    }

    // ------------------------------------------------------------------ helpers

    private boolean isAuthed(IHTTPSession session) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_ADMIN_TOKEN, "");
        String cookie = header(session, "cookie");
        if (!token.isEmpty() && cookie != null && cookie.contains("kiosk_admin=" + token)) {
            return true;
        }
        String headerToken = header(session, "x-admin-token");
        if (!token.isEmpty() && token.equals(headerToken)) {
            return true;
        }
        String pin = header(session, "x-admin-pin");
        if (pin == null) {
            List<String> qp = session.getParameters().get("pin");
            pin = qp != null && !qp.isEmpty() ? qp.get(0) : null;
        }
        return pin != null && db.getSetting("admin_pin", "1234").equals(pin);
    }

    private String header(IHTTPSession session, String name) {
        String value = session.getHeaders().get(name);
        return value == null ? null : value.trim();
    }

    private String qp(IHTTPSession session, String key, String fallback) {
        List<String> values = session.getParameters().get(key);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private JSONObject readBody(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (Exception e) {
            return new JSONObject();
        }
        String body = files.get("postData");
        try {
            return new JSONObject(body == null ? "{}" : body);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // ------------------------------------------------------------------ static files

    private Response serveAsset(String uri) throws IOException {
        String path = "/".equals(uri) ? "index.html" : uri.substring(1);
        if (path.equals("admin")) path = "admin.html";
        if (path.equals("kitchen")) path = "kitchen.html";
        if (path.contains("..") || path.startsWith("/")) {
            return text(Response.Status.BAD_REQUEST, "Invalid path");
        }
        byte[] bytes = readAsset("www/" + path);
        if (bytes == null) {
            bytes = readAsset("www/index.html");
            if (bytes == null) return text(Response.Status.NOT_FOUND, "File not found");
        }
        String mime = NanoHTTPD.getMimeTypeForFile(path);
        if (mime == null || mime.equals("application/octet-stream")) {
            if (path.endsWith(".js")) mime = "text/javascript";
            else if (path.endsWith(".css")) mime = "text/css";
            else if (path.endsWith(".webmanifest")) mime = "application/manifest+json";
            else if (path.endsWith(".json")) mime = "application/json";
            else if (path.endsWith(".svg")) mime = "image/svg+xml";
            else if (path.endsWith(".png")) mime = "image/png";
            else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) mime = "image/jpeg";
        }
        Response response = newFixedLengthResponse(Response.Status.OK, mime,
                new ByteArrayInputStream(bytes), bytes.length);
        response.addHeader("Cache-Control", "no-store");
        return cors(response);
    }

    private byte[] readAsset(String path) {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ QR

    private String kioskUrl() {
        String ip = KioskServerService.getLocalIpv4Address();
        return "http://" + (ip.isEmpty() ? "<phone-ip>" : ip) + ":" + SERVER_PORT;
    }

    private byte[] kioskUrlQr() throws Exception {
        return qrPng(kioskUrl(), 460);
    }

    private byte[] qrPng(String content, int size) throws Exception {
        BitMatrixHolder matrix = new BitMatrixHolder(new QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, size, size));
        Bitmap bitmap = Bitmap.createBitmap(matrix.matrix.getWidth(), matrix.matrix.getHeight(), Bitmap.Config.RGB_565);
        for (int x = 0; x < matrix.matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.matrix.getHeight(); y++) {
                bitmap.setPixel(x, y, matrix.matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private static final class BitMatrixHolder {
        final com.google.zxing.common.BitMatrix matrix;
        BitMatrixHolder(com.google.zxing.common.BitMatrix matrix) { this.matrix = matrix; }
    }

    // ------------------------------------------------------------------ response helpers

    private Response jsonOk(boolean value) throws JSONException {
        return cors(json(Response.Status.OK, new JSONObject().put("ok", value).toString()));
    }

    private Response json(Response.IStatus status, String body) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body);
    }

    private Response jsonError(Response.IStatus status, String message) {
        try {
            return cors(json(status, new JSONObject().put("error", message == null ? "error" : message).toString()));
        } catch (JSONException e) {
            return cors(json(status, "{\"error\":\"error\"}"));
        }
    }

    private Response text(Response.IStatus status, String body) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
    }

    private Response png(byte[] bytes) {
        return cors(newFixedLengthResponse(Response.Status.OK, "image/png",
                new ByteArrayInputStream(bytes), bytes.length));
    }

    private Response cors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Admin-PIN");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        return response;
    }
}

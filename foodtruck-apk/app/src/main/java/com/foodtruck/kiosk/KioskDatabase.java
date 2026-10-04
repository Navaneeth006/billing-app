package com.foodtruck.kiosk;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * All persistent data lives in one SQLite database on the phone:
 * menu (categories/products/extras), orders, settings and the admin PIN.
 * The admin web panel on any device rewrites whole tables through
 * /api/admin/sync, so every device stays in sync from one source of truth.
 */
public class KioskDatabase extends SQLiteOpenHelper {
    public static final String DB_NAME = "kiosk.db";
    private static final int DB_VERSION = 1;

    private static KioskDatabase instance;

    public static synchronized KioskDatabase get(Context context) {
        if (instance == null) instance = new KioskDatabase(context.getApplicationContext());
        return instance;
    }

    private KioskDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', price_paise INTEGER NOT NULL DEFAULT 0, image TEXT NOT NULL DEFAULT '', emoji TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE extras (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, price_paise INTEGER NOT NULL DEFAULT 0, emoji TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT, order_number INTEGER NOT NULL UNIQUE, status TEXT NOT NULL DEFAULT 'awaiting_payment', payment_status TEXT NOT NULL DEFAULT 'pending', payment_method TEXT NOT NULL DEFAULT '', subtotal_paise INTEGER NOT NULL DEFAULT 0, tax_paise INTEGER NOT NULL DEFAULT 0, total_paise INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, paid_at TEXT)");
        db.execSQL("CREATE TABLE order_items (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL, product_id INTEGER, name TEXT NOT NULL, unit_paise INTEGER NOT NULL, qty INTEGER NOT NULL, total_paise INTEGER NOT NULL, extras TEXT NOT NULL DEFAULT '[]')");
        db.execSQL("CREATE TABLE print_jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'receipt', status TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)");
        createIndexes(db);
        seed(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 has no migration paths yet.
    }

    private void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX idx_products_category ON products(category_id, sort_order)");
        db.execSQL("CREATE INDEX idx_order_items_order ON order_items(order_id)");
        db.execSQL("CREATE INDEX idx_orders_number ON orders(order_number)");
    }

    // ------------------------------------------------------------------ seed

    private void seed(SQLiteDatabase db) {
        setSettingOn(db, "business_name", "FOOD TRUCK");
        setSettingOn(db, "tagline", "FRESH • HOT • FAST");
        setSettingOn(db, "address", "");
        setSettingOn(db, "phone", "");
        setSettingOn(db, "gstin", "");
        setSettingOn(db, "gst_percent", "0");
        setSettingOn(db, "show_gst_on_receipt", "0");
        setSettingOn(db, "footer_msg", "Welcome! Order from the kiosk screen.");
        setSettingOn(db, "thank_you_line", "Thank you! Please visit again.");
        setSettingOn(db, "paper_chars", "32");
        setSettingOn(db, "auto_reset_seconds", "4");
        setSettingOn(db, "next_order_number", "1001");
        setSettingOn(db, "printer_type", "bluetooth");
        setSettingOn(db, "show_kitchen_slip", "1");
        setSettingOn(db, "admin_pin", "1234");
        setSettingOn(db, "brand_emoji", "🚚");
        setSettingOn(db, "vpa", "");

        long c = insertCategory(db, "FRIES", "🍟", 0);
        insertProduct(db, c, "Salt Fries", "Crispy golden fries", 7900, "🍟");
        insertProduct(db, c, "Peri Peri Fries", "Fiery peri peri coating", 9900, "🌶️");
        insertProduct(db, c, "Garlic Fries", "Buttery garlic", 9900, "🧄");
        insertProduct(db, c, "Manchuri Fries", "Indo-Chinese tangy fries", 10900, "🥡");
        c = insertCategory(db, "CHICKEN", "🍗", 1);
        insertProduct(db, c, "Plain Chicken", "Juicy and salted", 14900, "🍗");
        insertProduct(db, c, "Peri Peri Chicken", "Spicy peri peri", 17900, "🌶️");
        insertProduct(db, c, "Loaded Chicken", "Piled with sauces", 19900, "🔥");
        insertProduct(db, c, "Ghee Garlic Chicken", "Roasted in ghee", 21900, "🧈");
        insertProduct(db, c, "Loaded Ghee Garlic", "The full works", 24900, "👑");
        c = insertCategory(db, "ROLLS", "🌯", 2);
        insertProduct(db, c, "Crispy Jumbo Roll", "Double filling wrap", 14900, "🌯");
        insertProduct(db, c, "Garlic Chicken Roll", "Garlic mayo + chicken", 15900, "🧄");
        insertProduct(db, c, "Jumbo Fries Roll", "Fries in a wrap", 12900, "🌯");
        insertProduct(db, c, "Bun Fries", "Signature bun + fries", 11900, "🥯");
        insertExtra(db, "Melted Cheese", 2500, "🧀");
    }

    private long insertCategory(SQLiteDatabase db, String name, String emoji, int order) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("emoji", emoji);
        v.put("sort_order", order);
        return db.insert("categories", null, v);
    }

    private long insertProduct(SQLiteDatabase db, long categoryId, String name, String desc, int paise, String emoji) {
        ContentValues v = new ContentValues();
        v.put("category_id", categoryId);
        v.put("name", name);
        v.put("description", desc);
        v.put("price_paise", paise);
        v.put("image", "");
        v.put("emoji", emoji);
        v.put("sort_order", 0);
        return db.insert("products", null, v);
    }

    private long insertExtra(SQLiteDatabase db, String name, int paise, String emoji) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("price_paise", paise);
        v.put("emoji", emoji);
        return db.insert("extras", null, v);
    }

    // -------------------------------------------------------------- settings

    public String getSetting(String key, String fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }

    public int getIntSetting(String key, int fallback) {
        try {
            return Integer.parseInt(getSetting(key, String.valueOf(fallback)).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public void setSetting(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase().insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public JSONObject settingsJson() throws JSONException {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT key,value FROM settings", null)) {
            JSONObject o = new JSONObject();
            while (c.moveToNext()) {
                String key = c.getString(0);
                String value = c.getString(1);
                // Numeric and boolean values are passed through as native JSON
                // values so the web frontends can read them directly.
                if (value.equals("0") || value.equals("1")) {
                    o.put(key, "1".equals(value));
                } else if (value.matches("-?\\d+")) {
                    o.put(key, Long.parseLong(value));
                } else {
                    o.put(key, value);
                }
            }
            return o;
        }
    }

    private void setSettingOn(SQLiteDatabase db, String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        db.insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ------------------------------------------------------------------ menu

    public JSONArray menuJson() throws JSONException {
        JSONArray out = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cats = db.rawQuery("SELECT id,name,emoji FROM categories WHERE available=1 ORDER BY sort_order,id", null)) {
            while (cats.moveToNext()) {
                JSONObject cat = new JSONObject();
                cat.put("id", cats.getInt(0));
                cat.put("name", cats.getString(1));
                cat.put("emoji", cats.getString(2));
                JSONArray items = new JSONArray();
                try (Cursor pro = db.rawQuery("SELECT id,name,description,price_paise,image,emoji FROM products WHERE category_id=? AND available=1 ORDER BY sort_order,id", new String[]{String.valueOf(cats.getInt(0))})) {
                    while (pro.moveToNext()) {
                        JSONObject it = new JSONObject();
                        it.put("id", pro.getInt(0));
                        it.put("name", pro.getString(1));
                        it.put("description", pro.getString(2));
                        it.put("price", pro.getInt(3) / 100.0);
                        it.put("pricePaise", pro.getInt(3));
                        it.put("image", pro.getString(4));
                        it.put("emoji", pro.getString(5));
                        items.put(it);
                    }
                }
                cat.put("items", items);
                out.put(cat);
            }
        }
        return out;
    }

    public JSONArray extrasJson() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor ex = getReadableDatabase().rawQuery("SELECT id,name,price_paise,emoji FROM extras WHERE available=1 ORDER BY sort_order,id", null)) {
            while (ex.moveToNext()) {
                JSONObject e = new JSONObject();
                e.put("id", ex.getInt(0));
                e.put("name", ex.getString(1));
                e.put("price", ex.getInt(2) / 100.0);
                e.put("pricePaise", ex.getInt(2));
                e.put("emoji", ex.getString(3));
                out.put(e);
            }
        }
        return out;
    }

    //////////////////////////////////////////////////////////////////////
    // Orders
    //////////////////////////////////////////////////////////////////////

    public static class CreateResult {
        public final JSONObject order;
        public final String error;
        CreateResult(JSONObject order, String error) { this.order = order; this.error = error; }
    }

    public CreateResult createOrder(JSONObject body) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray cart = body.optJSONArray("cart");
            if (cart == null || cart.length() == 0) return new CreateResult(null, "Cart is empty");
            if (cart.length() > 60) return new CreateResult(null, "Too many lines");

            int maxQty = getIntSetting("max_qty_per_item", 10);
            int nextNumber = getIntSetting("next_order_number", 1001);

            int subtotal = 0;
            JSONArray items = new JSONArray();
            for (int i = 0; i < cart.length(); i++) {
                JSONObject requested = cart.optJSONObject(i);
                if (requested == null) continue;
                int pid = requested.optInt("id", -1);
                if (pid <= 0) return new CreateResult(null, "Unknown menu item");

                int pricePaise;
                try (Cursor pc = getReadableDatabase().rawQuery("SELECT price_paise,name FROM products WHERE id=?", new String[]{String.valueOf(pid)})) {
                    if (!pc.moveToFirst()) return new CreateResult(null, "Unknown menu item");
                    pricePaise = pc.getInt(0);
                }

                int qty = requested.optInt("qty", 1);
                if (qty < 1) qty = 1;
                if (qty > maxQty) qty = maxQty;
                int lineTotal = pricePaise * qty;
                subtotal += lineTotal;

                JSONObject item = new JSONObject();
                item.put("productId", pid);
                item.put("name", requested.optString("name", productName(pid)));
                item.put("qty", qty);
                item.put("unitPaise", pricePaise);
                item.put("totalPaise", lineTotal);

                JSONArray extraNames = new JSONArray();
                JSONArray picked = requested.optJSONArray("extras");
                if (picked != null) {
                    for (int e = 0; e < picked.length(); e++) {
                        int eid = picked.optInt(e, -1);
                        if (eid <= 0) continue;
                        int extraPrice = 0;
                        String extraName = null;
                        try (Cursor ec = getReadableDatabase().rawQuery("SELECT name,price_paise FROM extras WHERE id=?", new String[]{String.valueOf(eid)})) {
                            if (ec.moveToFirst()) { extraName = ec.getString(0); extraPrice = ec.getInt(1); }
                        }
                        if (extraName != null) {
                            subtotal += extraPrice;
                            JSONObject ex = new JSONObject();
                            ex.put("id", eid);
                            ex.put("name", extraName);
                            ex.put("price", extraPrice / 100.0);
                            ex.put("qty", qty);
                            extraNames.put(ex);
                        }
                    }
                }
                item.put("extras", extraNames);
                items.put(item);
            }
            if (items.length() == 0) return new CreateResult(null, "Cart is empty");

            int gstPercent = getIntSetting("gst_percent", 0);
            int tax = "1".equals(getSetting("show_gst_on_receipt", "0")) ? Math.round(subtotal * gstPercent / 100f) : 0;
            int total = subtotal; // displayed prices are what the customer pays

            ContentValues order = new ContentValues();
            order.put("order_number", nextNumber);
            order.put("status", "awaiting_payment");
            order.put("payment_status", "pending");
            order.put("subtotal_paise", subtotal);
            order.put("tax_paise", tax);
            order.put("total_paise", total);
            order.put("note", body.optString("note", ""));
            order.put("created_at", isoNow());
            db.insert("orders", null, order);

            long orderId;
            try (Cursor c = db.rawQuery("SELECT id FROM orders WHERE order_number=?", new String[]{String.valueOf(nextNumber)})) {
                if (!c.moveToFirst()) return new CreateResult(null, "Order could not be stored");
                orderId = c.getLong(0);
            }

            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                ContentValues oi = new ContentValues();
                oi.put("order_id", orderId);
                oi.put("product_id", it.optInt("productId"));
                oi.put("name", it.getString("name"));
                oi.put("unit_paise", it.getInt("unitPaise"));
                oi.put("qty", it.getInt("qty"));
                oi.put("total_paise", it.getInt("totalPaise"));
                oi.put("extras", it.getJSONArray("extras").toString());
                db.insert("order_items", null, oi);
            }
            setSettingOn(db, "next_order_number", String.valueOf(nextNumber + 1));
            db.setTransactionSuccessful();

            return new CreateResult(getOrderJson(orderId), null);
        } catch (Exception e) {
            return new CreateResult(null, "Could not create order: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    private String productName(int productId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name FROM products WHERE id=?", new String[]{String.valueOf(productId)})) {
            return c.moveToFirst() ? c.getString(0) : "Item";
        }
    }

    public String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date());
    }

    public JSONObject getOrderJson(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT id,order_number,status,payment_status,payment_method,subtotal_paise,tax_paise,total_paise,note,created_at,paid_at FROM orders WHERE id=?", new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            JSONObject order = new JSONObject();
            fillOrder(order, c);
            order.put("items", orderItemsJson(id));
            return order;
        } catch (JSONException e) {
            return null;
        }
    }

    private void fillOrder(JSONObject o, Cursor c) throws JSONException {
        o.put("id", c.getLong(0));
        o.put("orderNumber", c.getInt(1));
        o.put("status", c.getString(2));
        o.put("paymentStatus", c.getString(3));
        o.put("paymentMethod", c.getString(4));
        o.put("subtotal", c.getInt(5) / 100.0);
        o.put("subtotalPaise", c.getInt(5));
        o.put("tax", c.getInt(6) / 100.0);
        o.put("taxPaise", c.getInt(6));
        o.put("total", c.getInt(7) / 100.0);
        o.put("totalPaise", c.getInt(7));
        o.put("note", c.getString(8));
        o.put("createdAt", c.getString(9));
        o.put("paidAt", c.isNull(10) ? JSONObject.NULL : c.getString(10));
    }

    public JSONArray orderItemsJson(long orderId) throws JSONException {
        JSONArray items = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name,unit_paise,qty,total_paise,extras FROM order_items WHERE order_id=? ORDER BY id", new String[]{String.valueOf(orderId)})) {
            while (c.moveToNext()) {
                JSONObject it = new JSONObject();
                it.put("name", c.getString(0));
                it.put("unitPrice", c.getInt(1) / 100.0);
                it.put("unitPaise", c.getInt(1));
                it.put("qty", c.getInt(2));
                it.put("totalPaise", c.getInt(3));
                try {
                    it.put("extras", new JSONArray(c.getString(4)));
                } catch (JSONException e) {
                    it.put("extras", new JSONArray());
                }
                items.put(it);
            }
        }
        return items;
    }

    public JSONArray ordersJson(String statusFilter, int limit) {
        JSONArray out = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT id,order_number,status,payment_status,payment_method,subtotal_paise,tax_paise,total_paise,note,created_at,paid_at FROM orders";
        if ("paid".equals(statusFilter)) {
            sql += " WHERE payment_status='paid'";
        } else if ("pending".equals(statusFilter)) {
            sql += " WHERE payment_status!='paid'";
        } else if (statusFilter != null && !statusFilter.isEmpty() && !"all".equals(statusFilter)) {
            sql += " WHERE status='" + statusFilter.replace("'", "") + "'";
        }
        sql += " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(500, limit));
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                try {
                    JSONObject o = new JSONObject();
                    fillOrder(o, c);
                    o.put("items", orderItemsJson(c.getLong(0)));
                    out.put(o);
                } catch (JSONException ignored) {
                }
            }
        }
        return out;
    }

    /** Returns true when the order existed and payment was newly marked. */
    public boolean markOrderPaid(long id, String method) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT payment_status FROM orders WHERE id=?", new String[]{String.valueOf(id)})) {
                if (!c.moveToFirst()) return false;
                if ("paid".equals(c.getString(0))) {
                    db.setTransactionSuccessful();
                    return false;
                }
            }
            ContentValues v = new ContentValues();
            v.put("payment_status", "paid");
            v.put("payment_method", "upi".equalsIgnoreCase(method) ? "upi" : "cash");
            v.put("status", "preparing");
            v.put("paid_at", isoNow());
            db.update("orders", v, "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** Kitchen/status flow update. Returns false when the order is missing. */
    public boolean setOrderStatus(long id, String status) {
        if (!status.matches("^(preparing|ready|completed)$")) return false;
        ContentValues v = new ContentValues();
        v.put("status", status);
        if ("completed".equals(status)) {
            v.put("paid_at", isoNow());
        }
        return getWritableDatabase().update("orders", v, "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    // ------------------------------------------------------------- sync / admin writes

    /** Rewrites the whole menu from another device; used by /api/admin/sync. */
    public void importMenu(JSONArray categories, JSONArray extras) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("products", null, null);
            db.delete("categories", null, null);
            db.delete("extras", null, null);
            for (int i = 0; i < categories.length(); i++) {
                JSONObject cat = categories.optJSONObject(i);
                if (cat == null) continue;
                ContentValues cv = new ContentValues();
                cv.put("name", cat.optString("name", "Category"));
                cv.put("emoji", cat.optString("emoji", ""));
                cv.put("sort_order", cat.optInt("sortOrder", i));
                cv.put("available", cat.optBoolean("available", true) ? 1 : 0);
                long cid = db.insert("categories", null, cv);
                JSONArray items = cat.optJSONArray("items");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject it = items.optJSONObject(j);
                        if (it == null) continue;
                        ContentValues v = new ContentValues();
                        v.put("category_id", cid);
                        v.put("name", it.optString("name", "Item"));
                        v.put("description", it.optString("description", ""));
                        v.put("price_paise", it.optInt("pricePaise", Math.round((float) it.optDouble("price", 0) * 100)));
                        v.put("image", it.optString("image", ""));
                        v.put("emoji", it.optString("emoji", cat.optString("emoji", "")));
                        v.put("sort_order", it.optInt("sortOrder", j));
                        v.put("available", it.optBoolean("available", true) ? 1 : 0);
                        db.insert("products", null, v);
                    }
                }
            }
            for (int i = 0; i < extras.length(); i++) {
                JSONObject ex = extras.optJSONObject(i);
                if (ex == null) continue;
                ContentValues v = new ContentValues();
                v.put("name", ex.optString("name", ""));
                v.put("price_paise", ex.optInt("pricePaise", Math.round((float) ex.optDouble("price", 0) * 100)));
                v.put("emoji", ex.optString("emoji", ""));
                v.put("sort_order", ex.optInt("sortOrder", i));
                v.put("available", ex.optBoolean("available", true) ? 1 : 0);
                db.insert("extras", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void importSettings(JSONObject settings) throws JSONException {
        java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            setSetting(key, String.valueOf(settings.opt(key)));
        }
    }

    /** Full menu incl. unavailable rows for the admin panel. */
    public JSONObject adminMenuJson() throws JSONException {
        JSONObject wrap = new JSONObject();
        JSONArray all = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cats = db.rawQuery("SELECT id,name,emoji,sort_order,available FROM categories ORDER BY sort_order,id", null)) {
            while (cats.moveToNext()) {
                JSONObject cat = new JSONObject();
                cat.put("id", cats.getInt(0));
                cat.put("name", cats.getString(1));
                cat.put("emoji", cats.getString(2));
                cat.put("sortOrder", cats.getInt(3));
                cat.put("available", cats.getInt(4) == 1);
                JSONArray items = new JSONArray();
                try (Cursor pro = db.rawQuery("SELECT id,name,description,price_paise,image,emoji,sort_order,available FROM products WHERE category_id=? ORDER BY sort_order,id", new String[]{String.valueOf(cats.getInt(0))})) {
                    while (pro.moveToNext()) {
                        JSONObject it = new JSONObject();
                        it.put("id", pro.getInt(0));
                        it.put("name", pro.getString(1));
                        it.put("description", pro.getString(2));
                        it.put("pricePaise", pro.getInt(3));
                        it.put("price", pro.getInt(3) / 100.0);
                        it.put("image", pro.getString(4));
                        it.put("emoji", pro.getString(5));
                        it.put("sortOrder", pro.getInt(6));
                        it.put("available", pro.getInt(7) == 1);
                        items.put(it);
                    }
                }
                cat.put("items", items);
                all.put(cat);
            }
        }
        JSONArray extras = new JSONArray();
        try (Cursor ex = db.rawQuery("SELECT id,name,price_paise,emoji,sort_order,available FROM extras ORDER BY sort_order,id", null)) {
            while (ex.moveToNext()) {
                JSONObject e = new JSONObject();
                e.put("id", ex.getInt(0));
                e.put("name", ex.getString(1));
                e.put("pricePaise", ex.getInt(2));
                e.put("price", ex.getInt(2) / 100.0);
                e.put("emoji", ex.getString(3));
                e.put("sortOrder", ex.getInt(4));
                e.put("available", ex.getInt(5) == 1);
                extras.put(e);
            }
        }
        wrap.put("categories", all);
        wrap.put("extras", extras);
        return wrap;
    }

    public JSONObject statsJson() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int todayOrders = 0;
        int todayPaise = 0;
        int openOrders = 0;
        try (Cursor c = db.rawQuery("SELECT payment_status,status,total_paise,substr(created_at,1,10) FROM orders", null)) {
            while (c.moveToNext()) {
                boolean paid = "paid".equals(c.getString(0));
                String status = c.getString(1);
                int paise = c.getInt(2);
                String day = c.getString(3);
                if (today.equals(day)) {
                    todayOrders++;
                    if (paid) todayPaise += paise;
                }
                if (!"completed".equals(status)) openOrders++;
            }
        }
        JSONObject o = new JSONObject();
        o.put("todayOrders", todayOrders);
        o.put("todayRevenue", todayPaise / 100.0);
        o.put("openOrders", openOrders);
        return o;
    }
}

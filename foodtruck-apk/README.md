# Food Truck Kiosk — Android Server + iPad Kiosk

One Android app turns **any Android phone (7.0+) into the entire backend** of your food truck:

- 🖥️ **HTTP kiosk server** runs on the phone as a **foreground service** — it keeps running even when you close the app or the screen turns off (persistent notification with a Stop button, survives reboot if left ON, wake-locks keep Wi-Fi awake).
- 📱 **iPad (or any tablet/PC) on the same Wi-Fi** opens `http://PHONE-IP:4242` — that's the customer **billing kiosk**: one continuous menu, large product cards, a quantity-editable mini-cart, cart review, cash checkout, or a UPI QR screen.
- 🧾 **Bluetooth thermal printing** — pair your ESC/POS printer in Android settings, pick it in the app. Every paid order prints a **customer receipt + kitchen slip** automatically (3 retries).
- 🛠️ **Admin panel** — open it on the phone (built-in button) or from any browser at `/admin.html`: PIN login (default **1234**), dashboard stats, orders (mark paid / ready / complete), **full menu editor** (categories, items, prices, descriptions, emoji, persistent JPG/PNG/WebP image uploads or image URLs, reorder ↑↓, hide, add, delete), **extras/toppings editor**, **kiosk theme colors**, and **billing settings** (business name, tagline, address, phone, GSTIN, GST on/off + %, separate cash/UPI receipt headings, thank-you line, paper width 32/48, kitchen slip on/off, auto-reset seconds, UPI VPA, PIN change).
- 👨‍🍳 **Kitchen display** at `/kitchen.html` — big tickets, auto-refresh, tap to advance preparing → ready → done.

Everything (menu, orders, settings) lives in **SQLite on the phone**. The admin panel edits that data from any device, so there is exactly one source of truth.

---

## Get the APK (no computer needed)

1. Put this project on GitHub (see *Repo layout* below).
2. On GitHub open the **Actions** tab → **Build Android APK**.
3. Either push to `main`, or use **Run workflow** (the "action button").
4. Open the finished run → **Artifacts** → download **food-truck-kiosk-debug-apk** → unzip → `app-debug.apk`.
5. On the phone: open the APK (allow "install unknown apps" if asked), allow Notifications and Nearby-devices permissions.

Tag a release with `v1.0` etc. and the same workflow also attaches the APK to a GitHub **Release** for stable download links.

## Daily use

1. Open **Food Truck Kiosk** on the phone → leave the **Server toggle ON**. It shows the address + QR.
2. Connect the iPad to the **same Wi-Fi**, open `http://PHONE-IP:4242` (or scan the QR from the phone). Pin it in Safari for a fullscreen kiosk.
3. First run: tap **Open Admin Panel** → PIN `1234` → change the PIN and edit the menu/settings. The kiosk keeps every category in one scrollable menu; tapping an item adds it directly to the mini-cart.
4. Tap **Choose Bluetooth Printer** → pick your paired ESC/POS printer (pair it first in Android Bluetooth settings). **Send Test Print** to verify.
5. Tap the battery card once to exempt the app from battery optimization so the server survives screen-off. On aggressive phones (Xiaomi/Samsung) also set the app to *Unrestricted* in system settings.
6. Close the phone app — the server keeps running (you'll see the notification). Turn it off from the app toggle or the notification's **Stop server** action.

> Guest Wi-Fi networks often isolate devices ("AP isolation"). If the iPad can't connect, use a phone hotspot, home router, or travel router instead.

## PhonePe (later)

Payment is deliberately **not** integrated. The kiosk shows a UPI QR for the VPA in admin settings, or a clearly labeled sample QR if no VPA is configured. The customer manually confirms payment; this does not verify that money was received. Do not use the sample QR for live payments—connect a payment provider and verify payment server-side first. Cash checkout asks for one cash confirmation, then marks the order CASH and queues the receipt and kitchen slip. Customer receipts print a large centered CASH heading for cash orders and a centered PAID heading for UPI orders; both headings are editable in Admin → Billing & receipts. The order confirmation screen returns to the menu after 3–4 seconds.

## Repo layout

```
.github/workflows/android-apk.yml   ← builds the APK on every push / manual run
foodtruck-apk/
├── settings.gradle · build.gradle · gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/          (Gradle 8.14.3, no SDK download needed)
├── app/build.gradle                                 (Java 17, minSdk 24, targetSdk 36)
└── app/src/main/
    ├── AndroidManifest.xml                          (INTERNET, FOREGROUND_SERVICE, BLUETOOTH_CONNECT, boot receiver…)
    ├── java/com/foodtruck/kiosk/
    │   ├── MainActivity.java         (server toggle, URL + QR, printer picker, battery card)
    │   ├── AdminActivity.java        (admin panel in a WebView)
    │   ├── KioskServerService.java   (foreground service, wake locks, notification)
    │   ├── KioskHttpServer.java      (NanoHTTPD: REST API + static pages + QR endpoints)
    │   ├── KioskDatabase.java        (SQLite: menu/orders/settings + pricing engine)
    │   ├── ReceiptPrinter.java       (ESC/POS receipt + kitchen slip, retry queue)
    │   ├── BluetoothPrinter.java     (Bluetooth SPP transport)
    │   └── ServerBootReceiver.java   (auto-start after reboot when left ON)
    ├── assets/www/                   (index.html kiosk · admin.html · kitchen.html)
    └── res/                          (layouts, icons, strings, theme)
```

## Build on your own computer (optional)

```bash
cd foodtruck-apk
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+; the Android SDK is fetched automatically by Gradle.

## HTTP API (for hacking / future PhonePe)

| Method & path | What it does |
|---|---|
| `GET /api/health` | server alive check |
| `GET /api/menu` | categories + items + extras + business settings |
| `POST /api/orders` | create order `{ cart: [{ id, qty, extras:[id] }] }` — prices recomputed server-side |
| `GET /api/orders/:id` | order status (kiosk polls after paying) |
| `POST /api/orders/:id/confirm-paid` | kiosk-side placeholder confirm `{ method: "upi"\|"cash" }` → prints |
| `GET /api/kitchen/orders` | paid & not-completed orders |
| `POST /api/kitchen/orders/:id/status` | `{ status: "preparing"\|"ready"\|"completed" }` |
| `POST /api/admin/login` | `{ pin }` → sets `kiosk_admin` cookie + returns token |
| `GET /api/admin/stats` | today's orders/revenue, open + awaiting payment |
| `GET /api/admin/orders?status=&limit=` | order list |
| `POST /api/admin/orders/:id/mark-paid` | `{ method }` → prints receipt + kitchen slip |
| `GET /api/admin/menu` / `GET /api/admin/export` | full menu incl. hidden rows / settings+menu export |
| `POST /api/admin/sync` | write settings + categories + extras from the admin panel |
| `POST /api/admin/images` | upload a menu image (JPG/PNG/WebP, max 5 MB); saved in the phone app's private storage |
| `GET /api/admin/printer` · `POST /api/admin/printer/test` | printer status / test slip |
| `GET /api/qr/kiosk` · `GET /api/qr/upi?am=&tn=` | PNG QR codes |

Admin endpoints accept the session cookie, `X-Admin-Token: <token>` from login, or `X-Admin-PIN: <pin>`.

## Data model

- `settings` — key/value (business, billing, printer, PIN, `next_order_number` starting at 1001)
- `categories → products` (price in paise, image URL, emoji, sort order, available)
- `extras` — global paid add-ons (e.g. Melted Cheese ₹25)
- `orders` + `order_items` — server-authoritative totals, order numbers sequential
- `print_jobs` — reserved for print auditing

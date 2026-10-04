package com.foodtruck.kiosk;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Loads the same admin web panel that the iPad serves from this phone, so the
 * owner always has one place to manage menu, prices, images, order and
 * billing settings even without the iPad around.
 */
public class AdminActivity extends AppCompatActivity {
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);
        WebView web = findViewById(R.id.adminWeb);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("http://127.0.0.1:" + KioskHttpServer.SERVER_PORT + "/admin.html");
    }

    @Override
    public void onBackPressed() {
        WebView web = findViewById(R.id.adminWeb);
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}

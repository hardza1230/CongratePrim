package com.congrateprim.gobsong;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * ตัวหุ้มบาง ๆ ของเว็บแอป "งบซอง"
 *
 * เนื้อหาทั้งหมดโหลดจาก GitHub Pages ({@link BuildConfig#APP_URL}) แปลว่าเมื่อแก้โค้ดเว็บแล้ว push
 * ขึ้น main แอปในเครื่องจะได้ของใหม่เองในการเปิดครั้งถัดไป โดยไม่ต้องลง APK ใหม่
 *
 * service worker ของหน้าเว็บทำให้เปิดแบบออฟไลน์ได้หลังเปิดสำเร็จครั้งแรก
 * ข้อมูลผู้ใช้เก็บใน localStorage ของ WebView ตัวนี้ (แยกจากเบราว์เซอร์ในเครื่อง)
 */
public class MainActivity extends Activity {

    private WebView web;
    private boolean loadedOnce = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // จำเป็นสำหรับ localStorage ที่แอปใช้เก็บข้อมูล
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                String appHost = Uri.parse(BuildConfig.APP_URL).getHost();
                if (appHost != null && appHost.equals(target.getHost())) {
                    return false; // ลิงก์ภายในแอป ให้โหลดใน WebView ตามปกติ
                }
                // ลิงก์ออกข้างนอก เปิดด้วยเบราว์เซอร์ของเครื่องแทน
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (Exception ignored) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadedOnce = true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // ถ้ายังไม่เคยโหลดสำเร็จเลย แปลว่ายังไม่มีแคชให้ใช้งานออฟไลน์
                if (request.isForMainFrame() && !loadedOnce) {
                    Toast.makeText(MainActivity.this,
                            R.string.offline_first_run, Toast.LENGTH_LONG).show();
                }
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(BuildConfig.APP_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}

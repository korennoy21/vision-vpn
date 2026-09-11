package app.vision.control;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.*;
import android.webkit.*;
import android.widget.*;

/** Native Android shell for the VISION Control web administration panel. */
public final class MainActivity extends Activity {
    private static final String PREFS = "vision_control";
    private static final String DEFAULT_URL = "https://panel.korennoy-ay.com";
    private WebView web;
    private TextView state;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(4,16,28));
        getWindow().setNavigationBarColor(Color.rgb(4,16,28));

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(4,16,28));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(16), dp(10), dp(8), dp(10));
        TextView brand = new TextView(this); brand.setText("VISION Control"); brand.setTextColor(Color.WHITE); brand.setTextSize(21); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD); bar.addView(brand);
        Space space = new Space(this); bar.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        state = new TextView(this); state.setText("●"); state.setTextColor(Color.rgb(45,235,183)); state.setTextSize(18); bar.addView(state);
        Button refresh = tiny("↻"); refresh.setOnClickListener(v -> web.reload()); bar.addView(refresh);
        Button settings = tiny("⚙"); settings.setOnClickListener(v -> changePanel()); bar.addView(settings);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        web = new WebView(this);
        configure(web);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        web.loadUrl(panelUrl());
    }

    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);
        w.setBackgroundColor(Color.rgb(4,16,28));
        w.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            Uri requested = Uri.parse(url); Uri allowed = Uri.parse(panelUrl());
            if (!"https".equalsIgnoreCase(requested.getScheme()) || allowed.getHost() == null ||
                    !allowed.getHost().equalsIgnoreCase(requested.getHost())) {
                toast("Загрузка заблокирована: чужой адрес"); return;
            }
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(requested);
                request.setTitle(fileName);
                request.setDescription("VISION Control backup");
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) request.addRequestHeader("Cookie", cookies);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                toast("Файл сохраняется в Загрузки");
            } catch (Exception e) { toast("Не удалось начать загрузку"); }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri requested = request.getUrl(); Uri allowed = Uri.parse(panelUrl());
                if ("https".equalsIgnoreCase(requested.getScheme()) && allowed.getHost() != null && allowed.getHost().equalsIgnoreCase(requested.getHost())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, requested)); return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { state.setTextColor(Color.rgb(255,193,75)); }
            @Override public void onPageFinished(WebView view, String url) { state.setTextColor(Color.rgb(45,235,183)); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) state.setTextColor(Color.rgb(255,90,90));
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                // Never bypass TLS errors in an administrator application.
                handler.cancel(); state.setTextColor(Color.rgb(255,90,90));
                new AlertDialog.Builder(MainActivity.this).setTitle("Ошибка TLS").setMessage("Сертификат панели не прошёл проверку. Подключение заблокировано.").setPositiveButton("OK", null).show();
            }
        });
    }

    private String panelUrl() { return getSharedPreferences(PREFS, MODE_PRIVATE).getString("panel", DEFAULT_URL); }

    private void changePanel() {
        EditText input = new EditText(this); input.setText(panelUrl()); input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Адрес VISION Control").setView(input)
            .setPositiveButton("Сохранить", (d,w) -> {
                String raw = input.getText().toString().trim();
                Uri uri = Uri.parse(raw);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                    toast("Нужен HTTPS-адрес панели"); return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("panel", raw.replaceAll("/+$", "")).apply();
                web.clearHistory(); web.loadUrl(panelUrl());
            }).setNegativeButton("Отмена", null).show();
    }

    private Button tiny(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(17); b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.TRANSPARENT); b.setMinWidth(dp(46)); return b; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}

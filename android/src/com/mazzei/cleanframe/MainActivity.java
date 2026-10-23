package com.mazzei.cleanframe;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline shell. Only bundled content can reach the narrowly scoped JS bridge. */
public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/ui/index.html";
    private WebView web;
    private boolean resumed;
    // Brand mappings are hints; a target must also resolve to an installed TV launch activity.
    private static final String[][] BRANDS = {
        {"netflix", "com.netflix.ninja"},
        {"hbomax", "com.wbd.stream", "com.hbo.hbonow", "com.hbo.hbomax"},
        {"youtube", "com.google.android.youtube.tv"},
        {"paramount", "com.cbs.ott"},
        {"appletv", "com.apple.atve.androidtv.appletv"},
        {"primevideo", "com.amazon.amazonvideo.livingroom"},
        {"peacock", "com.peacocktv.peacockandroid"},
        {"disneyplus", "com.disney.disneyplus"},
        {"tubi", "com.tubitv"}
    };
    private static String brand(String pkg) {
        for (String[] row : BRANDS) for (int i = 1; i < row.length; i++) if (row[i].equals(pkg)) return row[0];
        return "";
    }
    private List<ResolveInfo> tvApps() {
        return getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
    }
    private Intent launchIntent(String pkg) {
        if (getPackageName().equals(pkg)) return null;
        // Reject arbitrary services, components, URIs and non-TV packages.
        for (ResolveInfo info : tvApps()) {
            if (info.activityInfo != null && info.activityInfo.exported && pkg.equals(info.activityInfo.packageName)) {
                return getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            }
        }
        return null;
    }
    private String resolveBrand(String target) {
        if (!target.startsWith("brand.")) return target;
        for (String[] row : BRANDS) if (target.equals("brand." + row[0])) {
            for (int i = 1; i < row.length; i++) if (launchIntent(row[i]) != null) return row[i];
        }
        return "";
    }
    private JSONArray appList() throws Exception {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        PackageManager pm = getPackageManager();
        for (ResolveInfo info : tvApps()) {
            String pkg = info.activityInfo.packageName;
            if (getPackageName().equals(pkg) || !info.activityInfo.exported || !seen.add(pkg)) continue;
            String b = brand(pkg);
            result.put(new JSONObject().put("appId", pkg).put("name", info.loadLabel(pm).toString())
                .put("brand", b).put("mark", !b.isEmpty() && !b.equals("paramount") && !b.equals("peacock"))
                .put("icon", "/icons/" + pkg + ".png"));
        }
        return result;
    }
    private void originalHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo chosen = null;
        for (ResolveInfo info : getPackageManager().queryIntentActivities(home, 0)) {
            if (info.activityInfo == null || !info.activityInfo.exported || getPackageName().equals(info.activityInfo.packageName)) continue;
            // Prefer the stock Google TV home, including when CleanFrame is now the default.
            if ("com.google.android.apps.tv.launcherx".equals(info.activityInfo.packageName)) { chosen = info; break; }
            if (chosen == null && (info.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) chosen = info;
        }
        if (chosen == null) throw new IllegalArgumentException("The original TV home could not be found. Open TV settings to choose a Home screen.");
        home.setComponent(new ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name));
        HomeService.stockChosen = true; // The owner asked for the original home; let it stay until CleanFrame returns.
        startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new LocalContent());
        web.addJavascriptInterface(new Bridge(), "CleanFrameAndroid");
        web.setBackgroundColor(0xff111111);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(web);
        web.requestFocus();
        web.loadUrl(START);
    }
    private void js(String script) { if (web != null) web.evaluateJavascript(script, null); }
    @Override protected void onResume() {
        super.onResume(); resumed = true; HomeService.stockChosen = false;
        if (web != null) { web.onResume(); js("window.FrameVisibility && window.FrameVisibility(true)"); }
    }
    @Override protected void onPause() {
        resumed = false;
        if (web != null) { js("window.FrameVisibility && window.FrameVisibility(false)"); web.onPause(); }
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (web != null) { web.removeJavascriptInterface("CleanFrameAndroid"); web.destroy(); web = null; }
        super.onDestroy();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        String key = null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: key = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: key = "right"; break;
            case KeyEvent.KEYCODE_DPAD_UP: key = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: key = "down"; break;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: key = "ok"; break;
            case KeyEvent.KEYCODE_BACK: key = "back"; break;
            default: return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
            && (event.getRepeatCount() == 0 || (!key.equals("ok") && !key.equals("back")))) {
            js("window.FrameRemoteKey && window.FrameRemoteKey(" + JSONObject.quote(key) + ")");
        }
        return true;
    }
    public final class Bridge {
        @JavascriptInterface public void request(String method, String token, String target) {
            if (token == null || !token.matches("[0-9]{1,10}")) return;
            // android.jar intentionally omits LambdaMetafactory implementation details.
            // A Runnable keeps the standalone javac + d8 build independent of Gradle desugaring.
            runOnUiThread(new Runnable() { @Override public void run() {
                if (web == null || !START.equals(web.getUrl()) || !resumed) return;
                JSONObject response = new JSONObject();
                try {
                    if ("apps".equals(method)) response.put("apps", appList());
                    else if ("launch".equals(method)) {
                        if (target == null || !target.matches("[A-Za-z0-9._]{1,161}")) throw new IllegalArgumentException("Invalid app target.");
                        Intent intent = launchIntent(resolveBrand(target));
                        if (intent == null) throw new IllegalArgumentException("This app is not installed or has no TV launch screen. Install it from the TV’s Play Store, or choose it in All apps.");
                        startActivity(intent);
                    } else if ("settings".equals(method)) startActivity(new Intent(Settings.ACTION_SETTINGS));
                    else if ("home".equals(method)) originalHome();
                    // This firmware ignores the HOME role for the remote button, so the toggle is the Home accessibility service.
                    else if ("defaultHome".equals(method)) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    else throw new IllegalArgumentException("Unsupported TV action.");
                } catch (Exception e) {
                    try { response.put("error", e instanceof IllegalArgumentException ? e.getMessage() : "The TV could not open this screen. Use the remote’s Home button to return."); }
                    catch (Exception ignored) { /* Fixed JSON key. */ }
                }
                js("window.CleanFrameAndroidResult && window.CleanFrameAndroidResult(" + JSONObject.quote(token) + "," + response + ")");
            } });
        }
    }
    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg")) return "image/jpeg";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
    private final class LocalContent extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true; // No links, redirects, external pages or subframes are navigation targets.
        }
        @Override public void onPageFinished(WebView view, String url) {
            if (START.equals(url)) js("window.FrameVisibility && window.FrameVisibility(" + resumed + ")");
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"GET".equals(request.getMethod()) || !"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null) return denied();
            String path = uri.getPath();
            if (path == null || path.contains("..") || path.contains("\\")) return denied();
            try {
                if (path.startsWith("/assets/ui/")) {
                    WebResourceResponse response = new WebResourceResponse(mime(path), "UTF-8", getAssets().open(path.substring(8)));
                    response.setResponseHeaders(Collections.singletonMap("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'"));
                    return response;
                }
                if (path.matches("/icons/[A-Za-z0-9._]+\\.png")) {
                    String pkg = path.substring(7, path.length() - 4);
                    if (launchIntent(pkg) == null) return denied();
                    Drawable icon = getPackageManager().getApplicationIcon(pkg);
                    Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                    icon.setBounds(0, 0, 128, 128); icon.draw(new Canvas(bitmap));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle();
                    return new WebResourceResponse("image/png", null, new ByteArrayInputStream(bytes.toByteArray()));
                }
            } catch (Exception ignored) { return denied(); }
            return denied();
        }
    }
}

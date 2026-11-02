package com.mazzei.cleanframe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.webkit.ValueCallback;
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
    private EufyNative eufy;
    private boolean resumed;
    private BackupController backups;
    private AlertDialog healthDialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static boolean homePromptDeclined; // For this process only; a reboot asks again.
    private AlertDialog homePrompt;
    private final Runnable homeCheck = new Runnable() { @Override public void run() {
        if (!resumed || homePromptDeclined || (homePrompt != null && homePrompt.isShowing()) || !HomeService.needsReenable(MainActivity.this)) return;
        homePrompt = new AlertDialog.Builder(MainActivity.this)
            .setTitle("Turn the Home button back on")
            .setMessage("After the TV restarts, TCL pauses CleanFrame’s Home button. In Accessibility, open CleanFrame Home button, turn it off, then turn it on again.")
            .setPositiveButton("Open Accessibility", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int which) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } })
            .setNegativeButton("Not now", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int which) {
                homePromptDeclined = true;
            } })
            .show();
    } };
    private final HomeLaunchPolicy.Session homeSession = new HomeLaunchPolicy.Session();
    private final Runnable homeOk = new Runnable() { @Override public void run() {
        if (!resumed || web == null) return;
        final int token = homeSession.generation();
        web.evaluateJavascript("Boolean(window.FrameReady && window.FrameReady())", new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (homeSession.observeReady(token, "true".equals(value), SystemClock.elapsedRealtime())) HomeService.launchSucceeded(MainActivity.this);
                if (homeSession.shouldPoll(token)) main.postDelayed(homeOk, 250);
            }
        });
    } };
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
        // The owner asked for the original home; let it stay until CleanFrame returns or the TV restarts.
        if (!HomeService.chooseStock(this)) throw new IllegalStateException("Stock Home choice was not saved.");
        try { startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { HomeService.clearStock(this); throw e; }
    }
    private void showHealth() {
        String state = HomeService.health(this);
        String detail = "running".equals(state) ? "Running. Press Home from another app to test the button."
            : "paused".equals(state) ? "Enabled, but not running. The TV may have paused it after a restart or update. Open Accessibility, turn CleanFrame Home button off, then on again."
            : "off".equals(state) ? "Off. Open Accessibility and enable CleanFrame Home button if you want Home to return here."
            : "Status unavailable. Open Accessibility to check CleanFrame Home button.";
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        if (healthDialog != null) healthDialog.dismiss();
        healthDialog = new AlertDialog.Builder(this).setTitle("Home health")
            .setMessage(detail + "\n\nStartup: " + (HomeService.bootLaunchEnabled(this) ? "Open CleanFrame" : "Follow TV") + "\nCleanFrame " + version)
            .setPositiveButton("Open Accessibility", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int which) {
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); HomeService.launchSucceeded(MainActivity.this); }
                catch (RuntimeException e) { new AlertDialog.Builder(MainActivity.this).setMessage("Accessibility could not be opened. Use TV settings instead.").setPositiveButton("OK", null).show(); }
            } })
            .setNeutralButton("Check again", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int which) { showHealth(); } })
            .setNegativeButton("Close", null).show();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (backups != null) backups.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (backups != null && backups.result(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        eufy=new EufyNative(this);EufyProvision.owner=new java.lang.ref.WeakReference<>(this);
        web = new WebView(this);
        backups = new BackupController(this, web, state);
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
        super.onResume(); resumed = true; HomeService.clearStock(this);
        homeSession.resume();
        main.postDelayed(homeCheck, 1500); // Let a just-restarted process reconnect the service first.
        main.postDelayed(homeOk, 1500);
        if (web != null) { web.onResume(); js("window.FrameVisibility && window.FrameVisibility(true)"); }
    }
    @Override protected void onPause() {
        resumed = false; main.removeCallbacks(homeCheck);
        if(eufy!=null)eufy.visible(false);
        main.removeCallbacks(homeOk); homeSession.pause();
        // A dialog left behind another activity can lose key focus to the WebView on return; ask afresh.
        if (homePrompt != null) { homePrompt.dismiss(); homePrompt = null; }
        if (web != null) { js("window.FrameVisibility && window.FrameVisibility(false)"); web.onPause(); }
        super.onPause();
    }
    public void provisionEufy(String value){eufy.provision(value);}
    @Override protected void onDestroy() {
        if(eufy!=null)eufy.destroy();
        if (backups != null) backups.destroy();
        if (healthDialog != null) healthDialog.dismiss();
        if (homePrompt != null) homePrompt.dismiss();
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
                        HomeService.launchSucceeded(MainActivity.this);
                    } else if ("settings".equals(method)) { startActivity(new Intent(Settings.ACTION_SETTINGS)); HomeService.launchSucceeded(MainActivity.this); }
                    else if ("home".equals(method)) { originalHome(); HomeService.launchSucceeded(MainActivity.this); }
                    // This firmware ignores the HOME role for the remote button, so the toggle is the Home accessibility service.
                    else if ("defaultHome".equals(method)) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); HomeService.launchSucceeded(MainActivity.this); }
                    else if ("vacuumView".equals(method)) { eufy.visible("open".equals(target)); }
                    else if ("vacuumCommand".equals(method)) { eufy.confirm(target); }
                    else if ("vacuumStatus".equals(method)) { response = eufy.status(); }
                    else if ("health".equals(method)) { showHealth(); }
                    else if ("backupStatus".equals(method)) { response.put("filePicker", backups.hasFilePicker()); }
                    else if (method != null && method.startsWith("backup.")) { backups.request(method.substring(7), target); }
                    else if ("bootLaunch".equals(method)) {
                        if ("on".equals(target) || "off".equals(target)) {
                            if (!HomeService.setBootLaunch(MainActivity.this, "on".equals(target))) throw new IllegalArgumentException("The startup preference could not be saved.");
                        } else if (!"get".equals(target)) throw new IllegalArgumentException("Invalid startup preference.");
                        response.put("enabled", HomeService.bootLaunchEnabled(MainActivity.this));
                    }
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

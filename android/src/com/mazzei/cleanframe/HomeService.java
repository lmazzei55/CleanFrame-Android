package com.mazzei.cleanframe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * Returns Home to CleanFrame on firmware that keeps resolving HOME to Google TV even when CleanFrame
 * holds the HOME role. It only reads which activity came to the front: no keys, window content,
 * gestures or network. Google TV and setup stay enabled; turning the service off restores stock Home.
 * Guards adapted from TV Detox (MIT): no redirect during first setup, a saved stock-Home choice that lasts
 * until reboot, and a stop after repeated launches that never stayed open, so a broken build cannot loop.
 */
public final class HomeService extends AccessibilityService {
    static final String STOCK_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final String STOCK_HOME = STOCK_PACKAGE + ".home.HomeActivity";
    // After this many launches that never stayed open, stop redirecting until CleanFrame is opened by hand.
    private static final int MAX_FAILURES = 3;
    private static final long LAUNCH_GRACE_MS = 3000, DUPLICATE_MS = 500;
    private static volatile boolean connected;
    private static long lastLaunch = -DUPLICATE_MS;

    @Override protected void onServiceConnected() { connected = true; }
    @Override public boolean onUnbind(Intent intent) { connected = false; return super.onUnbind(intent); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || !STOCK_PACKAGE.contentEquals(pkg) || !STOCK_HOME.contentEquals(cls)) return;
        // During first setup, and after the owner chose the original home this boot, Google TV keeps Home.
        if (!setupComplete(this) || stockChosen(this)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < DUPLICATE_MS) return;
        SharedPreferences prefs = prefs(this);
        long pending = prefs.getLong("pending", -1);
        if (pending > now) pending = -1; // Saved before a reboot; the elapsed clock restarted.
        if (pending >= 0 && now - pending < LAUNCH_GRACE_MS) return; // Give a slow launch time to resume.
        int failures = prefs.getInt("failures", 0) + (pending >= 0 ? 1 : 0);
        if (failures >= MAX_FAILURES) { prefs.edit().remove("pending").putInt("failures", failures).commit(); return; }
        // Saved before launching: a crash can take this service's process down with the activity.
        if (!prefs.edit().putLong("pending", now).putInt("failures", failures).commit()) return;
        lastLaunch = now;
        try {
            // CATEGORY_HOME makes Android create CleanFrame as the home-type task, not a standard app task.
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException e) { /* Counted as a failure on the next Home. */ }
    }

    @Override public void onInterrupt() { /* No feedback to interrupt. */ }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("home_service", Context.MODE_PRIVATE); }

    private static boolean setupComplete(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), "user_setup_complete", 1) == 1;
    }

    private static int bootCount(Context context) { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    private static long bootTime() { return System.currentTimeMillis() - SystemClock.elapsedRealtime(); }

    /** The owner opened the original home: keep it through process death and updates, but not a reboot. */
    static boolean chooseStock(Context context) {
        return prefs(context).edit().putInt("stock_boot_count", bootCount(context)).putLong("stock_boot_time", bootTime()).commit();
    }

    static void clearStock(Context context) { prefs(context).edit().remove("stock_boot_count").remove("stock_boot_time").commit(); }

    private static boolean stockChosen(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains("stock_boot_time")) return false;
        int saved = prefs.getInt("stock_boot_count", -1), boot = bootCount(context);
        if (saved >= 0 && boot >= 0) return saved == boot;
        return Math.abs(bootTime() - prefs.getLong("stock_boot_time", 0)) < 5000; // Wall-clock fallback.
    }

    /** CleanFrame stayed open, or was left normally: the last launch worked. Also re-arms a stopped redirect. */
    static void launchSucceeded(Context context) {
        prefs(context).edit().remove("pending").remove("failures").commit();
        lastLaunch = -DUPLICATE_MS;
    }

    /**
     * TCL's boot guard refuses to bind this service after a reboot because CleanFrame is not running yet,
     * and Android does not retry; on this firmware an app cannot re-trigger the bind itself. True when the
     * owner still has the service enabled but it is not running, so CleanFrame should ask them to toggle it.
     */
    static boolean needsReenable(Context context) {
        ComponentName self = new ComponentName(context, HomeService.class);
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (connected || manager == null) return false;
        for (AccessibilityServiceInfo running : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (self.flattenToString().equals(running.getId()) || self.flattenToShortString().equals(running.getId())) return false;
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled != null) for (String entry : enabled.split(":")) if (self.equals(ComponentName.unflattenFromString(entry))) return true;
        return false;
    }
}

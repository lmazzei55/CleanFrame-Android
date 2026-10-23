package com.mazzei.cleanframe;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.PatternMatcher;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Returns Home to CleanFrame on firmware that keeps resolving HOME to Google TV even when CleanFrame
 * holds the HOME role. It only reads which activity came to the front: no keys, window content,
 * gestures or network. Google TV and setup stay enabled; turning the service off restores stock Home.
 */
public final class HomeService extends AccessibilityService {
    static final String STOCK_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final String STOCK_HOME = STOCK_PACKAGE + ".home.HomeActivity";
    // Set when the owner deliberately opens the original home; cleared when CleanFrame is shown again.
    static volatile boolean stockChosen;
    private static volatile boolean connected;
    private static boolean rearming;

    @Override protected void onServiceConnected() { connected = true; }
    @Override public boolean onUnbind(Intent intent) { connected = false; return super.onUnbind(intent); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || stockChosen) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || !STOCK_PACKAGE.contentEquals(pkg) || !STOCK_HOME.contentEquals(cls)) return;
        // CATEGORY_HOME makes Android create CleanFrame as the home-type task, not a standard app task.
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(new ComponentName(this, MainActivity.class))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override public void onInterrupt() { /* No feedback to interrupt. */ }

    /**
     * TCL's boot guard refuses to bind this service after a reboot because CleanFrame is not running yet,
     * and Android does not retry. While CleanFrame is open the bind is allowed, so toggle this service
     * component: once Android has seen it disabled and then restored, it re-reads and binds enabled
     * services. Package-change broadcasts are delayed and coalesced, so each step waits for its own
     * broadcast. Acts only when the owner still has the service enabled; needs no permission.
     */
    static void rearm(Context activity, final Handler handler) {
        if (connected || rearming) return;
        final Context context = activity.getApplicationContext();
        final ComponentName self = new ComponentName(context, HomeService.class);
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean listed = false;
        if (enabled != null) for (String entry : enabled.split(":")) if (self.equals(ComponentName.unflattenFromString(entry))) listed = true;
        if (!listed) return;
        rearming = true;
        final PackageManager pm = context.getPackageManager();
        final BroadcastReceiver[] receiver = new BroadcastReceiver[1];
        final Runnable finish = new Runnable() { @Override public void run() {
            if (!rearming) return;
            rearming = false;
            handler.removeCallbacks(this);
            try { context.unregisterReceiver(receiver[0]); } catch (IllegalArgumentException ignored) { /* Already gone. */ }
            pm.setComponentEnabledSetting(self, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        } };
        receiver[0] = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent intent) {
            int state = pm.getComponentEnabledSetting(self);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(self, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
            } else finish.run(); // The restore was announced; Android rebinds now.
        } };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(context.getPackageName(), PatternMatcher.PATTERN_LITERAL);
        context.registerReceiver(receiver[0], filter, null, handler);
        handler.postDelayed(finish, 45000); // Never leave the service component disabled.
        pm.setComponentEnabledSetting(self, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}

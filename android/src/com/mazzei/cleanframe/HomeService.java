package com.mazzei.cleanframe;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
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
}

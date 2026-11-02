package com.mazzei.cleanframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;

/** Experimental shell-authorized status feed. Never launches UI or controls a vacuum. */
public final class VacuumStatusReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.mazzei.cleanframe.VACUUM_STATUS";
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("vacuum_status", 0); }
    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String raw = intent.getStringExtra("snapshot");
        if (raw == null || raw.length() > 2048) return;
        try {
            JSONObject value = new JSONObject(raw), safe = new JSONObject();
            if (!value.optBoolean("configured", true)) { prefs(context).edit().clear().commit(); setResultCode(1); return; }
            String state = value.optString("state", "unknown");
            if (!state.matches("cleaning|docked|charging|returning|paused|idle|error|unknown")) state = "unknown";
            safe.put("configured", true).put("state", state);
            Object battery = value.opt("battery"), error = value.opt("errorCode");
            if (battery instanceof Number && ((Number) battery).doubleValue() >= 0 && ((Number) battery).doubleValue() <= 100)
                safe.put("battery", Math.round(((Number) battery).doubleValue()));
            if (error instanceof Number && ((Number) error).longValue() > 0 && ((Number) error).longValue() <= Integer.MAX_VALUE)
                safe.put("errorCode", ((Number) error).longValue());
            // Source age is carried across delivery; receiving a stale sample never refreshes it.
            double received = value.optDouble("receivedAt", 0);
            long age = VacuumFeedPolicy.sourceAge(received, System.currentTimeMillis());
            boolean fresh = age <= VacuumFeedPolicy.TTL_MS;
            safe.put("receivedAt", fresh ? received : 0);
            boolean saved = prefs(context).edit().putString("snapshot", safe.toString())
                .putLong("arrival", SystemClock.elapsedRealtime()).putLong("age", age)
                .putInt("boot", Settings.Global.getInt(context.getContentResolver(), "boot_count", -1)).commit();
            setResultCode(saved ? 1 : 0);
        } catch (Exception ignored) { /* Reject malformed feeds; retain previous sample until stale. */ }
    }
    static JSONObject status(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String raw = p.getString("snapshot", null);
        if (raw == null) return new JSONObject().put("configured", false);
        JSONObject result = new JSONObject(raw);
        long elapsed = SystemClock.elapsedRealtime() - p.getLong("arrival", 0);
        boolean sameBoot = p.getInt("boot", -2) >= 0 && p.getInt("boot", -2) == Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
        long age = p.getLong("age", 90001) + elapsed;
        if (!VacuumFeedPolicy.fresh(p.getLong("age", 90001), elapsed, sameBoot)) {
            result.put("state", "unknown").put("receivedAt", 0); result.remove("battery"); result.remove("errorCode");
        } else result.put("receivedAt", System.currentTimeMillis() - age);
        return result;
    }
}

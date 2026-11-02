package com.mazzei.cleanframe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** User-selected files only; no storage permission, server, or account data. */
final class BackupController {
    private static final int EXPORT = 301, IMPORT = 302, LIMIT = 131072;
    private final Activity activity;
    private final WebView web;
    private String exportData;
    private boolean picking;
    private AlertDialog dialog;

    BackupController(Activity activity, WebView web, Bundle state) {
        this.activity = activity; this.web = web;
        if (state != null) { exportData = state.getString("backup.export"); picking = state.getBoolean("backup.picking", false); }
    }
    void saveState(Bundle state) { state.putString("backup.export", exportData); state.putBoolean("backup.picking", picking); }
    void destroy() { if (dialog != null) dialog.dismiss(); }
    private void tell(String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null) dialog.dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle("Backup and restore").setMessage(message).setPositiveButton("OK", null).show();
    }
    private static JSONObject document(String data) throws Exception {
        if (data == null || data.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IllegalArgumentException("Choose a CleanFrame backup smaller than 128 KB.");
        JSONObject doc = new JSONObject(data);
        if (!"cleanframe.android.backup".equals(doc.optString("format")) || doc.optInt("version", -1) != 1 || doc.optJSONObject("preferences") == null)
            throw new IllegalArgumentException("Choose a supported CleanFrame Android backup.");
        return doc;
    }
    private Intent picker(boolean exporting) {
        Intent intent = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(exporting ? "application/json" : "*/*");
        for (android.content.pm.ResolveInfo info : activity.getPackageManager().queryIntentActivities(intent, 0)) {
            if (info.activityInfo != null && info.activityInfo.exported
                && !"com.android.tv.frameworkpackagestubs".equals(info.activityInfo.packageName)) {
                return intent.setComponent(new android.content.ComponentName(info.activityInfo.packageName, info.activityInfo.name));
            }
        }
        return null;
    }
    boolean hasFilePicker() { return picker(true) != null && picker(false) != null; }
    void request(String action, String data) throws Exception {
        if (picking) throw new IllegalArgumentException("Finish or cancel the current file selection first.");
        if ("save".equals(action)) {
            String value = document(data).toString();
            if (!activity.getSharedPreferences("backup", 0).edit().putString("snapshot", value).commit()) throw new IllegalArgumentException("The snapshot could not be saved.");
            tell("Snapshot saved on this TV. It survives updates, but uninstalling CleanFrame removes it. " + (hasFilePicker() ? "Export a file to keep a separate copy." : "File export is unavailable because this TV has no document picker."));
        } else if ("restore".equals(action)) {
            String value = activity.getSharedPreferences("backup", 0).getString("snapshot", null);
            if (value == null) throw new IllegalArgumentException("No snapshot saved yet. Choose Save a snapshot first.");
            preview(document(value));
        } else if ("export".equals(action) || "import".equals(action)) {
            boolean exporting = "export".equals(action);
            String value = exporting ? document(data).toString() : null;
            Intent intent = picker(exporting);
            if (intent == null) throw new IllegalArgumentException("This TV has no document picker. Local snapshots still work. File export/import needs a compatible document picker.");
            if (exporting) intent.putExtra(Intent.EXTRA_TITLE, "CleanFrame-backup.json");
            try { activity.startActivityForResult(intent, exporting ? EXPORT : IMPORT); }
            catch (android.content.ActivityNotFoundException e) { throw new IllegalArgumentException("This TV has no document picker. Local snapshots still work. File export/import needs a compatible document picker."); }
            exportData = value; picking = true;
        } else throw new IllegalArgumentException("Unsupported backup action.");
    }
    boolean result(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT && requestCode != IMPORT) return false;
        picking = false;
        final String payload = exportData; exportData = null;
        if (resultCode != Activity.RESULT_OK) return true; // Cancel changes nothing.
        final Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) { tell("The selected file could not be opened. Nothing changed."); return true; }
        // Provider IO is bounded and kept off the UI thread.
        new Thread(new Runnable() { @Override public void run() {
            try {
                if (requestCode == EXPORT) {
                    if (payload == null) throw new IllegalStateException();
                    try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IllegalStateException();
                        out.write(payload.getBytes(StandardCharsets.UTF_8));
                    }
                    activity.runOnUiThread(new Runnable() { @Override public void run() { tell("Backup file saved. Keep a copy outside the TV for recovery after reinstalling."); } });
                } else {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException();
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = in.read(buffer)) != -1) { if (bytes.size() + count > LIMIT) throw new IllegalArgumentException(); bytes.write(buffer, 0, count); }
                    }
                    final JSONObject value = document(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                    activity.runOnUiThread(new Runnable() { @Override public void run() { if (!activity.isDestroyed()) preview(value); } });
                }
            } catch (Exception e) {
                activity.runOnUiThread(new Runnable() { @Override public void run() { tell(requestCode == EXPORT ? "The file was not saved completely. Try another location; delete the incomplete file if one was created." : "Could not read a supported backup smaller than 128 KB. Your preferences are unchanged."); } });
            }
        } }, "CleanFrame-backup").start();
        return true;
    }
    private void preview(final JSONObject value) {
        web.evaluateJavascript("window.FrameBackupCheck && window.FrameBackupCheck(" + value + ")", new ValueCallback<String>() {
            @Override public void onReceiveValue(String result) {
                try {
                    JSONObject check = new JSONObject(result);
                    if (!check.optBoolean("ok")) { tell(check.optString("message", "Invalid backup. Nothing changed.")); return; }
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    if (dialog != null) dialog.dismiss();
                    dialog = new AlertDialog.Builder(activity).setTitle("Restore this backup?")
                        .setMessage(check.optInt("apps") + " Home apps · " + check.optString("wallpaper") + "\n\nReplaces appearance, wallpaper rotation and app arrangement. Apps must already be installed; unavailable fonts use the default. Home permissions and startup behavior stay as they are.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Restore", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int which) { restore(value); } }).show();
                } catch (Exception e) { tell("CleanFrame is not ready to restore. Try again after returning Home."); }
            }
        });
    }
    private void restore(JSONObject value) {
        web.evaluateJavascript("window.FrameRestore && window.FrameRestore(" + value + ")", new ValueCallback<String>() {
            @Override public void onReceiveValue(String result) {
                try {
                    JSONObject response = new JSONObject(result);
                    if (response.optBoolean("ok")) { web.reload(); tell("Preferences restored."); }
                    else tell(response.optString("message", "Nothing changed. Could not restore preferences."));
                } catch (Exception e) { tell("Could not confirm restoration. Return Home to check your preferences."); }
            }
        });
    }
}

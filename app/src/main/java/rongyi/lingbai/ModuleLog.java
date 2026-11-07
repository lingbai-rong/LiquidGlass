package rongyi.lingbai;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

/** Opt-in diagnostics shared by the app and API 102 host processes. */
public final class ModuleLog {
    public static final String PREF_NAME = "module_logging";
    public static final String KEY_ENABLED = "enabled";
    private static final String ACTION_CHANGED = "rongyi.lingbai.LOGGING_CHANGED";
    private static volatile boolean enabled = false;
    private static SharedPreferences preferences;
    private static BroadcastReceiver receiver;
    // Keep a strong reference: SharedPreferences stores listeners weakly.
    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
            (changed, key) -> refresh(changed);

    private ModuleLog() {}

    public static synchronized void initialize(Context context) {
        Context device = context.createDeviceProtectedStorageContext();
        bind(device.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context source, Intent intent) {
                    if (ACTION_CHANGED.equals(intent.getAction())) {
                        enabled = intent.getBooleanExtra(KEY_ENABLED, false);
                    }
                }
            };
            // Broadcast changes to any already-running host process. SharedPreferences listeners
            // alone only observe writes within their own process.
            context.getApplicationContext().registerReceiver(
                    receiver, new IntentFilter(ACTION_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        }
    }

    static synchronized void attachRemote() {
        if (preferences != null) return;
        try {
            bind(XposedBridge.getRemotePreferences(PREF_NAME));
        } catch (Throwable ignored) {
            // Unavailable preferences must never opt a process into logging.
            enabled = false;
        }
    }

    private static void bind(SharedPreferences value) {
        if (preferences == value) {
            refresh(value);
            return;
        }
        value.registerOnSharedPreferenceChangeListener(LISTENER);
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(LISTENER);
        preferences = value;
        refresh(preferences);
    }

    private static void refresh(SharedPreferences value) {
        try {
            enabled = value.getBoolean(KEY_ENABLED, false);
        } catch (Throwable ignored) {
            enabled = false;
        }
    }

    public static boolean isEnabled() { return enabled; }

    public static boolean setEnabled(Context context, boolean value) {
        try {
            SharedPreferences local = HookPreferenceTransport.open(context, PREF_NAME);
            if (!local.edit().putBoolean(KEY_ENABLED, value).commit()) return false;
            enabled = value;
            context.sendBroadcast(new Intent(ACTION_CHANGED)
                    .setPackage(context.getPackageName()).putExtra(KEY_ENABLED, value));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void i(String tag, String message) {
        if (enabled) Log.i(tag, message);
    }

    public static void w(String tag, String message) {
        if (enabled) Log.w(tag, message);
    }

    public static void w(String tag, String message, Throwable error) {
        if (enabled) Log.w(tag, message, error);
    }

    public static void e(String tag, String message, Throwable error) {
        if (enabled) Log.e(tag, message, error);
    }
}

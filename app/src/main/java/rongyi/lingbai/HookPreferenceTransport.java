package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;

/** Module-app entry point for preferences consumed through libxposed API 102. */
final class HookPreferenceTransport {
    private HookPreferenceTransport() {}

    static void initialize(Context context) {
        if (context == null) return;
        RemotePreferencePublisher.start(context);
        bindKnownGroups(context);
        // A cached service binder can arrive before the groups are bound. Notify after binding so
        // already-running targets re-read the snapshots just published by open().
        GlassSettings.notifyChanged(context);
    }

    static SharedPreferences open(Context context, String preferenceName) {
        if (context == null) throw new IllegalArgumentException("Context is null");
        if (preferenceName == null || preferenceName.isEmpty()) {
            throw new IllegalArgumentException("Preference name is empty");
        }
        if (!GlassSettings.MODULE_PACKAGE.equals(context.getPackageName())) {
            throw new SecurityException(
                    "Refusing to open module preferences from " + context.getPackageName()
            );
        }

        return RemotePreferencePublisher.open(context, preferenceName);
    }

    /** Migrates and publishes every group used by hooks without changing user-visible values. */
    static void prepareExistingSettings(Context context) {
        if (context == null || !GlassSettings.MODULE_PACKAGE.equals(context.getPackageName())) return;
        initialize(context);
        GlassSettings.notifyChanged(context);
    }

    private static void bindKnownGroups(Context context) {
        open(context, ModuleLog.PREF_NAME);
        open(context, GlassSettings.PREF_NAME);
        open(context, GlobalAppearanceStore.PREF_NAME);
        open(context, TargetAppSettings.PREF_NAME);
    }
}

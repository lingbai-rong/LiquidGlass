package rongyi.lingbai;

import android.app.Application;

/** Initializes the API 102 module service before any settings screen is opened. */
public final class LiquidGlassApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ModuleLog.initialize(this);
        HookPreferenceTransport.initialize(this);
        dev.amenhancer.module.AmppSettingsEditor.grantHostAccess(this);
    }
}

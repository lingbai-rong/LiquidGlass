package rongyi.lingbai;

import android.content.pm.ApplicationInfo;

/** Class-loader context retained by the existing host-specific hook implementations. */
public final class LoadPackageParam {
    public final String packageName;
    public final String processName;
    public final ClassLoader classLoader;
    public final ApplicationInfo appInfo;
    public final boolean isFirstApplication;

    LoadPackageParam(
            String packageName,
            String processName,
            ClassLoader classLoader,
            ApplicationInfo appInfo,
            boolean isFirstApplication
    ) {
        this.packageName = packageName;
        this.processName = processName;
        this.classLoader = classLoader;
        this.appInfo = appInfo;
        this.isFirstApplication = isFirstApplication;
    }
}

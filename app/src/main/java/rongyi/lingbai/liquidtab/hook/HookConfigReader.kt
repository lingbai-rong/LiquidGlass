package rongyi.lingbai.liquidtab.hook

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import rongyi.lingbai.TargetApp
import rongyi.lingbai.TargetAppHookSettingsReader
import rongyi.lingbai.TargetAppSettings
import rongyi.lingbai.liquidtab.config.GlassConfig
import java.util.concurrent.Executors

object HookConfigReader {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "LiquidGlass-port-config").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    fun from(settings: TargetAppSettings, context: Context): GlassConfig = GlassConfig(
        enabled = settings.enabled, themeMode = settings.themeMode, tabWidth = settings.tabWidthDp,
        bottomPadding = settings.bottomPaddingDp, blurRadius = settings.blurRadiusDp,
        lightAlpha = settings.lightAlpha, darkAlpha = settings.darkAlpha, iconScale = settings.iconScale,
        textSize = settings.textSizeSp, iconOnly = settings.iconOnly, hiddenMask = settings.hiddenMask,
        showPostButton = settings.showPost, customAccentEnabled = settings.customPostColorEnabled,
        customAccentColor = settings.customPostColor,
        systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    fun read(context: Context, packageName: String): GlassConfig {
        val app = TargetApp.fromPackage(packageName) ?: return GlassConfig(enabled = false)
        return from(TargetAppHookSettingsReader.load(context, app), context)
    }
    fun readFresh(context: Context, packageName: String): GlassConfig = read(context, packageName)
    fun readFreshAsync(context: Context, packageName: String, callback: (GlassConfig) -> Unit) {
        worker.execute { val value = read(context, packageName); main.post { callback(value) } }
    }
}

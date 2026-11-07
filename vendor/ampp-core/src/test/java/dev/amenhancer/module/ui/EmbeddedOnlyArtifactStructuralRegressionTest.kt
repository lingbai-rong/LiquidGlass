package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOnlyArtifactStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    private fun projectPath(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::exists) ?: File(relativePath)

    @Test
    fun `embedded core shares configuration without adding a standalone launcher`() {
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val entry = projectFile("src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val host = projectFile("src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val provider = projectFile("src/main/java/dev/amenhancer/module/config/AmppSettingsProvider.kt")

        assertFalse(manifest.contains("<activity"))
        assertFalse(manifest.contains("<activity-alias"))
        assertTrue(manifest.contains("dev.amenhancer.module.config.AmppSettingsProvider"))
        assertTrue(provider.contains("SharedSettingsAccessPolicy.allows("))
        assertTrue(provider.contains("throw SecurityException"))
        assertTrue(entry.contains("EmbeddedSettingsHost.install("))
        assertTrue(entry.contains("EmbeddedRuntimeSettingsController("))
        assertTrue(host.contains("Application.ActivityLifecycleCallbacks"))
        assertTrue(host.contains("showSettingsDialog(activity)"))
    }

    @Test
    fun `obsolete standalone settings sources are absent`() {
        listOf(
            "src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt",
            "src/main/java/dev/amenhancer/module/ui/LauncherIconController.kt",
            "src/main/java/dev/amenhancer/module/ui/CurrentSongIdentityRequester.kt",
            "src/main/java/dev/amenhancer/module/ModuleApplication.kt",
            "src/main/java/dev/amenhancer/module/XposedServiceSnapshot.kt",
            "src/main/java/dev/amenhancer/module/config/ConfigStore.kt",
            "src/main/java/dev/amenhancer/module/font/SafFontImporter.kt",
            "src/main/java/dev/amenhancer/module/lyrics/CustomLyricsManager.kt",
        ).forEach { path -> assertFalse("obsolete standalone source remains: $path", projectPath(path).isFile) }
    }
}

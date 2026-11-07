package rongyi.lingbai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class HookEntryCompatibilityTest {
    @Test
    public void supportedPackagesIncludeCoolapkAndModernTargets() {
        assertTrue(HookEntry.isSupportedPackageForTest("com.coolapk.market"));
        assertTrue(HookEntry.isSupportedPackageForTest("com.tencent.mm"));
        assertTrue(HookEntry.isSupportedPackageForTest("com.topjohnwu.magisk"));
        assertTrue(HookEntry.isSupportedPackageForTest("com.github.android"));
        assertTrue(HookEntry.isSupportedPackageForTest("com.mi.health"));
        assertTrue(HookEntry.isSupportedPackageForTest("com.cainiao.wireless"));
        assertTrue(HookEntry.isSupportedPackageForTest("com.netease.uuremote"));
    }

    @Test
    public void githubTargetUsesVerifiedMainNavigationOrder() {
        TargetApp github = TargetApp.fromPackage("com.github.android");
        assertEquals(TargetApp.GITHUB, github);
        assertArrayEquals(
                new String[]{"主页", "Inbox", "探索", "Copilot"},
                github.labels
        );
        assertArrayEquals(new int[]{0, 1, 2, 3}, github.nativeIds);
    }

    @Test
    public void miFitnessTargetUsesVerifiedMainNavigationOrder() {
        TargetApp fitness = TargetApp.fromPackage("com.mi.health");
        assertEquals(TargetApp.MI_FITNESS, fitness);
        assertArrayEquals(
                new String[]{"健康", "运动", "设备", "我的"},
                fitness.labels
        );
        assertArrayEquals(new int[]{0, 1, 2, 3}, fitness.nativeIds);
        assertTrue(fitness.matchesActivity("com.xiaomi.fitness.main.MainActivity"));
    }

    @Test
    public void cainiaoTargetUsesVerifiedMainNavigationOrder() {
        TargetApp cainiao = TargetApp.fromPackage("com.cainiao.wireless");
        assertEquals(TargetApp.CAINIAO, cainiao);
        assertArrayEquals(
                new String[]{"首页", "发现", "购物券", "消息", "我的"},
                cainiao.labels
        );
        // The supplied reference maps all five current physical native route slots.
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, cainiao.nativeIds);
        assertTrue(cainiao.matchesActivity(
                "com.cainiao.wireless.homepage.view.activity.WelcomeActivity"));
    }

    @Test
    public void uuRemoteKeepsLegacyNativeOrder() {
        TargetApp uuRemote = TargetApp.fromPackage("com.netease.uuremote");
        assertEquals(TargetApp.UUREMOTE, uuRemote);
        assertArrayEquals(
                new String[]{"我的设备", "云设备", "远程协助", "我的"},
                uuRemote.labels
        );
        assertArrayEquals(new int[]{0, 2, 1, 3}, uuRemote.nativeIds);
        assertTrue(uuRemote.matchesActivity(
                "com.remote.app.ui.activity.ComposeMainActivity"));
    }

    @Test
    public void unrelatedPackagesAreIgnoredWithoutStoppingLifecycleCallbacks() {
        assertFalse(HookEntry.isSupportedPackageForTest("android"));
        assertFalse(HookEntry.isSupportedPackageForTest("com.example.unrelated"));
    }

    @Test
    public void packageInstallationRunsImmediatelyWithoutAccountState() {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicInteger installations = new AtomicInteger();
        assertTrue(HookEntry.runInstallationOnce(started, installations::incrementAndGet));
        assertEquals(1, installations.get());
        assertTrue(started.get());
        assertFalse(HookEntry.runInstallationOnce(started, installations::incrementAndGet));
        assertEquals(1, installations.get());
    }

    @Test
    public void failedInstallationCanBeRetriedOnTheNextPackageCallback() {
        AtomicBoolean started = new AtomicBoolean(false);
        assertThrows(IllegalStateException.class, () -> HookEntry.runInstallationOnce(started, () -> {
            throw new IllegalStateException("installation failed");
        }));
        assertFalse(started.get());
        AtomicInteger installations = new AtomicInteger();
        assertTrue(HookEntry.runInstallationOnce(started, installations::incrementAndGet));
        assertEquals(1, installations.get());
    }
}

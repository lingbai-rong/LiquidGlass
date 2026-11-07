package rongyi.lingbai;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

public final class LiquidTabNavigationRulesTest {
    private static final TargetApp[] PORTS = {
            TargetApp.MI_MARKET, TargetApp.MI_COMMUNITY, TargetApp.WEIBO,
            TargetApp.TIEBA, TargetApp.TAOBAO, TargetApp.BILIBILI_IN
    };

    @Test public void allRequestedHostsAreRegisteredAndScoped() throws Exception {
        Path path = Path.of("src/main/resources/META-INF/xposed/scope.list");
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        List<String> scope = Files.readAllLines(path);
        assertEquals(scope.size(), new HashSet<>(scope).size());
        for (TargetApp app : PORTS) {
            assertSame(app, TargetApp.fromPackage(app.packageName));
            assertSame(app, TargetApp.fromKey(app.key));
            assertTrue(scope.contains(app.packageName));
            assertTrue(HookEntry.isSupportedPackageForTest(app.packageName));
            assertTrue(LiquidTabNavigationRules.supports(app));
            assertFalse(app.hasPostAction);
            assertEquals(app.labels.length, app.nativeIds.length);
        }
        assertTrue(scope.contains(TargetApp.JD.packageName));
    }

    @Test public void bilibiliInternationalIsNotTheHdOrPhoneHost() {
        assertEquals("com.bilibili.app.in", TargetApp.BILIBILI_IN.packageName);
        assertArrayEquals(new int[]{0, 1, 2, 3}, TargetApp.BILIBILI_IN.nativeIds);
        assertTrue(LiquidTabNavigationRules.matchesRow(TargetApp.BILIBILI_IN,
                LiquidTabNavigationRules.COMPOSE_VIEW, "tab_host"));
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.BILIBILI_IN,
                LiquidTabNavigationRules.COMPOSE_VIEW, "content"));
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.BILIBILI_IN,
                "android.widget.LinearLayout", "tab_host"));
        assertFalse(LiquidTabNavigationRules.supports(TargetApp.BILIBILI));
        assertFalse(LiquidTabNavigationRules.supports(TargetApp.BILIBILI_HD));
    }

    @Test public void miRowsRequireHostClassesNotGenericTabIds() {
        assertTrue(LiquidTabNavigationRules.matchesRow(TargetApp.MI_MARKET,
                LiquidTabNavigationRules.MARKET_ROW, "tab_container"));
        assertTrue(LiquidTabNavigationRules.matchesRow(TargetApp.MI_COMMUNITY,
                LiquidTabNavigationRules.COMMUNITY_ROW, "tab_indicator"));
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.MI_MARKET,
                "android.widget.LinearLayout", "tab_container"));
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.MI_COMMUNITY,
                "android.widget.LinearLayout", "tab_indicator"));
    }

    @Test public void taobaoAndTiebaDoNotCaptureUnrelatedPageRows() {
        assertTrue(LiquidTabNavigationRules.matchesRow(TargetApp.TAOBAO,
                "android.widget.TabWidget", "tabs"));
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.TAOBAO,
                "android.widget.TabWidget", "product_tabs"));
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.TAOBAO,
                "android.widget.LinearLayout", "tabs"));
        for (String name : LiquidTabNavigationRules.TIEBA_ROWS) {
            assertTrue(LiquidTabNavigationRules.matchesRow(TargetApp.TIEBA, name, ""));
        }
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.TIEBA,
                "android.widget.LinearLayout", "bottom_navigation"));
    }

    @Test public void weiboOnlyAcceptsItsTwoMainRows() {
        assertTrue(LiquidTabNavigationRules.matchesRow(TargetApp.WEIBO,
                "android.widget.RadioGroup", "main_radio"));
        assertTrue(LiquidTabNavigationRules.matchesRow(TargetApp.WEIBO,
                "android.widget.RadioGroup", "main_theme_radio"));
        assertFalse(LiquidTabNavigationRules.matchesRow(TargetApp.WEIBO,
                "android.widget.RadioGroup", "feed_filter"));
    }

    @Test public void changedModelsAreRejectedInsteadOfMisroutingClicks() {
        for (TargetApp app : PORTS) {
            assertTrue(LiquidTabNavigationRules.validSlotCount(app, app.labels.length));
            assertFalse(LiquidTabNavigationRules.validSlotCount(app, 0));
            assertFalse(LiquidTabNavigationRules.validSlotCount(app, app.labels.length - 1));
            assertFalse(LiquidTabNavigationRules.validSlotCount(app, app.labels.length + 1));
        }
        assertFalse(LiquidTabNavigationRules.validSlotCount(null, 5));
    }

    @Test public void composeCoordinatesStayWithinTheirOwnNativeSlot() {
        assertEquals(50f, LiquidTabNavigationRules.slotCenter(0, 4, 400), 0.001f);
        assertEquals(350f, LiquidTabNavigationRules.slotCenter(3, 4, 400), 0.001f);
        assertEquals(-1f, LiquidTabNavigationRules.slotCenter(-1, 4, 400), 0f);
        assertEquals(-1f, LiquidTabNavigationRules.slotCenter(4, 4, 400), 0f);
        assertEquals(-1f, LiquidTabNavigationRules.slotCenter(0, 4, 0), 0f);
        assertEquals(-1f, LiquidTabNavigationRules.slotCenter(0, 0, 400), 0f);
    }

    @Test public void existingJdAndUuControllersAreNotReplaced() {
        assertFalse(LiquidTabNavigationRules.supports(TargetApp.JD));
        assertFalse(LiquidTabNavigationRules.supports(TargetApp.UUREMOTE));
        assertFalse(LiquidTabNavigationRules.supports(TargetApp.MEITUAN));
        assertFalse(LiquidTabNavigationRules.supports(TargetApp.CAINIAO));
        assertArrayEquals(new int[]{0, 2, 11, 3, 4}, TargetApp.JD.nativeIds);
        assertArrayEquals(new int[]{0, 2, 1, 3}, TargetApp.UUREMOTE.nativeIds);
        assertFalse(TargetApp.TAOBAO.matchesActivity("com.taobao.tao.detail.activity.DetailActivity"));
        assertFalse(TargetApp.TIEBA.matchesActivity("com.baidu.tieba.sidebar.SideBarAssistActivity"));
    }
}

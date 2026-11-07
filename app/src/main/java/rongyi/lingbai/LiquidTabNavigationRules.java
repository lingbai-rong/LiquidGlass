package rongyi.lingbai;

/**
 * Host symbols recovered from the user-supplied LiquidTab 1.0.0 (216) APK.
 * This is an interoperability description, not a bundled copy of its Xposed runtime.
 */
final class LiquidTabNavigationRules {
    static final String MARKET_ROW = "com.xiaomi.market.widget.BottomTabLayout";
    static final String MARKET_ITEM = "com.xiaomi.market.widget.TabView";
    static final String COMMUNITY_ROW = "com.xiaomi.vipaccount.ui.widget.tab.BottomNavView";
    static final String COMMUNITY_ITEM = "com.xiaomi.vipaccount.ui.widget.tab.NavItemView";
    static final String COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView";
    static final String[] TIEBA_ROWS = {
            "com.baidu.tbadk.core.tabHost.FragmentTabWidget",
            "com.baidu.tieba.tbui.widget.FragmentTabWidget",
            "com.baidu.tieba.widget.FragmentTabWidget"
    };

    private LiquidTabNavigationRules() {}

    static boolean supports(TargetApp app) {
        return app == TargetApp.MI_MARKET || app == TargetApp.MI_COMMUNITY
                || app == TargetApp.WEIBO || app == TargetApp.TIEBA
                || app == TargetApp.TAOBAO || app == TargetApp.BILIBILI_IN;
    }

    static boolean matchesRow(TargetApp app, String className, String resourceName) {
        if (app == TargetApp.MI_MARKET) return MARKET_ROW.equals(className);
        if (app == TargetApp.MI_COMMUNITY) return COMMUNITY_ROW.equals(className);
        if (app == TargetApp.WEIBO) {
            return "main_radio".equals(resourceName) || "main_theme_radio".equals(resourceName);
        }
        if (app == TargetApp.TIEBA) {
            for (String name : TIEBA_ROWS) if (name.equals(className)) return true;
            return false;
        }
        if (app == TargetApp.TAOBAO) {
            return "android.widget.TabWidget".equals(className) && "tabs".equals(resourceName);
        }
        return app == TargetApp.BILIBILI_IN && COMPOSE_VIEW.equals(className)
                && "tab_host".equals(resourceName);
    }

    static boolean validSlotCount(TargetApp app, int count) {
        return supports(app) && count == app.labels.length;
    }

    static float slotCenter(int nativeIndex, int slotCount, int width) {
        if (nativeIndex < 0 || nativeIndex >= slotCount || width <= 0) return -1f;
        return (nativeIndex + 0.5f) * width / slotCount;
    }
}

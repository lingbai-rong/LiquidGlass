package rongyi.lingbai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static description of every non-CoolAPK host supported by LiquidGlass 3.25. */
public final class TargetApp {
    public static final TargetApp MAGISK = new TargetApp(
            "magisk", "com.topjohnwu.magisk", "Magisk",
            new String[]{"com.topjohnwu.magisk.ui.MainActivity"},
            new String[]{"主页", "超级用户", "日志", "模块"}, new int[]{0, 1, 2, 3},
            new String[0], new int[0], false
    );
    public static final TargetApp GITHUB = new TargetApp(
            "github", "com.github.android", "GitHub",
            new String[]{"com.github.android.main.MainActivity"},
            new String[]{"主页", "Inbox", "探索", "Copilot"}, new int[]{0, 1, 2, 3},
            new String[0], new int[0], false
    );
    public static final TargetApp WECHAT = new TargetApp(
            "wechat", "com.tencent.mm", "微信",
            new String[]{"com.tencent.mm.ui.LauncherUI"},
            new String[]{"微信", "通讯录", "发现", "我"}, new int[]{0, 1, 2, 3},
            new String[]{"隐藏发现"}, new int[]{2}, false
    );
    public static final TargetApp JD = new TargetApp(
            "jd", "com.jingdong.app.mall", "京东",
            new String[]{"com.jingdong.app.mall.MainFrameActivity"},
            // JD 15.9.50 maps function ids to sparse native ids:
            // index=0, find=2, messagenew=11, cart=3, home=4.
            new String[]{"首页", "逛", "消息", "购物车", "我的"}, new int[]{0, 2, 11, 3, 4},
            new String[]{"隐藏逛"}, new int[]{1}, false
    );
    public static final TargetApp TIM = new TargetApp(
            "tim", "com.tencent.tim", "TIM",
            new String[]{"com.tencent.mobileqq.activity.SplashActivity"},
            new String[]{"消息", "联系人", "我的"}, new int[]{0, 1, 2},
            new String[0], new int[0], false
    );
    public static final TargetApp MOONDROP = new TargetApp(
            "moondrop", "com.moondroplab.moondrop.moondrop_app", "水月雨 MOONDROP",
            new String[]{"com.moondroplab.moondrop.moondrop_app.MainActivity"},
            new String[]{"产品信息", "设置", "我的"}, new int[]{0, 1, 2},
            new String[]{"隐藏产品信息"}, new int[]{0}, false
    );
    public static final TargetApp BILIBILI = new TargetApp(
            "bilibili", "tv.danmaku.bili", "哔哩哔哩",
            new String[]{"tv.danmaku.bili.MainActivityV2"},
            new String[]{"首页", "关注", "会员购", "我的"}, new int[]{0, 1, 3, 4},
            new String[]{"隐藏会员购"}, new int[]{2}, true
    );
    public static final TargetApp BILIBILI_HD = new TargetApp(
            "bilibili_hd", "tv.danmaku.bilibilihd", "哔哩哔哩 HD",
            new String[]{"tv.danmaku.bili.MainActivityV2"},
            new String[]{"首页", "动态", "我的"}, new int[]{0, 1, 2},
            new String[0], new int[0], false
    );
    public static final TargetApp BILIBILI_IN = new TargetApp(
            "bilibili_in", "com.bilibili.app.in", "哔哩哔哩国际版",
            new String[]{"tv.danmaku.bili.MainActivityV2"},
            new String[]{"首页", "关注", "消息", "我的"}, new int[]{0, 1, 2, 3},
            new String[0], new int[0], false
    );
    public static final TargetApp MI_MARKET = new TargetApp(
            "mi_market", "com.xiaomi.market", "小米应用商店",
            new String[]{"com.xiaomi.market.business_ui.main.MarketTabActivity"},
            new String[]{"首页", "游戏", "应用", "榜单", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[0], new int[0], false
    );
    public static final TargetApp MI_COMMUNITY = new TargetApp(
            "mi_community", "com.xiaomi.vipaccount", "小米社区",
            new String[]{
                    "com.xiaomi.mi.launch.LaunchActivity",
                    "com.xiaomi.vipaccount.ui.home.page.HomeFrameActivity"
            },
            new String[]{"论坛", "官方", "活动", "消息", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[0], new int[0], false
    );
    public static final TargetApp WEIBO = new TargetApp(
            "weibo", "com.sina.weibo", "微博",
            new String[]{
                    "com.sina.weibo.MainTabActivity",
                    "com.sina.weibo.MainActivity",
                    "com.sina.weibo.SplashActivity"
            },
            new String[]{"首页", "视频", "发现", "消息", "我"}, new int[]{0, 1, 2, 3, 4},
            new String[0], new int[0], false
    );
    public static final TargetApp TIEBA = new TargetApp(
            "tieba", "com.baidu.tieba", "百度贴吧",
            new String[]{
                    "com.baidu.tieba.tblauncher.MainTabActivity",
                    "com.baidu.tieba.app.MainActivity"
            },
            new String[]{"首页", "进吧", "小卖部", "消息", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[0], new int[0], false
    );
    public static final TargetApp TAOBAO = new TargetApp(
            "taobao", "com.taobao.taobao", "淘宝",
            new String[]{"com.taobao.tao.welcome.Welcome"},
            new String[]{"首页", "视频", "消息", "购物车", "我的淘宝"}, new int[]{0, 1, 2, 3, 4},
            new String[0], new int[0], false
    );
    public static final TargetApp MIDEA = new TargetApp(
            "midea", "com.midea.ai.appliances", "美的美居",
            new String[]{"com.midea.meiju.main.MainActivity"},
            new String[]{"智控", "服务", "AI", "商城", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[]{"隐藏服务", "隐藏 AI", "隐藏商城"}, new int[]{1, 2, 3}, false,
            2
    );
    public static final TargetApp MEITUAN = new TargetApp(
            "meituan", "com.sankuai.meituan.takeoutnew", "美团外卖",
            new String[]{"com.sankuai.waimai.business.page.homepage.MainActivity"},
            new String[]{"首页", "神券", "订单", "我的"}, new int[]{0, 1, 3, 4},
            new String[0], new int[0], false
    );
    public static final TargetApp CAINIAO = new TargetApp(
            "cainiao", "com.cainiao.wireless", "菜鸟",
            new String[]{
                    "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
                    "com.cainiao.wireless.homepage.view.activity.WelcomeActivity"
            },
            // These stable route slots are resolved by CainiaoAdapter against the active remote
            // model. They are not positions in NavigationView: 8.11.825 ships several tab lists.
            new String[]{"首页", "发现", "购物券", "消息", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[]{"隐藏发现", "隐藏购物券", "隐藏消息"}, new int[]{1, 2, 3}, false
    );
    public static final TargetApp MEITUAN_MAIN = new TargetApp(
            "meituan_main", "com.sankuai.meituan", "美团",
            new String[]{"com.meituan.android.pt.homepage.activity.MainActivity"},
            new String[]{"推荐", "视频", "AI", "购物车", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[0], new int[0], false,
            2
    );
    public static final TargetApp MIHOME = new TargetApp(
            "mihome", "com.xiaomi.smarthome", "米家",
            new String[]{"com.xiaomi.smarthome.SmartHomeMainActivity"},
            new String[]{"米家", "智能", "产品", "商城", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[]{"隐藏产品", "隐藏商城"}, new int[]{2, 3}, false
    );
    public static final TargetApp MI_FITNESS = new TargetApp(
            "mi_fitness", "com.mi.health", "小米运动健康",
            new String[]{"com.xiaomi.fitness.main.MainActivity"},
            new String[]{"健康", "运动", "设备", "我的"}, new int[]{0, 1, 2, 3},
            new String[0], new int[0], false
    );
    public static final TargetApp NETEASE = new TargetApp(
            "netease", "com.netease.cloudmusic", "网易云音乐",
            new String[]{"com.netease.cloudmusic.activity.MainActivity"},
            new String[]{"首页", "搜索", "笔记", "我的"}, new int[]{0, 1, 2, 3},
            new String[]{"隐藏搜索", "隐藏笔记"}, new int[]{1, 2}, false
    );
    public static final TargetApp UUREMOTE = new TargetApp(
            "uuremote", "com.netease.uuremote", "UU远程",
            new String[]{
                    "com.remote.app.ui.activity.ComposeMainActivity",
                    "com.remote.app.ui.activity.MainActivity"
            },
            // Legacy MainActivity keeps Device(0), Assist(1), CloudPc(2), Mine(3). The 4.38
            // Compose adapter owns its newer Device/CloudPc/Assist/Mine mapping independently.
            new String[]{"我的设备", "云设备", "远程协助", "我的"}, new int[]{0, 2, 1, 3},
            new String[0], new int[0], false
    );
    public static final TargetApp STARDESK = new TargetApp(
            "stardesk", "com.stardesk", "StarDesk",
            new String[]{"com.remote.app.ui.activity.MainActivity"},
            new String[]{"我的裝置", "遠程協助", "個人資料"}, new int[]{0, 1, 3},
            new String[0], new int[0], false
    );
    public static final TargetApp QQMUSIC = new TargetApp(
            "qqmusic", "com.tencent.qqmusic", "QQ音乐",
            new String[]{"com.tencent.qqmusic.activity.AppStarterActivity"},
            new String[]{"首页", "视频", "刷歌", "星光", "我的"}, new int[]{1, 2, 4, 3, 0},
            new String[0], new int[0], false
    );
    public static final TargetApp XHS = new TargetApp(
            "xhs", "com.xingin.xhs", "小红书",
            new String[]{"com.xingin.xhs.index.v2.IndexActivityV2"},
            new String[]{"首页", "市集", "消息", "我的"}, new int[]{0, 1, 2, 3},
            new String[0], new int[0], true
    );
    public static final TargetApp PDD = new TargetApp(
            "pdd", "com.xunmeng.pinduoduo", "拼多多",
            new String[]{"com.xunmeng.pinduoduo.ui.activity.MainFrameActivity"},
            new String[]{"首页", "直播", "领券", "消息", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[]{"隐藏直播", "隐藏领券"}, new int[]{1, 2}, false
    );
    public static final TargetApp DOUYIN = new TargetApp(
            "douyin", "com.ss.android.ugc.aweme", "抖音",
            new String[]{"com.ss.android.ugc.aweme.main.MainActivity"},
            new String[]{"首页", "朋友", "发视频", "消息", "我的"}, new int[]{0, 1, 2, 3, 4},
            new String[]{"隐藏朋友", "隐藏发视频", "隐藏消息"}, new int[]{1, 2, 3}, false,
            2
    );

    public static final TargetApp MI_FILE_MANAGER = new TargetApp(
            "mi_file_manager", "com.android.fileexplorer", "文件管理",
            new String[]{
                    "com.android.fileexplorer.FileExplorerTabActivity",
                    "com.android.fileexplorer.activity.PrivateFolderActivity"
            },
            new String[]{"最近", "浏览", "云盘"}, new int[]{1000, 1001, 1002},
            new String[]{"隐藏云盘"}, new int[]{2}, false
    );
    public static final TargetApp MI_PHONE = new TargetApp(
            "mi_phone", "com.android.contacts", "电话",
            new String[]{
                    "com.android.contacts.activities.TwelveKeyDialer",
                    "com.android.contacts.activities.DialtactsActivity",
                    "com.android.contacts.activities.PeopleActivity",
                    "com.android.contacts.activities.MainActivity",
                    "com.android.contacts.ui.MainActivity"
            },
            new String[]{"通话", "联系人"}, new int[]{0, 1},
            new String[0], new int[0], false
    );
    public static final TargetApp MI_MESSAGES = new TargetApp(
            "mi_messages", "com.android.mms", "短信",
            new String[]{"com.android.mms.ui.MmsTabActivity"},
            new String[]{"主要", "推广"}, new int[]{1000, 1001},
            new String[0], new int[0], false
    );
    public static final TargetApp MI_CALENDAR = new TargetApp(
            "mi_calendar", "com.android.calendar", "日历",
            new String[]{"com.android.calendar.homepage.AllInOneActivity"},
            // BottomNavigatorView#getNavigatorItemView stores Month/Week/Day/Year as
            // [0, 1, 2, 3]. The native visual order and the requested glass order are
            // Year/Month/Week/Day, hence this explicit mapping.
            new String[]{"年", "月", "周", "日"}, new int[]{3, 0, 1, 2},
            new String[0], new int[0], false
    );
    public static final TargetApp MI_GALLERY = new TargetApp(
            "mi_gallery", "com.miui.gallery", "相册",
            new String[]{"com.miui.gallery.activity.HomePageActivity"},
            new String[]{"照片", "影集"}, new int[]{1000, 1001},
            new String[0], new int[0], false
    );
    public static final TargetApp MI_NOTES = new TargetApp(
            "mi_notes", "com.miui.notes", "笔记",
            new String[]{"com.miui.notes.ui.NotesListActivity"},
            new String[]{"笔记", "待办"}, new int[]{0, 1},
            new String[0], new int[0], false
    );
    public static final TargetApp MI_COMPASS = new TargetApp(
            "mi_compass", "com.miui.compass", "指南针",
            new String[]{"com.miui.compass.CompassActivity"},
            new String[]{"指南针", "水平仪"}, new int[]{0, 1},
            new String[0], new int[0], false
    );

    public static final TargetApp AMAP = new TargetApp(
        "amap", "com.autonavi.minimap", "高德地图",
        new String[]{"com.autonavi.map.activity.NewMapActivity"},
        new String[]{"首页", "附近", "消息", "打车", "我的"}, new int[]{0, 1, 2, 3, 4},
        new String[0], new int[0], false
    );
    public static final TargetApp XIANYU = new TargetApp(
        "xianyu", "com.taobao.idlefish", "闲鱼",
        new String[]{"com.taobao.fleamarket.home.activity.MainActivity", "com.taobao.fleamarket.home.activity.InitActivity"},
        new String[]{"闲鱼", "会玩", "发布", "消息", "我的"}, new int[]{0, 1, 2, 3, 4},
        new String[0], new int[0], false, 2
    );
    public static final TargetApp YOUTUBE = new TargetApp(
        "youtube", "com.google.android.youtube", "YouTube",
        new String[]{"com.google.android.apps.youtube.app.WatchWhileActivity", "com.google.android.apps.youtube.app.watchwhile.MainActivity"},
        new String[]{"首页", "Shorts", "订阅内容", "通知", "你"}, new int[]{0, 1, 2, 3, 4},
        new String[0], new int[0], false
    );
    public static final TargetApp XJTU = new TargetApp(
        "xjtu", "com.supwisdom.xjtu", "移动交通大学",
        new String[]{},
        new String[]{"首页", "服务", "事务", "日程", "我的"}, new int[]{0, 1, 2, 3, 4},
        new String[0], new int[0], false
    );
    public static final TargetApp ZHIHU = new TargetApp(
        "zhihu", "com.zhihu.android", "知乎",
        new String[]{"com.zhihu.android.app.ui.activity.MainActivity"},
        new String[]{"首页", "推荐", "直答", "消息", "我的"}, new int[]{0, 1, 2, 3, 4},
        new String[0], new int[0], false
    );

    private static final List<TargetApp> MI_SYSTEM_APPS = Collections.unmodifiableList(Arrays.asList(
            MI_FILE_MANAGER, MI_PHONE, MI_MESSAGES, MI_CALENDAR, MI_GALLERY, MI_NOTES, MI_COMPASS
    ));

    private static final List<TargetApp> ALL = Collections.unmodifiableList(Arrays.asList(
            MAGISK, GITHUB, WECHAT, JD, TIM, MOONDROP, BILIBILI, BILIBILI_HD, MIDEA, MEITUAN, CAINIAO, MEITUAN_MAIN, MIHOME, MI_FITNESS, NETEASE,
            BILIBILI_IN, MI_MARKET, MI_COMMUNITY, WEIBO, TIEBA, TAOBAO,
            QQMUSIC, UUREMOTE, STARDESK, XHS, PDD, DOUYIN, AMAP, XIANYU, YOUTUBE, XJTU, ZHIHU,
            MI_FILE_MANAGER, MI_PHONE, MI_MESSAGES, MI_CALENDAR, MI_GALLERY, MI_NOTES, MI_COMPASS
    ));
    private static final Map<String, TargetApp> BY_PACKAGE;
    private static final Map<String, TargetApp> BY_KEY;

    static {
        Map<String, TargetApp> byPackage = new LinkedHashMap<>();
        Map<String, TargetApp> byKey = new LinkedHashMap<>();
        for (TargetApp app : ALL) {
            byPackage.put(app.packageName, app);
            byKey.put(app.key, app);
        }
        BY_PACKAGE = Collections.unmodifiableMap(byPackage);
        BY_KEY = Collections.unmodifiableMap(byKey);
    }

    public final String key;
    public final String packageName;
    public final String displayName;
    public final String[] mainActivities;
    public final String[] labels;
    public final int[] nativeIds;
    public final String[] optionLabels;
    public final int[] optionLabelIndexes;
    public final boolean hasPostAction;
    /** Visible label index rendered as a CoolAPK-style circular center action, or -1. */
    public final int centerActionLabelIndex;

    private TargetApp(
            String key,
            String packageName,
            String displayName,
            String[] mainActivities,
            String[] labels,
            int[] nativeIds,
            String[] optionLabels,
            int[] optionLabelIndexes,
            boolean hasPostAction
    ) {
        this(
                key, packageName, displayName, mainActivities, labels, nativeIds,
                optionLabels, optionLabelIndexes, hasPostAction, -1
        );
    }

    private TargetApp(
            String key,
            String packageName,
            String displayName,
            String[] mainActivities,
            String[] labels,
            int[] nativeIds,
            String[] optionLabels,
            int[] optionLabelIndexes,
            boolean hasPostAction,
            int centerActionLabelIndex
    ) {
        this.key = key;
        this.packageName = packageName;
        this.displayName = displayName;
        this.mainActivities = mainActivities;
        this.labels = labels;
        this.nativeIds = nativeIds;
        this.optionLabels = optionLabels;
        this.optionLabelIndexes = optionLabelIndexes;
        this.hasPostAction = hasPostAction;
        this.centerActionLabelIndex = centerActionLabelIndex;
    }

    public static List<TargetApp> all() {
        return ALL;
    }

    public static List<TargetApp> miSystemApps() {
        return MI_SYSTEM_APPS;
    }

    public boolean isMiSystemApp() {
        return MI_SYSTEM_APPS.contains(this);
    }

    public static TargetApp fromPackage(String packageName) {
        return BY_PACKAGE.get(packageName);
    }

    public static TargetApp fromKey(String key) {
        return BY_KEY.get(key);
    }

    public boolean matchesActivity(String className) {
        if (className == null) return false;
        for (String activity : mainActivities) {
            if (activity.equals(className)) return true;
        }
        // Pinduoduo occasionally suffixes/repackages MainFrameActivity while retaining the exact
        // PddTabView host. Limit the fallback to its own package and main-frame class family.
        return "pdd".equals(key)
                && className.startsWith("com.xunmeng.pinduoduo.")
                && className.contains("MainFrameActivity");
    }

    public String[] visibleLabels(TargetAppSettings settings) {
        List<String> result = new ArrayList<>();
        int hidden = settings == null ? 0 : settings.hiddenMask;
        for (int i = 0; i < labels.length; i++) {
            if ((hidden & (1 << i)) != 0) continue;
            String label = labels[i];
            if (this == WECHAT && i == 0 && settings != null) {
                label = settings.wechatFirstTabLabel;
            }
            result.add(label);
        }
        return result.toArray(new String[0]);
    }

    public int[] visibleNativeIds(TargetAppSettings settings) {
        List<Integer> result = new ArrayList<>();
        int hidden = settings == null ? 0 : settings.hiddenMask;
        for (int i = 0; i < nativeIds.length; i++) {
            if ((hidden & (1 << i)) == 0) result.add(nativeIds[i]);
        }
        int[] values = new int[result.size()];
        for (int i = 0; i < result.size(); i++) values[i] = result.get(i);
        return values;
    }

    /** Maps the configured source label index into the current visible-label list. */
    public int visibleCenterActionIndex(TargetAppSettings settings) {
        if (centerActionLabelIndex < 0 || centerActionLabelIndex >= labels.length) return -1;
        int hidden = settings == null ? 0 : settings.hiddenMask;
        if ((hidden & (1 << centerActionLabelIndex)) != 0) return -1;
        int visible = 0;
        for (int i = 0; i < centerActionLabelIndex; i++) {
            if ((hidden & (1 << i)) == 0) visible++;
        }
        return visible;
    }
}

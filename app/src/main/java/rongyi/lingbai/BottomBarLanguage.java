package rongyi.lingbai;

/**
 * Converts labels supplied by adapted hosts for the injected bottom bar.
 *
 * The host keeps its original labels for routing and accessibility. Conversion is therefore
 * deliberately render-only and can also handle labels that arrive dynamically (for example,
 * file-manager action mode commands).
 */
public final class BottomBarLanguage {
    private static final String[][] PHRASE_REPLACEMENTS = new String[][]{
            {"首页", "首頁"},
            {"主页", "主頁"},
            {"新发现", "新發現"},
            {"哔哩哔哩", "嗶哩嗶哩"},
            {"数码", "數碼"},
            {"发现", "發現"},
            {"通讯录", "通訊錄"},
            {"产品信息", "產品資訊"},
            {"我的设备", "我的裝置"},
            {"个人资料", "個人資料"},
            {"联系人", "聯絡人"},
            {"搜索", "搜尋"},
            {"相册", "相簿"},
            {"文件管理", "檔案管理"},
            {"短信", "簡訊"},
            {"消息", "訊息"},
            {"购物车", "購物車"},
            {"会员购", "會員購"},
            {"发视频", "發視頻"},
            {"发送", "傳送"},
            {"推广", "推廣"},
            {"资料库", "資料庫"},
            {"日志", "日誌"},
            {"日历", "日曆"},
            {"指南针", "指南針"},
            {"水平仪", "水平儀"},
            {"超级用户", "超級用戶"},
            {"产品", "產品"},
            {"设置", "設定"},
    };

    private BottomBarLanguage() {}

    /** Returns {@code value} unchanged unless traditional Chinese rendering is enabled. */
    public static String display(String value, boolean traditionalChinese) {
        if (!traditionalChinese || value == null || value.isEmpty()) return value;

        String result = value;
        // Phrase substitutions cover locale-specific UI terms whose Traditional wording is not
        // a one-to-one character conversion (for example 搜索 -> 搜尋 and 相册 -> 相簿).
        for (String[] replacement : PHRASE_REPLACEMENTS) {
            result = result.replace(replacement[0], replacement[1]);
        }

        StringBuilder converted = new StringBuilder(result.length());
        for (int i = 0; i < result.length(); i++) {
            converted.append(toTraditionalCharacter(result.charAt(i)));
        }
        return converted.toString();
    }

    private static char toTraditionalCharacter(char value) {
        switch (value) {
            case '东': return '東';
            case '乐': return '樂';
            case '书': return '書';
            case '哔': return '嗶';
            case '页': return '頁';
            case '数': return '數';
            case '码': return '碼';
            case '发': return '發';
            case '现': return '現';
            case '讯': return '訊';
            case '录': return '錄';
            case '产': return '產';
            case '资': return '資';
            case '设': return '設';
            case '联': return '聯';
            case '个': return '個';
            case '传': return '傳';
            case '册': return '冊';
            case '购': return '購';
            case '车': return '車';
            case '会': return '會';
            case '员': return '員';
            case '动': return '動';
            case '态': return '態';
            case '关': return '關';
            case '视': return '視';
            case '频': return '頻';
            case '广': return '廣';
            case '库': return '庫';
            case '笔': return '筆';
            case '记': return '記';
            case '待': return '待';
            case '办': return '辦';
            case '针': return '針';
            case '仪': return '儀';
            case '删': return '刪';
            case '云': return '雲';
            case '盘': return '盤';
            case '浏': return '瀏';
            case '览': return '覽';
            case '话': return '話';
            case '络': return '絡';
            case '扩': return '擴';
            case '远': return '遠';
            case '协': return '協';
            case '户': return '戶';
            case '级': return '級';
            case '块': return '塊';
            case '志': return '誌';
            case '荐': return '薦';
            case '务': return '務';
            case '领': return '領';
            case '单': return '單';
            case '卖': return '賣';
            case '历': return '歷';
            case '团': return '團';
            case '备': return '備';
            case '电': return '電';
            case '红': return '紅';
            case '网': return '網';
            case '装': return '裝';
            case '订': return '訂';
            case '闭': return '閉';
            case '隐': return '隱';
            case '周': return '週';
            case '简': return '簡';
            case '体': return '體';
            case '语': return '語';
            case '栏': return '欄';
            case '开': return '開';
            case '选': return '選';
            case '项': return '項';
            case '进': return '進';
            case '从': return '從';
            case '为': return '為';
            case '与': return '與';
            default: return value;
        }
    }
}

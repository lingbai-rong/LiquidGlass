package rongyi.lingbai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Apps
import com.composables.icons.materialsymbols.outlinedfilled.Celebration
import com.composables.icons.materialsymbols.outlinedfilled.Chat
import com.composables.icons.materialsymbols.outlinedfilled.Dynamic_feed
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Forum
import com.composables.icons.materialsymbols.outlinedfilled.Groups
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Leaderboard
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Person
import com.composables.icons.materialsymbols.outlinedfilled.Shopping_cart
import com.composables.icons.materialsymbols.outlinedfilled.Sports_esports
import com.composables.icons.materialsymbols.outlinedfilled.Storefront
import com.composables.icons.materialsymbols.outlinedfilled.Verified

/** Used only by the new bridges when a native icon has not loaded yet. */
@Composable
internal fun PortedNavigationIcon(label: String, color: Color, modifier: Modifier) {
    val key = label.trim().lowercase()
    if (key in setOf("搜索","搜尋","search","附近","nearby","地图","地圖","打车","打車","taxi",
        "购物券","購物券","领券","領券","coupons","shorts","订阅内容","訂閱內容","subscriptions",
        "通知","notifications","服务","服務","services","事务","事務","tasks","日程","schedule",
        "笔记","筆記","notes","直答","会玩","會玩","朋友","friends","闲鱼","閒魚","发布","發佈","发视频","發視頻","create","音乐","音樂","music")) {
        Canvas(modifier) { drawSupplementalNavigationIcon(label, color) }
        return
    }
    val icon = when (label) {
        "\u9996\u9875" -> MaterialSymbols.OutlinedFilled.Home
        "\u6e38\u620f" -> MaterialSymbols.OutlinedFilled.Sports_esports
        "\u5e94\u7528" -> MaterialSymbols.OutlinedFilled.Apps
        "\u699c\u5355" -> MaterialSymbols.OutlinedFilled.Leaderboard
        "\u8bba\u575b" -> MaterialSymbols.OutlinedFilled.Forum
        "\u5b98\u65b9" -> MaterialSymbols.OutlinedFilled.Verified
        "\u6d3b\u52a8" -> MaterialSymbols.OutlinedFilled.Celebration
        "\u89c6\u9891" -> MaterialSymbols.OutlinedFilled.Movie
        "\u53d1\u73b0" -> MaterialSymbols.OutlinedFilled.Explore
        "\u6d88\u606f" -> MaterialSymbols.OutlinedFilled.Chat
        "\u8fdb\u5427" -> MaterialSymbols.OutlinedFilled.Groups
        "\u5c0f\u5356\u90e8" -> MaterialSymbols.OutlinedFilled.Storefront
        "\u8d2d\u7269\u8f66" -> MaterialSymbols.OutlinedFilled.Shopping_cart
        "\u5173\u6ce8" -> MaterialSymbols.OutlinedFilled.Dynamic_feed
        "我", "我的", "我的淘宝", "個人資料" -> MaterialSymbols.OutlinedFilled.Person
        else -> null
    }
    if (icon == null) {
        Canvas(modifier) { drawAdaptiveNavIcon(label, color) }
        return
    }
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

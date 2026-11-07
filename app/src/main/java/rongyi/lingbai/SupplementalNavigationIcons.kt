package rongyi.lingbai

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.sin

/** Only fills gaps in the existing fallback family; host-provided icons always take priority. */
internal fun DrawScope.drawSupplementalNavigationIcon(label: String, color: Color): Boolean {
    val key = label.trim().lowercase()
    val kind = when (key) {
        "全部快递", "全部快遞", "快递", "快遞" -> "parcel"
        "积分", "積分" -> "points"
        "指南针", "指南針" -> "compass"
        "水平仪", "水平儀" -> "level"
        "刷歌", "音乐", "音樂", "music" -> "music"
        "搜索", "搜尋", "search" -> "search"
        "附近", "nearby", "地图", "地圖" -> "pin"
        "打车", "打車", "taxi" -> "car"
        "购物券", "購物券", "领券", "領券", "coupons" -> "coupon"
        "shorts" -> "shorts"
        "订阅内容", "訂閱內容", "subscriptions" -> "subscriptions"
        "通知", "notifications" -> "bell"
        "服务", "服務", "services" -> "services"
        "事务", "事務", "tasks" -> "tasks"
        "日程", "schedule" -> "calendar"
        "笔记", "筆記", "notes", "直答" -> "notes"
        "会玩", "會玩", "朋友", "friends" -> "friends"
        "闲鱼", "閒魚" -> "fish"
        "发布", "發佈", "发视频", "發視頻", "create" -> "plus"
        "星光" -> "star"
        else -> return false
    }
    val s = size.minDimension / 24f
    val stroke = Stroke(1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun point(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, point(x1, y1), point(x2, y2), stroke.width, StrokeCap.Round)
    fun path(vararg points: Pair<Float, Float>, closed: Boolean = false) {
        val p = Path().apply {
            points.forEachIndexed { index, xy ->
                if (index == 0) moveTo(xy.first * s, xy.second * s)
                else lineTo(xy.first * s, xy.second * s)
            }
            if (closed) close()
        }
        drawPath(p, color, style = stroke)
    }
    translate(size.width / 2f, size.height / 2f) {
        when (kind) {
            "search" -> { drawCircle(color,6f*s,point(-2f,-2f),style=stroke); line(3f,3f,9f,9f) }
            "pin" -> { path(0f to 10f,-7f to 0f,-7f to -5f,-3f to -9f,3f to -9f,7f to -5f,7f to 0f,closed=true); drawCircle(color,2.5f*s,point(0f,-3f),style=stroke) }
            "car" -> { drawRoundRect(color,point(-9f,-2f),Size(18f*s,10f*s),CornerRadius(2f*s),style=stroke); path(-7f to -2f,-5f to -8f,5f to -8f,7f to -2f); line(-6f,8f,-6f,10f); line(6f,8f,6f,10f); line(-6f,2f,-4f,2f); line(4f,2f,6f,2f) }
            "coupon" -> { path(-9f to -7f,9f to -7f,9f to -3f,6f to 0f,9f to 3f,9f to 7f,-9f to 7f,-9f to 3f,-6f to 0f,-9f to -3f,closed=true); line(1f,-4f,1f,-2f); line(1f,1f,1f,4f) }
            "shorts" -> { path(3f to -10f,8f to -8f,8f to -3f,-3f to 10f,-8f to 8f,-8f to 3f,closed=true); path(-2f to -3f,4f to 0f,-2f to 3f,closed=true) }
            "subscriptions" -> { line(-6f,-10f,6f,-10f); line(-8f,-6f,8f,-6f); drawRoundRect(color,point(-10f,-2f),Size(20f*s,12f*s),CornerRadius(2f*s),style=stroke); path(-2f to 1f,3f to 4f,-2f to 7f,closed=true) }
            "bell" -> { path(-8f to 6f,-6f to 2f,-6f to -5f,-3f to -8f,3f to -8f,6f to -5f,6f to 2f,8f to 6f,closed=true); line(-2f,10f,2f,10f) }
            "services" -> { for (x in listOf(-8f,2f)) for (y in listOf(-8f,2f)) drawRoundRect(color,point(x,y),Size(6f*s,6f*s),CornerRadius(1f*s),style=stroke) }
            "tasks" -> { drawRoundRect(color,point(-8f,-8f),Size(16f*s,18f*s),CornerRadius(2f*s),style=stroke); line(-3f,-10f,3f,-10f); path(-5f to 0f,-2f to 3f,5f to -4f) }
            "calendar" -> { drawRoundRect(color,point(-9f,-7f),Size(18f*s,17f*s),CornerRadius(2f*s),style=stroke); line(-9f,-2f,9f,-2f); line(-5f,-10f,-5f,-5f); line(5f,-10f,5f,-5f); drawCircle(color,1f*s,point(-3f,3f)); drawCircle(color,1f*s,point(3f,3f)) }
            "notes" -> { path(-8f to -10f,4f to -10f,8f to -6f,8f to 10f,-8f to 10f,closed=true); line(-4f,-2f,4f,-2f); line(-4f,3f,4f,3f); line(-4f,7f,1f,7f) }
            "friends" -> { drawCircle(color,3.5f*s,point(-3f,-5f),style=stroke); drawCircle(color,2.5f*s,point(6f,-3f),style=stroke); path(-10f to 9f,-9f to 3f,-3f to 0f,3f to 3f,4f to 9f); path(4f to 2f,9f to 3f,10f to 8f) }
            "fish" -> { path(-10f to 0f,-4f to -7f,5f to -6f,9f to 0f,5f to 6f,-4f to 7f,closed=true); path(9f to 0f,12f to -5f,12f to 5f,closed=true); drawCircle(color,1f*s,point(-4f,-1f)) }
            "plus" -> { line(-8f,0f,8f,0f); line(0f,-8f,0f,8f) }
            "parcel" -> {
                path(0f to -9f, 8f to -5f, 8f to 5f, 0f to 9f, -8f to 5f, -8f to -5f, closed = true)
                path(-8f to -5f, 0f to -1f, 8f to -5f)
                line(0f, -1f, 0f, 9f)
                path(-4f to -7f, 4f to -3f, 4f to 1f)
            }
            "points" -> {
                drawCircle(color, 9f * s, Offset.Zero, style = stroke)
                path(0f to -5f, 1.6f to -1.7f, 5f to -1.4f, 2.4f to 1f,
                    3.2f to 4.5f, 0f to 2.8f, -3.2f to 4.5f, -2.4f to 1f,
                    -5f to -1.4f, -1.6f to -1.7f, closed = true)
            }
            "compass" -> {
                drawCircle(color, 9f * s, Offset.Zero, style = stroke)
                path(5f to -5f, 1.8f to 1.8f, -5f to 5f, -1.8f to -1.8f, closed = true)
                line(-1.8f, -1.8f, 1.8f, 1.8f)
            }
            "level" -> {
                drawRoundRect(color, point(-10f, -5f), Size(20f * s, 10f * s), CornerRadius(2f * s), style = stroke)
                line(-4f, -5f, -4f, 5f)
                line(4f, -5f, 4f, 5f)
                drawCircle(color, 2f * s, Offset.Zero, style = stroke)
            }
            "music" -> {
                path(-3f to 5f, -3f to -7f, 7f to -9f, 7f to 3f)
                line(-3f, -3f, 7f, -5f)
                drawOval(color, point(-9f, 3f), Size(6f * s, 5f * s))
                drawOval(color, point(1f, 1f), Size(6f * s, 5f * s))
            }
            "star" -> {
                val star = Path().apply {
                    for (i in 0 until 10) {
                        val angle = -Math.PI / 2 + i * Math.PI / 5
                        val radius = (if (i % 2 == 0) 9f else 4f) * s
                        val x = cos(angle).toFloat() * radius
                        val y = sin(angle).toFloat() * radius
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(star, color, style = stroke)
            }
        }
    }
    return true
}

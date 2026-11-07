package rongyi.lingbai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar

/**
 * Compact settings primitives matching HyperCeiler's real HomePageFragment structure: a MIUI
 * action-bar title, search anchor, preference-category labels, grouped card rows and a chevron.
 */
object HyperCeilerStyle {
    val LightPage = Color(0xFFF7F7F7)
    val DarkPage = Color(0xFF101010)
    val Accent = Color(0xFF3482F6)
    val Success = Color(0xFF32A852)
    val Warning = Color(0xFFE99A23)

    val CardShape = RoundedCornerShape(16.dp)
    val SearchShape = RoundedCornerShape(13.dp)
    val IconShape = RoundedCornerShape(11.dp)
}

@Composable
fun HyperHomeActionBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    val secondary = foreground.copy(alpha = 0.48f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { HyperBackIcon(foreground, Modifier.size(23.dp)) }
            Spacer(Modifier.width(2.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = foreground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, color = secondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun HyperPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    largeTitle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                HyperBackIcon(foreground, Modifier.size(28.dp))
            }
        }
    }
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        trailing?.invoke()
    }

    if (largeTitle) {
        TopAppBar(
            title = title,
            largeTitle = title,
            subtitle = subtitle.orEmpty(),
            color = Color.Transparent,
            titleColor = foreground,
            largeTitleColor = foreground,
            defaultWindowInsetsPadding = false,
            navigationIconPadding = 8.dp,
            actionIconPadding = 8.dp,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        SmallTopAppBar(
            title = title,
            subtitle = subtitle.orEmpty(),
            color = Color.Transparent,
            titleColor = foreground,
            defaultWindowInsetsPadding = false,
            titlePadding = 12.dp,
            navigationIconPadding = 8.dp,
            actionIconPadding = 8.dp,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

@Composable
fun HyperSearchLauncher(
    placeholder: String = "搜索设置项",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(HyperCeilerStyle.SearchShape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HyperSearchIcon(foreground.copy(alpha = 0.42f), Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        Text(placeholder, color = foreground.copy(alpha = 0.42f), fontSize = 15.sp)
    }
}

@Composable
fun HyperSearchAnchor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "搜索设置项",
    modifier: Modifier = Modifier,
) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    val secondary = foreground.copy(alpha = 0.42f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(HyperCeilerStyle.SearchShape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HyperSearchIcon(secondary, Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = foreground, fontSize = 15.sp),
            modifier = Modifier.weight(1f),
            decorationBox = { inner: @Composable () -> Unit ->
                if (value.isEmpty()) Text(placeholder, color = secondary, fontSize = 15.sp)
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = secondary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun HyperSectionTitle(text: String, modifier: Modifier = Modifier) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    Text(
        text = text,
        color = foreground.copy(alpha = 0.55f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 14.dp, top = 18.dp, bottom = 7.dp),
    )
}

@Composable
fun HyperCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HyperCeilerStyle.CardShape)
            .background(MiuixTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

@Composable
fun HyperDivider(startPadding: Int = 66) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = startPadding.dp)
            .height(0.7.dp)
            .background(foreground.copy(alpha = 0.075f)),
    )
}

@Composable
fun HyperPreferenceRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    val secondary = foreground.copy(alpha = 0.50f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(HyperCeilerStyle.IconShape)
                    .background(HyperCeilerStyle.Accent.copy(alpha = 0.11f)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Normal)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, color = secondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (trailing != null) trailing() else HyperChevronIcon(secondary, Modifier.size(18.dp))
    }
}

@Composable
fun HyperBackIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.085f
        drawLine(color, Offset(size.width * 0.76f, size.height * 0.50f), Offset(size.width * 0.25f, size.height * 0.50f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.25f, size.height * 0.50f), Offset(size.width * 0.48f, size.height * 0.27f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.25f, size.height * 0.50f), Offset(size.width * 0.48f, size.height * 0.73f), stroke, StrokeCap.Round)
    }
}

@Composable
fun HyperSearchIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawCircle(color, size.minDimension * 0.28f, Offset(size.width * 0.42f, size.height * 0.42f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.63f, size.height * 0.63f), Offset(size.width * 0.84f, size.height * 0.84f), stroke, StrokeCap.Round)
    }
}

@Composable
fun HyperChevronIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.095f
        drawLine(color, Offset(size.width * 0.36f, size.height * 0.22f), Offset(size.width * 0.64f, size.height * 0.50f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.64f, size.height * 0.50f), Offset(size.width * 0.36f, size.height * 0.78f), stroke, StrokeCap.Round)
    }
}

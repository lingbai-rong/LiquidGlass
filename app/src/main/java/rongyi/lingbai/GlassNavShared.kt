package rongyi.lingbai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

internal fun fourTabLogicalIndexToFiveSlot(index: Int): Int = when (index.coerceIn(0, 3)) {
    0 -> 0
    1 -> 1
    2 -> 3
    else -> 4
}

internal fun fiveSlotToFourTabLogicalIndex(slot: Int): Int = when (slot) {
    0 -> 0
    1 -> 1
    3 -> 2
    4 -> 3
    else -> 0
}

/**
 * Places an independent post/action slot near the middle of the currently visible navigation
 * items. Unlike the old 4+1 mapping, this remains valid when a host hides one or more tabs.
 */
internal fun dynamicPostSlotIndex(labelCount: Int): Int {
    val count = labelCount.coerceAtLeast(0)
    return ((count + 1) / 2).coerceIn(0, count)
}

internal fun logicalIndexToSlotWithPost(logicalIndex: Int, labelCount: Int): Int {
    val count = labelCount.coerceAtLeast(1)
    val logical = logicalIndex.coerceIn(0, count - 1)
    val postSlot = dynamicPostSlotIndex(count)
    return if (logical < postSlot) logical else logical + 1
}

/** Returns -1 for the post/action slot or for an invalid physical slot. */
internal fun slotToLogicalIndexWithPost(slot: Int, labelCount: Int): Int {
    val count = labelCount.coerceAtLeast(0)
    val postSlot = dynamicPostSlotIndex(count)
    if (slot == postSlot) return -1
    val logical = if (slot < postSlot) slot else slot - 1
    return if (logical in 0 until count) logical else -1
}

internal data class GlassPostButtonColors(
    val background: Color,
    val content: Color,
)

@Composable
internal fun rememberGlassPostButtonColors(settings: GlassSettings): GlassPostButtonColors {
    return remember(
        settings.customColorEnabled,
        settings.customPostColor,
    ) {
        if (settings.customColorEnabled) {
            val background = Color(settings.customPostColor)
            val luminance = 0.2126f * background.red +
                0.7152f * background.green +
                0.0722f * background.blue
            GlassPostButtonColors(
                background = background,
                content = if (luminance > 0.58f) Color(0xFF17181B) else Color.White,
            )
        } else {
            GlassPostButtonColors(
                background = Color(0xFF2FC267),
                content = Color.White,
            )
        }
    }
}

@Composable
internal fun GlassPostButtonIcon(
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension * 0.46f
        drawCircle(color = backgroundColor, radius = radius, center = center)
        val stroke = size.minDimension * 0.085f
        val half = size.minDimension * 0.19f
        drawLine(
            color = contentColor,
            start = Offset(center.x - half, center.y),
            end = Offset(center.x + half, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = contentColor,
            start = Offset(center.x, center.y - half),
            end = Offset(center.x, center.y + half),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun GlassOvalPostButtonIcon(
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val capsuleHeight = size.height * 0.88f
        val capsuleTop = (size.height - capsuleHeight) / 2f
        val capsuleCorner = capsuleHeight * 0.48f
        drawRoundRect(
            color = backgroundColor,
            topLeft = Offset(0f, capsuleTop),
            size = Size(size.width, capsuleHeight),
            cornerRadius = CornerRadius(capsuleCorner, capsuleCorner),
        )

        val stroke = size.height * 0.15f
        val half = size.height * 0.18f
        drawLine(
            color = contentColor,
            start = Offset(center.x - half, center.y),
            end = Offset(center.x + half, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = contentColor,
            start = Offset(center.x, center.y - half),
            end = Offset(center.x, center.y + half),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

internal fun DrawScope.drawDefaultNavIcon(index: Int, color: Color) {
    val scale = size.minDimension / 24f
    val detailColor = if (color.red > 0.8f && color.green > 0.8f && color.blue > 0.8f) {
        Color(0xFF303238)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    when (index) {
        0 -> {
            val path = Path().apply {
                moveTo(centerX - 8.8f * scale, centerY - 1.0f * scale)
                lineTo(centerX, centerY - 8.8f * scale)
                lineTo(centerX + 8.8f * scale, centerY - 1.0f * scale)
                lineTo(centerX + 6.6f * scale, centerY - 1.0f * scale)
                lineTo(centerX + 6.6f * scale, centerY + 8.0f * scale)
                lineTo(centerX + 2.1f * scale, centerY + 8.0f * scale)
                lineTo(centerX + 2.1f * scale, centerY + 2.8f * scale)
                lineTo(centerX - 2.1f * scale, centerY + 2.8f * scale)
                lineTo(centerX - 2.1f * scale, centerY + 8.0f * scale)
                lineTo(centerX - 6.6f * scale, centerY + 8.0f * scale)
                lineTo(centerX - 6.6f * scale, centerY - 1.0f * scale)
                close()
            }
            drawPath(path, color)
        }
        1 -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(centerX - 5.6f * scale, centerY - 9.2f * scale),
                size = Size(11.2f * scale, 18.4f * scale),
                cornerRadius = CornerRadius(2.6f * scale, 2.6f * scale),
            )
            drawCircle(
                detailColor,
                radius = 1.1f * scale,
                center = Offset(centerX, centerY + 6.0f * scale),
            )
        }
        2 -> {
            drawCircle(color = color, radius = 8.7f * scale, center = Offset(centerX, centerY))
            val path = Path().apply {
                moveTo(centerX + 3.8f * scale, centerY - 5.2f * scale)
                lineTo(centerX + 0.9f * scale, centerY + 2.0f * scale)
                lineTo(centerX - 4.8f * scale, centerY + 5.1f * scale)
                close()
            }
            drawPath(path, detailColor)
        }
        else -> {
            drawCircle(
                color = color,
                radius = 4.0f * scale,
                center = Offset(centerX, centerY - 4.8f * scale),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(centerX - 8.0f * scale, centerY + 1.6f * scale),
                size = Size(16.0f * scale, 10.0f * scale),
                cornerRadius = CornerRadius(5.0f * scale, 5.0f * scale),
            )
        }
    }
}

/** Original LiquidGlass publish circle; null callback leaves interaction to the parent slot. */
@Composable
internal fun GlassSeparatePostButton(dark: Boolean, circleDiameterDp: Float, onPost: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .size(circleDiameterDp.dp)
            .dropShadow(
                shape = CircleShape,
                shadow = Shadow(
                    radius = 6.dp,
                    color = Color.Black,
                    alpha = if (dark) 0.35f else 0.18f,
                ),
            )
            .background(
                if (dark) Color(0x59000000) else Color(0x59FFFFFF),
                CircleShape,
            )
            .clip(CircleShape)
            .then(if (onPost != null) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onPost,
            ) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // A simple translucent white cross; the glass surface shows the video.
        Canvas(modifier = Modifier.fillMaxSize().padding(11.dp)) {
            val stroke = size.minDimension * 0.11f
            val half = size.minDimension * 0.25f
            val crossColor = Color.White.copy(alpha = 0.92f)
            drawLine(
                color = crossColor,
                start = Offset(center.x - half, center.y),
                end = Offset(center.x + half, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = crossColor,
                start = Offset(center.x, center.y - half),
                end = Offset(center.x, center.y + half),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

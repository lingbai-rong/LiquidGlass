// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Glyphs and label mapping from the user-supplied HyperChanger, unchanged.
package rongyi.lingbai.moduleui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal fun hyperIcon(label: String): ImageVector = when {
    label == "+" -> HyperIcons.Add
    label.contains("\u9996\u9875") -> HyperIcons.Home
    label.contains("\u5206\u7c7b") || label.contains("\u6d4f\u89c8") ||
        label.contains("\u8d44\u6599\u5e93") -> HyperIcons.Category
    label.contains("\u670d\u52a1") -> HyperIcons.Service
    label.contains("\u8d2d\u7269\u8f66") -> HyperIcons.Cart
    label.contains("\u89c6\u9891") || label.contains("\u77ed\u5267") -> HyperIcons.Video
    label.contains("\u7701\u94b1") -> HyperIcons.Savings
    label.contains("\u501f\u94b1") -> HyperIcons.Loan
    label.contains("\u53d1\u73b0") || label.contains("\u5e7f\u573a") ||
        label.contains("\u641c\u7d22") || label.contains("\u96f7\u8fbe") -> HyperIcons.Search
    label.contains("\u6211") || label.contains("\u4e2a\u4eba") -> HyperIcons.Profile
    label.contains("\u6d88\u606f") || label.contains("\u804a\u5929") ||
        label.contains("\u6536\u4ef6\u7bb1") -> HyperIcons.Messages
    label.contains("\u8054\u7cfb") || label.contains("\u793e\u533a") -> HyperIcons.Contacts
    label.contains("\u9891\u9053") || label.contains("\u5e7f\u64ad") ||
        label.contains("\u7acb\u5373\u8046\u542c") -> HyperIcons.Channels
    else -> HyperIcons.Dynamic
}

internal object HyperIcons {
    val Empty: ImageVector = lineIcon("Empty") {}
    val Add: ImageVector = lineIcon("Add") {
        moveTo(12f, 4f); lineTo(12f, 20f)
        moveTo(4f, 12f); lineTo(20f, 12f)
    }
    val Home: ImageVector = lineIcon("Home") {
        moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
        lineTo(19f, 11f); lineTo(19f, 21f); lineTo(5f, 21f)
        lineTo(5f, 11f); close()
        moveTo(9f, 21f); lineTo(9f, 14f); lineTo(15f, 14f); lineTo(15f, 21f)
    }
    val Category: ImageVector = lineIcon("GridView") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }
    val Service: ImageVector = lineIcon("Store") {
        moveTo(4f, 10f); lineTo(20f, 10f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(3f, 10f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(21f, 10f)
        moveTo(8f, 10f); curveTo(8f, 12f, 10f, 13f, 12f, 13f)
        curveTo(14f, 13f, 16f, 12f, 16f, 10f)
    }
    val Cart: ImageVector = lineIcon("ShoppingCart") {
        moveTo(3f, 4f); lineTo(5f, 4f); lineTo(7.5f, 16f); lineTo(19f, 16f); lineTo(21f, 8f); lineTo(6f, 8f)
        moveTo(9f, 20f); curveTo(9f, 20.6f, 8.6f, 21f, 8f, 21f); curveTo(7.4f, 21f, 7f, 20.6f, 7f, 20f); curveTo(7f, 19.4f, 7.4f, 19f, 8f, 19f); curveTo(8.6f, 19f, 9f, 19.4f, 9f, 20f)
        moveTo(19f, 20f); curveTo(19f, 20.6f, 18.6f, 21f, 18f, 21f); curveTo(17.4f, 21f, 17f, 20.6f, 17f, 20f); curveTo(17f, 19.4f, 17.4f, 19f, 18f, 19f); curveTo(18.6f, 19f, 19f, 19.4f, 19f, 20f)
    }
    val Video: ImageVector = lineIcon("Video") {
        moveTo(4f, 6f); lineTo(15f, 6f); curveTo(16.1f, 6f, 17f, 6.9f, 17f, 8f)
        lineTo(17f, 16f); curveTo(17f, 17.1f, 16.1f, 18f, 15f, 18f)
        lineTo(4f, 18f); curveTo(2.9f, 18f, 2f, 17.1f, 2f, 16f)
        lineTo(2f, 8f); curveTo(2f, 6.9f, 2.9f, 6f, 4f, 6f); close()
        moveTo(17f, 10f); lineTo(22f, 7f); lineTo(22f, 17f); lineTo(17f, 14f)
    }
    val Savings: ImageVector = lineIcon("Savings") {
        moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
        moveTo(8f, 9f); lineTo(12f, 12f); lineTo(16f, 9f)
        moveTo(8f, 14f); lineTo(16f, 14f)
        moveTo(12f, 12f); lineTo(12f, 18f)
    }
    val Loan: ImageVector = lineIcon("Loan") {
        moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(15f, 12f); curveTo(15f, 13.7f, 13.7f, 15f, 12f, 15f)
        curveTo(10.3f, 15f, 9f, 13.7f, 9f, 12f); curveTo(9f, 10.3f, 10.3f, 9f, 12f, 9f)
        curveTo(13.7f, 9f, 15f, 10.3f, 15f, 12f); close()
        moveTo(6f, 12f); lineTo(6.1f, 12f)
        moveTo(18f, 12f); lineTo(18.1f, 12f)
    }
    val Search: ImageVector = lineIcon("Search") {
        moveTo(19f, 11f); curveTo(19f, 15.4f, 15.4f, 19f, 11f, 19f)
        curveTo(6.6f, 19f, 3f, 15.4f, 3f, 11f); curveTo(3f, 6.6f, 6.6f, 3f, 11f, 3f)
        curveTo(15.4f, 3f, 19f, 6.6f, 19f, 11f); close()
        moveTo(17f, 17f); lineTo(22f, 22f)
    }
    val Profile: ImageVector = lineIcon("Profile") {
        moveTo(16f, 8f); curveTo(16f, 10.2f, 14.2f, 12f, 12f, 12f)
        curveTo(9.8f, 12f, 8f, 10.2f, 8f, 8f); curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
        curveTo(14.2f, 4f, 16f, 5.8f, 16f, 8f); close()
        moveTo(4f, 21f); curveTo(4.8f, 16.8f, 7.5f, 14.5f, 12f, 14.5f)
        curveTo(16.5f, 14.5f, 19.2f, 16.8f, 20f, 21f)
    }
    val Messages: ImageVector = ImageVector.Builder(
        name = "Messages",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(5f, 5f)
            curveTo(3.9f, 5f, 3f, 5.9f, 3f, 7f)
            lineTo(3f, 15f)
            curveTo(3f, 16.1f, 3.9f, 17f, 5f, 17f)
            lineTo(8f, 17f)
            lineTo(6f, 21f)
            lineTo(12f, 17f)
            lineTo(19f, 17f)
            curveTo(20.1f, 17f, 21f, 16.1f, 21f, 15f)
            lineTo(21f, 7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
        }
    }.build()

    val Contacts: ImageVector = ImageVector.Builder(
        name = "Contacts",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(16f, 8f)
            curveTo(16f, 10.2f, 14.2f, 12f, 12f, 12f)
            curveTo(9.8f, 12f, 8f, 10.2f, 8f, 8f)
            curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
            curveTo(14.2f, 4f, 16f, 5.8f, 16f, 8f)
            close()
            moveTo(4f, 21f)
            curveTo(4.8f, 16.8f, 7.5f, 14.5f, 12f, 14.5f)
            curveTo(16.5f, 14.5f, 19.2f, 16.8f, 20f, 21f)
        }
    }.build()

    val Channels: ImageVector = ImageVector.Builder(
        name = "Channels",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            close()
            moveTo(14.8f, 8.2f)
            lineTo(12.7f, 13.1f)
            lineTo(8.2f, 15.8f)
            lineTo(10.3f, 10.9f)
            close()
        }
    }.build()

    val Dynamic: ImageVector = ImageVector.Builder(
        name = "Dynamic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            lineTo(14.2f, 9.8f)
            lineTo(22f, 12f)
            lineTo(14.2f, 14.2f)
            lineTo(12f, 22f)
            lineTo(9.8f, 14.2f)
            lineTo(2f, 12f)
            lineTo(9.8f, 9.8f)
            close()
        }
    }.build()
}

private fun lineIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}.build()


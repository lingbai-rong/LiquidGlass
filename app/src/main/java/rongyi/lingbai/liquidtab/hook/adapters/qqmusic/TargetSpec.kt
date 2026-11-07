// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.qqmusic

import rongyi.lingbai.liquidtab.targets.AdapterSource
import rongyi.lingbai.liquidtab.targets.TargetSpec

internal val targetSpec = TargetSpec("qqmusic", "com.tencent.qqmusic", "QQ音乐", AdapterSource.DEDICATED,
    idHints = setOf("g5b"),
    classHints = setOf("MainDeskNavigateContainer"),
    preferredSlots = 3..6,
    defaultAccentColor = 0xFF00C982.toInt(),
    entryLabels = listOf("首页", "视频", "刷歌", "星光", "我的"),
)

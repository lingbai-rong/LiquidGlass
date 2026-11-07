// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.xjtu

import rongyi.lingbai.liquidtab.targets.AdapterSource
import rongyi.lingbai.liquidtab.targets.TargetSpec

internal val targetSpec = TargetSpec("xjtu", "com.supwisdom.xjtu", "移动交通大学", AdapterSource.DEDICATED,
    classHints = setOf("supwisdom.rv"),
    textHints = setOf("首页", "服务", "事务", "日程", "我的"),
    preferredSlots = 5..5,
    defaultAccentColor = 0xFFED663F.toInt(),
    glassAccentColor = 0xFFED663F.toInt(),
    drawsNavigationInOptics = true,
    sceneVisibilityAnimation = true,
    entryLabels = listOf("首页", "服务", "事务", "日程", "我的"),
    uiProcessSuffixes = setOf("unimp0", "unimp1", "unimp2", "unimp3", "unimp4"),
)

// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.xianyu

import rongyi.lingbai.liquidtab.targets.AdapterSource
import rongyi.lingbai.liquidtab.targets.HideOption
import rongyi.lingbai.liquidtab.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("xianyu", "com.taobao.idlefish", "闲鱼", AdapterSource.GENERIC_FALLBACK,
            activityHints = setOf(
                "com.taobao.fleamarket.home.activity.MainActivity",
                "com.taobao.fleamarket.home.activity.InitActivity",
            ),
            idHints = setOf("id_indicator", "indicator_itmes", "indicator_item_container"),
            classHints = setOf("MainActivity", "FrameLayout", "RelativeLayout", "LinearLayout"),
            textHints = setOf("闲鱼", "消息", "我的"),
            preferredSlots = 5..5, supportsPostButton = true,
            entryLabels = listOf("闲鱼", "会玩", "发布", "消息", "我的"),
            defaultAccentColor = 0xFFFFCD00.toInt(), drawsNavigationInOptics = true)

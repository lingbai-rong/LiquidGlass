// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.meituan_main

import rongyi.lingbai.liquidtab.hook.adapters.TargetHookSignal
import rongyi.lingbai.liquidtab.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.meituan.android.pt.homepage.tab.TabBlockV2", listOf("onAttachedToWindow", "C", "A", "t")),
            TargetHookSignal("com.meituan.android.pt.homepage.activity.MainActivity", listOf("T5")),
        )

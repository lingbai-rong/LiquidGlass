package rongyi.lingbai.liquidtab.targets

internal object TargetSpecRegistry {
 val all=listOf(rongyi.lingbai.liquidtab.hook.adapters.amap.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.xianyu.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.youtube.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.xjtu.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.zhihu.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.cainiao.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.qqmusic.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.meituan_main.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.douyin.targetSpec,
rongyi.lingbai.liquidtab.hook.adapters.netease.targetSpec)
 val byKey=all.associateBy(TargetSpec::key)
}

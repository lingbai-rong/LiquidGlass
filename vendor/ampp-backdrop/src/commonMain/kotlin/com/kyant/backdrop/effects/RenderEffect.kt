package dev.amenhancer.vendor.backdrop.effects

import androidx.compose.ui.graphics.RenderEffect
import dev.amenhancer.vendor.backdrop.BackdropEffectScope
import dev.amenhancer.vendor.backdrop.RuntimeShader
import dev.amenhancer.vendor.backdrop.internal.RuntimeShaderEffect
import dev.amenhancer.vendor.backdrop.internal.chain
import dev.amenhancer.vendor.backdrop.isRenderEffectSupported
import dev.amenhancer.vendor.backdrop.isRuntimeShaderSupported
import org.intellij.lang.annotations.Language
import kotlin.contracts.ExperimentalContracts

fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (!isRenderEffectSupported()) return

    renderEffect = renderEffect.chain(effect)
}

@OptIn(ExperimentalContracts::class)
fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    @Language("AGSL") shaderString: String,
    uniformShaderName: String,
    block: RuntimeShader.() -> Unit
) {
    if (!isRuntimeShaderSupported()) return

    val effect =
        RuntimeShaderEffect(
            runtimeShader = obtainRuntimeShader(key, shaderString).apply(block),
            uniformShaderName = uniformShaderName
        )
    renderEffect = renderEffect.chain(effect)
}

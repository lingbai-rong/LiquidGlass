package dev.amenhancer.vendor.backdrop.internal

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Paint
import dev.amenhancer.vendor.backdrop.RuntimeShader
import dev.amenhancer.vendor.backdrop.asAndroidRuntimeShader

internal actual fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        else null
}

internal actual fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.asFrameworkPaint().shader = runtimeShader?.asAndroidRuntimeShader()
}

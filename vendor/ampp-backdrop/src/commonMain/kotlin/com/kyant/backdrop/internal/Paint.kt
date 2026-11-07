package dev.amenhancer.vendor.backdrop.internal

import androidx.compose.ui.graphics.Paint
import dev.amenhancer.vendor.backdrop.RuntimeShader

internal expect fun Paint.blur(radius: Float)

internal expect fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?)

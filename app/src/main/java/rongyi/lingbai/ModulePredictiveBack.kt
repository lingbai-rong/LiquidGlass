// Interaction reference: HyperChanger by btm_m, Apache-2.0.
// LiquidGlass implementation adds spring cancellation and preference-aware fallback.
package rongyi.lingbai

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Stable
internal class ModuleBackState {
    val progress = Animatable(0f)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
}

@Composable
internal fun rememberModuleBackState(
    enabled: Boolean = true,
    settings: ModuleAppearanceSettings = ModuleAppearanceSettings.read(LocalContext.current),
    resetAfterCommit: Boolean = true,
    onBack: () -> Unit,
): ModuleBackState {
    val state = remember { ModuleBackState() }
    val latestOnBack by rememberUpdatedState(onBack)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSettings by rememberUpdatedState(settings)

    BackHandler(enabled = enabled && !settings.predictiveBack) { latestOnBack() }
    PredictiveBackHandler(enabled = enabled && settings.predictiveBack) { events ->
        var committed = false
        try {
            events.collect { event ->
                state.edge = event.swipeEdge
                state.progress.snapTo(event.progress.coerceIn(0f, latestSettings.backProgress / 100f))
            }
            if (latestEnabled) {
                // Button/keyboard back has no gesture progress and should return immediately.
                if (state.progress.value > 0f) state.progress.animateTo(1f, tween(100))
                latestOnBack()
                committed = true
            }
        } catch (cancelled: CancellationException) {
            // Cancellation must restore the current page and never perform its save/exit action.
            withContext(NonCancellable) {
                state.progress.animateTo(0f, spring(stiffness = 650f, dampingRatio = 0.85f))
            }
            throw cancelled
        } finally {
            if (!committed || resetAfterCommit) {
                withContext(NonCancellable) { state.progress.snapTo(0f) }
            }
        }
    }
    return state
}

internal fun Modifier.moduleBackTransform(state: ModuleBackState): Modifier = graphicsLayer {
    val progress = state.progress.value.coerceIn(0f, 1f)
    val direction = if (state.edge == BackEventCompat.EDGE_LEFT) 1f else -1f
    scaleX = 1f - progress * 0.12f
    scaleY = 1f - progress * 0.12f
    translationX = direction * progress * 48.dp.toPx()
    translationY = progress * 16.dp.toPx()
    shape = RoundedCornerShape((28f * progress).dp)
    clip = progress > 0f
}

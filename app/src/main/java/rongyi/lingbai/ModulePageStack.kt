package rongyi.lingbai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import rongyi.lingbai.moduleui.ModuleGlassSurface

internal val LocalModulePageActive = compositionLocalOf { true }
internal val LocalModuleOwnsBack = compositionLocalOf { false }
internal val LocalSuppressParentBack = compositionLocalOf<(Boolean) -> Unit> { {} }
// A form may guard the commit while the stack still animates the whole page over its real parent.
internal val LocalModuleBackInterceptor = compositionLocalOf<((() -> Boolean)?) -> Unit> { {} }

@Stable
internal class ModulePageStack(private val path: SnapshotStateList<String>) {
    val routes: List<String> get() = path.toList()
    val isEmpty: Boolean get() = path.isEmpty()

    fun push(route: String) {
        if (path.lastOrNull() == route) return
        // A route has one live owner; revisiting an ancestor returns to its retained instance.
        val existing = path.indexOf(route)
        if (existing >= 0) while (path.lastIndex > existing) path.removeAt(path.lastIndex)
        else path.add(route)
    }

    fun pop() { if (path.isNotEmpty()) path.removeAt(path.lastIndex) }
}

@Composable
internal fun rememberModulePageStack(): ModulePageStack = rememberSaveable(
    saver = listSaver(
        save = { stack: ModulePageStack -> stack.routes },
        restore = { ModulePageStack(it.toMutableStateList()) },
    ),
) { ModulePageStack(mutableStateListOf()) }

/** Covered screens stay composed, but cannot receive input or accessibility focus. */
internal fun Modifier.modulePageInput(active: Boolean): Modifier = this
    .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
    .pointerInput(active) {
        if (!active) awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/**
 * Draw every retained route once, bottom to top. Only the foreground transforms on Back.
 * Its parent is the actual composed page, including its current scroll, form and list state.
 * Entries remain through the exit animation; disposal/autosave happens only after a real pop.
 */
@Composable
internal fun ModulePageStackHost(
    stack: ModulePageStack,
    appearance: ModuleAppearanceSettings,
    pageColor: Color,
    onPageRemoved: () -> Unit,
    content: @Composable (String, () -> Unit) -> Unit,
) {
    val path = stack.routes
    val rendered = remember { path.toMutableStateList() }
    SideEffect { path.forEach { if (it !in rendered) rendered.add(it) } }
    rendered.toList().forEach { route ->
        key(route) {
            val wanted = route in path
            val active = wanted && route == path.lastOrNull()
            val transition = remember { MutableTransitionState(false) }
            transition.targetState = wanted
            var childHandlesBack by remember { mutableStateOf(false) }
            var childInterceptor by remember { mutableStateOf<(() -> Boolean)?>(null) }
            val suppressChildBack = remember { { suppressed: Boolean -> childHandlesBack = suppressed } }
            val interceptChildBack = remember { { interceptor: (() -> Boolean)? -> childInterceptor = interceptor } }
            val back = rememberModuleBackState(
                enabled = active && !childHandlesBack,
                settings = appearance,
                resetAfterCommit = childInterceptor != null,
            ) { if (childInterceptor?.invoke() != true) stack.pop() }
            LaunchedEffect(wanted) { if (wanted) back.progress.snapTo(0f) }
            LaunchedEffect(transition.isIdle, transition.currentState, wanted) {
                if (!wanted && transition.isIdle && !transition.currentState) {
                    rendered.remove(route)
                    onPageRemoved()
                }
            }
            AnimatedVisibility(
                visibleState = transition,
                modifier = Modifier.fillMaxSize(),
                enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 5 } +
                    fadeIn(tween(260)) + scaleIn(tween(300), initialScale = .97f),
                exit = slideOutHorizontally(tween(260)) { it / 8 } +
                    fadeOut(tween(220)) + scaleOut(tween(260), targetScale = .985f),
            ) {
                Box(Modifier.fillMaxSize().moduleBackTransform(back).background(pageColor)
                    .modulePageInput(active)) {
                    CompositionLocalProvider(
                        LocalModulePageActive provides active,
                        LocalModuleOwnsBack provides true,
                        LocalSuppressParentBack provides suppressChildBack,
                        LocalModuleBackInterceptor provides interceptChildBack,
                    ) {
                        ModuleGlassSurface { content(route) { stack.pop() } }
                    }
                }
            }
        }
    }
}

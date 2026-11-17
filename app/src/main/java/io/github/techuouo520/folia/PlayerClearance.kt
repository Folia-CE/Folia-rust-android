package io.github.techuouo520.folia

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

internal class PlayerClearance {
    var panel by mutableStateOf(Rect.Zero)
    val controls = mutableStateMapOf<Any, Rect>()
    var gap by mutableFloatStateOf(0f)
    var ceiling by mutableFloatStateOf(0f)
    val lifts by derivedStateOf {
        val occupied = panel
        if (occupied.isEmpty) emptyMap() else {
            val entries = controls.entries.toList()
            val boxes = FloatArray(4 + entries.size * 5)
            fun store(index: Int, r: Rect) {
                boxes[index] = r.left
                boxes[index+1] = r.top
                boxes[index+2] = r.right
                boxes[index+3] = r.bottom
            }
            store(0, occupied)
            entries.forEachIndexed { i, entry -> store(4+i*5, entry.value) }
            MotionKernel.clearance(boxes, gap, ceiling)
            entries.mapIndexed { i, entry -> entry.key to boxes[8+i*5] }.toMap()
        }
    }
}
internal val LocalPlayerClearance = staticCompositionLocalOf<PlayerClearance?> { null }

/** Register the control's undisturbed layout box. Only its child and hit region move. */
internal fun Modifier.avoidPlayer(visibleBounds: ((Rect) -> Rect)? = null): Modifier = composed {
    val field = LocalPlayerClearance.current ?: return@composed this
    val key = remember { Any() }
    DisposableEffect(field) { onDispose { field.controls.remove(key) } }
    var base by remember { mutableStateOf(Rect.Zero) }
    val shape by rememberUpdatedState(visibleBounds)
    LaunchedEffect(field, key) {
        snapshotFlow { if (base.isEmpty) Rect.Zero else shape?.invoke(base) ?: base }
            .collect { field.controls[key] = it }
    }
    val lift = rustTravel(field.lifts[key] ?: 0f, 19.0)
    this.onGloballyPositioned { base = it.boundsInRoot() }
        .layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            layout(child.width, child.height) {
                child.placeWithLayer(0, 0) { translationY = -lift.floatValue }
            }
        }
}

@Composable
internal fun rustTravel(target: Float, frequency: Double, initial: Float = 0f): FloatState {
    val state = remember { doubleArrayOf(initial.toDouble(), 0.0) }
    val value = remember { mutableFloatStateOf(initial) }
    val goal by rememberUpdatedState(target)
    LaunchedEffect(frequency) {
        var lastGoal = initial
        while (isActive) {
            snapshotFlow { goal }.first { it != lastGoal }
            var previous = System.nanoTime()
            do {
                val moving = withFrameNanos { now ->
                    lastGoal = goal
                    val moving = MotionKernel.travel(state, lastGoal.toDouble(), (now-previous)/1e9, frequency, true)
                    previous = now
                    value.floatValue = state[0].toFloat()
                    moving
                }
            } while (moving)
        }
    }
    return value
}

package io.github.techuouo520.folia

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import kotlinx.coroutines.delay
import kotlin.math.abs

internal val SeekHeight = 40.dp
internal val SeekGap = 6.dp

@Composable
internal fun BubbleSeekBar(playback: PlaybackSnapshot, enabled: Boolean, frost: FrostBackdrop,
    modifier: Modifier, held: (Boolean) -> Unit, commit: (Double) -> Unit) {
    val duration = playback.durationSeconds
    val valid = enabled && duration.isFinite() && duration > 0 && !playback.loading
    val total = duration.takeIf { it.isFinite() && it > 0 } ?: 0.0
    val position = playback.positionSeconds.takeIf { it.isFinite() }?.coerceIn(0.0, total) ?: 0.0
    val live = if (total > 0) (position / total).toFloat() else 0f
    var railWidth by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Float?>(null) }
    val currentCommit by rememberUpdatedState(commit)
    val currentHeld by rememberUpdatedState(held)
    val playbackFraction by rememberUpdatedState(live)
    val currentDuration by rememberUpdatedState(duration)
    LaunchedEffect(pending) { if (pending != null) { delay(1200); pending = null } }
    LaunchedEffect(live, duration) {
        if (pending?.let { abs(it-live)*duration <= .75 } == true) pending = null
    }
    val target = preview ?: pending
    val seconds = (target?.let { it * total } ?: position).toInt()
    val elapsed = remember(seconds) { formatTime(seconds.toDouble()) }
    val remaining = remember(total.toInt(), seconds) { "-" + formatTime((total.toInt() - seconds).toDouble()) }
    val shown = rustTravel(target ?: live, 32.0, live)
    val pressure = rustTravel(if (pressed) 1f else 0f, 24.0)
    val gate = LocalGripGate.current
    val dark = LocalFoliaDark.current
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.height(SeekHeight).graphicsLayer {
        scaleX = 1f + .012f*pressure.floatValue
        scaleY = 1f + .09f*pressure.floatValue
    }.clip(CircleShape).frostedBackdrop(frost,dark).semantics {
        contentDescription = "播放进度"
        stateDescription = "$elapsed，剩余 ${remaining.drop(1)}"
        progressBarRangeInfo = ProgressBarRangeInfo(preview ?: pending ?: live,0f..1f)
        if (valid) setProgress { value ->
            val fraction = value.coerceIn(0f,1f)
            pending = fraction
            currentCommit(fraction*currentDuration)
            true
        }
    }.pointerInput(valid) {
        if (!valid) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed=false, pass=PointerEventPass.Initial)
            gate?.resolve(down.uptimeMillis,false)
            down.consume()
            pressed = true
            currentHeld(true)
            val origin = pending ?: playbackFraction
            val width = railWidth.coerceAtLeast(1).toFloat()
            var dragged = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.singleOrNull() ?: break
                    if (change.id != down.id || change.isConsumed) break
                    val dx = change.position.x-down.position.x
                    if (!dragged && abs(dx)>viewConfiguration.touchSlop) dragged=true
                    if (dragged) {
                        val delta = (abs(dx)-viewConfiguration.touchSlop).coerceAtLeast(0f)*kotlin.math.sign(dx)
                        preview = (origin + delta/width).coerceIn(0f,1f)
                    }
                    change.consume()
                    if (!change.pressed) {
                        preview?.let { pending=it; currentCommit(it*currentDuration) }
                        break
                    }
                }
            } finally {
                preview=null
                pressed=false
                currentHeld(false)
            }
        }
    }) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                Text(elapsed, style = style, color = ink, maxLines = 1)
                Canvas(Modifier.weight(1f).fillMaxHeight().onSizeChanged { railWidth = it.width }) {
                    val height = 4.dp.toPx() + 2.dp.toPx()*pressure.floatValue
                    val at = Offset(0f,(size.height-height)/2)
                    val radius = CornerRadius(height/2)
                    drawRoundRect(ink.copy(alpha=.18f),at,Size(size.width,height),radius)
                    drawRoundRect(ink.copy(alpha=.88f),at,Size(size.width*(preview ?: pending ?: shown.floatValue).coerceIn(0f,1f),height),radius)
                }
                Text(remaining, style = style, color = ink, maxLines = 1)
            }
        }
    }
}

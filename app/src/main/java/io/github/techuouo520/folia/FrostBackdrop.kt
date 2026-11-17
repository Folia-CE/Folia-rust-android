package io.github.techuouo520.folia

import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Compose adaptation of SalamPlayer's ordinary frost path:
 * ProgressiveBlurSourceCache retains the display list, ProgressiveBottomBlurRenderer
 * limits sampling to the strip + overscan at 0.5 scale, and HardwareRendererCompat
 * applies a CLAMP Gaussian blur. No liquid-glass shader or CPU bitmap readback.
 */
internal class FrostBackdrop(val source: GraphicsLayer) {
    var position by mutableStateOf(Offset.Zero)
    val revision = mutableLongStateOf(0L)
    var recorded = false

    fun sourceFramePresented() {
        // Publishing from source drawing must not make that drawing observe its own clock.
        Snapshot.withoutReadObservation { revision.longValue += 1 }
    }
}

@Composable
internal fun rememberFrostBackdrop(): FrostBackdrop {
    val source = rememberGraphicsLayer()
    return remember(source) { FrostBackdrop(source) }
}

/** Keep the source and frosted player as siblings, so no previous glass enters the source. */
internal fun Modifier.frostSource(backdrop: FrostBackdrop): Modifier {
    if (Build.VERSION.SDK_INT < 31) return this
    return graphicsLayer()
        .onGloballyPositioned { backdrop.position = it.positionInRoot() }
        .drawWithContent {
            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                backdrop.source.record { this@drawWithContent.drawContent() }
                backdrop.recorded = true
                drawLayer(backdrop.source)
                backdrop.sourceFramePresented()
            } else {
                backdrop.recorded = false
                drawContent()
            }
        }
}

@Composable
internal fun Modifier.frostedBackdrop(backdrop: FrostBackdrop, dark: Boolean): Modifier {
    val filtered = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val ground = MaterialTheme.colorScheme.background
    val tint = MaterialTheme.colorScheme.surfaceContainer.copy(alpha=if(dark) .70f else .63f)
    val fallback = MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.94f)
    return onGloballyPositioned { position = it.positionInRoot() }.drawWithCache {
        val radius = 20.dp.toPx() // SalamPlayer TopBarGlassHelper.BLUR_RADIUS_DP.
        val overscan = maxOf(32.dp.toPx(), radius * 1.5f)
        val width = size.width + overscan * 2
        val height = size.height + overscan * 2
        val sample = IntSize(maxOf(1, (width * .5f).roundToInt()), maxOf(1, (height * .5f).roundToInt()))
        val sx = sample.width / width
        val sy = sample.height / height
        val relative = position - backdrop.position
        val supported = Build.VERSION.SDK_INT >= 31
        if (supported) filtered.renderEffect = BlurEffect(radius * minOf(sx, sy), radius * minOf(sx, sy), TileMode.Clamp)
        var recordedRevision = Long.MIN_VALUE
        onDrawWithContent {
            val revision = backdrop.revision.longValue
            if (supported && backdrop.recorded && !backdrop.source.isReleased && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                if (recordedRevision != revision) {
                    // This offscreen target is only the pill's neighborhood, never the screen.
                    filtered.record(size = sample) {
                        drawRect(ground)
                        scale(sx, sy, Offset.Zero) {
                            translate(overscan - relative.x, overscan - relative.y) {
                                drawLayer(backdrop.source)
                            }
                        }
                    }
                    recordedRevision = revision
                }
                translate(-overscan, -overscan) {
                    scale(1f / sx, 1f / sy, Offset.Zero) { drawLayer(filtered) }
                }
                drawRect(tint)
            } else {
                drawRect(fallback)
            }
            // Player text, artwork and controls are drawn after the frost, at full resolution.
            drawContent()
        }
    }
}

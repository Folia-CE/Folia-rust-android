package io.github.techuouo520.folia.spatial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import io.github.techuouo520.folia.MotionKernel
import kotlin.math.roundToInt

/** Releases small pieces of the printed face as the camera's scan crosses them. */
internal class AlbumPixelScan(viewport: Size, focus: Rect, private val artwork: GraphicsLayer) {
    private val texture = IntSize(
        (viewport.width * focus.width).roundToInt().coerceAtLeast(1),
        (viewport.height * focus.height).roundToInt().coerceAtLeast(1)
    )
    private val columns = 32
    private val rows = (columns.toFloat() * texture.height / texture.width).roundToInt().coerceIn(8, 80)
    private val lanes = IntArray(rows * columns) { index ->
        var bits = (index + 1) * 0x45d9f3b
        bits = (bits xor (bits ushr 16)) * 0x45d9f3b
        (bits ushr 8) % 3
    }
    private val scanFrame = FloatArray(rows + 8 * 3 * 5)
    private val rowsByLane = Array(columns * 3) { group ->
        (0 until rows).filter { lanes[it * columns + group / 3] == group % 3 }.toIntArray()
    }
    private val viewportMatrix = Matrix().apply { scale(viewport.width, viewport.height) }
    private var sampledFace: Path? = null
    private val facePixels = Path()
    private val black = Path()
    private val pieces = Path()

    fun DrawScope.drawScan(world: GraphicsLayer, face: Path, focus: Rect, ground: Color, progress: Float) {
        val bounds = Rect(focus.left * size.width, focus.top * size.height,
            focus.right * size.width, focus.bottom * size.height)
        if (sampledFace !== face) {
            facePixels.reset()
            facePixels.addPath(face)
            facePixels.transform(viewportMatrix)
            // Keep only this sleeve in the sampled layer, including its projected edges.
            artwork.record(size = texture) {
                withTransform({
                    scale(texture.width / bounds.width, texture.height / bounds.height, Offset.Zero)
                    translate(-bounds.left, -bounds.top)
                }) {
                    clipPath(facePixels) { drawLayer(world) }
                }
            }
            sampledFace = face
        }
        if (progress >= 1f) {
            drawPath(facePixels, ground)
            return
        }
        clipPath(facePixels) {
            withTransform({
                translate(bounds.left, bounds.top)
                scale(bounds.width / columns, bounds.height / rows, Offset.Zero)
            }) {
                drawCells(progress, ground)
            }
        }
    }

    private fun DrawScope.drawCells(progress: Float, ground: Color) {
        val end = MotionKernel.scan(scanFrame, rows, progress)
        black.reset()
        black.moveTo(columns.toFloat(), 0f)
        for (row in 0 until rows) {
            val edge = scanFrame[row]
            black.lineTo(edge, row.toFloat())
            black.lineTo(edge, row + 1f)
        }
        black.lineTo(columns.toFloat(), rows.toFloat())
        black.close()
        drawPath(black, ground)

        // All pieces in a column/lane share one texture draw. The source and destination
        // coincide at release, then each piece drifts into the wake and dwindles to zero.
        var at = rows
        while (at < end) {
            val column = scanFrame[at].toInt()
            val lane = scanFrame[at + 1].toInt()
            val inset = scanFrame[at + 2]
            val dx = scanFrame[at + 3]
            val dy = scanFrame[at + 4]
            at += 5
            pieces.reset()
            for (row in rowsByLane[column * 3 + lane]) {
                pieces.addRect(Rect(column + inset, row + inset, column + 1f - inset, row + 1f - inset))
            }
            translate(dx, dy) {
                clipPath(pieces) {
                    scale(columns.toFloat() / texture.width, rows.toFloat() / texture.height, Offset.Zero) {
                        drawLayer(artwork)
                    }
                }
            }
        }
    }
}

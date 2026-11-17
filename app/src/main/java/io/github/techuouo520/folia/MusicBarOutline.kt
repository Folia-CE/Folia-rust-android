package io.github.techuouo520.folia

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.*

@Composable
internal fun playerDisplayRadius(): Float {
    val view = LocalView.current
    // Reading drawing insets makes the radius follow display changes in a retained window.
    WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val px = if (Build.VERSION.SDK_INT >= 31)
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0 else 0
    return if (px > 0) px / LocalDensity.current.density else 34f
}

/** Same cubic–arc–cubic outline as SmoothCorners.Builder at smoothing 0.6. */
internal data class MusicBarOutline(val radiusDp: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val budget = min(w, h) * .5f
        val r = min(radiusDp * density.density, budget)
        if (r <= .01f) return Outline.Rectangle(Rect(0f, 0f, w, h))
        val smoothing = (budget / r - 1f).coerceIn(0f, .6f)
        val extent = min(budget, (1 + smoothing) * r)
        val angle = 45f * smoothing
        val sweep = 90f - 2 * angle
        val radians = Math.toRadians(angle.toDouble())
        val arc = sin(Math.toRadians(sweep / 2.0)) * r * sqrt(2.0)
        val c = (r * tan(radians / 2) * cos(radians)).toFloat()
        val d = (c * tan(radians)).toFloat()
        val b = ((extent - arc - c - d) / 3).toFloat()
        val a = 2 * b
        val p = extent
        return Outline.Generic(Path().apply {
            moveTo(p, 0f)
            lineTo(w-p, 0f)
            cubicTo(w-p+a, 0f, w-p+a+b, 0f, w-p+a+b+c, d)
            arcTo(Rect(w-2*r, 0f, w, 2*r), -90f+angle, sweep, false)
            cubicTo(w, p-a-b, w, p-a, w, p)
            lineTo(w, h-p)
            cubicTo(w, h-p+a, w, h-p+a+b, w-d, h-p+a+b+c)
            arcTo(Rect(w-2*r, h-2*r, w, h), angle, sweep, false)
            cubicTo(w-p+a+b, h, w-p+a, h, w-p, h)
            lineTo(p, h)
            cubicTo(p-a, h, p-a-b, h, p-a-b-c, h-d)
            arcTo(Rect(0f, h-2*r, 2*r, h), 90f+angle, sweep, false)
            cubicTo(0f, h-p+a+b, 0f, h-p+a, 0f, h-p)
            lineTo(0f, p)
            cubicTo(0f, p-a, 0f, p-a-b, d, p-a-b-c)
            arcTo(Rect(0f, 0f, 2*r, 2*r), 180f+angle, sweep, false)
            cubicTo(p-a-b, 0f, p-a, 0f, p, 0f)
            close()
        })
    }
}

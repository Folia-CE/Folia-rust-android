package io.github.techuouo520.folia

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Real Gaussian backdrop blur with a four-sided alpha feather. No tint or colour ramp.
 * The source contains the collection scene, excluding this halo and title.
 */
@Composable
internal fun Modifier.headerBlur(backdrop: FrostBackdrop): Modifier {
    val blurred=rememberGraphicsLayer()
    var position by remember {mutableStateOf(Offset.Zero)}
    return onGloballyPositioned {position=it.positionInRoot()}.drawWithCache {
        val feather=24.dp.toPx()
        val overscan=40.dp.toPx()
        val radius=20.dp.toPx()
        val width=size.width+overscan*2
        val height=size.height+overscan*2
        val sample=IntSize((width*.5f).roundToInt().coerceAtLeast(1),(height*.5f).roundToInt().coerceAtLeast(1))
        val sx=sample.width/width;val sy=sample.height/height
        val relative=position-backdrop.position
        if(Build.VERSION.SDK_INT>=31) blurred.renderEffect=BlurEffect(radius*sx,radius*sy,TileMode.Clamp)
        val bounds=Rect(-feather,-feather,size.width+feather,size.height+feather)
        val maskX=Brush.horizontalGradient(listOf(Color.Transparent,Color.White,Color.White,Color.Transparent),bounds.left,bounds.right)
        val maskY=Brush.verticalGradient(listOf(Color.Transparent,Color.White,Color.White,Color.Transparent),bounds.top,bounds.bottom)
        val paint=Paint()
        var revision=Long.MIN_VALUE
        onDrawWithContent {
            val current=backdrop.revision.longValue
            if(Build.VERSION.SDK_INT>=31 && backdrop.recorded && !backdrop.source.isReleased && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                if(revision!=current) {
                    blurred.record(size=sample) {
                        scale(sx,sy,Offset.Zero) {
                            translate(overscan-relative.x,overscan-relative.y){drawLayer(backdrop.source)}
                        }
                    }
                    revision=current
                }
                drawContext.canvas.saveLayer(bounds,paint)
                translate(-overscan,-overscan) {scale(1/sx,1/sy,Offset.Zero){drawLayer(blurred)}}
                // White is an alpha mask only (DstIn); it never paints a white/black overlay.
                drawRect(maskX,Offset(bounds.left,bounds.top),Size(bounds.width,bounds.height),blendMode=BlendMode.DstIn)
                drawRect(maskY,Offset(bounds.left,bounds.top),Size(bounds.width,bounds.height),blendMode=BlendMode.DstIn)
                drawContext.canvas.restore()
            }
            drawContent()
        }
    }
}

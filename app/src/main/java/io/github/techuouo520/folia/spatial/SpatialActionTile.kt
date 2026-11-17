package io.github.techuouo520.folia.spatial

import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.techuouo520.folia.R

/** DailySpatialActionTile drawing port. Pose/alpha belong exclusively to the original Rust frame. */
@Composable
internal fun SpatialActionTile(action: Int,label: String,collection: Boolean,favorite: Boolean,
    material: Color,enabled: Boolean,modifier: Modifier,onClick: ()->Unit) {
    val ink=if(action==2&&favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val icon=painterResource(when(action) {
        1->R.drawable.spatial_rfg_bubble_icon_queue_next
        2->if(favorite) R.drawable.spatial_rfg_bubble_icon_star_filled else R.drawable.spatial_rfg_bubble_icon_star
        else->R.drawable.spatial_rfg_bubble_icon_play
    })
    val density=LocalDensity.current
    val text=remember(density.density,density.fontScale) {Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=with(density){12.sp.toPx()};textAlign=Paint.Align.CENTER
    }}
    val lines=remember(action,label) {if(action==1) listOf("下一首","播放") else listOf(label)}
    val dislike=remember {dislikeHeart()}
    val glyphPaint=remember {Paint(Paint.ANTI_ALIAS_FLAG)}
    val slashPaint=remember {Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeCap=Paint.Cap.ROUND;strokeWidth=24*.085f}}
    Canvas(modifier.semantics {contentDescription=label;selected=action==2&&favorite}
        .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,enabled=enabled,role=Role.Button,onClick=onClick)) {
        drawRoundRect(material,cornerRadius=CornerRadius(6.dp.toPx()))
        val cx=size.width/2;val cy=size.height/2
        text.color=ink.toArgb()
        val edge=24.dp.toPx()
        if(action==3) {
            val textWidth=text.measureText(label)
            val left=cx-(34.dp.toPx()+textWidth)/2
            translate(left,cy-edge/2) {scale(edge/24,edge/24,Offset.Zero) {
                glyphPaint.color=ink.toArgb();slashPaint.color=ink.toArgb()
                drawContext.canvas.nativeCanvas.drawPath(dislike,glyphPaint)
                drawContext.canvas.nativeCanvas.drawLine(24*.12f,24*.12f,24*.88f,24*.88f,slashPaint)
            }}
            drawContext.canvas.nativeCanvas.drawText(label,left+34.dp.toPx()+textWidth/2,cy-(text.ascent()+text.descent())/2,text)
        } else {
            val top=cy-(edge+7.dp.toPx()+text.fontSpacing*lines.size)/2
            translate(cx-edge/2,top) {
                if(action==0&&collection) scale(edge/24,edge/24,Offset.Zero) {drawSceneGlyph(SceneGlyph.Open,ink)}
                else with(icon) {draw(Size(edge,edge),colorFilter=ColorFilter.tint(ink))}
            }
            var baseline=top+31.dp.toPx()-text.ascent()
            for(line in lines) {
                drawContext.canvas.nativeCanvas.drawText(line,cx,baseline,text)
                baseline+=text.fontSpacing
            }
        }
    }
}

/** OnlineGlyphs.DISLIKE: solid heart with a real cutout around its diagonal slash. */
private fun dislikeHeart(): AndroidPath {
    val heart=AndroidPath().apply {
        moveTo(12f,20.16f)
        cubicTo(4.32f,14.88f,1.44f,10.56f,2.88f,7.2f)
        cubicTo(4.32f,3.84f,9.6f,2.88f,12f,6.72f)
        cubicTo(14.4f,2.88f,19.68f,3.84f,21.12f,7.2f)
        cubicTo(22.56f,10.56f,19.68f,14.88f,12f,20.16f);close()
    }
    val line=AndroidPath().apply {moveTo(2.4f,2.4f);lineTo(21.6f,21.6f)}
    val gap=AndroidPath()
    Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=24*.085f*2.6f;strokeCap=Paint.Cap.ROUND}.getFillPath(line,gap)
    heart.op(gap,AndroidPath.Op.DIFFERENCE)
    return heart
}

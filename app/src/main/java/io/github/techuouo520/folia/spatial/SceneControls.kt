package io.github.techuouo520.folia.spatial

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import io.github.techuouo520.folia.avoidPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class SceneGlyph { Back, Open, Play, Retry, Locate, Rhine, Map, Next, Star, Dislike, Close, Check, Settings, User, Logout }
@Composable
internal fun sceneInk(dark: Boolean) = MaterialTheme.colorScheme.onSurface

@Composable
internal fun SceneLabel(text: String, dark: Boolean, modifier: Modifier=Modifier, large: Boolean=false, muted: Boolean=false) {
    BasicText(text,modifier,style=TextStyle(color=sceneInk(dark).copy(alpha=if(muted) .6f else 1f),
        fontSize=if(large) 22.sp else 11.sp, fontWeight=if(large) FontWeight.Medium else FontWeight.Normal,
        fontFamily=if(large) FontFamily.Default else FontFamily.Monospace,letterSpacing=if(large) .2.sp else .8.sp),
        maxLines=if(large) 2 else 1,overflow=TextOverflow.Ellipsis)
}

/** Foundation input + drawn geometry only; no Material button, ripple or menu. */
@Composable
internal fun SceneIcon(glyph: SceneGlyph, label: String, dark: Boolean, rhine: Boolean,
    modifier: Modifier=Modifier, enabled: Boolean=true, selected: Boolean=false, onClick: ()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale=animateFloatAsState(if(pressed) .91f else 1f,spring(dampingRatio=.9f,stiffness=600f),label="sceneIconPress")
    val ink=if(selected) MaterialTheme.colorScheme.primary else sceneInk(dark)
    val fill=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.90f)
    Canvas(modifier.size(48.dp).avoidPlayer().graphicsLayer { scaleX=scale.value;scaleY=scale.value;alpha=if(enabled) 1f else .32f }
        .semantics { contentDescription=label }
        .clickable(interactionSource=interaction,indication=null,enabled=enabled,role=Role.Button,onClick=onClick)) {
        val inset=4.dp.toPx();val side=size.width-inset*2
        translate(inset,inset) {
            if(rhine) {
                val cut=7.dp.toPx()
                val shape=Path().apply { moveTo(0f,0f);lineTo(side-cut,0f);lineTo(side,cut);lineTo(side,side);lineTo(cut,side);lineTo(0f,side-cut);close() }
                drawPath(shape,fill);drawPath(shape,ink.copy(alpha=.22f),style=Stroke(.8.dp.toPx()))
                drawLine(ink.copy(alpha=.65f),Offset(0f,0f),Offset(9.dp.toPx(),0f),1.dp.toPx())
            } else {
                drawRoundRect(fill,size=Size(side,side),cornerRadius=CornerRadius(12.dp.toPx()))
                drawRoundRect(ink.copy(alpha=.13f),size=Size(side,side),cornerRadius=CornerRadius(12.dp.toPx()),style=Stroke(.8.dp.toPx()))
            }
        }
        val unit=24.dp.toPx()/24f
        translate((size.width-24*unit)/2,(size.height-24*unit)/2) { scale(unit,unit,Offset.Zero) { drawSceneGlyph(glyph,ink) } }
    }
}

internal fun DrawScope.drawSceneGlyph(glyph: SceneGlyph, ink: Color) {
    fun line(x:Float,y:Float,xx:Float,yy:Float)=drawLine(ink,Offset(x,y),Offset(xx,yy),1.8f,StrokeCap.Round)
    fun path(vararg points: Float,fill: Boolean=false) {
        val p=Path().apply { moveTo(points[0],points[1]);for(i in 2 until points.size step 2) lineTo(points[i],points[i+1]);if(fill) close() }
        drawPath(p,ink,style=if(fill) Fill else Stroke(1.8f,cap=StrokeCap.Round,join=StrokeJoin.Round))
    }
    when(glyph) {
        // SalamPlayer ic_settings_back.xml: 24-unit chevron, rounded 2-unit stroke.
        SceneGlyph.User -> {drawCircle(ink,3.4f,Offset(12f,8f),style=Stroke(1.8f));drawArc(ink,180f,180f,false,Offset(5f,13f),Size(14f,12f),style=Stroke(1.8f,cap=StrokeCap.Round))}
        SceneGlyph.Settings -> {
            drawCircle(ink,3f,Offset(12f,12f),style=Stroke(1.6f))
            val gear=Path();for(i in 0..47){val a=(i*2*Math.PI/48).toFloat();val r=if(i%6 in 1..4) 8.8f else 7.1f;val x=12+r*kotlin.math.cos(a);val y=12+r*kotlin.math.sin(a);if(i==0)gear.moveTo(x,y)else gear.lineTo(x,y)};gear.close();drawPath(gear,ink,style=Stroke(1.6f,join=StrokeJoin.Round))
        }
        SceneGlyph.Logout -> {path(10f,4f,5f,4f,5f,20f,10f,20f);line(10f,12f,21f,12f);path(17f,8f,21f,12f,17f,16f)}
        SceneGlyph.Back -> path(15f,18f,9f,12f,15f,6f)
        SceneGlyph.Open -> path(9f,6f,15f,12f,9f,18f)
        SceneGlyph.Play -> path(8f,5f,19f,12f,8f,19f,fill=true)
        SceneGlyph.Next -> { path(5f,6f,15f,12f,5f,18f,fill=true);line(18f,6f,18f,18f) }
        SceneGlyph.Close -> {line(7f,7f,17f,17f);line(17f,7f,7f,17f)}
        SceneGlyph.Check -> path(5f,12f,10f,17f,19f,7f)
        SceneGlyph.Retry -> { drawArc(ink,35f,290f,false,Offset(5f,5f),Size(14f,14f),style=Stroke(1.8f,cap=StrokeCap.Round));path(19f,4f,19f,9f,14f,9f) }
        SceneGlyph.Locate -> {drawCircle(ink,6f,Offset(12f,12f),style=Stroke(1.8f));drawCircle(ink,1.5f,Offset(12f,12f));line(12f,2f,12f,5f);line(12f,19f,12f,22f);line(2f,12f,5f,12f);line(19f,12f,22f,12f)}
        SceneGlyph.Rhine -> {path(4f,9f,12f,4f,20f,9f,12f,14f,4f,9f);path(4f,14f,12f,19f,20f,14f);line(12f,14f,12f,19f)}
        SceneGlyph.Map -> {drawRoundRect(ink,Offset(4f,4f),Size(16f,16f),CornerRadius(2f),style=Stroke(1.6f));line(11f,4f,11f,20f);line(4f,13f,11f,13f);line(11f,10f,20f,10f)}
        SceneGlyph.Star -> path(12f,3f,15f,9f,21f,10f,16.5f,14.5f,17.5f,21f,12f,18f,6.5f,21f,7.5f,14.5f,3f,10f,9f,9f,12f,3f)
        SceneGlyph.Dislike -> {path(6f,9f,6f,5f,18f,5f,18f,17f,14f,17f,14f,21f,6f,13f);line(3f,4f,21f,22f)}
    }
}

/** A scene-specific orbit mark, used only while work is pending. */
@Composable
internal fun SceneLoading(dark: Boolean, modifier: Modifier=Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start=withFrameNanos { it }
        while(true) withFrameNanos { phase=((it-start)%1_600_000_000L)/1_600_000_000f }
    }
    val ink=MaterialTheme.colorScheme.primary
    Canvas(modifier.size(36.dp)) {
        drawCircle(ink.copy(alpha=.12f),size.minDimension*.32f,style=Stroke(1.dp.toPx()))
        rotate(phase*360f) {
            drawArc(ink,0f,90f,false,Offset(size.width*.18f,size.height*.18f),size*.64f,style=Stroke(1.5.dp.toPx(),cap=StrokeCap.Round))
        }
        drawRect(ink.copy(alpha=.65f),Offset(size.width/2-2.dp.toPx(),size.height/2-2.dp.toPx()),Size(4.dp.toPx(),4.dp.toPx()))
    }
}

package io.github.techuouo520.folia

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** HdrHighlightImageView transport motion, INSIDE the glyph's frame.
 * Two fixed play/pause glyphs, never a growing stack of outgoing screenshots on rapid toggles.
 */
@Composable
internal fun PlayerTransport(resource:Int,label:String,enabled:Boolean,onClick:()->Unit,modifier:Modifier,
    padding:Float=0f,direction:Int=0,compactWeight:Float=0f,available:Boolean=enabled,compactIconHeight:Dp=18.dp) {
    val interaction=remember {MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val press=remember {Animatable(1f)};val nudge=remember {Animatable(0f)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(pressed) {
        press.animateTo(if(pressed) .84f else 1f,spring(dampingRatio=.86f,stiffness=900f),
            initialVelocity=if(pressed) minOf(press.velocity,-3.5f) else press.velocity)
    }
    val isPlayPause=resource==R.drawable.salam_transport_play||resource==R.drawable.salam_transport_pause
    val selected=if(resource==R.drawable.salam_transport_play) 0 else 1
    var glyphs by remember {mutableStateOf(List(2){i->Glyph(if(i==selected) 1f else 0f,if(i==selected) 1f else .55f)})}
    LaunchedEffect(resource) {
        if(!isPlayPause) return@LaunchedEffect
        val from=glyphs
        if(from[selected].alpha==1f&&from[1-selected].alpha==0f) return@LaunchedEffect
        val start=withFrameNanos {it}
        do {
            val t=withFrameNanos {((it-start)/260_000_000f).coerceIn(0f,1f)}
            val eased=1-(1-t)*(1-t)*(1-t)
            glyphs=List(2){i->if(i==selected) Glyph(playerMix(from[i].alpha,1f,(eased*1.4f).coerceAtMost(1f)),playerMix(from[i].size,1f,eased))
                else Glyph(from[i].alpha*(1-eased)*(1-eased),playerMix(from[i].size,.55f,eased))}
        }while(t<1)
    }
    val icon=painterResource(resource)
    val pause=if(isPlayPause)painterResource(R.drawable.salam_transport_play) else icon
    val play=if(isPlayPause)painterResource(R.drawable.salam_transport_pause) else icon
    val ink=MaterialTheme.colorScheme.onSurface
    Canvas(modifier.semantics {contentDescription=label}.clickable(enabled=enabled,role=Role.Button,interactionSource=interaction,indication=null) {
        if(direction!=0) scope.launch {
            nudge.animateTo(0f,spring(.86f,900f),initialVelocity=nudge.velocity+direction*.16f*30f*2.7182818f)
        }
        onClick()
    }) {
        val fullExtent=(size.minDimension-2*padding.dp.toPx()).coerceAtLeast(1f)
        translate(nudge.value*fullExtent,0f) {
            scale(press.value,press.value,center) {
                fun drawGlyph(painter:androidx.compose.ui.graphics.painter.Painter,glyph:Glyph,inkHeight:Float) {
                    if(glyph.alpha<=0f) return
                    // The three 500-unit viewports have different transparent margins.
                    val side=playerMix(fullExtent,compactIconHeight.toPx()/inkHeight,compactWeight)
                    val extent=Size(side,side)
                    scale(glyph.size,glyph.size,center) {translate(center.x-side/2,center.y-side/2) {
                        with(painter){draw(extent,alpha=glyph.alpha*(if(available) 1f else .30f),colorFilter=ColorFilter.tint(ink))}
                    }}
                }
                if(isPlayPause) {
                    fun drawState(index:Int) {
                        if(index==0) drawGlyph(pause,glyphs[0],397.109f/500f)
                        else drawGlyph(play,glyphs[1],435.811f/500f)
                    }
                    drawState(1-selected)
                    drawState(selected)
                } else drawGlyph(icon,Glyph(1f,1f),388.053f/500f)
            }
        }
    }
}
private data class Glyph(val alpha:Float,val size:Float)

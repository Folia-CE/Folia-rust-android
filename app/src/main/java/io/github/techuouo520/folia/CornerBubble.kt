package io.github.techuouo520.folia

import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.font.FontWeight
import io.github.techuouo520.folia.spatial.SceneGlyph
import io.github.techuouo520.folia.spatial.drawSceneGlyph
import io.github.techuouo520.folia.spatial.sceneInk
import kotlin.math.*

/** One surface, from the actual 50 dp button outline to the open window and back. */
@Composable
internal fun CornerBubble(
    modifier: Modifier, motion: CornerMotion, frame: State<Int>, active: Boolean, right: Boolean,
    frost: FrostBackdrop, dark: Boolean, avatar: ImageBitmap?, label: String, title: String,
    dockMotion: CornerMotion? = null, backEnabled: Boolean = false,
    onBackPressed: (Boolean)->Unit = {}, onBack: ()->Unit = {},
    onPressed: (Boolean)->Unit, onGeometryChanged: ()->Unit, onClick: ()->Unit, content: @Composable ()->Unit,
) {
    val scroll=rememberScrollState()
    // Shape/blur are driven directly by the same state as the outline, not a second animator.
    SubcomposeLayout(modifier.widthIn(max=420.dp).fillMaxWidth().layout {measurable,constraints->
        // Keep the source's travelling blur and edge overshoot outside the final panel bounds.
        val pad=64.dp.roundToPx()
        val child=measurable.measure(constraints.offset(horizontal=pad*2,vertical=pad*2))
        layout(child.width-pad*2,child.height-pad*2) {child.place(-pad,-pad)}
    }.graphicsLayer {
        frame.value
        // Resting-shape motion must not blur an already expanded account window.
        val dockBlur=(dockMotion?.blur?:0f)*(1f-motion.progress)
        val px=max(motion.blur*25.dp.toPx(),dockBlur*6.dp.toPx())
        val q=if(px<.75f) 0f else (px*2).roundToInt()*.5f
        renderEffect=if(Build.VERSION.SDK_INT>=31&&q>0) BlurEffect(q,q,TileMode.Decal) else null
    }) {constraints->
        frame.value
        val pad=64.dp.roundToPx()
        val width=constraints.maxWidth-pad*2
        val viewport=constraints.maxHeight-pad*2
        val body=if(active) subcompose("body") {
            PinnedBubbleContents(title,dark,motion,frame,scroll,content)
        }.single().measure(Constraints(minWidth=width,maxWidth=width,maxHeight=viewport)) else null
        if(motion.bounds(width/density,body?.height?.div(density),right)) onGeometryChanged()
        val r=motion.rect();val radius=motion.radius()
        val glass=subcompose("glass") {
            Box(Modifier.clip(RoundedCornerShape(radius.dp)).frostedBackdrop(frost,dark)
                .then(if(active) Modifier.pointerInput(Unit){detectTapGestures{}} else Modifier))
        }.single().measure(Constraints.fixed(max(1,(r.width*density).roundToInt()),max(1,(r.height*density).roundToInt())))
        val fade=motion.iconAlpha()
        val icon=subcompose("icon") {
            val ink=MaterialTheme.colorScheme.onSurface
            val glyphFraction=21f/CORNER_BUTTON_DIAMETER // 19dp → 21dp; retain press scaling.
            Box(Modifier.fillMaxSize().cornerAction(label,fade>.01f,onPressed,onClick),contentAlignment=androidx.compose.ui.Alignment.Center) {
                if(avatar!=null) Image(avatar,null,Modifier.fillMaxSize().clip(CircleShape),contentScale=ContentScale.Crop)
                else if(right) Image(painterResource(R.drawable.salam_settings),null,Modifier.fillMaxSize(glyphFraction),colorFilter=ColorFilter.tint(ink))
                else Canvas(Modifier.fillMaxSize(glyphFraction)) {
                    scale(size.width/24,size.height/24,Offset.Zero) {drawSceneGlyph(SceneGlyph.User,ink)}
                }
            }
        }.single().measure(Constraints.fixed((CORNER_BUTTON_DIAMETER*motion.press.scaleX*density).roundToInt(),(CORNER_BUTTON_DIAMETER*motion.press.scaleY*density).roundToInt()))
        // The lower hit region never overlaps the avatar's 50dp slot. It grows inside the
        // same surface and remains clipped while appearing/retracting.
        val backTop=r.top+CORNER_BUTTON_DIAMETER*motion.press.scaleY
        val backHeight=if(dockMotion==null) 0f else
            ((dockMotion.rect().height-CORNER_BUTTON_DIAMETER)*motion.press.scaleY).coerceAtLeast(0f)
        val back=if(dockMotion!=null && backHeight>.01f) subcompose("back") {
            val label=stringResource(R.string.collection_back)
            val ink=MaterialTheme.colorScheme.onSurface
            Box(Modifier.fillMaxSize().clipToBounds()
                .cornerAction(label,backEnabled&&backHeight>=32f,onBackPressed,onBack),
                contentAlignment=androidx.compose.ui.Alignment.Center) {
                Canvas(Modifier.requiredSize(21.dp).graphicsLayer {
                    scaleX=motion.press.scaleX;scaleY=motion.press.scaleY
                }) {scale(size.width/24,size.height/24,Offset.Zero) {drawSceneGlyph(SceneGlyph.Back,ink)}}
            }
        }.single().measure(Constraints.fixed(
            max(1,(CORNER_BUTTON_DIAMETER*motion.press.scaleX*density).roundToInt()),
            max(1,(backHeight*density).roundToInt()))) else null
        layout(width+pad*2,max(ceil(r.bottom*density).toInt(),body?.height?:CORNER_BUTTON_DIAMETER.dp.roundToPx())+pad*2) {
            glass.place(pad+(r.left*density).roundToInt(),pad+(r.top*density).roundToInt())
            body?.placeWithLayer(pad,pad) {
                frame.value
                shape=CornerClip(motion.rect(),motion.radius());clip=true
            }
            val anchorX=if(right) r.right-CORNER_BUTTON_RADIUS*motion.press.scaleX else r.left+CORNER_BUTTON_RADIUS*motion.press.scaleX
            val anchorY=r.top+CORNER_BUTTON_RADIUS*motion.press.scaleY
            back?.placeWithLayer(pad+(anchorX*density-back.width*.5f).roundToInt(),pad+(backTop*density).roundToInt()) {
                alpha=fade*cornerSmooth(dockMotion?.progress?:0f)
                scaleX=motion.iconScale();scaleY=motion.iconScale()
            }
            icon.placeWithLayer(pad+(anchorX*density-icon.width*.5f).roundToInt(),pad+(anchorY*density-icon.height*.5f).roundToInt()) {
                alpha=fade;scaleX=motion.iconScale();scaleY=motion.iconScale()
            }
        }
    }
}

/** Independent action owners share the original escape-slop, cancellation and press response. */
@Composable
private fun Modifier.cornerAction(label: String,enabled: Boolean,onPressed: (Boolean)->Unit,onClick: ()->Unit): Modifier {
    val latestClick=rememberUpdatedState(onClick)
    val latestPress=rememberUpdatedState(onPressed)
    val haptics=LocalHapticFeedback.current
    return semantics {
        if(enabled) {
            contentDescription=label;role=Role.Button
            onClick {latestClick.value();true}
        } else hideFromAccessibility()
    }.then(if(enabled) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down=awaitFirstDown();down.consume();latestPress.value(true)
            var armed=true
            try {
                while(true) {
                    val event=awaitPointerEvent()
                    val change=event.changes.firstOrNull {it.id==down.id} ?: break
                    val slop=12.dp.toPx()
                    if(change.isConsumed||change.position.x < -slop||change.position.y < -slop||change.position.x>size.width+slop||change.position.y>size.height+slop) {
                        armed=false;latestPress.value(false)
                    }
                    if(!change.pressed) {
                        change.consume()
                        if(armed) {haptics.performHapticFeedback(HapticFeedbackType.ContextClick);latestClick.value()}
                        break
                    }
                    change.consume()
                }
            } finally {latestPress.value(false)}
        }
    } else Modifier)
}

/** Header and scroll viewport are separate layout children, both inside the moving outline. */
@Composable
private fun PinnedBubbleContents(title: String,dark: Boolean,motion: CornerMotion,frame: State<Int>,
    scroll: androidx.compose.foundation.ScrollState,content: @Composable ()->Unit) {
    SubcomposeLayout {constraints->
        val w=constraints.maxWidth
        val maxHeight=min(constraints.maxHeight,560.dp.roundToPx())
        val inset=20.dp.roundToPx()
        val top=(CORNER_BUTTON_DIAMETER+12).dp.roundToPx()
        val header=subcompose("header") {
            WindowText(title,sceneInk(dark),28,FontWeight.SemiBold,lineHeight=36,modifier=Modifier.semantics {heading()})
        }.single().measure(Constraints(maxWidth=(w-inset*2).coerceAtLeast(0),maxHeight=(maxHeight-top).coerceAtLeast(0)))
        val contentTop=(top+header.height+16.dp.roundToPx()).coerceAtMost(maxHeight)
        val body=subcompose("scroll") {
            Box(Modifier.verticalScroll(scroll)) {
                BubbleRows(motion,frame,{scroll.value},contentTop/density,content)
            }
        }.single().measure(Constraints(minWidth=w,maxWidth=w,maxHeight=(maxHeight-contentTop).coerceAtLeast(0)))
        layout(w,contentTop+body.height) {
            body.place(0,contentTop)
            header.placeWithLayer(inset,top) {
                frame.value
                val f=motion.content((inset+header.width*.5f)/density,(top+header.height*.5f)/density)
                translationX=f.x*density;translationY=f.y*density
                scaleX=f.scale;scaleY=f.scale;alpha=f.alpha
            }
        }
    }
}

/** Each row is laid out at its final size, then rides the source's distance-based content fling. */
@Composable
private fun BubbleRows(motion: CornerMotion,frame: State<Int>,scrollY: ()->Int,contentTop: Float,content: @Composable ()->Unit) {
    Layout(content=content) {measurables,constraints->
        val inset=20.dp.roundToPx();val top=0;val gap=16.dp.roundToPx()
        val w=constraints.maxWidth
        val children=measurables.map {it.measure(Constraints(maxWidth=(w-inset*2).coerceAtLeast(0)))}
        motion.rows(children.size)
        val h=top+children.sumOf {it.height}+gap*max(0,children.size-1)+inset
        layout(w,h) {
            var y=top
            children.forEachIndexed {index,child->
                val at=y
                child.placeWithLayer(inset,at) {
                    frame.value
                    val f=motion.content((inset+child.width*.5f)/density,contentTop+(at-scrollY()+child.height*.5f)/density)
                    translationX=f.x*density;translationY=f.y*density
                    scaleX=f.scale;scaleY=f.scale;alpha=f.alpha*motion.rowAppearance(index)
                }
                y+=child.height+gap
            }
        }
    }
}

/** Value identity changes with geometry, invalidating Compose's cached outline every moving frame. */
private data class CornerClip(val rect: CornerRect,val radius: Float): Shape {
    override fun createOutline(size: Size,layoutDirection: LayoutDirection,density: Density): Outline {
        val d=density.density
        return Outline.Rounded(RoundRect(Rect(rect.left*d,rect.top*d,rect.right*d,rect.bottom*d),CornerRadius(radius*d)))
    }
}

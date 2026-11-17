package io.github.techuouo520.folia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import kotlin.math.abs
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.techuouo520.folia.bridge.*
import io.github.techuouo520.folia.spatial.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** One retained card: the closed frost becomes SalamPlayer's opaque immersive player. */
@Composable
internal fun MusicBar(bridge: FoliaBridge,playback: PlaybackSnapshot,frost: FrostBackdrop,
    expanded: Boolean,onExpanded: (Boolean)->Unit,onToggle: ()->Unit,action: (() -> Unit)->Unit,progress: MutableFloatState,screenCorner: Float) {
    val dark=LocalFoliaDark.current;val colors=MaterialTheme.colorScheme
    val motion=remember { MusicBarMotion() }
    var dragging by remember { mutableStateOf(false) }
    var dragRevision by remember { mutableIntStateOf(0) }
    val latestExpanded=rememberUpdatedState(onExpanded)
    val expandedIntent=rememberUpdatedState(expanded)
    val cardInteraction=remember { MutableInteractionSource() }
    val pressed by cardInteraction.collectIsPressedAsState()
    val press=remember { Animatable(0f) }
    val targetPress = pressed && !dragging && !expanded && progress.floatValue == 0f
    LaunchedEffect(targetPress) {
        val target=if(targetPress) 1f else 0f
        val duration=((if(targetPress) 300 else 220)*abs(target-press.value)).roundToInt().coerceAtLeast(80)
        val curve=if(targetPress) playerPress else playerAttack
        press.animateTo(target,tween(duration,easing=Easing(curve::sample)))
    }
    LaunchedEffect(expanded,dragging,dragRevision) {
        if(dragging) return@LaunchedEffect
        motion.target(expanded)
        if(!motion.moving) return@LaunchedEffect
        var previous=System.nanoTime()
        while(motion.moving) withFrameNanos { now ->
            motion.step((now-previous)/1e9f)
            previous=now
            progress.floatValue=motion.value
        }
    }
    val navigation=LocalCornerNavigation.current
    BackHandler((expanded||progress.floatValue>.001f)&&navigation?.modalOpen!=true) {onExpanded(false)}
    val source=playback.artSource()
    val identity=source?.owner
    val cover by key(identity) {
        produceState<ImageBitmap?>(null,bridge,source) {
            value=source?.let { bridge.readArtwork(it) { bytes -> decodeCover(bytes,1024) } }
        }
    }
    var showQueue by remember {mutableStateOf(false)}
    val queue by produceState(emptyList<LibraryTrack>(),showQueue,playback.queueGeneration) {
        if(showQueue) value=withContext(Dispatchers.IO){runCatching {bridge.queue()}.getOrDefault(emptyList())}
    }
    var showLyrics by remember {mutableStateOf(false)}
    val showLyricContent = showLyrics && !showQueue && (expanded || progress.floatValue > 0f)
    val lyric by key(identity) { produceState("",showLyricContent) {
        if(showLyricContent) while(isActive) {
            value=withContext(Dispatchers.IO) {runCatching {val l=bridge.lyrics();l.lines.getOrNull(l.lineIndex)?.text.orEmpty()}.getOrDefault("")}
            delay(250)
        }
    } }
    val playhead by produceState(playback.positionSeconds,identity,playback.positionSeconds,playback.state,playback.loading) {
        value=playback.positionSeconds
        if(playback.state==TransportState.PLAYING&&!playback.loading) {
            val start=withFrameNanos {it}
            while(isActive) withFrameNanos {now->
                value=(playback.positionSeconds+(now-start)/1e9).coerceAtMost(if(playback.durationSeconds>0) playback.durationSeconds else Double.MAX_VALUE)
            }
        }
    }
    val hasTrack=playback.queueLength>0
    val displayDensity=LocalDensity.current
    val presence=remember {androidx.compose.animation.core.Animatable(0f)}
    LaunchedEffect(cover!=null) {presence.animateTo(if(cover!=null) 1f else 0f,tween(560,easing=Easing(playerAttack::sample)))}
    val lyricsMode=remember { Animatable(0f) }
    LaunchedEffect(showLyrics) {
        lyricsMode.animateTo(if(showLyrics) 1f else 0f,tween(600,easing=Easing(playerAttack::sample)))
    }
    val palette=LocalPlayerPalette.current
    val lineRatio=remember {android.graphics.Paint().apply {textSize=100f}.fontMetrics.let {(it.descent-it.ascent)/100f}}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val p=progress.floatValue
        val safe=WindowInsets.safeDrawing.asPaddingValues()
        val direction=LocalLayoutDirection.current
        val g=MusicBarGeometry(maxWidth.value,maxHeight.value,safe.calculateBottomPadding().value,p,
            displayDensity.fontScale*lineRatio/1.18f,presence.value,press.value,screenCorner,lyricsMode.value,safe.calculateTopPadding().value,
            safe.calculateLeftPadding(direction).value,safe.calculateRightPadding(direction).value)
        SideEffect {navigation?.playerVisible=p>.001f||expanded}
        DisposableEffect(navigation) {onDispose {navigation?.playerVisible=false}}
        fun Modifier.at(rect: PlayerRect)=absoluteOffset(rect.x.dp,rect.y.dp).size(rect.width.dp,rect.height.dp)
        val coords=remember { arrayOfNulls<LayoutCoordinates>(1) }
        val beganMoving=remember { mutableStateOf(false) }
        Box(Modifier.at(g.card).onPlaced { coords[0]=it }.clip(MusicBarOutline(g.cardRadius))
            .semantics {if(p>.001f) dismiss {latestExpanded.value(false);true}}
            .clickable(interactionSource=cardInteraction,indication=null) {
                // A second tap during either direction is a new intent, never a blocked gesture.
                if(!expandedIntent.value||motion.moving||p<1f||beganMoving.value) latestExpanded.value(!expandedIntent.value)
                beganMoving.value=false
            }
            .pointerInput(maxHeight,displayDensity.density) {
                val range=(maxHeight.toPx()-68.dp.toPx()).coerceAtLeast(1f)
                val threshold=viewConfiguration.touchSlop+10.dp.toPx()
                val tracker=VelocityTracker()
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    val coordinates=coords[0] ?: return@awaitEachGesture
                    val origin=coordinates.localToWindow(down.position)
                    var start=motion.value
                    var originTravel=0f
                    val interrupted=motion.moving
                    beganMoving.value=interrupted
                    val dpScale=this@pointerInput.density
                    var grabbed=false
                    var travel=0f
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis,origin)
                    try {
                        do {
                            val event=awaitPointerEvent()
                            val change=event.changes.firstOrNull { it.id==down.id } ?: break
                            if(!coordinates.isAttached || event.changes.count { it.pressed }>1) break
                            val point=coordinates.localToWindow(change.position)
                            travel=point.y-origin.y
                            if(change.pressed) tracker.addPosition(change.uptimeMillis,point)
                            if(!change.pressed) break
                            if(change.isConsumed && !grabbed) break
                            if(!grabbed) {
                                if(abs(point.x-origin.x)>maxOf(threshold,abs(travel))) break
                                if(abs(travel)<=threshold) continue
                                if(interrupted) {
                                    start=motion.value
                                    originTravel=travel
                                }
                                beganMoving.value=false
                                grabbed=true
                                dragging=true
                                motion.hold()
                            }
                            change.consume()
                            motion.drag(start-(travel-originTravel)/range)
                            progress.floatValue=motion.value
                        } while(true)
                    } finally {
                        if(grabbed) {
                            val velocity=-tracker.calculateVelocity().y/range
                            motion.drag(start-(travel-originTravel)/range,velocity)
                            progress.floatValue=motion.value
                            latestExpanded.value(settleMusicBar(start,motion.value,travel/dpScale))
                            dragging=false
                            dragRevision++
                        }
                    }
                }
            }) {
            // Source dispatchDraw: opaque album field takes over within the first 5%.
            // No world capture/blur remains once that hand-off is complete.
            if(g.backgroundAlpha<1) Box(Modifier.matchParentSize().frostedBackdrop(frost,dark))
            Box(Modifier.matchParentSize().graphicsLayer {alpha=g.backgroundAlpha}
                .playerBackdrop(palette))
            val fadeMask=remember(g.fadeStart,g.fadeEnd) {
                Brush.verticalGradient(*Array(65) { i ->
                    val y=i/64f
                    y to Color.Black.copy(alpha=playerSmooth(g.fadeStart,g.fadeEnd,y))
                },startY=0f,endY=1f)
            }
            Box(Modifier.at(g.cover).clip(RoundedCornerShape(topStart=g.coverRadius.dp,bottomStart=g.coverRadius.dp,
                topEnd=g.coverInnerRadius.dp,bottomEnd=g.coverInnerRadius.dp))
                .graphicsLayer {compositingStrategy=if(g.coverFade>0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto}
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        if(g.coverFade>0f) scale(1f,size.height,Offset.Zero) {
                            drawRect(fadeMask,size=Size(size.width,1f),alpha=g.coverFade,blendMode=BlendMode.DstOut)
                        }
                    }
                }.background(colors.primaryContainer),contentAlignment=Alignment.Center) {
                if(cover!=null) Image(cover!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                else Canvas(Modifier.fillMaxSize().padding((g.cover.width*.25f).dp)) {drawCircle(colors.primary,style=Stroke(2.dp.toPx()));drawCircle(colors.primary,radius=size.minDimension*.12f)}
            }
            PlayerMarquee(playback.title.ifBlank {"暂无播放"},g.titleSize,true,colors.onSurface,Modifier.at(g.title))
            PlayerMarquee(playback.artist,g.artistSize,false,colors.onSurfaceVariant,Modifier.at(g.artist))
            PlayerTransport(if(playback.state==TransportState.PLAYING) R.drawable.salam_transport_play else R.drawable.salam_transport_pause,
                if(playback.state==TransportState.PLAYING) "暂停" else "播放",hasTrack,onToggle,Modifier.at(g.play),padding=6f,compactWeight=1-p)
            val canSkip=hasTrack&&playback.hasNext
            if(g.compactActionAlpha>0f) PlayerTransport(R.drawable.salam_transport_next,"下一首",canSkip,
                {action {bridge.next()}},Modifier.at(g.compactNext).graphicsLayer {alpha=g.compactActionAlpha},
                direction=1,compactWeight=1f)
            if(g.transportAlpha>0f) {
                PlayerTransport(R.drawable.salam_transport_next,"下一首",canSkip,{action {bridge.next()}},
                    Modifier.at(g.next).graphicsLayer {alpha=g.transportAlpha},direction=1)
                PlayerTransport(R.drawable.salam_transport_previous_icon,"上一首",hasTrack&&playback.hasPrevious,
                    {action {bridge.previous()}},Modifier.at(g.previous).graphicsLayer {alpha=g.transportAlpha},direction=-1)
            }
            var seeking by remember(identity) {mutableStateOf<Float?>(null)}
            val duration=playback.durationSeconds.toFloat().coerceAtLeast(1f)
            val progressValue=(seeking?:playhead.toFloat()).coerceIn(0f,duration)
            PlayerProgress(progressValue/duration,progressValue.toDouble(),playback.durationSeconds,p,hasTrack&&p>.8f,
                onScrub={seeking=it*duration},onCancel={seeking=null},
                onCommit={fraction->action {bridge.seek((fraction*duration).toDouble())};seeking=null},modifier=Modifier.at(g.progress),inlineTimes=g.inlineTimes*p)
            PlayerTransport(when(playback.repeatMode){RepeatMode.ONE->R.drawable.salam_mode_one;else->R.drawable.salam_mode_sequence},
                "循环模式",p>.8f,{action {bridge.cycleRepeat()}},Modifier.at(g.mode).graphicsLayer {alpha=g.auxAlpha},padding=g.auxSize*.25f,available=true)
            PlayerTransport(R.drawable.salam_lyrics,"歌词",p>.8f,{showLyrics=!showLyrics;showQueue=false},
                Modifier.at(g.lyrics).graphicsLayer {alpha=g.auxAlpha},padding=(g.auxSize-25)/2,available=true)
            PlayerTransport(R.drawable.salam_queue,"播放队列",p>.8f,{showQueue=!showQueue;showLyrics=false},
                Modifier.at(g.queue).graphicsLayer {alpha=g.auxAlpha},padding=(g.auxSize-30)/2,available=true)
            if((showQueue||lyricsMode.value>0f)&&p>.001f) {
                val panel=if(showQueue && !g.wide) {
                    val top=safe.calculateTopPadding().value+66
                    PlayerRect(24f,top,(g.card.width-48).coerceAtLeast(0f),(g.title.y-top-24).coerceAtLeast(0f))
                } else g.lyricPanel
                Box(Modifier.at(panel)
                    .graphicsLayer {alpha=playerSmooth(.5f,.95f,p)*(if(showQueue) 1f else lyricsMode.value)}.clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha=.96f))) {
                    if(showQueue) LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
                        itemsIndexed(queue,key={i,t->"$i:${t.id}"}) {i,t->Text(t.title,Modifier.fillMaxWidth()
                            .clickable(enabled=p>.8f){action {bridge.playIndex(i)}}.padding(vertical=14.dp),fontSize=15.sp,
                            color=if(i==playback.queueIndex) colors.primary else colors.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    } else Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center) {
                        Text(lyric,fontSize=22.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,color=colors.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerMarquee(text:String,size:Float,bold:Boolean,ink:Color,modifier:Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(factory={context->MarqueeTitleView(context).apply {
        gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        typeface=android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,if(bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }},update={view->
        if(view.text.toString()!=text) view.text=text
        val sizePx=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,size,view.resources.displayMetrics)
        if(view.textSize!=sizePx) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sizePx)
        view.setTextColor(ink.toArgb())
    },modifier=modifier)
}

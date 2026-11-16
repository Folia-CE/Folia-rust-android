package io.github.techuouo520.folia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
    expanded: Boolean,onExpanded: (Boolean)->Unit,onToggle: ()->Unit,action: (() -> Unit)->Unit) {
    val dark=LocalFoliaDark.current;val colors=MaterialTheme.colorScheme
    val motion=remember {MusicBarMotion()};var frame by remember {mutableIntStateOf(0)}
    var dragging by remember {mutableStateOf(false)}
    var dragRevision by remember {mutableIntStateOf(0)}
    val latestExpanded=rememberUpdatedState(onExpanded)
    LaunchedEffect(expanded,dragging,dragRevision) {
        if(dragging) return@LaunchedEffect
        motion.target(expanded);var previous=withFrameNanos {it}
        do {var moving=false;withFrameNanos {now->moving=motion.step((now-previous)/1e9f);previous=now;frame++}} while(moving)
    }
    frame // Keep back-handler enablement on the same frame as the card.
    val navigation=LocalCornerNavigation.current
    BackHandler((expanded||motion.value>.01f)&&navigation?.modalOpen!=true) {onExpanded(false)}
    val identity="${playback.trackId}:${playback.provider}:${playback.onlineId}"
    val current=rememberUpdatedState(identity)
    val cover by produceState<ImageBitmap?>(null,identity) {
        value=null
        if(playback.queueLength==0) return@produceState
        repeat(80) {
            val image=withContext(Dispatchers.IO) {runCatching {
                val bytes=bridge.artwork() ?: return@runCatching null
                val now=bridge.status()
                if("${now.trackId}:${now.provider}:${now.onlineId}"!=identity) null else decodeCover(bytes,1024)
            }.getOrNull()}
            if(current.value!=identity) return@produceState
            if(image!=null){value=image;return@produceState};delay(250)
        }
    }
    var showQueue by remember {mutableStateOf(false)}
    val queue by produceState(emptyList<LibraryTrack>(),showQueue,playback.queueGeneration) {
        if(showQueue) value=withContext(Dispatchers.IO){runCatching {bridge.queue()}.getOrDefault(emptyList())}
    }
    val lyric by produceState("",identity,expanded,showQueue) {
        value=""
        if(expanded&&!showQueue) while(isActive) {
            value=withContext(Dispatchers.IO) {runCatching {val l=bridge.lyrics();l.lines.getOrNull(l.lineIndex)?.text.orEmpty()}.getOrDefault("")}
            delay(250)
        }
    }
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
    val density=LocalDensity.current
    val presence=remember {androidx.compose.animation.core.Animatable(0f)}
    LaunchedEffect(cover!=null) {presence.animateTo(if(cover!=null) 1f else 0f,androidx.compose.animation.core.tween(380))}
    var showLyrics by remember {mutableStateOf(false)}
    val expandedIntent=rememberUpdatedState(expanded)
    val palette=LocalPlayerPalette.current
    val lineRatio=remember {android.graphics.Paint().apply {textSize=100f}.fontMetrics.let {(it.descent-it.ascent)/100f}}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        frame
        val p=motion.value
        val safe=WindowInsets.safeDrawing.asPaddingValues()
        val g=MusicBarGeometry(maxWidth.value,maxHeight.value,safe.calculateBottomPadding().value,p,
            density.fontScale*lineRatio/1.18f,presence.value)
        SideEffect {navigation?.playerVisible=p>.001f||expanded}
        DisposableEffect(navigation) {onDispose {navigation?.playerVisible=false}}
        fun Modifier.at(rect: PlayerRect)=offset(rect.x.dp,rect.y.dp).size(rect.width.dp,rect.height.dp)
        Box(Modifier.at(g.card).clip(RoundedCornerShape((34*(1-p)).dp))
            .semantics {if(p>.001f) dismiss {latestExpanded.value(false);true}}
            .clickable(interactionSource=remember {MutableInteractionSource()},indication=null) {
                // A second tap during either direction is a new intent, never a blocked gesture.
                if(!expandedIntent.value||p<.999f) latestExpanded.value(!expandedIntent.value)
            }
            .pointerInput(maxHeight,density.density) {
                var start=0f;var travel=0f;var lastTime=0L
                val range=(maxHeight.value-68).coerceAtLeast(1f)*density.density
                detectVerticalDragGestures(onDragStart={start=motion.value;travel=0f;lastTime=0;motion.hold();dragging=true},
                    onDragCancel={dragging=false;dragRevision++},
                    onDragEnd={
                        val open=if(travel < -72*density.density) true else if(travel>72*density.density) false else motion.value>=.5f
                        dragging=false;latestExpanded.value(open);dragRevision++
                    }) {change,dy->
                    change.consume();travel+=dy
                    val dt=if(lastTime==0L) 1f/60 else ((change.uptimeMillis-lastTime)/1000f).coerceAtLeast(.001f)
                    lastTime=change.uptimeMillis
                    motion.drag(start-travel/range,-dy/range/dt);frame++
                }
            }) {
            // Source dispatchDraw: opaque album field takes over within the first 5%.
            // No world capture/blur remains once that hand-off is complete.
            if(g.backgroundAlpha<1) Box(Modifier.matchParentSize().graphicsLayer {alpha=1-g.backgroundAlpha}.frostedBackdrop(frost,dark))
            Box(Modifier.matchParentSize().graphicsLayer {alpha=g.backgroundAlpha}
                .playerBackdrop(palette))
            Box(Modifier.at(g.cover).clip(RoundedCornerShape(topStart=g.coverRadius.dp,bottomStart=g.coverRadius.dp,
                topEnd=g.coverInnerRadius.dp,bottomEnd=g.coverInnerRadius.dp))
                .graphicsLayer {compositingStrategy=CompositingStrategy.Offscreen}
                .drawWithCache {
                    // ScanSwitchImageView's smooth fade, not a black/white colour overlay.
                    val mask=Brush.verticalGradient(*Array(65){i->
                        val y=i/64f
                        y to Color.White.copy(alpha=1-g.coverFade*playerSmooth(g.fadeStart,g.fadeEnd,y))
                    })
                    onDrawWithContent {drawContent();if(g.coverFade>0) drawRect(mask,blendMode=BlendMode.DstIn)}
                }.background(colors.primaryContainer),contentAlignment=Alignment.Center) {
                if(cover!=null) Image(cover!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                else Canvas(Modifier.fillMaxSize().padding((g.cover.width*.25f).dp)) {drawCircle(colors.primary,style=Stroke(2.dp.toPx()));drawCircle(colors.primary,radius=size.minDimension*.12f)}
            }
            PlayerMarquee(playback.title.ifBlank {"暂无播放"},g.titleSize,true,colors.onSurface,Modifier.at(g.title))
            PlayerMarquee(playback.artist,g.artistSize,false,colors.onSurfaceVariant,Modifier.at(g.artist))
            PlayerTransport(if(playback.state==TransportState.PLAYING) R.drawable.salam_transport_play else R.drawable.salam_transport_pause,
                if(playback.state==TransportState.PLAYING) "暂停" else "播放",hasTrack,onToggle,Modifier.at(g.play),padding=6f)
            PlayerTransport(R.drawable.salam_transport_next,"下一首",hasTrack&&playback.hasNext,{action {bridge.next()}},
                Modifier.at(g.next),padding=playerMix(10.8f,0f,p),direction=1)
            PlayerTransport(R.drawable.salam_transport_previous_icon,"上一首",hasTrack&&p>.35f&&playback.hasPrevious,{action {bridge.previous()}},
                Modifier.at(g.previous).graphicsLayer {alpha=g.transportAlpha},direction=-1)
            var seeking by remember(identity) {mutableStateOf<Float?>(null)}
            val duration=playback.durationSeconds.toFloat().coerceAtLeast(1f)
            val progressValue=(seeking?:playhead.toFloat()).coerceIn(0f,duration)
            PlayerProgress(progressValue/duration,progressValue.toDouble(),playback.durationSeconds,p,hasTrack&&p>.8f,
                onScrub={seeking=it*duration},onCancel={seeking=null},
                onCommit={fraction->action {bridge.seek((fraction*duration).toDouble())};seeking=null},modifier=Modifier.at(g.progress))
            PlayerTransport(when(playback.repeatMode){RepeatMode.ONE->R.drawable.salam_mode_one;else->R.drawable.salam_mode_sequence},
                "循环模式",p>.8f,{action {bridge.cycleRepeat()}},Modifier.at(g.mode).graphicsLayer {alpha=g.auxAlpha},padding=g.auxSize*.25f)
            PlayerTransport(R.drawable.salam_lyrics,"歌词",p>.8f,{showLyrics=!showLyrics;showQueue=false},
                Modifier.at(g.lyrics).graphicsLayer {alpha=g.auxAlpha},padding=(g.auxSize-25)/2)
            PlayerTransport(R.drawable.salam_queue,"播放队列",p>.8f,{showQueue=!showQueue;showLyrics=false},
                Modifier.at(g.queue).graphicsLayer {alpha=g.auxAlpha},padding=(g.auxSize-30)/2)
            if((showQueue||showLyrics)&&p>.001f) {
                val panelTop=safe.calculateTopPadding().value+66
                val panelHeight=(g.title.y-panelTop-24).coerceAtLeast(0f)
                Box(Modifier.offset(24.dp,panelTop.dp).size((g.card.width-48).coerceAtLeast(0f).dp,panelHeight.dp)
                    .graphicsLayer {alpha=playerSmooth(.5f,.95f,p)}.clip(RoundedCornerShape(16.dp)).background(colors.surface.copy(alpha=.96f))) {
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
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,size)
        view.setTextColor(ink.toArgb())
    },modifier=modifier)
}

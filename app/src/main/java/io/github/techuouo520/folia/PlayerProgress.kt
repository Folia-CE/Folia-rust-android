package io.github.techuouo520.folia

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/** NowPlayingProgressView's 10dp round rail, 13sp times and scrub inertia; no Android slider knob. */
@Composable
internal fun PlayerProgress(value:Float,position:Double,duration:Double,expansion:Float,enabled:Boolean,
    onScrub:(Float)->Unit,onCancel:()->Unit,onCommit:(Float)->Unit,modifier:Modifier) {
    val ink=MaterialTheme.colorScheme.onSurface
    val density=LocalDensity.current
    val timePaint=remember {android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)}
    val scope=rememberCoroutineScope()
    val press=remember {Animatable(1f)}
    var dragging by remember {mutableStateOf(false)}
    var interacted by remember {mutableStateOf(false)}
    var pending by remember {mutableStateOf<Float?>(null)}
    var inertia by remember {mutableStateOf<Job?>(null)}
    val scrub=rememberUpdatedState(onScrub);val commit=rememberUpdatedState(onCommit);val cancel=rememberUpdatedState(onCancel)
    val live=rememberUpdatedState(value)
    LaunchedEffect(dragging) {
        if(!interacted) return@LaunchedEffect
        if(dragging) press.animateTo(1.04f,spring(.86f,900f))
        else {press.animateTo(.97f,spring(.86f,900f));press.animateTo(1f,spring(.86f,900f))}
    }
    DisposableEffect(Unit) {onDispose {inertia?.cancel()}}
    LaunchedEffect(enabled) {if(!enabled) {inertia?.cancel();pending=null;dragging=false;cancel.value()}}
    fun finish() {pending?.let {commit.value(it)};pending=null;dragging=false}
    Canvas(modifier.semantics {
        contentDescription="播放进度";progressBarRangeInfo=ProgressBarRangeInfo(value,0f..1f)
        if(enabled)setProgress {commit.value(it.coerceIn(0f,1f));true}
    }.then(if(enabled) Modifier.pointerInput(Unit) {
        detectTapGestures {point->
            inertia?.cancel();interacted=true
            commit.value((point.x/size.width).coerceIn(0f,1f))
            scope.launch {press.animateTo(1.04f,spring(.86f,900f));press.animateTo(.97f,spring(.86f,900f));press.animateTo(1f,spring(.86f,900f))}
        }
    }.pointerInput(Unit) {
        val tracker=VelocityTracker()
        detectHorizontalDragGestures(onDragStart={point->
            inertia?.cancel();tracker.resetTracking();interacted=true;dragging=true
            pending=(point.x/size.width).coerceIn(0f,1f);scrub.value(pending!!)
        },onDragCancel={pending=null;dragging=false;cancel.value()},onDragEnd={
            var speed=tracker.calculateVelocity().x
            if(abs(speed)<900) finish() else inertia=scope.launch {
                var previous=withFrameNanos {it}
                while(true) {
                    val now=withFrameNanos {it};val dt=((now-previous)/1e9f).coerceIn(.001f,.032f);previous=now
                    speed*=exp(-13.4f*dt)
                    val next=((pending?:live.value)+speed*dt/size.width).coerceIn(0f,1f)
                    pending=next;scrub.value(next)
                    if(next==0f||next==1f||abs(speed)<24) break
                }
                finish()
            }
        }) {change,dx->
            change.consume();tracker.addPosition(change.uptimeMillis,change.position)
            pending=((pending?:live.value)+dx/size.width).coerceIn(0f,1f);scrub.value(pending!!)
        }
    } else Modifier)) {
        timePaint.textSize=13*density.density*density.fontScale
        timePaint.color=ink.copy(alpha=((expansion-.35f)/.65f).coerceIn(0f,1f)).toArgb()
        val thickness=playerMix(2f,10f,expansion).dp.toPx()
        val timeBaseline=size.height-12.dp.toPx()
        val expandedTrackTop=timeBaseline+timePaint.ascent()-20.dp.toPx()
        val top=playerMix(0f,expandedTrackTop.coerceAtLeast(10.dp.toPx()),expansion)
        val fraction=(pending?:value).coerceIn(0f,1f)
        scale(press.value,press.value,Offset(size.width/2,top+thickness/2)) {
            val corner=CornerRadius(thickness/2)
            drawRoundRect(ink.copy(alpha=61/255f),Offset(0f,top),Size(size.width,thickness),corner)
            drawRoundRect(ink.copy(alpha=217/255f),Offset(0f,top),Size(size.width*fraction,thickness),corner)
        }
        if(expansion>.35f) drawContext.canvas.nativeCanvas.apply {
            drawText(formatTime(position),0f,timeBaseline,timePaint)
            val end=formatTime(duration);drawText(end,size.width-timePaint.measureText(end),timeBaseline,timePaint)
        }
    }
}

package io.github.techuouo520.folia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.*
import io.github.techuouo520.folia.spatial.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.*

@Composable
internal fun AppCornerWindows(bridge: FoliaBridge,content: @Composable ()->Unit) {
    FoliaApp.from(LocalContext.current).appearanceVersion
    val frost=rememberFrostBackdrop();val dark=LocalFoliaDark.current
    val session by produceState(SessionSnapshot(SessionPhase.RESOLVING,"","","",""),bridge) {
        while(isActive){value=withContext(Dispatchers.IO){runCatching {bridge.sessionSnapshot()}.getOrDefault(value)};delay(600)}
    }
    val avatar by produceState<ImageBitmap?>(null,session.provider,session.userId,session.avatarUrl) {
        value=null
        if(session.avatarUrl.isNotBlank())withContext(Dispatchers.IO) {
            val key="account:${session.provider}:${session.userId}:${session.avatarUrl}"
            bridge.requestCovers(listOf(CoverAsk(key,"",session.avatarUrl,128,128)))
            while(isActive){when(val found=bridge.coverLookup(key)){
                is CoverLookup.Ready->{value=decodeCover(found.bytes,128);break}
                is CoverLookup.Missing->break
                is CoverLookup.Pending->delay(80)
            }}
        }
    }
    val navigation=remember {CornerNavigation()}
    var backPressed by remember {mutableStateOf(false)}
    val dockMotion=remember {CornerMotion().apply {bounds(CORNER_BUTTON_DIAMETER,CORNER_BUTTON_DIAMETER*2,false)}}
    var owner by remember {mutableStateOf<String?>(null)}
    var requested by remember {mutableStateOf<String?>(null)}
    var accountPressed by remember {mutableStateOf(false)}
    var settingsPressed by remember {mutableStateOf(false)}
    var layoutSignal by remember {mutableIntStateOf(0)}
    val frame=remember {mutableIntStateOf(0)}
    val accountMotion=remember {CornerMotion()}
    val settingsMotion=remember {CornerMotion()}
    LaunchedEffect(Unit) {
        snapshotFlow {listOf(requested,accountPressed,settingsPressed,backPressed,navigation.expanded,layoutSignal)}.collectLatest {
            if(owner==null && requested!=null) owner=requested
            var last=withFrameNanos{it}
            do {
                var moving=false
                withFrameNanos {now->
                    val dt=(now-last)/1e9f;last=now
                    val dockMoving=dockMotion.step(navigation.expanded,dt)
                    accountMotion.dockBounds(dockMotion.rect())
                    val a=accountMotion.step(owner=="account"&&requested==owner,dt,accountPressed||backPressed)
                    val s=settingsMotion.step(owner=="settings"&&requested==owner,dt,settingsPressed)
                    moving=a||s||dockMoving
                    if(owner!=null && requested!=owner && (if(owner=="account") accountMotion else settingsMotion).docked) {
                        owner=requested
                        moving=moving||owner!=null
                    }
                    frame.intValue++
                }
            }while(moving)
        }
    }
    SideEffect {navigation.modalOpen=owner!=null}
    BackHandler(owner!=null){requested=null}
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().frostSource(frost)) {
            CompositionLocalProvider(LocalCornerNavigation provides navigation) {content()}
        }
        if(owner!=null) Canvas(Modifier.fillMaxSize().zIndex(1f).pointerInput(Unit){detectTapGestures{requested=null}}) {
            frame.intValue
            drawRect(Color.Black.copy(alpha=.18f*max(accountMotion.progress,settingsMotion.progress)))
        }
        listOf("account","settings").forEach {name->
            key(name) {
                val right=name=="settings"
                // Separate root siblings: the modal scrim covers both idle buttons; the active
                // window remains above the scrim, its sibling button, the player and the scene.
                Box(Modifier.fillMaxSize().zIndex(if(owner==name) 2f else 0f)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start=10.dp,end=10.dp,top=4.dp,bottom=10.dp)) {
                    CornerBubble(
                        modifier=Modifier.align(if(right) Alignment.TopEnd else Alignment.TopStart),
                        motion=if(right) settingsMotion else accountMotion,frame=frame,
                        active=owner==name,right=right,frost=frost,dark=dark,
                        dockMotion=if(right) null else dockMotion,
                        backEnabled=!right&&navigation.expanded&&owner==null,
                        onBackPressed={backPressed=it},onBack=navigation::back,
                        avatar=if(right) null else avatar,
                        label=if(right) "设置" else "用户账户",
                        title=if(right) "设置" else "账户",
                        onPressed={if(right) settingsPressed=it else accountPressed=it},
                        onGeometryChanged={Snapshot.withoutReadObservation {layoutSignal++}},
                        onClick={requested=if(requested==name) null else name},
                    ) {
                        if(right) SettingsContents(bridge,dark,requested==owner)
                        else AccountContents(bridge,session,dark,avatar)
                    }
                }
            }
        }
    }
}


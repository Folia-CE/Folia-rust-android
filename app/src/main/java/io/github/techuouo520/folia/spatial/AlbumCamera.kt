package io.github.techuouo520.folia.spatial

import io.github.techuouo520.folia.MotionKernel
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI

/** The camera belongs to the map that presented this exact instance of the sleeve. */
internal class AlbumCamera(val cell: Int, val itemKey: String) {
    // The zoom focus and scan mask follow the same presented pose during the overlap.
    var face by mutableStateOf<Path?>(null)
        private set
    var focus = Rect.Zero
        private set
    var entering by mutableStateOf(true)
    var progress by mutableFloatStateOf(0f)
        private set
    val pose = floatArrayOf(1f, 0f, 0f, 0f, 0f)

    fun sample(p: Float) {
        MotionKernel.albumPose(pose, p, focus.center.x, focus.center.y, focus.width, focus.height)
        progress = p
    }

    var worldAtRest = false
        private set

    fun followFrame(frame: FloatArray, density: Float, width: Int, height: Int, worldChanged: Boolean, idle: Boolean) {
        // The context menu temporarily presents the sleeve on a separate plane.
        if (frame[DailySpatialProtocol.ACTION + 27] > .5f || frame[DailySpatialProtocol.ACTION + 3] != 0f) return
        val ownsCell = frame[DailySpatialProtocol.ACTION + DailySpatialProtocol.PLAYING_CELL].toInt() == cell
        val opening = frame[DailySpatialProtocol.ACTION + DailySpatialProtocol.PLAYING_OPEN_PROGRESS]
        if (ownsCell && (face != null || opening >= .85f) && width > 0 && height > 0 && (face == null || worldChanged)) {
            val slot = (0 until frame[16].toInt()).firstOrNull {
                frame[DailySpatialProtocol.HEADER + it * DailySpatialProtocol.STRIDE + 7].toInt() == cell
            }
            if (slot != null) {
                val path = albumOutline(frame, slot, density / width, density / height)
                val bounds = path.getBounds().intersect(Rect(0f, 0f, 1f, 1f))
                if (!bounds.isEmpty) {
                    focus = bounds
                    sample(progress)
                    face = path
                }
            }
        }
        if (idle) {
            if (face == null) entering = false else worldAtRest = true
        }
    }
}

/** Project a point on the printed face into map-local dp for both camera and controls. */
internal fun albumPoint(f: FloatArray, slot: Int, u: Float, v: Float): Offset {
    val point = if (f[DailySpatialProtocol.RHINE] > .5f) {
        val m = DailySpatialProtocol.RHINE_POSES + slot * 16
        val z = if (f[m+10] < 0) .028733334f else -.028733334f
        Offset(f[m+12]+f[m]*(u-.5f)+f[m+4]*(v-.5f)+f[m+8]*z,
            f[m+13]+f[m+1]*(u-.5f)+f[m+5]*(v-.5f)+f[m+9]*z)
    } else {
        val o = DailySpatialProtocol.HEADER + slot * DailySpatialProtocol.STRIDE
        val ax=(f[o+1]+u*f[o+3]+f[2])*f[4]/f[5]
        val ay=(f[o+2]+v*f[o+4]+f[3])*f[4]/f[6]
        val perspective=1500f/(1500f-f[5]*(cos(ax)-1f)-f[6]*(cos(ay)-1f))
        val lx=42f+54f*u
        val ly=f[20]+8f+f[o]*72f-f[21]+54f*v
        Offset(lx+(f[7]+f[5]*sin(ax)*perspective-lx)*f[1], ly+(f[8]+f[6]*sin(ay)*perspective-ly)*f[1])
    }
    val center = Offset(f[7],f[8])
    return center + (point-center)*f[DailySpatialProtocol.ACTION+DailySpatialProtocol.BACKGROUND_SCALE]
}

/** Project the sleeve perimeter with the same pose/UV mapping as the two map renderers.
 * Normalized viewport coordinates keep it registered to the world throughout the zoom. */
private fun albumOutline(f: FloatArray, slot: Int, sx: Float, sy: Float): Path {
    val path = Path()
    var first = true
    fun point(u: Float, v: Float) {
        val p = albumPoint(f, slot, u, v)
        if (first) { path.moveTo(p.x*sx, p.y*sy); first = false } else path.lineTo(p.x*sx, p.y*sy)
    }
    if (f[DailySpatialProtocol.RHINE] > .5f) {
        point(0f,0f); point(1f,0f); point(1f,1f); point(0f,1f)
    } else {
        val o = DailySpatialProtocol.HEADER + slot * DailySpatialProtocol.STRIDE
        val mode = f[1]
        val w = 54f + (f[o+3]-54f)*mode
        val h = 54f + (f[o+4]-54f)*mode
        val r = min(12f-6f*mode, min(w,h)*.5f)
        fun project(x: Float, y: Float) = point(x/w, y/h)
        // Straight texture edges curve after projection, so sample them as well as the corners.
        fun edge(x: Float,y: Float,ex: Float,ey: Float) {
            for(i in 0..8) project(x+(ex-x)*i/8, y+(ey-y)*i/8)
        }
        fun arc(cx: Float,cy: Float,start: Int) {
            for(i in 1..6) {
                val a = (start+i*15)*PI/180
                project(cx+r*cos(a).toFloat(),cy+r*sin(a).toFloat())
            }
        }
        edge(r,0f,w-r,0f); arc(w-r,r,-90)
        edge(w,r,w,h-r); arc(w-r,h-r,0)
        edge(w-r,h,r,h); arc(r,h-r,90)
        edge(0f,h-r,0f,r); arc(r,r,180)
    }
    path.close()
    return path
}

@Composable
internal fun AnimateAlbumCamera(camera: AlbumCamera, onReturned: () -> Unit) {
    val returned by rememberUpdatedState(onReturned)
    LaunchedEffect(camera) {
        val motion = CollectionMotion(10.0)
        snapshotFlow { camera.entering to (camera.face != null) }.collectLatest { (entering, ready) ->
            if (entering && !ready) return@collectLatest
            var last = System.nanoTime()
            do {
                val moving = withFrameNanos { now ->
                    val active = motion.step(if (entering) 1.0 else 0.0, (now - last) / 1e9)
                    last = now
                    camera.sample(motion.position.toFloat())
                    active
                }
            } while (moving)
            if (!camera.entering && !entering) returned()
        }
    }
}

/** Transform the existing world and its captions together. The surrounding app is outside this draw scope. */
internal fun Modifier.albumCamera(camera: AlbumCamera?, world: GraphicsLayer, ground: Color): Modifier {
    if (camera == null) return this
    return drawWithCache {
        val artwork = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
        var pixels: AlbumPixelScan? = null
        onDrawWithContent {
            val p = camera.progress
            val face = camera.face
            if (face == null || p == 0f) drawContent()
            else if (p == 1f) {
                drawRect(ground)
            } else {
                val focus = camera.focus
                val zoom = camera.pose[0]
                val dx = camera.pose[1] * size.width
                val dy = camera.pose[2] * size.height
                clipRect {
                    withTransform({
                        translate(dx, dy)
                        scale(zoom, zoom, Offset.Zero)
                    }) {
                        this@onDrawWithContent.drawContent()
                        val scan = pixels ?: AlbumPixelScan(size, focus, artwork).also { pixels = it }
                        with(scan) { drawScan(world, face, focus, ground, camera.pose[3]) }
                    }
                    val end = camera.pose[4]
                    if (end > 0f) drawRect(ground, alpha = end)
                }
            }
        }
    }
}

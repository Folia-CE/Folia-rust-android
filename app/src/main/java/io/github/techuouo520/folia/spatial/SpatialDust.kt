package io.github.techuouo520.folia.spatial

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.flow.first

internal class SpatialDust(private val picture: Bitmap,private val left: Float,private val top: Float,density: Float,
    private val onVacated: ()->Unit,private val onDone: ()->Unit) : AutoCloseable {
    private var handle: Long
    private val counts=IntArray(3)
    private val mask: FloatArray;private val grains: FloatArray
    private val maskColors: IntArray;private val grainColors: IntArray;private val indices: ShortArray
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val erase=Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var vacated=false;private var closed=false
    init {
        val ratio=minOf(1f,160f/maxOf(picture.width,picture.height))
        val small=Bitmap.createScaledBitmap(picture,maxOf(1,(picture.width*ratio).toInt()),maxOf(1,(picture.height*ratio).toInt()),true)
        val pixels=IntArray(small.width*small.height);small.getPixels(pixels,0,small.width,0,0,small.width,small.height)
        handle=nativeCreate(pixels,small.width,small.height,picture.width.toFloat(),picture.height.toFloat(),density,1f,-.3f,System.nanoTime())
        if(small!==picture) small.recycle()
        check(handle!=0L) { "Dust initialization failed" }
        val capacity=nativeCapacity(handle);val cells=(capacity ushr 32).toInt();val dust=capacity.toInt()
        if(cells !in 0..16384 || dust !in 0..16384) { nativeDestroy(handle);handle=0;error("Invalid dust capacity") }
        mask=FloatArray(cells*8);grains=FloatArray(dust*8);maskColors=IntArray(cells*8);grainColors=IntArray(dust*8)
        indices=ShortArray(maxOf(cells,dust)*6)
        repeat(maxOf(cells,dust)) { q -> val v=q*4;val i=q*6
            indices[i]=v.toShort();indices[i+1]=(v+1).toShort();indices[i+2]=(v+2).toShort()
            indices[i+3]=v.toShort();indices[i+4]=(v+2).toShort();indices[i+5]=(v+3).toShort() }
    }
    fun step(dt: Float): Boolean {
        if(closed) return false
        val alive=nativeStep(handle,dt,mask,maskColors,grains,grainColors,counts)
        if(counts[2]!=0&&!vacated) { vacated=true;onVacated() }
        if(!alive) close()
        return alive
    }
    fun draw(canvas: Canvas) {
        if(closed) return
        val save=canvas.save();canvas.translate(left,top)
        if(counts[2]==0) {
            val layer=canvas.saveLayer(0f,0f,picture.width.toFloat(),picture.height.toFloat(),null)
            canvas.drawBitmap(picture,0f,0f,paint)
            if(counts[0]>0) canvas.drawVertices(Canvas.VertexMode.TRIANGLES,counts[0]*8,mask,0,null,0,maskColors,0,indices,0,counts[0]*6,erase)
            canvas.restoreToCount(layer)
        }
        if(counts[1]>0) canvas.drawVertices(Canvas.VertexMode.TRIANGLES,counts[1]*8,grains,0,null,0,grainColors,0,indices,0,counts[1]*6,paint)
        canvas.restoreToCount(save)
    }
    override fun close() {
        if(closed) return;closed=true;nativeDestroy(handle);handle=0
        if(!vacated) { vacated=true;onVacated() };picture.recycle();onDone()
    }
    private external fun nativeCreate(pixels: IntArray,sw: Int,sh: Int,width: Float,height: Float,unit: Float,windX: Float,windY: Float,seed: Long): Long
    private external fun nativeCapacity(handle: Long): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStep(handle: Long,dt: Float,mask: FloatArray,maskColors: IntArray,dust: FloatArray,dustColors: IntArray,counts: IntArray): Boolean
}

@Composable
internal fun SpatialDustLayer(dust: SpatialDust,start: Boolean) {
    var revision by remember(dust) { mutableIntStateOf(0) }
    val canStart=rememberUpdatedState(start)
    LaunchedEffect(dust) {
        // Phase 3 confirms that GL hid the source card; it is a one-time handoff.
        // onVacated removes the card and clears that phase before the particle tail
        // finishes, so subsequent phase changes must not cancel or restart this loop.
        snapshotFlow { canStart.value }.first { it }
        var previous=0L
        do {
            val time=withFrameNanos { it }
            val alive=dust.step(if(previous==0L) 0f else ((time-previous)/1e9f).coerceIn(0f,.05f))
            previous=time;revision++
        } while(alive)
    }
    Canvas(Modifier.fillMaxSize()) { revision;drawIntoCanvas { dust.draw(it.nativeCanvas) } }
}

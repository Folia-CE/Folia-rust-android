package io.github.techuouo520.folia.spatial

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Window
import java.util.function.Consumer

/** A page-scoped window lease. Only the live granted ratio reaches the shaders. */
internal class DailySpatialHdr(private val context: Context, private val publish: (Float) -> Unit) : AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private var window: Window?=null
    private var display: Display?=null
    private var manager: DisplayManager?=null
    private var requested=Float.NaN
    private var listening=false
    private val ratioChanged=Consumer<Display> { refresh() }
    private val listener=object: DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) { if(display?.displayId==id) close() }
        override fun onDisplayChanged(id: Int) { if(display?.displayId==id) refresh() }
    }
    fun setActive(active: Boolean, target: Display?) {
        if(Build.VERSION.SDK_INT<34) return
        if(!active || target==null) { close(); return }
        if(window!=null && display?.displayId==target.displayId) return
        close()
        var c=context
        while(c is ContextWrapper && c !is Activity) c=c.baseContext
        val w=(c as? Activity)?.window ?: return
        window=w; display=target
        // Parent and detail can both be alive during a morph. The last lease alone
        // restores SDR, regardless of which scene closes or toggles off first.
        acquire(w,this)
        manager=context.getSystemService(DisplayManager::class.java)
        manager?.registerDisplayListener(listener,main); refresh()
    }
    private fun refresh() {
        val w=window ?: return; val d=display ?: return
        val caps=DisplayCapabilities.read(d)
        if(Build.VERSION.SDK_INT>=34 && caps.ratioAvailable && !listening) {
            try { d.registerHdrSdrRatioChangedListener({ main.post(it) },ratioChanged); listening=true }
            catch (_: IllegalStateException) { }
        }
        val desired=caps.requestedHeadroom()
        if(desired!=requested) {request(w,this,desired);requested=desired}
        publish(caps.currentHeadroom)
    }
    override fun close() {
        if(Build.VERSION.SDK_INT>=34 && listening) display?.unregisterHdrSdrRatioChangedListener(ratioChanged)
        listening=false; manager?.unregisterDisplayListener(listener); manager=null
        window?.let {release(it,this)}
        window=null; display=null; requested=Float.NaN; publish(1f)
    }

    /** Main-thread window ownership, shared across retained map/Rhine renderers. */
    private class Lease(val mode: Int,val headroom: Float) {
        val requests=mutableMapOf<DailySpatialHdr,Float>()
        var applied=Float.NaN
    }
    companion object {
        private val leases=java.util.WeakHashMap<Window,Lease>()
        private fun acquire(window: Window,owner: DailySpatialHdr) {
            val lease=leases.getOrPut(window) {
                Lease(window.colorMode,if(Build.VERSION.SDK_INT>=35) window.desiredHdrHeadroom else 0f)
            }
            lease.requests[owner]=1f
            window.colorMode=ActivityInfo.COLOR_MODE_HDR
        }
        private fun request(window: Window,owner: DailySpatialHdr,ratio: Float) {
            val lease=leases[window] ?: return
            lease.requests[owner]=ratio
            apply(window,lease)
        }
        private fun apply(window: Window,lease: Lease) {
            val ratio=lease.requests.values.maxOrNull() ?: return
            if(Build.VERSION.SDK_INT>=35 && ratio!=lease.applied) window.desiredHdrHeadroom=ratio
            lease.applied=ratio
        }
        private fun release(window: Window,owner: DailySpatialHdr) {
            val lease=leases[window] ?: return
            lease.requests.remove(owner)
            if(lease.requests.isNotEmpty()) {apply(window,lease);return}
            leases.remove(window)
            if(window.colorMode==ActivityInfo.COLOR_MODE_HDR &&
                (Build.VERSION.SDK_INT<35 || lease.applied.isNaN() || window.desiredHdrHeadroom==lease.applied)) {
                if(Build.VERSION.SDK_INT>=35) window.desiredHdrHeadroom=lease.headroom
                window.colorMode=lease.mode
            }
        }
    }

}

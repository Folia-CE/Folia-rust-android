package io.github.techuouo520.folia.spatial

import android.os.Build
import android.view.Display

internal data class DisplayCapabilities(val ratioAvailable: Boolean, val currentHeadroom: Float, val highest: Float) {
    fun requestedHeadroom(): Float = if(!ratioAvailable) 1f else if(highest.isFinite()) highest else 10000f
    companion object {
        fun headroom(value: Float): Float = if(value.isFinite()) value.coerceIn(1f, 10000f) else 1f
        fun read(display: Display?): DisplayCapabilities {
            if(display == null || Build.VERSION.SDK_INT < 34) return DisplayCapabilities(false,1f,1f)
            return try {
                val available=display.isHdr && display.isHdrSdrRatioAvailable
                DisplayCapabilities(available,if(available) headroom(display.hdrSdrRatio) else 1f,
                    if(available && Build.VERSION.SDK_INT>=36) headroom(display.highestHdrSdrRatio) else Float.NaN)
            } catch (_: IllegalStateException) { DisplayCapabilities(false,1f,1f) }
        }
    }
}

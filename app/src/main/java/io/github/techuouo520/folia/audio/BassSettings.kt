package io.github.techuouo520.folia.audio

import android.content.Context
import androidx.compose.runtime.*
import kotlin.math.ln
import kotlin.math.pow

internal data class BassPreferences(val enabled: Boolean=false,val smart: Boolean=false,val strength: Float=20f)
internal class BassSettings(context: Context) {
    private val prefs=context.getSharedPreferences("salam-bass",0)
    val pad=context.resources.configuration.smallestScreenWidthDp>=600 && !context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")
    var value by mutableStateOf(BassPreferences(prefs.getBoolean("enabled",false),prefs.getBoolean("smart",false),prefs.getFloat("strength",20f).coerceIn(0f,30f)))
        private set
    fun update(next: BassPreferences) {
        value=next.copy(strength=next.strength.coerceIn(0f,30f))
        prefs.edit().putBoolean("enabled",value.enabled).putBoolean("smart",value.smart).putFloat("strength",value.strength).remove("quality").apply()
    }
    fun cutoffs(): Pair<Float,Float> {
        // Fixed 50% speaker adaptation; legacy saved quality is intentionally not read.
        val scale=1f+(.75f-.50f)*.4f
        return (if(pad) 80f else 110f)*scale to (if(pad) 105f else 150f)*scale
    }
    fun strength(volume: Float): Float {
        if(!value.smart)return value.strength
        // SalamPlayer's uncalibrated conservative preset, then its speaker amount scale.
        val ratio=if(volume<=.05f) 1f else ((1f-volume.coerceIn(0f,1f))/.95f).pow(ln(.55f)/ln(.55f/.95f))
        return (30f*ratio*1.05f).coerceIn(0f,30f)*.82f
    }
}

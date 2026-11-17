package io.github.techuouo520.folia.spatial

import android.content.Context

internal object DailySpatialShaders {
    fun load(context: Context?,path: String?,defines: String?): String {
        requireNotNull(context);requireNotNull(path)
        var source=context.assets.open(path).bufferedReader().use { it.readText() }
        if(source.contains("// RHINE_SHARED_LIGHT")) {
            val light=context.assets.open("shaders/daily_rhine_light.glsl").bufferedReader().use { it.readText() }
            source=source.replace("// RHINE_SHARED_LIGHT",light)
        }
        if (source.contains("// COVER_LYRICS_CHIP")) {
            val chip = context.assets.open("shaders/daily_lyrics_chip.glsl").bufferedReader().use { it.readText() }
            source = source.replace("// COVER_LYRICS_CHIP", chip)
        }
        if(!defines.isNullOrEmpty()) {
            val line=source.indexOf('\n')+1
            source=source.substring(0,line)+defines+source.substring(line)
        }
        return source
    }
}

package io.github.techuouo520.folia

import android.content.Context
import androidx.compose.runtime.*

/** Shared by live and cached scenes; opt-in only, persisted across process restarts. */
internal class DisplaySettings(context: Context) {
    private val store=context.getSharedPreferences("display",Context.MODE_PRIVATE)
    var themeMode by mutableStateOf(store.getString("theme-mode","system")?:"system")
        private set
    fun dark(system: Boolean)=when(themeMode) {"dark"->true;"light"->false;else->system}
    fun setTheme(mode: String) {
        require(mode in setOf("system","light","dark"))
        themeMode=mode;store.edit().putString("theme-mode",mode).apply()
    }
    var hdrEnabled by mutableStateOf(store.getBoolean("hdr-enabled",false))
        private set
    fun setHdr(enabled: Boolean) {
        if(hdrEnabled==enabled) return
        hdrEnabled=enabled
        store.edit().putBoolean("hdr-enabled",enabled).apply()
    }
}

// Process entry: Android context first, then the Rust bridge kept for the process lifetime.
package io.github.techuouo520.folia

import androidx.compose.runtime.*
import android.app.Application
import android.content.Context
import android.util.Log
import io.github.techuouo520.folia.bridge.FoliaBridge
import java.util.Locale

class FoliaApp : Application() {
    lateinit var bridge: FoliaBridge
        private set

    internal var appearanceVersion by mutableIntStateOf(0)

    internal lateinit var displaySettings: DisplaySettings
        private set

    internal lateinit var audioEffects: io.github.techuouo520.folia.audio.AudioEffects
        private set

    override fun onCreate() {
        super.onCreate()
        displaySettings=DisplaySettings(this)
        AndroidContext.nativeInit(this)
        bridge = FoliaBridge.initialize(filesDir.absolutePath, cacheDir.absolutePath)
        bridge.setSystemLocale(Locale.getDefault().toLanguageTag())
        audioEffects=io.github.techuouo520.folia.audio.AudioEffects(this,bridge) {appearanceVersion++}
    }

    companion object {
        fun from(context: Context): FoliaApp = context.applicationContext as FoliaApp
    }
}

fun FoliaBridge.t(key: String): String = label(key)

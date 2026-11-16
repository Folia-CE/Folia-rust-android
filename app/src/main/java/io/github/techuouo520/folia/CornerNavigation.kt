package io.github.techuouo520.folia

import androidx.compose.runtime.*
import io.github.techuouo520.folia.spatial.CollectionEntry

/** The visible collection owns navigation; cached/disposed pages cannot clear a newer owner. */
internal class CornerNavigation {
    private var entry by mutableStateOf<CollectionEntry?>(null)
    var modalOpen by mutableStateOf(false)
    var playerVisible by mutableStateOf(false)
    val expanded get() = entry?.openRequested == true && !playerVisible
    fun attach(owner: CollectionEntry) { entry=owner }
    fun detach(owner: CollectionEntry) { if(entry===owner) entry=null }
    fun back() { entry?.openRequested=false }
}

internal val LocalCornerNavigation=staticCompositionLocalOf<CornerNavigation?> {null}

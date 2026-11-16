package io.github.techuouo520.folia.spatial

data class SpatialItem(val key: String, val name: String, val singer: String, val coverUrl: String, val path: String = "", val collection: Boolean = false) {
    fun stableId(): String = key
}

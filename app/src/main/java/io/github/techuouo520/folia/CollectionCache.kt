package io.github.techuouo520.folia

import androidx.compose.runtime.mutableStateMapOf
import io.github.techuouo520.folia.bridge.CollectionPage
import io.github.techuouo520.folia.bridge.CoverAsk
import io.github.techuouo520.folia.bridge.FoliaBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Home-scoped, bounded and single-flight. UI reads snapshots; bridge work stays on IO. */
internal class CollectionCache(private val bridge: FoliaBridge, private val scope: CoroutineScope) {
    private val pages=mutableStateMapOf<String,CollectionPage>()
    private val jobs=mutableMapOf<String,Job>()
    private val order=ArrayDeque<String>()
    private val pinned=mutableSetOf<String>()
    private val permits=Semaphore(2)

    fun peek(key: String): CollectionPage?=pages[key]
    fun pin(key: String) { pinned.add(key);touch(key) }
    fun unpin(key: String) { pinned.remove(key);trim() }
    private fun touch(key: String) { order.remove(key);order.addLast(key) }
    private fun trim() {
        while(order.size>8) {
            val victim=order.firstOrNull {it !in pinned} ?: break
            order.remove(victim);jobs.remove(victim)?.cancel();pages.remove(victim)
        }
    }
    fun request(key: String,title: String,fallbackCover: String,force: Boolean=false) {
        touch(key);trim()
        if(jobs.containsKey(key)) return
        if(!force && pages[key]?.let {!it.loading}==true) return
        pages[key]=CollectionPage(key,title,true,"",emptyList())
        jobs[key]=scope.launch {
            try {
                permits.withPermit {
                    withContext(Dispatchers.IO) {bridge.beginCollection(key)}
                    while(isActive) {
                        val page=withContext(Dispatchers.IO) {bridge.collectionPage(key)}
                        pages[key]=page
                        if(!page.loading) {
                            // Before entry, keep predictive warming. Once pinned, the scene's
                            // radial admission owns demand; do not enqueue an extra bulk batch.
                            if(page.error.isEmpty() && key !in pinned) withContext(Dispatchers.IO) {
                                // Use precisely the same keys and resolution as the scene's loader.
                                val asks=page.tracks.take(12).map { track ->
                                    val url=track.coverUrl.ifBlank {fallbackCover}
                                    CoverAsk("collection-original:${track.path}:$url",track.path,url,1536,1536)
                                }.distinctBy {it.key}
                                bridge.requestCovers(asks)
                            }
                            break
                        }
                        delay(200)
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {pages[key]=CollectionPage(key,title,false,error.message ?: "读取失败",emptyList())}
            finally { if(jobs[key]===currentCoroutineContext()[Job]) jobs.remove(key) }
        }
    }
}

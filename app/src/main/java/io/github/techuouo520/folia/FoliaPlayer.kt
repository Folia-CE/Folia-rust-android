// Media3 player whose timeline and transport commands are answered by the Rust queue.
package io.github.techuouo520.folia

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.RepeatMode
import io.github.techuouo520.folia.bridge.TransportState
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.session.MediaSession

@UnstableApi
class FoliaPlayer(context: Context, private val bridge: FoliaBridge, looper: Looper) : SimpleBasePlayer(looper) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val calls=Mutex()
    private var status: PlaybackSnapshot?=null
    private var tracks=emptyList<LibraryTrack>()
    private var refreshing=false
    private var released=false
    private val appContext = context.applicationContext
    private var cachedGeneration = Long.MIN_VALUE
    private var cachedIndex = -1
    private var cachedItems = emptyList<MediaItemData>()
    private var artwork: ByteArray? = null
    private var artworkSource: PlaybackArtwork? = null
    private var artworkJob: kotlinx.coroutines.Job? = null

    private fun updateArtwork(snapshot: PlaybackSnapshot) {
        val source=snapshot.artSource()
        if(source==artworkSource) return
        artworkJob?.cancel()
        if(source?.owner!=artworkSource?.owner) artwork=null
        artworkSource=source
        cachedGeneration=Long.MIN_VALUE
        if(source!=null) artworkJob=scope.launch {
            val bytes=bridge.readArtwork(source,::notificationCover)
            if(!released && source==artworkSource) {
                artwork=bytes
                cachedGeneration=Long.MIN_VALUE
                invalidateState()
            }
        }
    }

    fun refresh() {
        if(refreshing||released) return
        refreshing=true
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {calls.withLock {
                    val next=bridge.status()
                    next to if(next.queueGeneration!=status?.queueGeneration) bridge.queue() else null
                }}
                if(!released) {status=result.first;result.second?.let {tracks=it};updateArtwork(result.first);invalidateState()}
            } catch(e: Exception) {if(e is CancellationException) throw e;Log.e(TAG,"refresh failed",e)}
            finally {refreshing=false}
        }
    }

    fun resumption(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future=SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                val pair=withContext(Dispatchers.IO) {calls.withLock {bridge.status() to bridge.queue()}}
                status=pair.first;tracks=pair.second;cachedGeneration=Long.MIN_VALUE;updateArtwork(pair.first)
                future.set(MediaSession.MediaItemsWithStartPosition(tracks.mapIndexed {i,t->item(i,t).mediaItem},pair.first.queueIndex.coerceAtLeast(0),(pair.first.positionSeconds*1000).toLong()))
                invalidateState()
            } catch(e: Exception) {future.setException(e)}
        }
        return future
    }

    override fun getState(): State {
        val status=this.status ?: return State.Builder().setPlaybackState(Player.STATE_IDLE)
            .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_SET_MEDIA_ITEM).build()).build()
        val playlist = playlist(status)
        val playbackState = when {
            playlist.isEmpty() -> Player.STATE_IDLE
            status.loading -> Player.STATE_BUFFERING
            status.state == TransportState.PLAYING || status.state == TransportState.PAUSED -> Player.STATE_READY
            status.state == TransportState.ENDED -> Player.STATE_ENDED
            else -> Player.STATE_IDLE
        }
        val positionMs = (status.positionSeconds * 1000.0).toLong().coerceAtLeast(0L)
        val builder = State.Builder()
            .setAvailableCommands(commands(status, playlist.isNotEmpty()))
            .setPlayWhenReady(status.state == TransportState.PLAYING || (status.loading&&status.state!=TransportState.PAUSED), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setRepeatMode(repeatMode(status.repeatMode))
            .setVolume(status.volume.coerceIn(0f, 1f))
            .setContentPositionMs(positionMs)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(positionMs))
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE / 4)
        if (playlist.isNotEmpty()) {
            builder.setPlaylist(playlist).setCurrentMediaItemIndex(status.queueIndex.coerceIn(0, playlist.lastIndex))
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if(playWhenReady&&!AudioFocus.acquire(appContext,bridge)) return Futures.immediateFailedFuture<Void>(IllegalStateException("Audio focus denied"))
        if(!playWhenReady) {AudioFocus.clearResume();AudioFocus.abandon()}
        return runBridge {if(playWhenReady) bridge.play() else bridge.pause()}
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if(seekCommand!=Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM && !AudioFocus.acquire(appContext,bridge))
            return Futures.immediateFailedFuture<Void>(IllegalStateException("Audio focus denied"))
        return runBridge {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> bridge.next()
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> bridge.previous()
                Player.COMMAND_SEEK_TO_MEDIA_ITEM -> if (mediaItemIndex >= 0) bridge.playIndex(mediaItemIndex)
                else -> if (positionMs != C.TIME_UNSET) bridge.seek(positionMs / 1000.0)
            }
        }
    }

    override fun handleStop(): ListenableFuture<*> {
        return runBridge {
            AudioFocus.clearResume()
            bridge.stop()
            AudioFocus.abandon()
        }
    }

    override fun handleRelease(): ListenableFuture<*> {
        released=true;scope.cancel();return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>,startIndex: Int,startPositionMs: Long): ListenableFuture<*> = runBridge {
        // MediaSession resumption refers to the queue already restored by the Rust owner.
        val current=bridge.status()
        if(startIndex>=0&&startIndex!=current.queueIndex) throw IllegalArgumentException("Restored queue index changed")
        if(startPositionMs!=C.TIME_UNSET) bridge.seek(startPositionMs/1000.0)
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        return runBridge {
            bridge.setRepeat(
                when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
            )
        }
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        return runBridge { bridge.setVolume(if (volumeOperationType == C.VOLUME_OPERATION_TYPE_MUTE) 0f else volume) }
    }

    private fun playlist(status: PlaybackSnapshot): List<MediaItemData> {
        if (status.queueGeneration == cachedGeneration && status.queueIndex == cachedIndex) {
            return if (status.queueLength == 0) emptyList() else cachedItems
        }
        val built = try {
            tracks.mapIndexed { index, track -> item(index, track) }
        } catch (error: Exception) {
            Log.e(TAG, "queue read failed", error)
            return cachedItems
        }
        cachedIndex = status.queueIndex
        cachedGeneration = status.queueGeneration
        cachedItems = built
        return built
    }

    private fun item(index: Int, track: LibraryTrack): MediaItemData {
        val metadataBuilder = MediaMetadata.Builder().setTitle(track.title)
        if (track.artist.isNotBlank()) {
            metadataBuilder.setArtist(track.artist)
        }
        if (track.album.isNotBlank()) {
            metadataBuilder.setAlbumTitle(track.album)
        }
        if (index == status?.queueIndex && artwork != null && track.artOwner() == artworkSource?.owner) {
            metadataBuilder.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val metadata = metadataBuilder.build()
        val mediaItem = MediaItem.Builder().setMediaId(track.path.ifBlank { track.id.toString() }).setUri(fileUri(track.path)).setMediaMetadata(metadata).build()
        val durationUs = if (track.durationSeconds > 0.0) (track.durationSeconds * 1_000_000.0).toLong() else C.TIME_UNSET
        return MediaItemData.Builder("$index:${track.id}")
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(durationUs)
            .setIsSeekable(durationUs > 0)
            .setIsDynamic(false)
            .build()
    }

    private fun commands(status: PlaybackSnapshot, hasPlaylist: Boolean): Player.Commands {
        val builder = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_SET_VOLUME)
            .add(Player.COMMAND_RELEASE)
        if (hasPlaylist) {
            builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        }
        if (status.hasNext) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (status.hasPrevious) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    private fun runBridge(block: () -> Unit): ListenableFuture<*> {
        val future=SettableFuture.create<Void>()
        scope.launch {
            try {withContext(Dispatchers.IO) {calls.withLock {block()}};refresh();future.set(null)}
            catch(e: Exception) {Log.e(TAG,"playback command failed",e);future.setException(e)}
        }
        return future
    }

    private fun repeatMode(mode: RepeatMode): Int {
        return when (mode) {
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun fileUri(path: String): Uri {
        if (path.isEmpty()) {
            return Uri.EMPTY
        }
        return Uri.fromFile(File(path))
    }

    private companion object {
        const val TAG = "FoliaPlayer"
    }
}

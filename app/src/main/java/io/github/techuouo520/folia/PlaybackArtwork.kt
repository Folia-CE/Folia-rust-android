package io.github.techuouo520.folia

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.techuouo520.folia.bridge.CoverLookup
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

internal data class ArtworkOwner(val id: Long, val provider: String, val song: String, val localFile: String)
internal data class PlaybackArtwork(val owner: ArtworkOwner, val path: String, val url: String)

private fun owner(id: Long, provider: String, song: String, path: String) =
    ArtworkOwner(id, provider, song, if (song.isBlank()) path else "")

internal fun LibraryTrack.artOwner() = owner(id, provider, onlineId, path)
internal fun PlaybackSnapshot.artSource(): PlaybackArtwork? =
    if (queueLength == 0) null else PlaybackArtwork(owner(trackId, provider, onlineId, path), path, coverUrl)

/** Every lookup names the source captured at selection. A completed old read cannot become the new song's art. */
internal suspend fun <T> FoliaBridge.readArtwork(source: PlaybackArtwork, decode: (ByteArray) -> T?): T? =
    withContext(Dispatchers.IO) {
        var path = source.path
        var interval = 125L
        while (true) {
            when (val result = playbackCover(path, source.url)) {
                is CoverLookup.Ready -> {
                    val value = decode(result.bytes)
                    if (value != null) return@withContext value
                    // An embedded image can exist but be unreadable by Android.
                    if (path.isEmpty() || source.url.isEmpty()) break
                    path = ""
                    continue
                }
                is CoverLookup.Missing -> break
                is CoverLookup.Pending -> {
                    delay(interval)
                    interval = (interval * 2).coerceAtMost(2000L)
                }
            }
        }
        null
    }

/** Media3 transports these bytes in metadata parcels. Keep even large embedded originals small. */
internal fun notificationCover(bytes: ByteArray): ByteArray? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val longest = maxOf(options.outWidth, options.outHeight)
    var sample = 1
    while (longest / sample > 768) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return try {
        if (sample == 1 && bytes.size <= 160 * 1024) bytes
        else ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) out.toByteArray() else null
        }
    } finally { bitmap.recycle() }
}

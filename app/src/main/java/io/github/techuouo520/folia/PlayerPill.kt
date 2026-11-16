package io.github.techuouo520.folia

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Collapsed phone player. Its backdrop samples only the home content, never this pill. */
@Composable
internal fun PlayerPill(
    bridge: FoliaBridge,
    playback: PlaybackSnapshot,
    frost: FrostBackdrop,
    dark: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val ink = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier.widthIn(max = 560.dp).fillMaxWidth()
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .16f))
            .clip(shape)
            .frostedBackdrop(frost, dark)
            .border(1.dp, Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (dark) .20f else .90f),
                Color.White.copy(alpha = if (dark) .05f else .18f),
            )), shape)
            .clickable(onClickLabel = stringResource(R.string.player_pill_open), onClick = onOpen)
            .heightIn(min = 68.dp)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerPillArtwork(bridge, playback, accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(playback.title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playback.artist.ifBlank { bridge.t("player.unknownArtist") }, color = secondary,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PillProgress(playback, accent)
        }
        PillTransportButton(bridge.t(if (playback.state == TransportState.PLAYING) "player.pause" else "player.play"),
            onClick = onToggle) {
            val pause = animateFloatAsState(if (playback.state == TransportState.PLAYING) 1f else 0f,
                tween(160), label = "pillTransport")
            Canvas(Modifier.size(22.dp)) {
                val unit = size.width / 22f
                val triangle = Path().apply {
                    moveTo(6 * unit, 3 * unit); lineTo(18 * unit, 11 * unit)
                    lineTo(6 * unit, 19 * unit); close()
                }
                drawPath(triangle, ink, alpha = 1f - pause.value)
                for (x in listOf(5f, 13f)) drawRoundRect(ink, Offset(x * unit, 3 * unit),
                    Size(4 * unit, 16 * unit), CornerRadius(unit), alpha = pause.value)
            }
        }
        PillTransportButton(bridge.t("ui.nextTrack"), enabled = playback.hasNext, onClick = onNext) {
            Icon(painterResource(R.drawable.player_pill_next), contentDescription = null,
                tint = ink.copy(alpha = if (playback.hasNext) 1f else .35f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PillTransportButton(label: String, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .9f else 1f, spring(dampingRatio = .85f, stiffness = 700f), label = "pillPress")
    IconButton(onClick, enabled = enabled, interactionSource = interaction,
        modifier = Modifier.size(44.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .semantics { contentDescription = label }) { content() }
}

@Composable
private fun PillProgress(playback: PlaybackSnapshot, accent: Color) {
    val target = if (playback.durationSeconds > 0 && playback.durationSeconds.isFinite() && playback.positionSeconds.isFinite())
        (playback.positionSeconds / playback.durationSeconds).toFloat().coerceIn(0f, 1f) else 0f
    // A new track starts at its own position; never animate backwards from the old song.
    key(playback.trackId, playback.provider, playback.onlineId) {
        val progress = animateFloatAsState(target, tween(240), label = "pillProgress")
        Canvas(Modifier.fillMaxWidth().height(2.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
        }) {
            drawRoundRect(accent.copy(alpha = .15f), cornerRadius = CornerRadius(size.height))
            drawRoundRect(accent, size = Size(size.width * progress.value, size.height), cornerRadius = CornerRadius(size.height))
        }
    }
}

@Composable
private fun PlayerPillArtwork(bridge: FoliaBridge, playback: PlaybackSnapshot, accent: Color) {
    val edge = with(LocalDensity.current) { 46.dp.roundToPx() }
    // The outer-side radius follows the inset of the pill; the inner side stays softly square.
    val shape = RoundedCornerShape(topStart = 23.dp, bottomStart = 23.dp, topEnd = 14.dp, bottomEnd = 14.dp)
    key(playback.trackId, playback.provider, playback.onlineId) {
        val cover by produceState<ImageBitmap?>(null, bridge, edge) {
            repeat(80) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = bridge.artwork()
                        if (bytes == null || bytes.isEmpty()) null else {
                            val current = bridge.status()
                            if (current.trackId == playback.trackId && current.provider == playback.provider && current.onlineId == playback.onlineId)
                                decodeCover(bytes, edge) else null
                        }
                    }.getOrNull()
                }
                if (value != null) return@produceState
                delay(250)
            }
        }
        val opacity = animateFloatAsState(if (cover != null) 1f else 0f, tween(180), label = "pillArtwork")
        Box(Modifier.size(46.dp).clip(shape).background(accent.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(24.dp)) {
                drawCircle(accent.copy(alpha = .5f), style = Stroke(1.5.dp.toPx()))
                drawCircle(accent.copy(alpha = .5f), radius = 3.dp.toPx())
            }
            cover?.let { image ->
                Image(image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value })
            }
        }
    }
}

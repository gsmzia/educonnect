package com.security.myapplication.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WhatsApp-style Floating In-App Mini Player Bar.
 * Appears at the top of the dashboard or screens whenever an audio/voice note is playing.
 *
 * v2 changes:
 *  - Shows a small loading spinner while the track is buffering, instead of a
 *    play button that silently does nothing for a moment (matches the
 *    perceived responsiveness WhatsApp has).
 *  - The progress bar is now draggable to scrub through the voice note, wired
 *    to GlobalAudioPlayer.seekTo(), which now actually works end-to-end since
 *    the underlying player survives backgrounding.
 *
 * v3 changes (this pass — visual design is untouched, only interaction bugs fixed):
 *  - The scrub bar used to run two independent gesture detectors
 *    (detectTapGestures + detectHorizontalDragGestures) on the same pointer
 *    events. Compose doesn't arbitrate between sibling detectors like that,
 *    so a touch-and-small-move could fire BOTH a tap-seek and a drag-seek for
 *    the same gesture, occasionally jumping to the wrong spot. It's now one
 *    unified gesture (press-to-seek, then follow the finger).
 *  - While dragging, the bar now shows the position you're dragging to
 *    instead of the value ticking in from the background player. Previously
 *    those two fought each other — the live playback position kept arriving
 *    every 200ms and could yank the thumb backward mid-drag.
 *  - Duration falls back to "--:--" instead of a nonsensical "0:00" if a
 *    track's length isn't known yet.
 */
@Composable
fun MiniAudioPlayerBar(
    modifier: Modifier = Modifier,
    onBarClick: () -> Unit = {}
) {
    val activeId by GlobalAudioPlayer.currentPlayingMsgId
    val isPlaying by GlobalAudioPlayer.isPlaying
    val isBuffering by GlobalAudioPlayer.isBuffering
    val progress by GlobalAudioPlayer.currentProgress
    val currentPosMs by GlobalAudioPlayer.currentPositionMs
    val totalDurMs by GlobalAudioPlayer.totalDurationMs
    val title by GlobalAudioPlayer.currentTitle
    val sender by GlobalAudioPlayer.currentSender

    val isVisible = activeId != null

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        val curSec = (currentPosMs / 1000) % 60
        val curMin = (currentPosMs / 1000) / 60
        val totSec = (totalDurMs / 1000) % 60
        val totMin = (totalDurMs / 1000) / 60
        val timeStr = if (totalDurMs > 0) {
            "%d:%02d / %d:%02d".format(curMin, curSec, totMin, totSec)
        } else {
            "--:--"
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF19163A)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable { onBarClick() }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play/Pause circular button (shows a spinner while buffering)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF8B6BFF), Color(0xFF6B4EFF))))
                            .clickable(enabled = !isBuffering) {
                                if (isPlaying) GlobalAudioPlayer.pause() else GlobalAudioPlayer.resume()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // Title & Time info
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Mic,
                                contentDescription = null,
                                tint = Color(0xFF9D80FF),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (sender.isNotBlank()) "$sender • $title" else title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = if (isBuffering) "Loading…" else timeStr,
                            color = Color(0xFF9D80FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Close (X) button
                    IconButton(
                        onClick = { GlobalAudioPlayer.stop() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Draggable progress bar — tap or drag anywhere to seek.
                var barWidthPx by remember { mutableFloatStateOf(0f) }
                var isDragging by remember { mutableStateOf(false) }
                var dragProgress by remember { mutableFloatStateOf(0f) }

                // While the user's finger is down, show the position they're
                // dragging to rather than the value ticking in from the
                // background player — otherwise the two race each other and
                // the thumb visibly jumps mid-drag.
                val displayedProgress = if (isDragging) dragProgress else progress

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp) // generous touch target, bigger than the visible track
                        .onSizeChanged { barWidthPx = it.width.toFloat() }
                        .pointerInput(Unit) {
                            // One gesture scope handling both "tap anywhere to
                            // jump there" and "drag to scrub", instead of two
                            // separate detectors racing over the same events.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (barWidthPx <= 0f) return@awaitEachGesture
                                down.consume()

                                isDragging = true
                                var fraction = (down.position.x / barWidthPx).coerceIn(0f, 1f)
                                dragProgress = fraction
                                GlobalAudioPlayer.seekTo(fraction)

                                horizontalDrag(down.id) { change ->
                                    change.consume()
                                    fraction = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                                    dragProgress = fraction
                                    GlobalAudioPlayer.seekTo(fraction)
                                }

                                isDragging = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LinearProgressIndicator(
                        progress = { displayedProgress },
                        color = Color(0xFF8B6BFF),
                        trackColor = Color(0xFF2B2655),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }
            }
        }
    }
}

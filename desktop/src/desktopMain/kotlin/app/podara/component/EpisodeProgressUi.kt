package app.podara.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import app.podara.player.EpisodeProgress
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings

/** Test tag of the progress underline, so UI tests can assert its presence. */
const val EPISODE_PROGRESS_BAR_TAG = "episodeProgressBar"

/**
 * Thin listening-progress line drawn along the bottom edge of a list card.
 *
 * Chosen over a progress ring on the artwork: at list density a ring is unreadable, while a
 * 3dp underline says "how much is left" at a glance without touching the layout, and it works
 * identically in the feed, in a channel's episode list and in the play queue.
 */
@Composable
fun EpisodeProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = DesignTokens.FavoriteEpisodeList.ProgressTrackColor,
    progressColor: Color = PodaraTheme.colors.accent,
    height: Dp = DesignTokens.FavoriteEpisodeList.ProgressBarHeight
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320),
        label = "episodeProgressBar"
    )
    Canvas(modifier = modifier.fillMaxWidth().height(height).testTag(EPISODE_PROGRESS_BAR_TAG)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        if (animated > 0f) {
            drawRoundRect(
                color = progressColor,
                size = Size(size.width * animated, size.height),
                cornerRadius = radius
            )
        }
    }
}

/**
 * Human readable listening state of an episode, or null when there is nothing to say
 * (never played).
 */
fun episodeStatusLabel(progress: EpisodeProgress?, isPlaying: Boolean): String? {
    if (progress == null || !progress.isStarted) return null
    if (progress.isFinished) return Strings["episode_state_finished"]
    val hasDuration = progress.durationMs > 0L
    return when {
        isPlaying && hasDuration -> Strings.get("episode_state_playing", progress.displayPercent)
        isPlaying -> Strings["episode_state_playing_plain"]
        hasDuration -> Strings.get("episode_state_listened", progress.displayPercent)
        else -> Strings["episode_state_listened_plain"]
    }
}

/**
 * Small inline tag ("已听完" / "已听 42%") placed next to an episode's metadata line.
 */
@Composable
fun EpisodeStatusTag(
    progress: EpisodeProgress?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    if (progress == null || !progress.isStarted) return
    val label = episodeStatusLabel(progress, isPlaying) ?: return
    val colors = PodaraTheme.colors
    val finished = progress.isFinished
    val tint = when {
        finished -> colors.success
        isPlaying -> colors.accent
        else -> colors.accent.copy(alpha = 0.9f)
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (finished) Icons.Default.Check else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(DesignTokens.FavoriteEpisodeList.StatusIconSize)
        )
        Spacer(modifier = Modifier.width(DesignTokens.FavoriteEpisodeList.StatusTextGap))
        Text(
            text = label,
            color = tint,
            fontSize = DesignTokens.FavoriteEpisodeList.StatusTextSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

package app.podara.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.podara.data.AppDatabase
import app.podara.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "EpisodeProgress"

/** At or above this fraction of an episode, it counts as fully listened. */
const val EPISODE_FINISHED_THRESHOLD = 0.98f

/** How often a position update is written to disk while playing. */
private const val PERSIST_INTERVAL_MS = 5_000L

/**
 * Listening progress of a single episode.
 *
 * [finished] is sticky: once an episode crossed [EPISODE_FINISHED_THRESHOLD] it stays
 * finished even if the user replays it, which matches how podcast clients behave.
 */
data class EpisodeProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val finished: Boolean = false
) {
    val fraction: Float
        get() = when {
            durationMs <= 0L -> 0f
            else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * 0..100. Rounded down on purpose: an episode that is not finished must never display
     * "98%", which would read as finished.
     */
    val percent: Int
        get() = when {
            isFinished -> 100
            else -> (fraction * 100f).toInt().coerceIn(0, 99)
        }

    val isFinished: Boolean
        get() = finished || fraction >= EPISODE_FINISHED_THRESHOLD

    /** True once the user has actually listened to a part of it. */
    val isStarted: Boolean
        get() = finished || positionMs > 0L || fraction > 0f

    /** Percent worth showing in a list: 1..97 while in progress, 100 once finished. */
    val displayPercent: Int
        get() = when {
            isFinished -> 100
            fraction <= 0f -> 1
            else -> percent.coerceIn(1, 97)
        }
}

/**
 * Whether an episode should disappear from the aggregated "latest updates" list.
 *
 * An episode leaves the feed as soon as the user starts listening to it or puts it in the
 * playback queue — from then on it lives in the play queue instead of occupying the feed.
 */
fun hideFromLatestFeed(progress: EpisodeProgress?, inQueue: Boolean): Boolean {
    if (inQueue) return true
    return progress?.isStarted == true
}

/**
 * Holds listening progress for every episode the user has touched.
 *
 * Hot path ([record]) is called a few times per second by the player. Position changes are
 * kept in memory as snapshot state so lists redraw live, while the database is written at
 * most once every [PERSIST_INTERVAL_MS] per episode (and immediately when an episode finishes).
 */
class EpisodeProgressStore(private val database: AppDatabase) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Per-episode detail; read by list rows that are on screen. */
    private val details = mutableStateMapOf<String, EpisodeProgress>()

    /**
     * Episodes that have been played at least a bit. Kept as a separate state object so that
     * the feed filter only invalidates when membership changes, not on every position tick.
     */
    var startedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private val lastPersistAt = HashMap<String, Long>()

    /**
     * Connects the store to the player so every reported position is recorded.
     * Call once after both exist.
     */
    fun attach(state: MediaPlayerState) {
        state.onProgressUpdate = { episodeId, positionMs, durationMs ->
            record(episodeId, positionMs, durationMs)
        }
    }

    fun get(episodeId: String?): EpisodeProgress? = episodeId?.let { details[it] }

    fun isStarted(episodeId: String?): Boolean = episodeId != null && episodeId in startedIds

    fun isFinished(episodeId: String?): Boolean = get(episodeId)?.isFinished == true

    /**
     * Marks an episode as started before the player reports its first position, so an item
     * leaves the feed the moment the user taps play (not a second later).
     */
    fun markStarted(episodeId: String) {
        if (episodeId in startedIds) return
        startedIds = startedIds + episodeId
    }

    /**
     * Whether the episode should be filtered out of the aggregated "latest updates" feed.
     * Cheap on purpose: reads only the membership set, not the per-episode detail, so the
     * feed does not recompose on every playback position tick.
     */
    fun isHiddenFromLatest(episodeId: String, inQueue: Boolean): Boolean =
        inQueue || episodeId in startedIds

    /** Loads every stored position. Call once on startup. */
    suspend fun load() {
        val stored = database.playStates.getAllProgress()
        val loaded = mutableMapOf<String, EpisodeProgress>()
        val started = mutableSetOf<String>()
        stored.forEach { (episodeId, row) ->
            val progress = EpisodeProgress(row.positionMs, row.durationMs, row.finished)
            if (progress.isStarted) {
                loaded[episodeId] = progress
                started.add(episodeId)
            }
        }
        details.clear()
        details.putAll(loaded)
        startedIds = started
        Logger.d(TAG, "load: ${loaded.size} stored episode positions")
    }

    /**
     * Records a position reported by the player.
     * [MediaPlayerState] already throttles calls to roughly once a second.
     */
    fun record(episodeId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val previous = details[episodeId]
        val reachedEnd = positionMs.toFloat() / durationMs.toFloat() >= EPISODE_FINISHED_THRESHOLD
        val finished = previous?.finished == true || reachedEnd
        details[episodeId] = EpisodeProgress(positionMs, durationMs, finished)
        if (episodeId !in startedIds) {
            startedIds = startedIds + episodeId
        }
        val becameFinished = finished && previous?.finished != true
        val now = System.currentTimeMillis()
        val last = lastPersistAt[episodeId] ?: 0L
        if (becameFinished || now - last >= PERSIST_INTERVAL_MS) {
            lastPersistAt[episodeId] = now
            persist(episodeId, positionMs, durationMs, finished)
        }
    }

    /** Forces a write for one episode (used when playback stops). */
    fun flush(episodeId: String?, positionMs: Long, durationMs: Long) {
        if (episodeId == null || durationMs <= 0L) return
        val previous = details[episodeId]
        val finished = previous?.finished == true ||
            positionMs.toFloat() / durationMs.toFloat() >= EPISODE_FINISHED_THRESHOLD
        details[episodeId] = EpisodeProgress(positionMs, durationMs, finished)
        if (episodeId !in startedIds) startedIds = startedIds + episodeId
        lastPersistAt[episodeId] = System.currentTimeMillis()
        persist(episodeId, positionMs, durationMs, finished)
    }

    private fun persist(episodeId: String, positionMs: Long, durationMs: Long, finished: Boolean) {
        scope.launch {
            try {
                database.playStates.saveProgress(episodeId, positionMs, durationMs, finished)
            } catch (e: Exception) {
                Logger.e(TAG, "persist failed for $episodeId: ${e.message}")
            }
        }
    }

    fun release() {
        scope.cancel()
    }
}

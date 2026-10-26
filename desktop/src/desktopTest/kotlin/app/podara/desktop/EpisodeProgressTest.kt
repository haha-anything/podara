package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.player.EPISODE_FINISHED_THRESHOLD
import app.podara.player.EpisodeProgress
import app.podara.player.EpisodeProgressStore
import app.podara.player.hideFromLatestFeed
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeProgressTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podara_progress_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    // ── The 98% rule ──

    @Test
    fun progressBelowThresholdIsNotFinished() {
        val progress = EpisodeProgress(positionMs = 979_000, durationMs = 1_000_000)
        assertEquals(0.979f, progress.fraction, 0.0001f)
        assertFalse(progress.isFinished)
        assertTrue(progress.isStarted)
        assertEquals(97, progress.percent)
    }

    @Test
    fun progressAtThresholdIsFinished() {
        val progress = EpisodeProgress(positionMs = 980_000, durationMs = 1_000_000)
        assertEquals(EPISODE_FINISHED_THRESHOLD, progress.fraction, 0.0001f)
        assertTrue(progress.isFinished)
    }

    @Test
    fun progressPastTheEndClampsToFinished() {
        val progress = EpisodeProgress(positionMs = 1_500_000, durationMs = 1_000_000)
        assertEquals(1f, progress.fraction, 0.0001f)
        assertTrue(progress.isFinished)
        assertEquals(100, progress.percent)
    }

    @Test
    fun finishedFlagIsStickyEvenWhenReplayedFromTheStart() {
        val replay = EpisodeProgress(positionMs = 2_000, durationMs = 1_000_000, finished = true)
        assertEquals(0.002f, replay.fraction, 0.0001f)
        assertTrue(replay.isFinished, "an episode marked finished stays finished when replayed")
    }

    @Test
    fun unknownDurationIsNotFinishedAndNotStarted() {
        val progress = EpisodeProgress(positionMs = 0, durationMs = 0)
        assertEquals(0f, progress.fraction)
        assertFalse(progress.isFinished)
        assertFalse(progress.isStarted)
    }

    @Test
    fun displayPercentNeverShowsZeroForAStartedEpisode() {
        val justStarted = EpisodeProgress(positionMs = 500, durationMs = 1_000_000)
        assertEquals(0, justStarted.percent)
        assertEquals(1, justStarted.displayPercent)
    }

    // ── Feed filtering ──

    @Test
    fun unplayedEpisodeStaysInTheFeed() {
        assertFalse(hideFromLatestFeed(null, inQueue = false))
        assertFalse(hideFromLatestFeed(EpisodeProgress(0, 0), inQueue = false))
    }

    @Test
    fun queuedEpisodeLeavesTheFeed() {
        assertTrue(hideFromLatestFeed(null, inQueue = true))
    }

    @Test
    fun startedOrFinishedEpisodeLeavesTheFeed() {
        assertTrue(hideFromLatestFeed(EpisodeProgress(positionMs = 1_000, durationMs = 100_000), inQueue = false))
        assertTrue(hideFromLatestFeed(EpisodeProgress(0, 100_000, finished = true), inQueue = false))
    }

    // ── Persistence ──

    @Test
    fun savedProgressIsReadBack() {
        runBlocking {
            database.playStates.saveProgress("ep-1", positionMs = 120_000, durationMs = 600_000, finished = false)
            database.playStates.saveProgress("ep-2", positionMs = 590_000, durationMs = 600_000, finished = true)

            val stored = database.playStates.getAllProgress()
            assertEquals(120_000, stored["ep-1"]?.positionMs)
            assertEquals(600_000, stored["ep-1"]?.durationMs)
            assertEquals(false, stored["ep-1"]?.finished)
            assertEquals(true, stored["ep-2"]?.finished)
        }
    }

    @Test
    fun rewritingProgressKeepsTheFinishedFlag() {
        runBlocking {
            database.playStates.saveProgress("ep-1", positionMs = 600_000, durationMs = 600_000, finished = true)
            // A replay restarts from the beginning: position goes back, finished must not.
            database.playStates.saveProgress("ep-1", positionMs = 1_000, durationMs = 600_000, finished = false)

            val stored = database.playStates.getAllProgress()["ep-1"]
            assertEquals(1_000, stored?.positionMs)
            assertTrue(stored?.finished == true, "finished is sticky across updates")
        }
    }

    @Test
    fun storeLoadsPersistedPositions() {
        runBlocking {
            database.playStates.saveProgress("ep-1", positionMs = 60_000, durationMs = 600_000, finished = false)
            database.playStates.saveProgress("ep-2", positionMs = 0, durationMs = 600_000, finished = false)

            val store = EpisodeProgressStore(database)
            try {
                store.load()
                assertEquals(0.1f, store.get("ep-1")?.fraction ?: 0f, 0.001f)
                // A zero position is not "started", so it must not hide anything from the feed.
                assertFalse(store.isStarted("ep-2"))
                assertTrue(store.isStarted("ep-1"))
                assertTrue(store.isHiddenFromLatest("ep-1", inQueue = false))
                assertNull(store.get("ep-3"))
            } finally {
                store.release()
            }
        }
    }

    @Test
    fun recordingAFinishedPositionMarksTheEpisode() {
        val store = EpisodeProgressStore(database)
        try {
            store.record("ep-1", positionMs = 990_000, durationMs = 1_000_000)
            val progress = store.get("ep-1")
            assertEquals(true, progress?.isFinished)
            assertTrue(store.isStarted("ep-1"))
            assertTrue(store.isFinished("ep-1"))
        } finally {
            store.release()
        }
    }

    @Test
    fun markStartedHidesAnEpisodeBeforeTheFirstPositionTick() {
        val store = EpisodeProgressStore(database)
        try {
            store.markStarted("ep-9")
            assertTrue(store.isStarted("ep-9"))
            assertTrue(store.isHiddenFromLatest("ep-9", inQueue = false))
        } finally {
            store.release()
        }
    }
}

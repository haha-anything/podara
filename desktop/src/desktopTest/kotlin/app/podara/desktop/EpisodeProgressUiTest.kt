package app.podara.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.podara.component.EPISODE_PROGRESS_BAR_TAG
import app.podara.component.EpisodeListItem
import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.player.EpisodeProgress
import app.podara.player.EpisodeProgressStore
import app.podara.player.MediaPlayerState
import app.podara.player.QueueDrawer
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Renders the episode row and the play queue to check that listening progress is
 * actually visible to the user (bar + status label), not just modelled in memory.
 */
class EpisodeProgressUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File

    @Before
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podara_progress_ui_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @After
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    private fun episode(id: String = "ep-1") = PodcastEpisode(
        id = id,
        guid = "guid-$id",
        origin = "https://example.com/feed.xml",
        link = "https://example.com/$id",
        title = "测试节目 $id",
        description = "描述",
        imageUrl = null,
        author = "作者",
        pubDate = 1_700_000_000_000,
        duration = 3600,
        audioUrl = "https://example.com/$id.mp3",
        podcastTitle = "测试频道"
    )

    private fun row(progress: EpisodeProgress?, isPlaying: Boolean = false) {
        composeTestRule.setContent {
            PodaraTheme {
                EpisodeListItem(
                    episode = episode(),
                    podcast = null,
                    isPlaying = isPlaying,
                    progress = progress,
                    secondaryText = "测试频道 · 2026-10-20",
                    tertiaryText = "描述",
                    onPlay = {}
                ) {}
            }
        }
    }

    @Test
    fun inProgressEpisodeShowsPercentAndBar() {
        row(EpisodeProgress(positionMs = 1_620_000, durationMs = 3_600_000))
        composeTestRule.onNodeWithText(Strings.get("episode_state_listened", 45)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(EPISODE_PROGRESS_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun finishedEpisodeIsMarkedAndHasNoPartialBar() {
        row(EpisodeProgress(positionMs = 3_600_000, durationMs = 3_600_000, finished = true))
        composeTestRule.onNodeWithText(Strings["episode_state_finished"]).assertIsDisplayed()
        composeTestRule.onNodeWithTag(EPISODE_PROGRESS_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun episodeAt98PercentCountsAsFinished() {
        // 98% is the threshold: 3528 of 3600 seconds.
        row(EpisodeProgress(positionMs = 3_528_000, durationMs = 3_600_000))
        composeTestRule.onNodeWithText(Strings["episode_state_finished"]).assertIsDisplayed()
    }

    @Test
    fun justBelow98PercentIsStillInProgress() {
        row(EpisodeProgress(positionMs = 3_500_000, durationMs = 3_600_000))
        composeTestRule.onNodeWithText(Strings.get("episode_state_listened", 97)).assertIsDisplayed()
    }

    @Test
    fun unplayedEpisodeHasNoStatusAndNoBar() {
        row(null)
        composeTestRule.onNodeWithTag(EPISODE_PROGRESS_BAR_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText(Strings["episode_state_finished"]).assertDoesNotExist()
    }

    @Test
    fun queueRowShowsProgressOfItsEpisode() {
        val state = MediaPlayerState()
        state.addToQueue(
            url = "https://example.com/ep-1.mp3",
            title = "测试节目 ep-1",
            podcastArtworkUrl = null,
            episodeId = "ep-1"
        )
        val store = EpisodeProgressStore(database)
        store.record("ep-1", positionMs = 1_800_000, durationMs = 3_600_000)
        assertEquals(false, store.isFinished("ep-1"))

        composeTestRule.setContent {
            PodaraTheme {
                QueueDrawer(
                    state = state,
                    onDismiss = {},
                    database = database,
                    progressStore = store
                )
            }
        }
        composeTestRule.onNodeWithText(Strings.get("episode_state_listened", 50)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(EPISODE_PROGRESS_BAR_TAG).assertIsDisplayed()
        store.release()
        state.release()
    }
}

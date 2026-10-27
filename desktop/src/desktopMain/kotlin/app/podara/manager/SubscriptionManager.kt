package app.podara.manager

import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.data.AppDatabase
import app.podara.util.RssConverter

class SubscriptionManager(
    private val db: AppDatabase,
    private val fetchPodcastClient: FetchPodcastClient = FetchPodcastClient()
) {
    suspend fun subscribe(origin: String) {
        db.subscriptions.insert(origin, false, false)
    }

    suspend fun unsubscribe(origin: String) {
        db.episodes.deleteByOrigin(origin)
        db.podcasts.delete(origin)
        db.subscriptions.delete(origin)
        db.itunesLookup.deleteByRssUrl(origin)
    }

    suspend fun isSubscribed(origin: String): Boolean {
        return db.subscriptions.getByOriginSync(origin) != null
    }

    suspend fun updatePodcast(origin: String, seedColor: Int?): UpdatePodcastResult {
        val subscription = db.subscriptions.getByOriginSync(origin) ?: return UpdatePodcastResult.NotSubscribed

        // The very first successful fetch establishes the baseline: everything in the
        // feed is old news, so nothing gets flagged as unread.
        val isBaselineFetch = subscription.lastUpdate == 0L

        val response = fetchPodcastClient.fetch(origin, subscription.cacheLastModified, subscription.cacheETag, subscription.cacheContentLength)
        return when (response) {
            is FetchPodcastClientResult.Unchanged -> UpdatePodcastResult.Unchanged(response.reason)
            is FetchPodcastClientResult.Failure -> UpdatePodcastResult.Error(response.e)
            is FetchPodcastClientResult.Success -> {
                val podcast = RssConverter.toPodcast(response.rssChannel, origin, response.fileSize, seedColor)
                val episodes = response.rssChannel.items.map { RssConverter.toPodcastEpisode(it, podcast) }

                val newEpisodes = db.transaction {
                    podcasts.insert(podcast)

                    val existingIds = this.episodes.getEpisodeIds(origin).toSet()
                    val addedEpisodes = episodes.filter { it.id !in existingIds }
                    episodes.forEach { this.episodes.insert(it) }
                    addedEpisodes.forEach { playStates.initState(it.id) }

                    if (!isBaselineFetch) {
                        // New since the last refresh -> unread, drives the dot indicator.
                        addedEpisodes.forEach { this.episodes.markAsNew(it.id) }
                    }
                    val unread = this.episodes.getUnreadCount(origin)
                    subscriptions.setNewEpisodes(origin, unread)

                    subscriptions.updateLastUpdate(origin, System.currentTimeMillis())
                    subscriptions.updateCache(origin, response.eTag, response.lastModified, response.contentLength)
                    addedEpisodes
                }

                UpdatePodcastResult.Updated(podcast, newEpisodes.size)
            }
        }
    }

    /**
     * Clears the unread flag of an episode and refreshes the subscription's cached counter.
     * Called whenever an episode actually starts playing.
     */
    suspend fun markEpisodePlayed(origin: String, episodeId: String) {
        db.episodes.markAsNotNew(episodeId)
        db.subscriptions.setNewEpisodes(origin, db.episodes.getUnreadCount(origin))
    }

    /** Marks every episode of a subscription as read (used when the user opens the channel). */
    suspend fun markAllPlayed(origin: String) {
        db.episodes.markAllAsNotNew(origin)
        db.subscriptions.setNewEpisodes(origin, 0)
    }

}

sealed class UpdatePodcastResult {
    data class Updated(val podcast: app.podara.data.model.Podcast, val newEpisodesCount: Int) : UpdatePodcastResult()
    data class Unchanged(val reason: String) : UpdatePodcastResult()
    data class Error(val exception: Exception) : UpdatePodcastResult()
    data object NotSubscribed : UpdatePodcastResult()
}

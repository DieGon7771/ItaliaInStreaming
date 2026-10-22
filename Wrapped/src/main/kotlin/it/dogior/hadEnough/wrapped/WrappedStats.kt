package it.dogior.hadEnough.wrapped

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.RESULT_WATCH_STATE_DATA
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR

data class WrappedStats(
    val profileName: String,
    val totalWatchedMs: Long,
    val moviesWatched: Int,
    val seriesWatched: Int,
    val rank: WrappedRank,
)

object WrappedStatsCalculator {

    fun compute(): WrappedStats {
        val profileName = currentProfileName()
        val totalMs = computeTotalWatchedMs()
        val (movies, series) = computeLibraryCounts()
        val rank = WrappedRank.forTotalMs(totalMs)
        return WrappedStats(
            profileName = profileName,
            totalWatchedMs = totalMs,
            moviesWatched = movies,
            seriesWatched = series,
            rank = rank,
        )
    }

    private fun currentProfileName(): String {
        val context = CloudStreamApp.context ?: return "Guest"
        val accounts = DataStoreHelper.getAccounts(context)
        val active = accounts.find { it.keyIndex == DataStoreHelper.selectedKeyIndex }
        return active?.name ?: DataStoreHelper.getDefaultAccount(context).name
    }

        private fun computeTotalWatchedMs(): Long {
        val folder = "${DataStoreHelper.currentAccount}/$VIDEO_POS_DUR"
        val keys = CloudStreamApp.getKeys(folder) ?: return 0L
        val prefix = "$folder/"
        return keys.sumOf { fullKey ->
            val id = fullKey.removePrefix(prefix)
            val posDur = CloudStreamApp.getKey<DataStoreHelper.PosDur>(folder, id)
            posDur?.let { minOf(it.position, it.duration).coerceAtLeast(0L) } ?: 0L
        }
    }

        private fun computeLibraryCounts(): Pair<Int, Int> {
        val folder = "${DataStoreHelper.currentAccount}/$RESULT_WATCH_STATE_DATA"
        val keys = CloudStreamApp.getKeys(folder) ?: return 0 to 0
        val prefix = "$folder/"
        var movies = 0
        var series = 0
        keys.forEach { fullKey ->
            val id = fullKey.removePrefix(prefix).toIntOrNull() ?: return@forEach
            if (DataStoreHelper.getResultWatchState(id) != WatchType.COMPLETED) return@forEach
            val data = DataStoreHelper.getBookmarkedData(id) ?: return@forEach
            val type = data.type ?: return@forEach
            if (type.isMovieType()) movies++ else series++
        }
        return movies to series
    }
}

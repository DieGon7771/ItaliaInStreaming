package it.dogior.hadEnough.wrapped

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WrappedFragment"

class WrappedFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private var cachedStats: WrappedStats? = null
    private var sharing = false

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }
            .getOrNull()
    }

        private fun coloredFallback(fill: Int, radiusDp: Float = 16f, strokeColor: Int? = null): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            strokeColor?.let { setStroke((2 * resources.displayMetrics.density).toInt(), it) }
        }
    }

    private fun cardBackground(): Drawable =
        getDrawable("wrapped_card_bg") ?: coloredFallback(0x14FFFFFF, radiusDp = 16f, strokeColor = 0x1FFFFFFF)

        private fun rankColor(rank: WrappedRank): Int = when (rank) {
        WrappedRank.BRONZE -> 0xFFD07040.toInt()
        WrappedRank.SILVER -> 0xFFB0B0B8.toInt()
        WrappedRank.GOLD -> 0xFFE0A830.toInt()
        WrappedRank.DIAMOND -> 0xFF4FC3F7.toInt()
        WrappedRank.MASTER -> 0xFFB388FF.toInt()
    }

        private fun rankIcon(rank: WrappedRank): Drawable =
        getDrawable(rank.iconName) ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(rankColor(rank))
        }

    private fun buildProgressDrawable(trackColor: Int, progressColor: Int): Drawable {
        val radius = 7f * resources.displayMetrics.density
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(trackColor)
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(progressColor)
        }
        val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
        val layer = LayerDrawable(arrayOf(track, clip))
        layer.setId(0, android.R.id.background)
        layer.setId(1, android.R.id.progress)
        return layer
    }

        private fun buildTimeUnitView(ctx: Context, unit: WrappedTimeUnit): View {
        val density = resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val m = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = m; bottomMargin = m }
        }
        val value = TextView(ctx).apply {
            text = unit.value.toString()
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val label = TextView(ctx).apply {
            text = unit.label
            textSize = 12f
            alpha = 0.6f
        }
        box.addView(value)
        box.addView(label)
        return box
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("wrapped_screen", inflater, container)

        val loading = root.findView<ProgressBar>("wrapped_loading")
        val content = root.findView<LinearLayout>("wrapped_content")

        val rankCard = root.findView<LinearLayout>("wrapped_rank_card")
        val rankIconView = root.findView<ImageView>("wrapped_rank_icon")
        val rankTitle = root.findView<TextView>("wrapped_rank_title")
        val rankProgress = root.findView<ProgressBar>("wrapped_rank_progress")
        val rankProgressLabel = root.findView<TextView>("wrapped_rank_progress_label")

        val timeCard = root.findView<LinearLayout>("wrapped_time_card")
        val timeContainer = root.findView<LinearLayout>("wrapped_time_container")

        val moviesCard = root.findView<LinearLayout>("wrapped_movies_card")
        val moviesValue = root.findView<TextView>("wrapped_movies_value")
        val seriesCard = root.findView<LinearLayout>("wrapped_series_card")
        val seriesValue = root.findView<TextView>("wrapped_series_value")

        val shareBtn = root.findView<TextView>("wrapped_share")
        val closeBtn = root.findView<TextView>("wrapped_close")
        val infoBtn = root.findView<TextView>("wrapped_info")

        rankCard.background = cardBackground()
        timeCard.background = cardBackground()
        moviesCard.background = cardBackground()
        seriesCard.background = cardBackground()
        shareBtn.background = getDrawable("wrapped_share_bg")
            ?: coloredFallback(0xFF3B65F5.toInt(), radiusDp = 16f)
        closeBtn.background = getDrawable("wrapped_outline_bg")
            ?: coloredFallback(0x12FFFFFF, radiusDp = 16f, strokeColor = 0x33FFFFFF)
        infoBtn.background = getDrawable("wrapped_outline_bg")
            ?: coloredFallback(0x12FFFFFF, radiusDp = 16f, strokeColor = 0x33FFFFFF)

        closeBtn.setOnClickListener { dismiss() }
        shareBtn.setOnClickListener { onShareClicked(shareBtn) }
        infoBtn.setOnClickListener { showInfoDialog() }

        loadStats(loading, content, rankIconView, rankTitle, rankProgress, rankProgressLabel, timeContainer, moviesValue, seriesValue)

        root
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "onCreateView() failed", t)
        runCatching { showToast("CloudStream Wrapped crash: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG) }
        null
    }

        private fun showInfoDialog() {
        runCatching {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("How it works")
                .setMessage(
                    "CloudStream Wrapped uses data already stored on your device. " +
                        "It makes no network requests, uses no external servers, and requires no additional permissions.\n\n" +
                        "- Watch time: Includes all recorded playback time, even for partially watched content.\n" +
                        "- Movies & TV shows: Only items marked as \"Completed\" in your Library are counted.\n" +
                        "- Your rank: Based on your total watch time.\n\n" +
                        "The plugin runs only when you open this screen and never runs in the background."
                )
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    private fun loadStats(
        loading: ProgressBar,
        content: LinearLayout,
        rankIconView: ImageView,
        rankTitle: TextView,
        rankProgress: ProgressBar,
        rankProgressLabel: TextView,
        timeContainer: LinearLayout,
        moviesValue: TextView,
        seriesValue: TextView,
    ) {
        val ctx = requireContext().applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            val result = runCatching { WrappedStatsCalculator.compute() }
            val stats = result.getOrNull()

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                loading.visibility = View.GONE
                if (stats == null) {
                    val reason = result.exceptionOrNull()
                    val detail = reason?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown reason"
                    showToast("Couldn't load your Wrapped stats ($detail)", Toast.LENGTH_LONG)
                    return@withContext
                }

                cachedStats = stats
                content.visibility = View.VISIBLE

                rankIconView.setImageDrawable(rankIcon(stats.rank))
                rankTitle.text = stats.rank.title
                val progress = WrappedRankProgress.compute(stats.totalWatchedMs)
                rankProgress.max = 1000
                rankProgress.progressDrawable = buildProgressDrawable(0x1AFFFFFF, rankColor(progress.current))
                rankProgress.progress = (progress.fraction * 1000).toInt()
                rankProgressLabel.text = if (progress.next == null) "MAX" else ""

                timeContainer.removeAllViews()
                WrappedFormat.breakDownDuration(stats.totalWatchedMs).forEach {
                    timeContainer.addView(buildTimeUnitView(ctx, it))
                }

                moviesValue.text = stats.moviesWatched.toString()
                seriesValue.text = stats.seriesWatched.toString()
            }
        }
    }

    private fun onShareClicked(shareBtn: TextView) {
        if (sharing) return
        val stats = cachedStats
        if (stats == null) {
            showToast("Stats not ready yet, try again in a moment")
            return
        }
        sharing = true
        val originalText = shareBtn.text
        shareBtn.text = "…"
        shareBtn.isEnabled = false

        val ctx = requireContext().applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            val icon = runCatching { rankIcon(stats.rank) }.getOrNull()
            val result = runCatching { WrappedCardRenderer.render(ctx, stats, icon) }
            val bitmap = result.getOrNull()

            withContext(Dispatchers.Main) {
                sharing = false
                if (!isAdded) return@withContext
                shareBtn.text = originalText
                shareBtn.isEnabled = true
                if (bitmap != null) {
                    WrappedShare.share(requireContext(), bitmap)
                } else {
                    val reason = result.exceptionOrNull()
                    val detail = reason?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown reason"
                    showToast("Couldn't generate your Wrapped card ($detail)", Toast.LENGTH_LONG)
                }
            }
        }
    }
}

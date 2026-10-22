package it.dogior.hadEnough.wrapped

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object WrappedCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1920
    private const val MARGIN = 80f

    private val BG_TOP = Color.parseColor("#05070C")
    private val BG_BOTTOM = Color.parseColor("#0B0E14")
    private val ACCENT = Color.parseColor("#3B65F5")
    private val CARD_FILL = Color.parseColor("#15161F")
    private val CARD_STROKE = Color.parseColor("#262838")
    private val TEXT_PRIMARY = Color.WHITE
    private val TEXT_SECONDARY = Color.parseColor("#9A98AE")

    private fun rankColor(rank: WrappedRank): Int = when (rank) {
        WrappedRank.BRONZE -> Color.parseColor("#D07040")
        WrappedRank.SILVER -> Color.parseColor("#B0B0B8")
        WrappedRank.GOLD -> Color.parseColor("#E0A830")
        WrappedRank.DIAMOND -> Color.parseColor("#4FC3F7")
        WrappedRank.MASTER -> Color.parseColor("#B388FF")
    }

    fun render(context: Context, stats: WrappedStats, rankIcon: Drawable? = null): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val accent = rankColor(stats.rank)

        drawBackground(canvas, accent)
        drawHeader(context, canvas, stats.profileName)
        drawRankBadge(canvas, stats.rank, rankIcon, accent)
        drawTotalTime(canvas, stats.totalWatchedMs)
        drawCounters(canvas, stats.moviesWatched, stats.seriesWatched)
        drawFooter(canvas)

        return bmp
    }

    fun saveToCache(context: Context, bitmap: Bitmap): File {
        val file = File(context.cacheDir, "cloudstream_wrapped.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }

    private fun drawBackground(canvas: Canvas, accent: Int) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), base)

        val steps = 18
        val peak = 95
        val colors = IntArray(steps)
        val positions = FloatArray(steps)
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val eased = ((1.0 + cos(PI * t)) / 2.0).toFloat()
            colors[i] = colorWithAlpha(accent, (peak * eased).toInt())
            positions[i] = t
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            shader = RadialGradient(WIDTH / 2f, 510f, 720f, colors, positions, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 1300f, glow)
    }

    private fun drawHeader(context: Context, canvas: Canvas, profileName: String) {
        val iconSize = 64f
        val top = 90f

        drawAppIcon(context, canvas, MARGIN, top, iconSize)

        val labelPaint = textPaint(TEXT_SECONDARY, 26f, bold = true).apply {
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.14f
        }
        canvas.drawText("CLOUDSTREAM WRAPPED", MARGIN + iconSize + 24f, top + iconSize / 2f + 9f, labelPaint)

        val handlePaint = textPaint(TEXT_PRIMARY, 32f, bold = true).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("@${profileName.trim()}", WIDTH - MARGIN, top + iconSize / 2f + 11f, handlePaint)

        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorWithAlpha(TEXT_SECONDARY, 50)
            strokeWidth = 2f
        }
        canvas.drawLine(MARGIN, top + iconSize + 34f, WIDTH - MARGIN, top + iconSize + 34f, divider)
    }

    private fun drawAppIcon(context: Context, canvas: Canvas, left: Float, top: Float, size: Float) {
        val icon: Drawable? = runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()

        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_FILL }
        canvas.drawCircle(left + size / 2f, top + size / 2f, size / 2f + 8f, circle)

        if (icon != null) {
            icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            icon.draw(canvas)
        } else {
            val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
            canvas.drawCircle(left + size / 2f, top + size / 2f, size / 2f, fallback)
        }
    }

    private fun drawRankBadge(canvas: Canvas, rank: WrappedRank, rankIcon: Drawable?, accent: Int) {
        val badgeSize = 420
        val badgeLeft = (WIDTH - badgeSize) / 2
        val badgeTop = 300

        if (rankIcon != null) {
            rankIcon.setBounds(badgeLeft, badgeTop, badgeLeft + badgeSize, badgeTop + badgeSize)
            rankIcon.draw(canvas)
        } else {
            drawHexagonFallback(canvas, badgeLeft + badgeSize / 2f, badgeTop + badgeSize / 2f, badgeSize / 2f, accent)
        }

        val pillTop = (badgeTop + badgeSize + 40).toFloat()
        val pillHeight = 88f
        val label = rank.title.uppercase()
        val text = textPaint(TEXT_PRIMARY, 40f, bold = true).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
        }
        val half = text.measureText(label) / 2f + 56f
        val pill = RectF(WIDTH / 2f - half, pillTop, WIDTH / 2f + half, pillTop + pillHeight)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorWithAlpha(accent, 48) }
        canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, stroke)
        canvas.drawText(label, WIDTH / 2f, pillTop + pillHeight / 2f + 15f, text)
    }

    private fun drawHexagonFallback(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val path = Path()
        for (i in 0 until 6) {
            val angle = Math.toRadians((60 * i - 90).toDouble())
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cx, cy - radius, cx, cy + radius, color, colorWithAlpha(color, 160), Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, fill)
    }

    private fun drawTotalTime(canvas: Canvas, totalMs: Long) {
        val units = WrappedFormat.breakDownDuration(totalMs)
        val top = 920f
        val height = 300f
        val rect = RectF(MARGIN, top, WIDTH - MARGIN, top + height)
        drawCardFrame(canvas, rect, 40f)

        val labelPaint = textPaint(TEXT_SECONDARY, 26f, bold = true).apply {
            letterSpacing = 0.14f
        }
        val label = "TOTAL TIME"
        val iconSize = 40f
        val gap = 20f
        val labelWidth = labelPaint.measureText(label)
        val startX = WIDTH / 2f - (iconSize + gap + labelWidth) / 2f
        val headerY = top + 66f
        drawClockIcon(canvas, startX + iconSize / 2f, headerY - 9f, iconSize / 2f)
        canvas.drawText(label, startX + iconSize + gap, headerY, labelPaint)

        val innerLeft = rect.left + 24f
        val innerWidth = rect.width() - 48f
        val colWidth = innerWidth / units.size
        val numberY = top + 205f
        val captionY = top + 255f

        val longest = units.maxOf { it.value.toString().length }
        var numberSize = 96f
        val numberPaint = textPaint(TEXT_PRIMARY, numberSize, bold = true).apply { textAlign = Paint.Align.CENTER }
        val widest = "8".repeat(longest)
        while (numberPaint.measureText(widest) > colWidth - 36f && numberSize > 44f) {
            numberSize -= 4f
            numberPaint.textSize = numberSize
        }
        val captionPaint = textPaint(TEXT_SECONDARY, 30f, bold = false).apply { textAlign = Paint.Align.CENTER }
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorWithAlpha(TEXT_SECONDARY, 60)
            strokeWidth = 2f
        }

        units.forEachIndexed { i, unit ->
            val cx = innerLeft + colWidth * i + colWidth / 2f
            canvas.drawText(unit.value.toString(), cx, numberY, numberPaint)
            canvas.drawText(unit.label, cx, captionY, captionPaint)
            if (i > 0) {
                val x = innerLeft + colWidth * i
                canvas.drawLine(x, top + 150f, x, top + 262f, divider)
            }
        }
    }

    private fun drawCounters(canvas: Canvas, movies: Int, series: Int) {
        val top = 1280f
        val height = 310f
        val gap = 32f
        val width = (WIDTH - 2 * MARGIN - gap) / 2f

        drawStatCard(canvas, MARGIN, top, width, height, movies.toString(), "Movies watched", ::drawMovieIcon)
        drawStatCard(canvas, MARGIN + width + gap, top, width, height, series.toString(), "TV shows watched", ::drawTvIcon)
    }

    private fun drawStatCard(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        value: String, label: String, icon: (Canvas, Float, Float, Float) -> Unit,
    ) {
        drawCardFrame(canvas, RectF(left, top, left + width, top + height), 40f)

        val cx = left + width / 2f
        icon(canvas, cx, top + 78f, 30f)

        val valuePaint = textPaint(TEXT_PRIMARY, 96f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(value, cx, top + 200f, valuePaint)

        val labelPaint = textPaint(TEXT_SECONDARY, 30f, bold = false).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(label, cx, top + 258f, labelPaint)
    }

    private fun drawCardFrame(canvas: Canvas, rect: RectF, radius: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_FILL }
        canvas.drawRoundRect(rect, radius, radius, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CARD_STROKE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    private fun iconPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_PRIMARY
        style = Paint.Style.STROKE
        strokeWidth = size * 0.14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun drawClockIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = iconPaint(r * 2)
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawLine(cx, cy, cx, cy - r * 0.55f, p)
        canvas.drawLine(cx, cy, cx + r * 0.42f, cy + r * 0.24f, p)
    }

    private fun drawMovieIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = iconPaint(r * 2)
        val w = r * 2f
        val left = cx - w / 2f
        val right = cx + w / 2f
        val barTop = cy - r * 0.95f
        val barBottom = cy - r * 0.35f
        val bodyBottom = cy + r * 0.95f

        canvas.drawRoundRect(RectF(left, barBottom, right, bodyBottom), r * 0.2f, r * 0.2f, p)
        canvas.drawRoundRect(RectF(left, barTop, right, barBottom), r * 0.14f, r * 0.14f, p)
        val stripeW = w / 4f
        for (i in 0..1) {
            val x = left + stripeW * (i * 2 + 1)
            canvas.drawLine(x - stripeW * 0.4f, barBottom, x + stripeW * 0.4f, barTop, p)
        }
    }

    private fun drawTvIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = iconPaint(r * 2)
        val w = r * 2.1f
        val left = cx - w / 2f
        val right = cx + w / 2f
        val top = cy - r * 0.5f
        val bottom = cy + r * 0.9f

        canvas.drawRoundRect(RectF(left, top, right, bottom), r * 0.25f, r * 0.25f, p)
        canvas.drawLine(cx - r * 0.55f, cy - r * 1.0f, cx, top, p)
        canvas.drawLine(cx + r * 0.55f, cy - r * 1.0f, cx, top, p)
    }

    private fun drawFooter(canvas: Canvas) {
        val paint = textPaint(TEXT_SECONDARY, 30f, bold = false).apply {
            textAlign = Paint.Align.CENTER
            alpha = 170
            letterSpacing = 0.04f
        }
        canvas.drawText("Developed by DieGon", WIDTH / 2f, HEIGHT - 110f, paint)
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

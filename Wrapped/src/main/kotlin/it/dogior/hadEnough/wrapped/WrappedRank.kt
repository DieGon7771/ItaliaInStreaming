package it.dogior.hadEnough.wrapped

enum class WrappedRank(val minHours: Int, val title: String, val iconName: String) {
    BRONZE(0, "Bronze", "rank_bronze"),
    SILVER(20, "Silver", "rank_silver"),
    GOLD(60, "Gold", "rank_gold"),
    DIAMOND(150, "Diamond", "rank_diamond"),
    MASTER(400, "CloudStream", "rank_master");

    companion object {
        fun forTotalMs(totalMs: Long): WrappedRank {
            val hours = totalMs / 3_600_000.0
            return entries.sortedByDescending { it.minHours }.first { hours >= it.minHours }
        }

        fun next(of: WrappedRank): WrappedRank? {
            val ordered = entries.sortedBy { it.minHours }
            val i = ordered.indexOf(of)
            return ordered.getOrNull(i + 1)
        }
    }
}

data class WrappedRankProgress(
    val current: WrappedRank,
    val next: WrappedRank?,
    val fraction: Float,
) {
    companion object {
        fun compute(totalMs: Long): WrappedRankProgress {
            val current = WrappedRank.forTotalMs(totalMs)
            val next = WrappedRank.next(current)
            if (next == null) return WrappedRankProgress(current, null, 1f)
            val hours = totalMs / 3_600_000.0
            val span = (next.minHours - current.minHours).coerceAtLeast(1)
            val progressed = (hours - current.minHours).coerceIn(0.0, span.toDouble())
            return WrappedRankProgress(current, next, (progressed / span).toFloat())
        }
    }
}

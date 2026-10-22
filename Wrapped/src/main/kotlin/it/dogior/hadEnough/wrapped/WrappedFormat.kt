package it.dogior.hadEnough.wrapped

data class WrappedTimeUnit(val value: Long, val label: String)

object WrappedFormat {
    fun breakDownDuration(totalMs: Long): List<WrappedTimeUnit> {
        val totalMinutes = totalMs / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60

        val units = mutableListOf<WrappedTimeUnit>()
        if (days > 0) units.add(WrappedTimeUnit(days, plural(days, "Day", "Days")))
        if (hours > 0 || units.isNotEmpty()) units.add(WrappedTimeUnit(hours, plural(hours, "Hour", "Hours")))
        units.add(WrappedTimeUnit(minutes, plural(minutes, "Minute", "Minutes")))
        return units
    }

    private fun plural(value: Long, one: String, many: String) = if (value == 1L) one else many
}

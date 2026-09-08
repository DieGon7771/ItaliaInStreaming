package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Response of GET /api/events.php — the whole catalogue in a single request.
 */
data class EventsResponse(
    @JsonProperty("generated_at") val generatedAt: String? = null,
    @JsonProperty("count") val count: Int? = null,
    @JsonProperty("events") val events: List<CalcioEvent> = emptyList()
)

data class CalcioEvent(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("home_team") val homeTeam: String? = null,
    @JsonProperty("away_team") val awayTeam: String? = null,
    @JsonProperty("home_team_badge") val homeTeamBadge: String? = null,
    @JsonProperty("away_team_badge") val awayTeamBadge: String? = null,
    @JsonProperty("sport") val sport: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("start_time") val startTime: String? = null,
    @JsonProperty("start_ts") val startTs: Long? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("streams") val streams: List<CalcioStream> = emptyList()
)

/**
 * A directly playable link plus the referer its CDN expects.
 * [url] is never the video itself: for `sportsonline` it is a channel page
 * wrapping an iframe chain, for `zicotv` a player page carrying a ZT_SOURCES array.
 */
data class CalcioStream(
    @JsonProperty("url") val url: String,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("label") val label: String? = null
)

/** One CDN entry of the `ZT_SOURCES` array embedded in a zicotv player page. */
data class ZicoSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("url") val url: String
)
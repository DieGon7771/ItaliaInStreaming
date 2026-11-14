package it.dogior.hadEnough

data class NuvioScraper(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val filename: String,
    val types: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
    val author: String = "",
    val logo: String = "",
    val formats: List<String> = emptyList(),
    val hasSettings: Boolean = false,
    val limited: Boolean = false,
    val notes: String = "",
) {
    
    fun supports(mediaType: String): Boolean {
        if (types.isEmpty()) return true
        val wanted = if (mediaType == "movie") listOf("movie") else listOf("tv", "series")
        return types.any { t -> wanted.any { it.equals(t, ignoreCase = true) } }
    }
}

data class NuvioRepo(
    val id: String,
    val url: String,
    val name: String,
    val description: String = "",
    val scrapers: List<NuvioScraper> = emptyList(),
    val version: String = "",
)

data class NuvioLoadData(
    val tmdbId: String? = null,
    /** "movie" or "tv". */
    val mediaType: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class NuvioSyncReport(
    val repo: NuvioRepo,
    val downloaded: Int,
    val failed: List<String>,
)

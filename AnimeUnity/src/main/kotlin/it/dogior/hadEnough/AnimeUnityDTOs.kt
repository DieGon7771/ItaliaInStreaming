package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuImages(
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("banner") val banner: String? = null,
    @JsonProperty("cover") val cover: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuVariant(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episodes_count") val episodesCount: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuAnime(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("title_it") val titleIt: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    @JsonProperty("episode_runtime") val episodeRuntime: Int? = null,
    @JsonProperty("default_status") val defaultStatus: String? = null,
    @JsonProperty("score") val score: String? = null,
    @JsonProperty("images") val images: AuImages? = null,
    @JsonProperty("genres") val genres: List<AuGenre>? = null,
    @JsonProperty("variants") val variants: List<AuVariant>? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
    @JsonProperty("anilist_id") val anilistId: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuArchivePage(
    @JsonProperty("data") val data: List<AuAnime>? = null,
    @JsonProperty("next_page_url") val nextPageUrl: String? = null,
    @JsonProperty("total") val total: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuLatestEpisodePage(
    @JsonProperty("data") val data: List<AuLatestEpisodeItem>? = null,
    @JsonProperty("next_page_url") val nextPageUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuLatestEpisodeItem(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("episode") val episode: AuLatestEpisodeInfo? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuLatestEpisodeInfo(
    @JsonProperty("number") val number: String? = null,
    @JsonProperty("anime") val anime: AuAnime? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuEpisodesPage(
    @JsonProperty("episodes") val episodes: List<AuEpisodeDto>? = null,
    @JsonProperty("total") val total: Int? = null,
    @JsonProperty("tab_size") val tabSize: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuEpisodeDto(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("number") val number: String? = null,
    @JsonProperty("videos") val videos: List<AuVideo>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuVideo(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("is_hidden") val isHidden: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuPageData(
    @JsonProperty("props") val props: AuPageProps? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuPageProps(
    @JsonProperty("anime") val anime: AuAnime? = null,
    @JsonProperty("embedUrl") val embedUrl: String? = null,
    @JsonProperty("relatedAnimes") val relatedAnimes: List<AuAnime>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistResponse(
    @JsonProperty("data") val data: AnilistData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistData(
    @JsonProperty("Media") val media: AnilistMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("coverImage") val coverImage: AnilistCoverImage? = null,
    @JsonProperty("duration") val duration: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistCoverImage(
    @JsonProperty("medium") val medium: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("extraLarge") val extraLarge: String? = null
)

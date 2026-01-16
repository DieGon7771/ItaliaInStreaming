package it.dogior.hadEnough

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import it.dogior.hadEnough.UltimaUtils.SectionInfo
import it.dogior.hadEnough.WatchSyncUtils.SyncContent
import it.dogior.hadEnough.WatchSyncUtils.WatchSyncCreds.SyncDevice

class Ultima(val plugin: UltimaPlugin) : MainAPI() {
    override var name = "SyncStream BETA"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false
    private val sm = UltimaStorageManager
    private val deviceSyncData = sm.deviceSyncCreds

    private val mapper = jacksonObjectMapper()
    private var sectionNamesList: List<String> = emptyList()
    
    // Variabili per tracking
    private var currentWatchingId: String? = null
    private var sessionStartTime: Long = 0

    private fun loadSections(): List<MainPageData> {
        val tempSectionNames = mutableListOf<String>()

        val result = mutableListOf<MainPageData>()
        val savedPlugins = sm.currentExtensions

        result += mainPageOf("" to "watch_sync")

        val enabledSections = savedPlugins
            .flatMap { it.sections?.asList() ?: emptyList() }
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        enabledSections.forEach { section ->
            try {
                val sectionKey = mapper.writeValueAsString(section)
                val sectionName = buildSectionName(section, tempSectionNames)
                result += mainPageOf(sectionKey to sectionName)
            } catch (e: Exception) {
                Log.e("loadSections", "Failed to load section ${section.name}: ${e.message}")
            }
        }

        sectionNamesList = tempSectionNames

        return if (result.size <= 1) mainPageOf("" to "") else result
    }

    private fun buildSectionName(section: SectionInfo, names: MutableList<String>): String {
        val name = if (sm.extNameOnHome) {
            "${section.pluginName}: ${section.name}"
        } else if (names.contains(section.name)) {
            "${section.name} ${names.count { it.startsWith(section.name) } + 1}"
        } else {
            section.name
        }
        names += name
        return name
    }

    override val mainPage = loadSections()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val creds = sm.deviceSyncCreds
        creds?.syncThisDevice()

        if (request.name.isEmpty()) {
            throw ErrorLoadingException("Select sections from the extension's settings page to show here.")
        }

        return try {
            if (request.name == "watch_sync") {
                val syncedDevices = creds?.fetchDevices()
                val filteredDevices = syncedDevices?.filter {
                    deviceSyncData?.enabledDevices?.contains(it.deviceId) == true
                } ?: emptyList()

                if (filteredDevices.isEmpty()) {
                    Log.w("getMainPage", "No enabled devices found in the synced list.")
                    return null
                }

                val homeSections = ArrayList<HomePageList>(filteredDevices.size)

                for (device in filteredDevices) {
                    val syncedItems = device.getSyncedItems()
                    if (syncedItems.isEmpty()) continue
                    
                    val modifiedContent = syncedItems.mapNotNull { syncContent ->
                        convertSyncToSearchResponse(syncContent)
                    }
                    
                    homeSections += HomePageList("📱 ${device.name}", modifiedContent)
                }

                newHomePageResponse(homeSections, false)
            } else {
                val section = AppUtils.parseJson<SectionInfo>(request.data)
                val provider = allProviders.find { it.name == section.pluginName }
                    ?: throw ErrorLoadingException("Provider '${section.pluginName}' is not available.")

                provider.getMainPage(
                    page,
                    MainPageRequest(
                        name = request.name,
                        data = section.url,
                        horizontalImages = request.horizontalImages
                    )
                )
            }
        } catch (e: Throwable) {
            Log.e("getMainPage", "Error loading main page: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    // --- FUNZIONE SEMPLIFICATA PER MOSTRARE PROGRESSO ---
    private fun convertSyncToSearchResponse(syncContent: SyncContent): SearchResponse? {
        val resumeData = syncContent.resumeData
        
        // Aggiungi progresso al nome
        val progressText = if (syncContent.progressPercent > 0) {
            " (${syncContent.progressString})"
        } else ""
        
        val displayName = "${resumeData.name}$progressText"
        
        // Aggiungi resume time all'URL se c'è progresso
        val finalUrl = if (syncContent.watchedSeconds > 60 && !syncContent.isCompleted) {
            addResumeTime(resumeData.url, syncContent.watchedSeconds)
        } else {
            resumeData.url
        }
        
        // Determina il tipo di contenuto (semplice)
        val isTvSeries = resumeData.url.contains("/tv/") || 
                         resumeData.name.contains("Stagione") || 
                         resumeData.name.contains("Season")
        
        // Usa le nuove funzioni factory invece dei costruttori deprecati
        return if (isTvSeries) {
            newTvSeriesSearchResponse(
                name = displayName,
                url = finalUrl,
                apiName = resumeData.apiName
            ) {
                // Non possiamo accedere a poster direttamente, ma va bene
                // Il progresso è già nel nome
            }
        } else {
            newMovieSearchResponse(
                name = displayName,
                url = finalUrl,
                apiName = resumeData.apiName
            ) {
                // Non possiamo accedere a poster direttamente
            }
        }
    }
    
    private fun addResumeTime(url: String, seconds: Int): String {
        return if (url.contains("?")) {
            "$url&t=${seconds}s"
        } else {
            "$url?t=${seconds}s"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val enabledSections = mainPage
            .filter { !it.name.equals("watch_sync", ignoreCase = true) }
            .mapNotNull {
                try {
                    val section = AppUtils.parseJson<SectionInfo>(it.data)
                    section.pluginName to section
                } catch (_: Exception) {
                    null
                }
            }

        val tasks = mutableListOf<suspend () -> List<SearchResponse>>()

        for ((pluginName, _) in enabledSections) {
            val provider = allProviders.find { it.name == pluginName } ?: continue

            tasks += suspend {
                try {
                    when (val result = provider.search(query)) {
                        is List<*> -> {
                            result.map { item ->
                                when (item) {
                                    is MovieSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    is AnimeSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    is TvSeriesSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    else -> item
                                }
                            }
                        }
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    Log.e("search", "Search failed for provider $pluginName: ${e.message}")
                    emptyList()
                }
            }
        }

        return runLimitedParallel(limit = 4, tasks).flatten()
    }

    override suspend fun load(url: String): LoadResponse {
        // SALVA TEMPO GUARDATO DEL CONTENUTO PRECEDENTE
        currentWatchingId?.let { contentId ->
            val sessionEnd = System.currentTimeMillis()
            val watchedMillis = sessionEnd - sessionStartTime
            val watchedSeconds = (watchedMillis / 1000).toInt()
            
            if (watchedSeconds > 60) { // Salva solo se >1 minuto
                deviceSyncData?.updateWatchedTime(contentId, watchedSeconds, false)
            }
            
            // Reset
            currentWatchingId = null
            sessionStartTime = 0
        }
        
        // REGISTRA INIZIO NUOVA VISIONE
        val contentId = extractContentIdFromUrl(url)
        if (contentId != null) {
            currentWatchingId = contentId
            sessionStartTime = System.currentTimeMillis()
        }

        val enabledPlugins = mainPage
            .filter { !it.name.equals("watch_sync", ignoreCase = true) }
            .mapNotNull {
                try {
                    AppUtils.parseJson<SectionInfo>(it.data).pluginName
                } catch (_: Exception) {
                    null
                }
            }

        val providersToTry = allProviders.filter { it.name in enabledPlugins }

        for (provider in providersToTry) {
            try {
                val response = provider.load(url)

                if (response != null &&
                    response.name.isNotBlank() &&
                    !response.posterUrl.isNullOrBlank()
                ) {
                    return response
                }
            } catch (_: Throwable) {
                Log.e("Ultima load", "Failed loading from ${provider.name}")
            }
        }

        return newMovieLoadResponse("Welcome to Ultima", "", TvType.Others, "")
    }
    
    private fun extractContentIdFromUrl(url: String): String? {
        // Estrai ID dall'URL
        return when {
            url.contains("?") -> url.substringBefore("?").hashCode().toString()
            else -> url.hashCode().toString()
        }
    }
}

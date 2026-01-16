package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.DataStoreHelper.ResumeWatchingResult
import it.dogior.hadEnough.UltimaStorageManager as sm

object WatchSyncUtils {
    // --- NUOVA CLASSE SyncContent ---
    data class SyncContent(
        val resumeData: ResumeWatchingResult,
        val totalDuration: Int? = null,      // Durata totale in secondi
        val watchedSeconds: Int = 0,        // Secondi guardati
        val lastUpdated: Long = System.currentTimeMillis(),
        val deviceName: String,
        val isCompleted: Boolean = false
    ) {
        // Calcola percentuale guardata
        val progressPercent: Int
            get() {
                return when {
                    isCompleted -> 100
                    totalDuration == null || totalDuration <= 0 -> 0
                    else -> {
                        val percent = (watchedSeconds * 100) / totalDuration
                        minOf(percent, 100)
                    }
                }
            }

        // Formatta per UI: "30/120 min"
        val progressString: String
            get() {
                return when {
                    isCompleted -> "✅ Completato"
                    totalDuration == null -> "${watchedSeconds / 60} min"
                    else -> {
                        val watchedMin = watchedSeconds / 60
                        val totalMin = totalDuration / 60
                        "⏱️ $watchedMin/$totalMin min"
                    }
                }
            }
    }

    data class WatchSyncCreds(
        @JsonProperty("token") var token: String? = null,
        @JsonProperty("projectNum") var projectNum: Int? = null,
        @JsonProperty("deviceName") var deviceName: String? = null,
        @JsonProperty("deviceId") var deviceId: String? = null, // draftIssueID
        @JsonProperty("itemId") var itemId: String? = null, // projectItemID
        @JsonProperty("projectId") var projectId: String? = null,
        @JsonProperty("isThisDeviceSync") var isThisDeviceSync: Boolean = false,
        @JsonProperty("enabledDevices") var enabledDevices: MutableList<String>? = null
    ) {
        data class APIRes(@JsonProperty("data") var data: Data) {
            data class Data(
                @JsonProperty("viewer") var viewer: Viewer?,
                @JsonProperty("addProjectV2DraftIssue") var issue: Issue?,
                @JsonProperty("deleteProjectV2Item") var delItem: DelItem?
            ) {
                data class Viewer(@JsonProperty("projectV2") var projectV2: ProjectV2) {
                    data class ProjectV2(
                        @JsonProperty("id") var id: String,
                        @JsonProperty("items") var items: Items?
                    ) {
                        data class Items(
                            @JsonProperty("nodes") var nodes: Array<Node>?,
                        ) {
                            data class Node(
                                @JsonProperty("id") var id: String,
                                @JsonProperty("content") var content: Content
                            ) {
                                data class Content(
                                    @JsonProperty("id") var id: String,
                                    @JsonProperty("title") var title: String,
                                    @JsonProperty("bodyText") var bodyText: String,
                                )
                            }
                        }
                    }
                }
                data class Issue(@JsonProperty("projectItem") var projectItem: ProjectItem) {
                    data class ProjectItem(
                        @JsonProperty("id") var id: String,
                        @JsonProperty("content") var content: Content
                    ) {
                        data class Content(@JsonProperty("id") var id: String)
                    }
                }
                data class DelItem(@JsonProperty("deletedItemId") var deletedItemId: String)
            }
        }

        // --- SyncDevice con backward compatibility ---
        data class SyncDevice(
            @JsonProperty("name") var name: String,
            @JsonProperty("deviceId") var deviceId: String, // draftIssueID
            @JsonProperty("itemId") var itemId: String, // projectItemID
            @JsonProperty("syncedData") var syncedData: List<ResumeWatchingResult>? = null,
            @JsonProperty("syncedContent") var syncedContent: List<SyncContent>? = null  // NUOVO
        ) {
            // Helper per ottenere sempre i dati sincronizzati
            fun getSyncedItems(): List<SyncContent> {
                return syncedContent ?: syncedData?.map { 
                    SyncContent(
                        resumeData = it,
                        deviceName = name,
                        watchedSeconds = 0,
                        totalDuration = null
                    )
                } ?: emptyList()
            }
        }

        private val apiUrl = "https://api.github.com/graphql"

        private fun Any.toStringData(): String {
            return mapper.writeValueAsString(this)
        }

        fun isLoggedIn(): Boolean {
            return !(token.isNullOrEmpty() ||
                    projectNum == null ||
                    deviceName.isNullOrEmpty() ||
                    projectId.isNullOrEmpty())
        }

        private suspend fun apiCall(query: String): APIRes? {
            val apiUrl = "https://api.github.com/graphql"
            val header =
                mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer " + (token ?: return null)
                )
            val data = """ { "query": ${query} } """
            val test = app.post(apiUrl, headers = header, json = data)
            val res = test.parsedSafe<APIRes>()
            return res
        }

        suspend fun syncProjectDetails(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            val query =
                """ query Viewer { viewer { projectV2(number: ${projectNum ?: return failure}) { id } } } """
            val res = apiCall(query.toStringData()) ?: return failure
            projectId = res.data.viewer?.projectV2?.id ?: return failure
            sm.deviceSyncCreds = this
            return true to "Project details saved"
        }

        suspend fun registerThisDevice(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            if (!isLoggedIn()) return failure
            
            // Converti ResumeWatchingResult in SyncContent
            val resumeList = getResumeWatching() ?: emptyList()
            val syncContentList = resumeList.map { resume ->
                SyncContent(
                    resumeData = resume,
                    deviceName = deviceName ?: "Unknown Device",
                    totalDuration = null,
                    watchedSeconds = 0
                )
            }
            
            val syncData = syncContentList.toStringData()
            val encodedData = base64Encode(syncData.toByteArray())
            
            val query =
                """ mutation AddProjectV2DraftIssue { addProjectV2DraftIssue( input: { projectId: "$projectId", title: "$deviceName", body: "$encodedData" } ) { projectItem { id content { ... on DraftIssue { id } } } } } """
            val res = apiCall(query.toStringData()) ?: return failure
            itemId = res.data.issue?.projectItem?.id ?: return failure
            deviceId = res.data.issue?.projectItem?.content?.id ?: return failure
            isThisDeviceSync = true
            sm.deviceSyncCreds = this
            return true to "Device is registered"
        }

        suspend fun deregisterThisDevice(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            if (!isLoggedIn()) return failure
            val query =
                """ mutation DeleteIssue { deleteProjectV2Item( input: { projectId: "$projectId" itemId: "$itemId" } ) { deletedItemId } } """
            val res = apiCall(query.toStringData()) ?: return failure
            if (res.data.delItem?.deletedItemId.equals(itemId)) {
                itemId = null
                deviceId = null
                isThisDeviceSync = false
                sm.deviceSyncCreds = this
                return true to "Device de-registered"
            } else return failure
        }

        suspend fun syncThisDevice(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            if (!isLoggedIn()) return failure
            if (!isThisDeviceSync) return failure
            
            // Converti ResumeWatchingResult in SyncContent
            val resumeList = getResumeWatching() ?: emptyList()
            val syncContentList = resumeList.map { resume ->
                SyncContent(
                    resumeData = resume,
                    deviceName = deviceName ?: "Unknown Device",
                    totalDuration = null,
                    watchedSeconds = 0
                )
            }
            
            val syncData = syncContentList.toStringData()
            val encodedData = base64Encode(syncData.toByteArray())
            
            val query =
                """ mutation UpdateProjectV2DraftIssue { updateProjectV2DraftIssue( input: { draftIssueId: "$deviceId", title: "$deviceName", body: "$encodedData" } ) { draftIssue { id } } } """
            apiCall(query.toStringData()) ?: return failure
            return true to "sync complete"
        }

        suspend fun fetchDevices(): List<SyncDevice>? {
            if (!isLoggedIn()) return null
            val query =
                """ query User { viewer { projectV2(number: ${projectNum ?: return null}) { id items(first: 50) { nodes { id content { ... on DraftIssue { id title bodyText } } } totalCount } } } } """
            val res = apiCall(query.toStringData()) ?: return null
            
            val devices = res.data.viewer?.projectV2?.items?.nodes?.mapNotNull { node ->
                try {
                    val encodedData = base64Decode(node.content.bodyText)
                    
                    // Prima prova a parsare come List<SyncContent>
                    val syncContentList = parseJson<List<SyncContent>?>(encodedData)
                    
                    if (syncContentList != null) {
                        // Nuovo formato
                        SyncDevice(
                            name = node.content.title,
                            deviceId = node.content.id,
                            itemId = node.id,
                            syncedContent = syncContentList
                        )
                    } else {
                        // Vecchio formato: parsare come List<ResumeWatchingResult>
                        val resumeList = parseJson<List<ResumeWatchingResult>?>(encodedData)
                        if (resumeList != null) {
                            SyncDevice(
                                name = node.content.title,
                                deviceId = node.content.id,
                                itemId = node.id,
                                syncedData = resumeList
                            )
                        } else {
                            null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            
            return devices
        }
        
        // --- NUOVA FUNZIONE: Aggiorna durata di un contenuto ---
        suspend fun updateContentDuration(contentId: String, totalDuration: Int?): Boolean {
            val devices = fetchDevices() ?: return false
            var updated = false
            
            devices.forEach { device ->
                val currentItems = device.getSyncedItems()
                val updatedItems = currentItems.map { syncContent ->
                    if (syncContent.resumeData.id == contentId) {
                        updated = true
                        syncContent.copy(totalDuration = totalDuration)
                    } else {
                        syncContent
                    }
                }
                
                if (updated && updatedItems.isNotEmpty()) {
                    // Salva le modifiche
                    val syncData = updatedItems.toStringData()
                    val encodedData = base64Encode(syncData.toByteArray())
                    
                    val query = """ mutation UpdateProjectV2DraftIssue { 
                        updateProjectV2DraftIssue( 
                            input: { 
                                draftIssueId: "${device.deviceId}", 
                                title: "${device.name}", 
                                body: "$encodedData" 
                            } 
                        ) { draftIssue { id } } 
                    } """
                    apiCall(query.toStringData())
                }
            }
            
            return updated
        }
        
        // --- NUOVA FUNZIONE: Aggiorna tempo guardato ---
        suspend fun updateWatchedTime(contentId: String, watchedSeconds: Int, markCompleted: Boolean = false): Boolean {
            val devices = fetchDevices() ?: return false
            var updated = false
            
            devices.forEach { device ->
                val currentItems = device.getSyncedItems()
                val updatedItems = currentItems.map { syncContent ->
                    if (syncContent.resumeData.id == contentId) {
                        updated = true
                        syncContent.copy(
                            watchedSeconds = watchedSeconds,
                            isCompleted = markCompleted || syncContent.isCompleted,
                            lastUpdated = System.currentTimeMillis()
                        )
                    } else {
                        syncContent
                    }
                }
                
                if (updated && updatedItems.isNotEmpty()) {
                    // Salva le modifiche
                    val syncData = updatedItems.toStringData()
                    val encodedData = base64Encode(syncData.toByteArray())
                    
                    val query = """ mutation UpdateProjectV2DraftIssue { 
                        updateProjectV2DraftIssue( 
                            input: { 
                                draftIssueId: "${device.deviceId}", 
                                title: "${device.name}", 
                                body: "$encodedData" 
                            } 
                        ) { draftIssue { id } } 
                    } """
                    apiCall(query.toStringData())
                }
            }
            
            return updated
        }
    }
}

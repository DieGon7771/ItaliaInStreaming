@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.util.Base64
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.fasterxml.jackson.module.kotlin.readValue
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object ApiUtils {
    private const val TAG = "SyncStream"

    private fun Any.toStringData(): String {
        return mapper.writeValueAsString(this)
    }

    private suspend fun apiCall(query: String): APIRes? {
        try {
            val token = getKey<String>("sync_token")
            val apiUrl = "https://api.github.com/graphql"
            val header = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token"
            )
            val data = """ { "query": ${query} } """
            val res = app.post(apiUrl, headers = header, json = data)
            val parsed = res.parsedSafe<APIRes>()
            if (parsed?.errors?.isNotEmpty() == true) {
                Log.w(TAG, "⚠️ GraphQL error: ${parsed.errors?.joinToString { it.message ?: "" }}")
            }
            return parsed
        } catch (e: Exception) {
            Log.w(TAG, "💥 apiCall error: ${e.message}")
            return null
        }
    }

    fun isLoggedIn(): Boolean {
        val token = getKey<String>("sync_token")
        val projectNum = getKey<String>("sync_project_num")
        val projectId = getKey<String>("sync_project_id")

        return !(token.isNullOrEmpty() || projectNum.isNullOrEmpty() || projectId.isNullOrEmpty())
    }

    /** Envelope valido solo se ha un updatedAt reale e un backup non nullo. */
    private fun parseEnvelope(raw: String?): SyncEnvelope? {
        if (raw.isNullOrBlank()) return null
        return try {
            val envelope = mapper.readValue<SyncEnvelope>(raw)
            if (envelope.updatedAt > 0L && envelope.backup != null) envelope else null
        } catch (e: Exception) {
            null
        }
    }

    /** Backup nel vecchio formato (prima dell'introduzione dell'envelope). */
    private fun parseLegacyBackup(raw: String?): BackupFile? {
        if (raw.isNullOrBlank()) return null
        return try {
            mapper.readValue<BackupFile>(raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun envelopeUpdatedAt(raw: String?): Long? {
        return parseEnvelope(raw)?.updatedAt
    }

    private fun parseIsoTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(value)?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ripristina (non distruttivo) il backup dal cloud: il backup viene applicato
     * solo se il JSON è valido e più recente dell'ultimo restore locale, senza
     * cancellare nulla in anticipo.
     */
    fun restoreFromDevice(context: Context?, device: SyncDevice): Boolean {
        if (context == null) return false
        try {
            val raw = device.syncedData ?: return false
            val envelope = parseEnvelope(raw)
            val backup = envelope?.backup ?: parseLegacyBackup(raw) ?: run {
                Log.w(TAG, "backup non valido, restore saltato")
                return false
            }
            val updatedAt = envelope?.updatedAt ?: 0L
            val lastRestore = getKey<Long>("sync_last_restore_at") ?: 0L
            if (updatedAt > 0L && updatedAt <= lastRestore) {
                Log.i(TAG, "backup non più recente del locale, restore saltato")
                return false
            }
            BackupUtils.restore(context, backup, true, true)
            reconcileResumeWatching(context, backup)
            setKey("sync_last_restore_at", if (updatedAt > 0L) updatedAt else System.currentTimeMillis())
            Log.i(TAG, "📥 restore applicato ✅ (updatedAt=$updatedAt)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "📥 restore fallito ❌: ${e.message}")
            return false
        }
    }

    /**
     * Riconciliazione a snapshot del "Continua a guardare": dopo il restore (che
     * è solo-merge e non elimina mai), rimuove localmente i resume item che non
     * sono più presenti nel backup. Così le cancellazioni si propagano a tutti
     * i dispositivi. Elimina anche le relative chiavi stagione/episodio/dub.
     *
     * Lavora direttamente sulle SharedPreferences (come BackupUtils) usando le
     * stringhe delle cartelle, senza dipendere da costanti interne non esposte
     * dall'artefatto pre-release di CloudStream su cui compila il plugin.
     */
    private fun reconcileResumeWatching(context: Context, backup: BackupFile) {
        val resumeFolder = "result_resume_watching_2"
        val seasonFolder = "result_season"
        val episodeFolder = "result_episode"
        val dubFolder = "result_dub"

        // account -> id presenti nel backup (chiavi "account/folder/id")
        val backupIdsByAccount = mutableMapOf<String, Set<Int>>()
        backup.datastore.string?.keys?.forEach { key ->
            val parts = key.split("/")
            if (parts.size == 3 && parts[1] == resumeFolder) {
                parts[2].toIntOrNull()?.let { id ->
                    backupIdsByAccount[parts[0]] = backupIdsByAccount[parts[0]].orEmpty() + id
                }
            }
        }

        val prefs = context.getSharedPrefs()
        val removed = mutableListOf<Int>()
        prefs.all.keys.forEach { key ->
            val parts = key.split("/")
            if (parts.size == 3 && parts[1] == resumeFolder) {
                val id = parts[2].toIntOrNull() ?: return@forEach
                val backupIds = backupIdsByAccount[parts[0]].orEmpty()
                if (id !in backupIds) {
                    val account = parts[0]
                    prefs.edit().remove(key).apply()
                    prefs.edit().remove("$account/$seasonFolder/$id").apply()
                    prefs.edit().remove("$account/$episodeFolder/$id").apply()
                    prefs.edit().remove("$account/$dubFolder/$id").apply()
                    removed += id
                }
            }
        }
        if (removed.isNotEmpty()) {
            Log.i(TAG, "🗑️ reconcile: rimossi ${removed.size} item non più nel cloud: $removed")
        }
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String?> {
        var failure = false to "Project not found"
        var failureToken = false to "Github token is wrong"
        val projectNum = getKey<String>("sync_project_num")
        val query = """ query Viewer { viewer { projectV2(number: ${projectNum}) { id } } } """
        val res = apiCall(query.toStringData()) ?: return failureToken
        val projectId = res.data?.viewer?.projectV2?.id ?: return failure
        setKey("sync_project_id", projectId)

        // Modello "nodo unico condiviso": tutti i dispositivi usano il nodo
        // DraftIssue più recente del progetto come "nuvola".
        val devices: List<SyncDevice>? = fetchDevices()
        val node: SyncDevice? = devices?.firstOrNull()
        if (node != null) {
            setKey("sync_item_id", node.itemId ?: "")
            setKey("sync_device_id", node.deviceId ?: "")
            if (getKey<String>("restore_device") == "true") {
                restoreFromDevice(context, node)
            }
        } else if (getKey<String>("backup_device") == "true") {
            // nessun nodo valido: lo creiamo come nuova "nuvola"
            val backup = BackupUtils.getBackup(context, getResumeWatching())
                ?: return false to "Backup non disponibile"
            val envelope = SyncEnvelope(System.currentTimeMillis(), backup)
            val data = Base64.encodeToString(envelope.toJson().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            val query = """ mutation AddProjectV2DraftIssue { addProjectV2DraftIssue( input: { projectId: "$projectId", title: "${getKey<String>("device_id")}", body: "$data" } ) { projectItem { id content { ... on DraftIssue { id } } } } } """
            val res = apiCall(query.toStringData()) ?: return failureToken
            val itemId = res.data?.issue?.projectItem?.id ?: return false to res.errors?.get(0)?.message.toString()
            val deviceId = res.data?.issue?.projectItem?.content?.id ?: return false to res.errors?.get(0)?.message.toString()
            setKey("sync_item_id", itemId)
            setKey("sync_device_id", deviceId)
        } else {
            setKey("sync_token", "")
            setKey("sync_project_num", "")
            return false to "Nessun backup trovato"
        }
        return true to "Dispositivo registrato correttamente"
    }

    suspend fun syncThisDevice(envelopeJson: String): Pair<Boolean, String?> {
        val failure = false to "Error sync this device id: ${getKey<String>("device_id")}"
        if (!isLoggedIn()) return failure
        val deviceId = getKey<String>("sync_device_id")
        if (deviceId.isNullOrEmpty()) return failure
        val data = Base64.encodeToString(envelopeJson.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val query = """ mutation UpdateProjectV2DraftIssue { updateProjectV2DraftIssue( input: { draftIssueId: "$deviceId", title: "${getKey<String>("device_id")}", body: "$data" } ) { draftIssue { id } } } """
        val res = apiCall(query.toStringData()) ?: return failure
        val error = res.errors?.get(0)?.message
        if (error != null) return false to error
        return true to "Sync success"
    }

    /**
     * Ritorna la lista dei DraftIssue del progetto, difensivo: salta i nodi che
     * non sono DraftIssue e quelli il cui body non è base64 decodificabile.
     * Ordinati dal più recente al meno recente.
     */
    suspend fun fetchDevices(): List<SyncDevice>? {
        if (!isLoggedIn()) return null
        val projectNum = getKey<String>("sync_project_num")
        val query = """ query User { viewer { projectV2(number: ${projectNum}) { id items(first: 100) { nodes { id content { __typename ... on DraftIssue { id title bodyText updatedAt } } } } } } } """
        val res = apiCall(query.toStringData()) ?: return null
        val nodes = res.data?.viewer?.projectV2?.items?.nodes ?: return emptyList()
        val devices = nodes.mapNotNull { node ->
            val content = node.content
            if (content.typeName != "DraftIssue") return@mapNotNull null
            val raw = content.bodyText ?: return@mapNotNull null
            val decoded = try {
                Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "nodo non decodificabile, saltato: ${e.message}")
                return@mapNotNull null
            }
            SyncDevice(
                name = content.title ?: "",
                deviceId = content.id ?: "",
                itemId = node.id,
                syncedData = decoded,
                updatedAt = envelopeUpdatedAt(decoded) ?: parseIsoTime(content.updatedAt) ?: 0L
            )
        }.sortedByDescending { it.updatedAt }
        Log.i(TAG, "📡 trovati ${devices.size} nodi validi")
        return devices
    }
}

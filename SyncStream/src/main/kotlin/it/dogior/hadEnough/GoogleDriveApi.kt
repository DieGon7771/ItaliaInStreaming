@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class GDeviceCodeRes(
    @JsonProperty("device_code") val deviceCode: String? = null,
    @JsonProperty("user_code") val userCode: String? = null,
    @JsonProperty("verification_url") val verificationUrl: String? = null,
    @JsonProperty("expires_in") val expiresIn: Int? = null,
    @JsonProperty("interval") val interval: Int? = null,
    @JsonProperty("error") val error: String? = null,
    @JsonProperty("error_description") val errorDescription: String? = null,
)

data class GTokenRes(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    @JsonProperty("expires_in") val expiresIn: Int? = null,
    @JsonProperty("error") val error: String? = null,
    @JsonProperty("error_description") val errorDescription: String? = null,
)

data class GFile(@JsonProperty("id") val id: String? = null)

data class GFiles(@JsonProperty("files") val files: List<GFile>? = null)

data class DriveRead(val ok: Boolean, val content: String?)

object GoogleDriveApi {
    private const val TAG = "SyncStream"
    private const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private const val SCOPE_FILE = "https://www.googleapis.com/auth/drive.file"
    private const val FILE_NAME = "syncstream_backup.json"
    private const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

    private val lock = Mutex()

    @Volatile
    var lastError: String? = null

    fun isConfigured(): Boolean =
        BuildConfig.GOOGLE_CLIENT_ID.isNotBlank() && BuildConfig.GOOGLE_CLIENT_SECRET.isNotBlank()

    fun isLoggedIn(): Boolean = !getKey<String>("gdrive_refresh_token").isNullOrBlank()

    fun logout() {
        setKey("gdrive_refresh_token", "")
        setKey("gdrive_access_token", "")
        setKey("gdrive_access_expires", 0L)
        setKey("gdrive_file_id", "")
        setKey("gdrive_scope", "")
    }

    private inline fun <reified T> parse(s: String): T? = try {
        mapper.readValue<T>(s)
    } catch (e: Exception) {
        null
    }

    private suspend fun call(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            app.baseClient.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            Log.w(TAG, "Drive rete: ${e.message}")
            -1 to ""
        }
    }

    @Volatile
    private var pendingScope: String = SCOPE_APPDATA

    suspend fun requestDeviceCode(): GDeviceCodeRes? {
        val first = requestDeviceCode(SCOPE_APPDATA)
        if (first != null) {
            pendingScope = SCOPE_APPDATA
            return first
        }
        if (lastError?.startsWith("invalid_scope") == true) {
            val second = requestDeviceCode(SCOPE_FILE)
            if (second != null) {
                pendingScope = SCOPE_FILE
                return second
            }
        }
        return null
    }

    private suspend fun requestDeviceCode(scope: String): GDeviceCodeRes? {
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
            .add("scope", scope)
            .build()
        val (httpCode, text) = call(Request.Builder().url(DEVICE_URL).post(body).build())
        val res = parse<GDeviceCodeRes>(text)
        if (res?.deviceCode == null || res.userCode == null) {
            Log.w(TAG, "device code: HTTP $httpCode $text")
            lastError = when {
                httpCode == -1 -> "nessuna connessione a Google"
                res?.error != null -> res.error + (res.errorDescription?.let { ": $it" } ?: "")
                else -> "HTTP $httpCode"
            }
            return null
        }
        lastError = null
        return res
    }

    suspend fun pollToken(code: GDeviceCodeRes): Pair<Boolean, String?> {
        var interval = (code.interval ?: 5).coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + (code.expiresIn ?: 600) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val body = FormBody.Builder()
                .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
                .add("client_secret", BuildConfig.GOOGLE_CLIENT_SECRET)
                .add("device_code", code.deviceCode ?: "")
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val (_, text) = call(Request.Builder().url(TOKEN_URL).post(body).build())
            val res = parse<GTokenRes>(text) ?: continue
            if (res.accessToken != null && res.refreshToken != null) {
                setKey("gdrive_refresh_token", res.refreshToken)
                setKey("gdrive_access_token", res.accessToken)
                setKey("gdrive_access_expires", System.currentTimeMillis() + (res.expiresIn ?: 3600) * 1000L)
                setKey("gdrive_file_id", "")
                setKey("gdrive_scope", pendingScope)
                return true to "ok"
            }
            when (res.error) {
                "authorization_pending", null -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> return false to "Accesso negato"
                else -> return false to (res.errorDescription ?: res.error)
            }
        }
        return false to "Codice scaduto, riprova"
    }

    private suspend fun accessToken(): String? {
        lock.withLock {
            val refresh = getKey<String>("gdrive_refresh_token")?.takeIf { it.isNotBlank() } ?: return null
            val cached = getKey<String>("gdrive_access_token")
            val expires = getKey<Long>("gdrive_access_expires") ?: 0L
            if (!cached.isNullOrBlank() && System.currentTimeMillis() < expires - 60_000L) return cached

            val body = FormBody.Builder()
                .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
                .add("client_secret", BuildConfig.GOOGLE_CLIENT_SECRET)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build()
            val (_, text) = call(Request.Builder().url(TOKEN_URL).post(body).build())
            val res = parse<GTokenRes>(text)
            if (res?.accessToken != null) {
                setKey("gdrive_access_token", res.accessToken)
                setKey("gdrive_access_expires", System.currentTimeMillis() + (res.expiresIn ?: 3600) * 1000L)
                return res.accessToken
            }
            if (res?.error == "invalid_grant") {
                Log.w(TAG, "Drive: accesso revocato o scaduto, serve un nuovo login")
                logout()
            }
            return null
        }
    }

    private class Found(val ok: Boolean, val id: String?)

    private fun useAppData(): Boolean = getKey<String>("gdrive_scope") != SCOPE_FILE

    private fun cachedId(): String? = getKey<String>("gdrive_file_id")?.takeIf { it.isNotBlank() }

    private fun auth(token: String) = "Bearer $token"

    private suspend fun findFile(token: String): Found {
        val url = FILES_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("spaces", if (useAppData()) "appDataFolder" else "drive")
            ?.addQueryParameter("q", "name='$FILE_NAME' and trashed=false")
            ?.addQueryParameter("orderBy", "createdTime")
            ?.addQueryParameter("fields", "files(id)")
            ?.addQueryParameter("pageSize", "10")
            ?.build() ?: return Found(false, null)
        val (code, text) = call(Request.Builder().url(url).header("Authorization", auth(token)).get().build())
        if (code != 200) {
            Log.w(TAG, "Drive cerca file: $code $text")
            return Found(false, null)
        }
        return Found(true, parse<GFiles>(text)?.files?.firstOrNull()?.id)
    }

    private suspend fun createFile(token: String): String? {
        val meta = if (useAppData()) """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
        else """{"name":"$FILE_NAME"}"""
        val (code, text) = call(
            Request.Builder().url("$FILES_URL?fields=id")
                .header("Authorization", auth(token))
                .post(meta.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
        if (code !in 200..299) {
            Log.w(TAG, "Drive crea file: $code $text")
            return null
        }
        return parse<GFile>(text)?.id
    }

    private suspend fun download(token: String, id: String): Pair<Int, String> =
        call(
            Request.Builder().url("$FILES_URL/$id?alt=media")
                .header("Authorization", auth(token)).get().build()
        )

    private suspend fun upload(token: String, id: String, content: String): Int =
        call(
            Request.Builder().url("$UPLOAD_URL/$id?uploadType=media")
                .header("Authorization", auth(token))
                .patch(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        ).first

    suspend fun readBackup(): DriveRead {
        val token = accessToken() ?: return DriveRead(false, null)
        cachedId()?.let { id ->
            val (code, body) = download(token, id)
            if (code == 200) return DriveRead(true, body)
            if (code != 404) return DriveRead(false, null)
            setKey("gdrive_file_id", "")
        }
        val found = findFile(token)
        if (!found.ok) return DriveRead(false, null)
        val id = found.id ?: return DriveRead(true, null)
        setKey("gdrive_file_id", id)
        val (code, body) = download(token, id)
        return if (code == 200) DriveRead(true, body) else DriveRead(false, null)
    }

    suspend fun writeBackup(content: String): Pair<Boolean, String?> {
        val token = accessToken() ?: return false to "Accesso a Google non valido: rifai il login"
        repeat(2) {
            var id = cachedId()
            if (id == null) {
                val found = findFile(token)
                if (!found.ok) return false to "Google Drive non raggiungibile"
                id = found.id ?: createFile(token) ?: return false to "Impossibile creare il file su Drive"
                setKey("gdrive_file_id", id)
            }
            val code = upload(token, id, content)
            if (code in 200..299) return true to "Sync success"
            if (code == 404) {
                setKey("gdrive_file_id", "")
                return@repeat
            }
            return false to "Errore Drive ($code)"
        }
        return false to "Errore Drive"
    }
}

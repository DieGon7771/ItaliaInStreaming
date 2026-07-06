package it.dogior.hadEnough

import java.io.File
import java.security.MessageDigest

object OnlineSerieTVCache {
    private const val MAX_MEMORY_ENTRIES = 512
    private const val MAX_DISK_ENTRIES = 1024

    private data class CacheEntry(
        val text: String,
        val expiresAtMs: Long,
    )

    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_MEMORY_ENTRIES
        }
    }

    private var diskDirectory: File? = null

    fun setDiskDirectory(directory: File) {
        diskDirectory = directory
        runCatching { directory.mkdirs() }
    }

    enum class CacheProfile(val ttlMs: Long) {
        PAGE(6 * 3600 * 1000L),
        DETAIL(12 * 3600 * 1000L),
        SEARCH(6 * 3600 * 1000L),
    }

    @Synchronized
    fun get(key: String, allowExpired: Boolean = false): String? {
        val entry = memoryCache[key]
        if (entry != null) {
            if (entry.expiresAtMs > System.currentTimeMillis()) {
                return entry.text
            }
            if (allowExpired) return entry.text
            memoryCache.remove(key)
        }

        return readDisk(key, allowExpired)
    }

    @Synchronized
    fun put(key: String, text: String, profile: CacheProfile) {
        if (text.isBlank()) return
        val expiresAtMs = System.currentTimeMillis() + profile.ttlMs
        val entry = CacheEntry(text = text, expiresAtMs = expiresAtMs)

        memoryCache[key] = entry
        writeDisk(key, text, expiresAtMs)
    }

    @Synchronized
    fun remove(key: String) {
        memoryCache.remove(key)
        runCatching { cacheFile(key)?.delete() }
    }

    @Synchronized
    fun clear() {
        memoryCache.clear()
        val directory = diskDirectory ?: return
        directory.listFiles { file -> file.isFile && file.extension == "html" }
            .orEmpty()
            .forEach { runCatching { it.delete() } }
    }

    private fun readDisk(key: String, allowExpired: Boolean = false): String? {
        val file = cacheFile(key) ?: return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val separator = raw.indexOf('\n')
        if (separator <= 0) {
            runCatching { file.delete() }
            return null
        }

        val expiresAtMs = raw.substring(0, separator).toLongOrNull()
        if (expiresAtMs == null) {
            runCatching { file.delete() }
            return null
        }

        val text = raw.substring(separator + 1)
        if (expiresAtMs <= System.currentTimeMillis()) {
            if (allowExpired) {
                memoryCache[key] = CacheEntry(text, expiresAtMs)
                return text
            }
            runCatching { file.delete() }
            return null
        }

        memoryCache[key] = CacheEntry(text, expiresAtMs)
        return text
    }

    private fun writeDisk(key: String, text: String, expiresAtMs: Long) {
        val directory = diskDirectory ?: return
        runCatching {
            directory.mkdirs()
            cacheFile(key)?.writeText("$expiresAtMs\n$text")
            trimDisk(directory)
        }
    }

    private fun cacheFile(key: String): File? {
        val directory = diskDirectory ?: return null
        return File(directory, "${sha256(key)}.html")
    }

    private fun trimDisk(directory: File) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "html" }.orEmpty()
        if (files.size <= MAX_DISK_ENTRIES) return

        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { runCatching { it.delete() } }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

package it.dogior.hadEnough.extractors

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AbyssProxy {
    private const val TAG = "AbyssProxy"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    const val CHUNK_MAX = 0x200000L
    private const val AHEAD = 4          // pezzi scaricati in anticipo (in parallelo)
    private const val FD_HEAD = 0x10000  // parte cifrata all'inizio del file "primi dati"
    private const val FD_CHECK = 0x20000 // byte confrontati per validare il file "primi dati"
    private const val CACHE_PARTS = 14

    class Source(
        val label: String,
        val resId: Int,
        val size: Long,
        val codec: String,
        val domain: String,
        val md5Id: Long,
        val fdUrl: String?,
        val fdPartSize: Long,
    ) {
        @Volatile
        var fdOk = false
        val fdChecked = CountDownLatch(1)
    }

    // ------------------------------------------------------------ cifratura

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun aesCtr(mode: Int, key32: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(mode, SecretKeySpec(key32, "AES"), IvParameterSpec(key32.copyOfRange(0, 16)))
        return c.doFinal(data)
    }

    /** Legge `datas` della pagina del player e restituisce le sorgenti disponibili. */
    fun parseDatas(datasBase64: String): List<Source> {
        val json = JSONObject(String(Base64.decode(datasBase64, Base64.DEFAULT), Charsets.ISO_8859_1))
        val slug = json.getString("slug")
        val md5Id = json.getLong("md5_id")
        val userId = json.getLong("user_id")
        val encrypted = json.getString("media").toByteArray(Charsets.ISO_8859_1)

        val key = md5Hex("$userId:$slug:$md5Id".toByteArray(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val media = JSONObject(String(aesCtr(Cipher.DECRYPT_MODE, key, encrypted), Charsets.UTF_8))
        val mp4 = media.getJSONObject("mp4")

        val domains = mp4.getJSONArray("domains").let { a -> (0 until a.length()).map { a.getString(it) } }
        if (domains.isEmpty()) return emptyList()

        class Fd(val resId: Int, val size: Long, val codec: String, val url: String, val partSize: Long)

        val fds = mutableListOf<Fd>()
        mp4.optJSONArray("fristDatas")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                val url = f.optString("url", "")
                if (url.isNotEmpty()) {
                    fds += Fd(
                        f.optInt("res_id"), f.optLong("size"), f.optString("codec"),
                        url, f.optLong("partSize")
                    )
                }
            }
        }

        val sources = mp4.getJSONArray("sources")
        val out = mutableListOf<Source>()
        for (i in 0 until sources.length()) {
            val s = sources.getJSONObject(i)
            if (!s.optBoolean("status", true)) continue
            val size = s.optLong("size", 0L)
            if (size <= 0L) continue
            val sub = s.optString("sub", "")
            val domain = domains.firstOrNull { sub.isNotEmpty() && it.contains(sub) }
                ?: domains[(size % domains.size).toInt()]
            val resId = s.getInt("res_id")
            val codec = s.optString("codec", "")
            val fd = fds.firstOrNull { it.size == size && it.resId == resId && it.codec == codec }
            out += Source(
                label = s.optString("label", ""),
                resId = resId,
                size = size,
                codec = codec,
                domain = domain,
                md5Id = md5Id,
                fdUrl = fd?.url,
                fdPartSize = fd?.partSize ?: 0L,
            )
        }
        return out
    }

    fun token(path: String, size: Long): String {
        val digits = size.toString().map { (it - '0').toByte() }.toByteArray()
        val key = md5Hex(digits).toByteArray(Charsets.UTF_8)
        val ct = aesCtr(Cipher.ENCRYPT_MODE, key, path.toByteArray(Charsets.UTF_8))
        val flags = Base64.NO_WRAP or Base64.NO_PADDING
        val first = Base64.encodeToString(ct, flags)
        return Base64.encodeToString(first.toByteArray(Charsets.ISO_8859_1), flags)
    }

    private fun chunkSize(size: Long) = if (size > CHUNK_MAX) CHUNK_MAX else size

    private fun soraUrl(src: Source, index: Long): String {
        val chunk = chunkSize(src.size)
        val path = "/mp4/${src.md5Id}/${src.resId}/${src.size}/$chunk/$index"
        return "https://${src.domain}/sora/${src.size}/${token(path, src.size)}"
    }

    /** Il player prova a decifrare l'inizio del file "primi dati" e usa il risultato solo se vede "ftyp". */
    private fun decryptHead(data: ByteArray, fdUrl: String): ByteArray {
        return try {
            val n = minOf(FD_HEAD, data.size)
            val key = md5Hex(fdUrl.substringAfterLast('/').toByteArray(Charsets.UTF_8))
                .toByteArray(Charsets.UTF_8)
            val dec = aesCtr(Cipher.DECRYPT_MODE, key, data.copyOf(n))
            val text = String(dec, Charsets.ISO_8859_1)
            if (text.contains("ftypisom") || text.contains("ftypmp42")) {
                dec + data.copyOfRange(n, data.size)
            } else data
        } catch (_: Exception) {
            data
        }
    }

    // ---------------------------------------------------------------- rete

    private val client: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Scarica `expected` byte; timeout brevi e nuovi tentativi rapidi. Null se fallisce. */
    private fun getBytes(url: String, range: String?, expected: Int, absStart: Long = 0L): ByteArray? {
        repeat(4) { attempt ->
            try {
                val rb = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://mload.cam/")
                    .header("Origin", "https://mload.cam")
                    .header("Accept", "*/*")
                if (range != null) rb.header("Range", range)
                client.newCall(rb.build()).execute().use { r ->
                    if (r.isSuccessful) {
                        var b = r.body?.bytes()
                        if (b != null) {
                            // il server ha ignorato Range e ha mandato tutto il file
                            if (r.code == 200 && range != null && absStart > 0 &&
                                b.size >= absStart + expected
                            ) {
                                b = b.copyOfRange(absStart.toInt(), absStart.toInt() + expected)
                            }
                            if (b.size == expected) return b
                            if (b.size > expected) return b.copyOf(expected)
                            Log.w(TAG, "risposta corta: ${b.size} byte, attesi $expected")
                        }
                    } else {
                        Log.w(TAG, "HTTP ${r.code} per ${url.substringBefore('?').takeLast(60)}")
                        if (r.code == 403 || r.code == 404) return null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "tentativo ${attempt + 1} fallito: ${e.message}")
            }
            try {
                Thread.sleep(200L * (attempt + 1))
            } catch (_: InterruptedException) {
            }
        }
        return null
    }

    private fun verifyFirstData(src: Source) {
        try {
            val fdUrl = src.fdUrl
            if (fdUrl == null || src.fdPartSize < CHUNK_MAX || src.size < FD_CHECK) return
            val fdFut = pool.submit<ByteArray?> { getBytes(fdUrl, "bytes=0-${FD_CHECK - 1}", FD_CHECK) }
            val soraFut = pool.submit<ByteArray?> { getBytes(soraUrl(src, 0), "bytes=0-${FD_CHECK - 1}", FD_CHECK) }
            val fd = fdFut.get(20, TimeUnit.SECONDS)
            val sora = soraFut.get(20, TimeUnit.SECONDS)
            if (fd != null && sora != null && fd.size == FD_CHECK && sora.size == FD_CHECK) {
                src.fdOk = decryptHead(fd, fdUrl).contentEquals(sora)
            }
            Log.d(TAG, "primi dati ${src.label}: ${if (src.fdOk) "OK (uso il server veloce)" else "non utilizzabili"}")
        } catch (e: Exception) {
            Log.w(TAG, "verifica primi dati: ${e.message}")
        } finally {
            src.fdChecked.countDown()
        }
    }

    private fun fetchPart(src: Source, idx: Long): ByteArray? {
        val t0 = System.currentTimeMillis()
        val chunk = chunkSize(src.size)
        val s = idx * chunk
        val expected = minOf(chunk, src.size - s).toInt()
        var from = "sora"
        var data: ByteArray? = null

        val fdUrl = src.fdUrl
        if (src.fdOk && fdUrl != null && s < src.fdPartSize) {
            val e = minOf(s + chunk, src.fdPartSize, src.size) - 1
            val len = (e - s + 1).toInt()
            val raw = getBytes(fdUrl, "bytes=$s-$e", len, s)
            if (raw != null) {
                data = if (idx == 0L) decryptHead(raw, fdUrl) else raw
                from = "fd"
            }
        }
        if (data == null) data = getBytes(soraUrl(src, idx), null, expected)
        Log.d(TAG, "pezzo $idx/${src.label} da $from: ${data?.size ?: -1} byte in ${System.currentTimeMillis() - t0} ms")
        return data
    }

    // ------------------------------------------------------- server locale

    private val streams = java.util.concurrent.ConcurrentHashMap<String, Source>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    private val cache = object : LinkedHashMap<String, Future<ByteArray?>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Future<ByteArray?>>?): Boolean =
            size > CACHE_PARTS
    }

    @Synchronized
    private fun partFuture(id: String, src: Source, idx: Long): Future<ByteArray?> =
        cache.getOrPut("$id:$idx") { pool.submit<ByteArray?> { fetchPart(src, idx) } }

    @Synchronized
    private fun dropPart(id: String, idx: Long) {
        cache.remove("$id:$idx")
    }

    @Synchronized
    private fun ensureStarted(): ServerSocket {
        server?.let { if (!it.isClosed) return it }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    val sock = s.accept()
                    pool.execute { handle(sock) }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; name = "AbyssProxy-accept" }.start()
        Log.d(TAG, "proxy locale su porta ${s.localPort}")
        return s
    }

    fun register(source: Source): String {
        val s = ensureStarted()
        val id = UUID.randomUUID().toString().replace("-", "")
        streams[id] = source
        pool.execute { verifyFirstData(source) }
        return "http://127.0.0.1:${s.localPort}/$id.mp4"
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 60_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val id = parts[1].substringAfterLast('/').substringBefore('.').substringBefore('?')
                val src = streams[id]
                val out = s.getOutputStream()
                if (src == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }

                val size = src.size
                var start = 0L
                var end = size - 1
                val range = headers["range"]
                val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
                if (m != null) {
                    val a = m.groupValues[1]
                    val b = m.groupValues[2]
                    if (a.isEmpty() && b.isNotEmpty()) {
                        start = maxOf(0L, size - b.toLong())
                    } else {
                        if (a.isNotEmpty()) start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                    }
                }
                if (start >= size || start > end) {
                    out.write(
                        ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    return
                }

                val status = if (m != null) "206 Partial Content" else "200 OK"
                val head = StringBuilder("HTTP/1.1 $status\r\n")
                head.append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
                head.append("Content-Length: ${end - start + 1}\r\n")
                if (m != null) head.append("Content-Range: bytes $start-$end/$size\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                out.flush()
                if (method == "HEAD") return

                try {
                    src.fdChecked.await(8, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }

                val chunk = chunkSize(size)
                val lastIdx = end / chunk
                var pos = start
                while (pos <= end) {
                    val idx = pos / chunk
                    for (k in idx..minOf(idx + AHEAD, lastIdx)) partFuture(id, src, k)
                    val data: ByteArray? = try {
                        partFuture(id, src, idx).get(60, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        null
                    }
                    if (data == null) {
                        dropPart(id, idx) // al prossimo tentativo del player si riprova
                        break
                    }
                    val off = (pos - idx * chunk).toInt()
                    val n = minOf((data.size - off).toLong(), end - pos + 1).toInt()
                    if (n <= 0) break
                    out.write(data, off, n)
                    pos += n
                }
                out.flush()
            } catch (_: Exception) {
                
            }
        }
    }
}

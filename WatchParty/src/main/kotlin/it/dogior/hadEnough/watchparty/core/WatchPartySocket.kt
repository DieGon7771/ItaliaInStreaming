package it.dogior.hadEnough.watchparty

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Wrapper minimale sopra OkHttp WebSocket verso il relay (vedi WatchPartyServer/).
 * Puro Kotlin/Java: nessuna libreria nativa, quindi caricabile senza problemi
 * dal PathClassLoader a 2 argomenti usato da PluginManager per i file .cs3.
 *
 * Il server raggruppa i client per "pin" (max 5 per stanza) e inoltra
 * ciecamente ogni messaggio testuale a tutti gli altri peer connessi.
 *
 * Ogni client passa il proprio cid (UUID) come query string: il server lo usa
 * per tracciare roster/ordine e l'host migration. Il cid viene iniettato in
 * ogni messaggio in uscita (message.copy), così chi riceve sa sempre il mittente.
 */
class WatchPartySocket(
    private val baseWsUrl: String, // es. "wss://tuoworker.workers.dev/room"
    private val clientId: String,
    private val onOpen: () -> Unit,
    private val onMessage: (WatchPartyMessage) -> Unit,
    private val onClosed: (code: Int, reason: String) -> Unit,
    private val onFailure: (Throwable, okhttp3.Response?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS) // tiene viva la connessione (keepalive)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null

    val isOpen: Boolean
        get() = socket != null

    fun connect(pin: String) {
        val request = Request.Builder()
            .url("$baseWsUrl/$pin?cid=$clientId")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.decodeFromString<WatchPartyMessage>(text) }
                    .getOrNull() ?: return
                onMessage(msg)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(code, reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure(t, response)
            }
        })
    }

    fun send(message: WatchPartyMessage) {
        val ws = socket ?: return
        runCatching {
            ws.send(json.encodeToString(WatchPartyMessage.serializer(), message.copy(cid = clientId)))
        }
    }

    fun send(type: String) = send(WatchPartyMessage(type = type))

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }
}

package it.dogior.hadEnough.watchparty

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

/**
 * Provider "vuoto", registrato SOLO quando l'host è Nuvio Enhanced.
 *
 * Il suo loader Android Full (CloudStreamPlatformRuntime.android.kt,
 * loadPlugin) rifiuta con un errore esplicito ogni plugin che non registri
 * almeno un MainAPI ("Plugin loaded but registered no providers"), perché
 * lì i plugin sono pensati come fonti di contenuti. WatchParty non lo è:
 * questa classe esiste solo per superare quel controllo e non viene mai
 * usata per cercare o riprodurre nulla.
 *
 * hasMainPage = false: Nuvio non la interroga per la home. Non sovrascrivo
 * search(): ogni ricerca in Nuvio interroga comunque tutti i provider
 * registrati (indipendentemente da hasMainPage), ma il suo loader
 * (AndroidDexCloudStreamProvider.search) avvolge già ogni chiamata a
 * ciascun provider in un runCatching che si limita a loggare un warning in
 * caso di errore, senza bloccare gli altri risultati — quindi anche
 * l'implementazione di default (non pensata per essere chiamata qui) resta
 * innocua, senza dover indovinare la firma esatta del metodo per questa
 * versione dell'API.
 */
class WatchPartyNuvioProvider : MainAPI() {
    override var mainUrl = "https://github.com/DieGon7771/ItaliaInStreaming"
    override var name = "WatchParty (solo sincronizzazione, non è una fonte)"
    override var lang = "it"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Others)
}

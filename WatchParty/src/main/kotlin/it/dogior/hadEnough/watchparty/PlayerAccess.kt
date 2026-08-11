package it.dogior.hadEnough.watchparty

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.IPlayer

/**
 * NON è API ufficiale dei plugin CloudStream: al momento in cui è stato scritto
 * (agosto 2026) non esiste un modo supportato per un plugin di agganciarsi
 * all'istanza del player. Questo file si appoggia a:
 *  - CommonActivity.activity  -> pubblico
 *  - MainActivity.supportFragmentManager -> API Android standard
 *  - GeneratorPlayer.player   -> proprietà pubblica ma "dettaglio implementativo"
 *
 * Se un aggiornamento di CloudStream cambia questi nomi/percorsi, questo file
 * smette di funzionare silenziosamente (ogni accesso è avvolto in try/catch
 * e ritorna null): il resto del plugin degrada senza crashare.
 */
object PlayerAccess {

    /** Ritorna il fragment del player attualmente in primo piano, se presente. */
    private fun currentPlayerFragment(): GeneratorPlayer? = runCatching {
        val activity = CommonActivity.activity as? MainActivity ?: return null
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return null
        val top: Fragment? = navHost.childFragmentManager.fragments.lastOrNull()
        top as? GeneratorPlayer
    }.getOrNull()

    /** True se l'utente ha in questo momento la schermata del player aperta. */
    fun isPlayerScreenActive(): Boolean = currentPlayerFragment() != null

    /** L'istanza IPlayer attiva, se il player è aperto. */
    fun currentPlayer(): IPlayer? = runCatching {
        currentPlayerFragment()?.player
    }.getOrNull()
}

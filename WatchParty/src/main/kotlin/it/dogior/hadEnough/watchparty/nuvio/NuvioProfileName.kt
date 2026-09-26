package it.dogior.hadEnough.watchparty

import android.util.Log

private const val TAG = "WatchParty"

/**
 * Legge il nome del profilo Nuvio attivo, per lo stesso motivo e con la
 * stessa tecnica di NuvioPlaybackBridge (ricerca di PlayerView): Nuvio non
 * espone una vera "API plugin" per questo, ma com.nuvio.app.features.
 * profiles.ProfileRepository è una classe reale dell'app (Kotlin
 * Multiplatform, commonMain, compilata come normale classe JVM sul target
 * Android) che il loader porta con sé comunque — quindi risolvibile a
 * runtime via reflection, con la "risoluzione pigra delle classi" di
 * sempre: mai chiamata su CloudStream (lì la classe non esiste affatto),
 * ogni passo avvolto in runCatching così un cambio di firma futuro nel
 * codice di Nuvio degrada silenziosamente al fallback "Guest" invece di
 * lanciare un'eccezione non gestita.
 *
 * Catena percorsa (tutta via reflection):
 *   ProfileRepository (object, singleton nel campo statico INSTANCE)
 *     .getState()            -> kotlinx.coroutines.flow.StateFlow<ProfileState>
 *     .getValue()            -> ProfileState (valore corrente, già in memoria)
 *     .getActiveProfile()    -> NuvioProfile? (null se nessun profilo attivo,
 *                                es. utente non autenticato / modalità anonima)
 *     .getName()             -> String
 */
object NuvioProfileName {
    fun currentName(): String? = runCatching {
        val repoClass = Class.forName("com.nuvio.app.features.profiles.ProfileRepository")
        val repoInstance = repoClass.getField("INSTANCE").get(null)

        val stateFlow = repoClass.getMethod("getState").invoke(repoInstance)
        val profileState = stateFlow.javaClass.getMethod("getValue").invoke(stateFlow)

        val activeProfile = profileState.javaClass.getMethod("getActiveProfile").invoke(profileState)
            ?: return@runCatching null

        val name = activeProfile.javaClass.getMethod("getName").invoke(activeProfile) as? String
        name?.takeIf { it.isNotBlank() }
    }.onFailure {
        Log.w(TAG, "Nome profilo Nuvio non risolto via reflection, uso il fallback", it)
    }.getOrNull()
}

package it.dogior.hadEnough

import com.lagradost.api.Log
import it.dogior.hadEnough.UltimaUtils.ExtensionInfo
import it.dogior.hadEnough.UltimaUtils.SectionInfo
import it.dogior.hadEnough.WatchSyncUtils.WatchSyncCreds
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey

object UltimaStorageManager {

    // #region - custom data variables

    var extNameOnHome: Boolean
        get() = getKey("ULTIMA_EXT_NAME_ON_HOME") ?: true
        set(value) {
            setKey("ULTIMA_EXT_NAME_ON_HOME", value)
        }

    var currentExtensions: Array<ExtensionInfo>
        get() = getKey("ULTIMA_EXTENSIONS_LIST") ?: emptyArray()
        set(value) {
            setKey("ULTIMA_EXTENSIONS_LIST", value)
        }

    // RIMOSSO: currentMetaProviders - non esiste più
    // RIMOSSO: currentMediaProviders - non esiste più

    var deviceSyncCreds: WatchSyncCreds?
        get() = getKey("ULTIMA_WATCH_SYNC_CREDS")
        set(value) {
            setKey("ULTIMA_WATCH_SYNC_CREDS", value)
        }

    // #endregion - custom data variables

    fun save() {
        // Funzione per salvare esplicitamente se necessario
        // Potrebbe non fare nulla se i dati sono salvati automaticamente via setKey
    }

    fun deleteAllData() {
        listOf(
            "ULTIMA_PROVIDER_LIST", // old key
            "ULTIMA_EXT_NAME_ON_HOME",
            "ULTIMA_EXTENSIONS_LIST",
            // RIMOSSO: "ULTIMA_CURRENT_META_PROVIDERS",
            // RIMOSSO: "ULTIMA_CURRENT_MEDIA_PROVIDERS",
            "ULTIMA_WATCH_SYNC_CREDS"
        )
        .forEach { setKey(it, null) }
    }

    fun fetchExtensions(): Array<ExtensionInfo> = synchronized(allProviders) {
        val cachedExtensions = getKey<Array<ExtensionInfo>>("ULTIMA_EXTENSIONS_LIST")
        val providers = allProviders.filter { it.name != "Ultima" }

        providers.map { provider ->
            val existing = cachedExtensions?.find { it.name == provider.name }
            existing ?: ExtensionInfo(
                name = provider.name,
                provider.mainPage.map { section ->
                    SectionInfo(
                        name = section.name,
                        section.data,
                        provider.name,
                        false
                    )
                }.toTypedArray()
            )
        }.toTypedArray()
    }

    // RIMOSSO: listMetaProviders() - non serve più
    // RIMOSSO: listMediaProviders() - non serve più
}

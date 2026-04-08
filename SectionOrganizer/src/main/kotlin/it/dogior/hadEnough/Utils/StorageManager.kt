package it.dogior.hadEnough

import it.dogior.hadEnough.UltimaUtils.ExtensionInfo
import it.dogior.hadEnough.UltimaUtils.SectionInfo
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object UltimaStorageManager {
    var extNameOnHome: Boolean
        get() = getKey("ULTIMA_EXT_NAME_ON_HOME") ?: true
        set(value) { setKey("ULTIMA_EXT_NAME_ON_HOME", value) }

    var currentExtensions: Array<ExtensionInfo>
        get() = getKey("ULTIMA_EXTENSIONS_LIST") ?: emptyArray()
        set(value) { setKey("ULTIMA_EXTENSIONS_LIST", value) }

    fun save() {}

    fun deleteAllData() {
        listOf("ULTIMA_PROVIDER_LIST", "ULTIMA_EXT_NAME_ON_HOME", "ULTIMA_EXTENSIONS_LIST")
            .forEach { setKey(it, null) }
    }

    fun fetchExtensions(): Array<ExtensionInfo> = synchronized(allProviders) {
        val cachedExtensions = getKey<Array<ExtensionInfo>>("ULTIMA_EXTENSIONS_LIST")
        val providers = allProviders.filter { it.name != "Homepage" }

        providers.map { provider ->
            cachedExtensions?.find { it.name == provider.name } ?: ExtensionInfo(
                name = provider.name,
                provider.mainPage.map { section ->
                    SectionInfo(section.name, section.data, provider.name, false)
                }.toTypedArray()
            )
        }.toTypedArray()
    }
}

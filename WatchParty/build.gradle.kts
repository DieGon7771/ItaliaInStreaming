@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
   
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

dependencies {
    
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebSocket puro Kotlin, nessuna libreria nativa
    // compileOnly: solo per compilare contro le classi app; a runtime vengono dall'app
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
    // Solo per compilare contro androidx.media3.ui.PlayerView/Player (bridge
    // Nuvio Enhanced, vedi NuvioPlaybackBridge.kt)
    compileOnly("androidx.media3:media3-ui:1.8.0")
    compileOnly("androidx.media3:media3-common:1.8.0")
    // Solo per compilare contro MaterialAlertDialogBuilder/FloatingActionButton
    
    compileOnly("com.google.android.material:material:1.4.0")
    
    compileOnly("androidx.compose.material3:material3:1.2.1")
    compileOnly("androidx.compose.ui:ui:1.6.7")
    compileOnly("androidx.compose.runtime:runtime:1.6.7")
}


version = 5

android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        buildConfigField("String", "WATCHPARTY_RELAY", "\"${properties.getProperty("WATCHPARTY_RELAY").orEmpty()}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

cloudstream {
    authors = listOf("DieGon")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 

    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/WatchParty/WatchParty_icon.png"
    description = "⚠️ BETA ⚠️ Watch movies and TV series together in real-time with live chat (Up to 5 users)."
    requiresResources = true
}

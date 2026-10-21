@file:Suppress("UnstableApiUsage")

plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

dependencies {
    compileOnly("com.google.android.material:material:1.4.0")
    compileOnly("androidx.compose.material3:material3:1.2.1")
    compileOnly("androidx.compose.ui:ui:1.6.7")
    compileOnly("androidx.compose.runtime:runtime:1.6.7")
}

version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    authors = listOf("DieGon")

    status = 3

    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/AutoExtensions/AutoExtensions_icon.png"
    description = "Plugin that revives some of the extension settings removed from CloudStream"
    requiresResources = true
}

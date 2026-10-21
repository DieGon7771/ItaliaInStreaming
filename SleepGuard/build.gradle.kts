@file:Suppress("UnstableApiUsage")

plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

dependencies {
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.google.android.material:material:1.4.0")
    compileOnly("androidx.core:core-ktx:1.13.1")
    // Required to compile against MainActivity (it implements ColorPickerDialogListener)
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
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

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/SleepGuard/SleepGuard_icon.png"
    description = "Detects inactivity during playback (no touch, no input) and pauses to ask if you're still watching"
    requiresResources = true
}

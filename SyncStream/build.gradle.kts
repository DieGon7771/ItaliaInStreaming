@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.konan.properties.Properties

dependencies {
    implementation("com.google.android.material:material:1.4.0")
}

// use an integer for version numbers
version = 12


android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val secrets = Properties()
        val secretsFile = project.rootProject.file("secrets.properties")
        if (secretsFile.exists()) secrets.load(secretsFile.inputStream())
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${secrets.getProperty("GOOGLE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${secrets.getProperty("GOOGLE_CLIENT_SECRET") ?: ""}\"")
    }
}


cloudstream {
    // All of these properties are optional, you can safely remove them

    authors = listOf("DieGon")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified
    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/SyncStream/SyncStream_icon.png"
    description = "Sincronizza Cloudstream tra dispositivi"
    requiresResources = true
}
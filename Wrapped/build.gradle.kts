@file:Suppress("UnstableApiUsage")

dependencies {
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.google.android.material:material:1.4.0")
    compileOnly("androidx.core:core-ktx:1.13.1")
}

version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    authors = listOf("DieGon")

        status = 1

    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/Wrapped/Wrapped_icon.png"
    description = "Generate your CloudStream Wrapped: track total time, movies/series, your Nerd Rank, and get a shareable summary."
    requiresResources = true
}

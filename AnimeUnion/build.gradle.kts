version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    description = "Anime da AnimeUnion"
    authors = listOf("DieGon")

    /**
     * 0: Down, 1: Ok, 2: Slow, 3: Beta only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    requiresResources = true
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/AnimeUnion/animeunion_icon.png"
}

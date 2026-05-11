// use an integer for version numbers
version = 18


cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

    authors = listOf("doGior", "DieGon")
    requiresResources = true
    description =
        "Anime da AnimeWorld"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/AnimeWorld/animeworld_icon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.mozilla:rhino:1.7.15")
}

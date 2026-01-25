import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 6


cloudstream {
    // All of these properties are optional, you can safely remove them
    description =
        "Torrent da Il Corsaro Nero. If something doesn't work the torrent has probably not enough seeds"
    authors = listOf("doGior")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 2

    tvTypes = listOf("Movie", "Torrent")

    requiresResources = true
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/CorsaroNero/corsaronero_icon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API")}\"")
    }
}

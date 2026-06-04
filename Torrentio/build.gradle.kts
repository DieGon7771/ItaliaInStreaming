import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 8

cloudstream {
    description =
        "Torrent da Torrentio"
    authors = listOf("doGior","DieGon")

    // Status int as the following:
    // 0: Down
    // 1: Ok
    // 2: Slow
    // 3: Beta only
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Torrent", "Documentary")

    // TODO: when I find a fix for the SearchResult, I will add back the search function
    // setDataTypeLink = true (crossplatform) is needed for that
    // I also removed that function due to incompatibility with openSettings
    requiresResources = true
    language = "it"

    iconUrl = "https://torrentio.strem.fun/images/logo_v1.png"
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

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}

import org.jetbrains.kotlin.konan.properties.Properties

version = 15

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API3", "\"${properties.getProperty("TMDB_API3")}\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "it"
    description = "Addons di Stremio su Cloudstream"
    authors = listOf("DieGon")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/Stremio/stremio_icon.png"
}

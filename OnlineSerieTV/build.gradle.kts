// use an integer for version numbers
version = 3

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Movies and Shows from OnlineSerieTV"
    authors = listOf("doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Cartoon", "Anime", "Documentary")

    requiresResources = false
    language = "it"

    iconUrl = "https://onlineserietv.online/wp-content/uploads/2023/01/cropped-tv-1.png"
}

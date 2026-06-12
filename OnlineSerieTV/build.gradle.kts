// use an integer for version numbers
version = 2

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Film e SerieTV da OnlineSerieTV"
    authors = listOf("DieGon")

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

    iconUrl = "https://lingering-truth-455c.diegon7771.workers.dev/wp-content/uploads/2023/01/cropped-tv-1.png"
}

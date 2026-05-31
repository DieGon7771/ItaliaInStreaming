// use an integer for version numbers
version = 2



cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Film e SerieTV da IlGenioDelloStreaming"
    authors = listOf("DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 0

    tvTypes = listOf("Movie", "TvSeries", "Cartoon", "Documentary")

    requiresResources = false
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/IlGenioDelloStreaming/IlGenioDelloStreaming_icon.png"
}

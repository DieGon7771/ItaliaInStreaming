// use an integer for version numbers
version = 17


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Anime da AnimeUnity"
    authors = listOf("doGior","DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    language = "it"
    requiresResources = false

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/AnimeUnity/animeunity_icon.png"
}

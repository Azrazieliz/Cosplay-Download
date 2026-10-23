package com.azrael.galleryflow

object AdapterRegistry {
    private val http = HttpClient()

    // Direct-media sources run first so Archive All produces real files quickly.
    val adapters: List<SourceAdapter> = listOf(
        BuonDuaAdapter(http),
        KiutakuAdapter(http),
        FourKGirlAdapter(http),
        EveriaAdapter(http),
        FourKhdAdapter(http),
        CosplayTeleAdapter(http)
    )

    fun forUrl(url: String): SourceAdapter =
        adapters.firstOrNull { it.matches(url) }
            ?: throw AdapterException("No Kyora adapter recognizes this URL.")

    fun forSource(source: SourceId): SourceAdapter =
        adapters.first { it.source == source }

    fun defaultEntities(): List<SourceEntity> =
        adapters.filter { it.enabled }.flatMap { it.defaultEntities() }
}

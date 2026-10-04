package com.azrael.galleryflow

object AdapterRegistry {
    private val http = HttpClient()
    val adapters: List<SourceAdapter> = listOf(
        KiutakuAdapter(http),
        BuonDuaAdapter(http),
        FourKhdAdapter(http),
        CosplayTeleAdapter(http)
    )

    fun forUrl(url: String): SourceAdapter =
        adapters.firstOrNull { it.matches(url) }
            ?: throw AdapterException("No GalleryFlow adapter recognizes this URL.")

    fun forSource(source: SourceId): SourceAdapter =
        adapters.first { it.source == source }

    fun defaultEntities(): List<SourceEntity> =
        adapters.filter { it.enabled }.flatMap { it.defaultEntities() }
}

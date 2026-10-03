package com.azrael.galleryflow

object AdapterRegistry {
    private val http = HttpClient()
    val adapters: List<SourceAdapter> = listOf(
        KiutakuAdapter(http),
        AuditedPlaceholderAdapter(
            SourceId.FOUR_K_HD,
            setOf("4khd.com", "www.4khd.com"),
            "Audited: /content/... galleries and /pages listings; implementation pending after Kiutaku validation."
        ),
        AuditedPlaceholderAdapter(
            SourceId.BUONDUA,
            setOf("buondua.net", "www.buondua.net"),
            "Audited: /tags/... entity pages, ?page=N pagination and /albums/... galleries; implementation pending."
        ),
        AuditedPlaceholderAdapter(
            SourceId.COSPLAYTELE,
            setOf("cosplaytele.com", "www.cosplaytele.com"),
            "Audited: WordPress-style gallery posts and creator/category archives; implementation pending."
        )
    )

    fun forUrl(url: String): SourceAdapter =
        adapters.firstOrNull { it.matches(url) }
            ?: throw AdapterException("No GalleryFlow adapter recognizes this URL.")

    fun forSource(source: SourceId): SourceAdapter =
        adapters.first { it.source == source }
}

class AuditedPlaceholderAdapter(
    override val source: SourceId,
    private val hosts: Set<String>,
    override val statusLabel: String
) : SourceAdapter {
    override val enabled: Boolean = false
    override fun matches(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host?.lowercase().orEmpty()
        hosts.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    private fun unavailable(): Nothing = throw AdapterException(statusLabel)
    override fun resolveEntity(inputUrl: String): SourceEntity = unavailable()
    override fun enumerateGalleries(entity: SourceEntity): Sequence<GalleryRef> = unavailable()
    override fun fetchGallery(gallery: GalleryRef): Pair<GalleryMeta, List<MediaRef>> = unavailable()
}

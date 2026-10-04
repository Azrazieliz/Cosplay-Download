package com.azrael.galleryflow

interface SourceAdapter {
    val source: SourceId
    val enabled: Boolean
    val statusLabel: String

    fun matches(url: String): Boolean
    fun defaultEntities(): List<SourceEntity> = emptyList()
    fun resolveEntity(inputUrl: String): SourceEntity
    fun enumerateGalleries(entity: SourceEntity): Sequence<GalleryRef>
    fun fetchGallery(gallery: GalleryRef): Pair<GalleryMeta, List<MediaRef>>
}

# Kyora

**Kyora** is an Android-first, multi-source cosplay/gallery archiver. It crawls supported sites, downloads direct media gallery-by-gallery, verifies physical files, deduplicates exact matches, and keeps Live Sync separate from historical backfill.

## Supported sources

- **BuonDua** — direct album images.
- **Kiutaku** — direct gallery images from the Mitaku CDN.
- **4KGirl** — direct multi-page cosplay sets.
- **Everia** — direct cosplay galleries, including paginated Karubox-hosted originals.
- **4KHD** — direct browsable album images first; TeraBox only as fallback.
- **CosplayTele** — direct images and any directly embedded video. Provider-only videos are best-effort and do not make a working image gallery fail.

4KGirl and Everia were selected as new sources because they add substantial directly browsable catalogs without being aliases of an existing Kyora source. Mirror/alias candidates such as Mitaku are not added as separate sources when they would largely duplicate an existing adapter.

## Core behavior

- one global Live Sync / Archive Backfill engine;
- sequential gallery processing rather than cataloguing hundreds of failures first;
- cooperative pause/resume/stop and queued Live checks;
- Android JobScheduler periodic Live checks;
- SQLite catalog with explicit transfer states;
- physical file existence is authoritative for completion;
- storage remains under `Downloads/Cosplay/GalleryFlow/` for compatibility with existing installs;
- SHA-256 physical deduplication plus image perceptual-hash recording;
- cross-source provenance and explicit alias support;
- FlowLink `MEDIA_DOWNLOADED` / `GALLERY_DOWNLOADED` events;
- no access-control/paywall bypass.

The Android product is branded **Kyora** from v0.6 onward; the package name, database name, and storage directory remain compatible with previous GalleryFlow test builds so existing downloads and state are not discarded.

Kuroha (`Azrazieliz/Pixiv-downloader`) remains a reference only for proven queueing, scheduling, pause/resume/stop, and disk-authoritative completion patterns.

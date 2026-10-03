# GalleryFlow

GalleryFlow is an Android-first multi-source image/gallery archiver.

Initial adapters:
- 4KHD
- BuonDua
- Kiutaku
- CosplayTele

Implementation order starts with Kiutaku end-to-end, then generalizes the same adapter contract to the other sources.

Core invariants:
- one global job engine;
- Live Sync + Archive Backfill;
- physical file existence is authoritative for completion;
- crash-safe SQLite state;
- content hash deduplication;
- provenance retained for every gallery/media item;
- source failures are isolated;
- no paywall/authentication bypasses.

The Kuroha/Pixiv downloader repository is used only as a reference for queueing, pause/resume/stop, Android scheduling, and on-disk completion checks.

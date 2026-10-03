# GalleryFlow

GalleryFlow is an Android-first multi-source image/gallery archiver built as one application with source adapters.

Initial sources:

- **Kiutaku** — first end-to-end adapter, enabled.
- **4KHD** — audited, isolated placeholder until the first adapter is validated.
- **BuonDua** — audited, isolated placeholder until the first adapter is validated.
- **CosplayTele** — audited, isolated placeholder until the first adapter is validated.

Core behavior already implemented:

- Live Sync and Archive Backfill;
- one global foreground job engine with cooperative pause/resume/stop;
- queued/coalesced Live checks and Backfill yielding;
- Android JobScheduler periodic Live checks;
- SQLite catalog and exact transfer states;
- physical file existence is authoritative for completion;
- MediaStore storage under `Downloads/GalleryFlow/`;
- SHA-256 physical deduplication plus non-destructive perceptual-hash recording;
- source provenance and explicit alias table;
- JSON/CSV export;
- FlowLink `MEDIA_DOWNLOADED` / `GALLERY_DOWNLOADED` events;
- no authentication/paywall bypass.

See `docs/SOURCE_AUDIT.md` and `docs/ARCHITECTURE.md`.

Kuroha (`Azrazieliz/Pixiv-downloader`) is used only as a reference for proven queueing, scheduling, pause/resume/stop and disk-authoritative completion patterns; Pixiv-specific assumptions are not copied into the adapters.

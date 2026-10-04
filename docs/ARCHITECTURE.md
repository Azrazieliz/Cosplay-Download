# GalleryFlow architecture

## Adapter contract

Every source adapter implements:

1. `resolveEntity` — turn a user-facing source URL into a stable source-native identity.
2. `enumerateGalleries` — enumerate canonical gallery references for Live/Backfill.
3. `fetchGallery` — fetch metadata and enumerate media with stable IDs.
4. `matches` — isolate URL routing to the owning adapter.

A broken adapter cannot prevent other source adapters from running.

## Global sync engine

- Exactly one foreground sync run is active at a time.
- Live requests arriving during another run are coalesced into one pending Live pass.
- Backfill yields to a pending Live pass at safe checkpoints and resumes.
- Pause/resume/stop are cooperative and network requests are disconnected on stop.
- Android JobScheduler performs periodic Live checks.

## Completion invariant

Database state is never sufficient evidence of completion. A media record is complete only when its stored content URI can still be opened. Missing physical files are reset to pending and downloaded again.

A gallery is complete only when every expected media record is complete and every referenced physical file exists.

## Storage and dedupe

Default root:

`Downloads/GalleryFlow/<source>/<entity>/<gallery>/`

Downloads are streamed through MediaStore. SHA-256 is computed while writing. If a matching complete physical file already exists, the new duplicate is deleted and the media record points to the existing file. A 64-bit average perceptual hash is also recorded for later near-duplicate review; it is not used for silent destructive merging.

## Catalog and provenance

SQLite stores:

- entities and Live cursors;
- galleries, source URL, title, tags, publication/retrieval time and state;
- media URL, referer, hash, perceptual hash, content URI, bytes and state;
- explicit cross-source identity aliases;
- FlowLink event records.

JSON and CSV exports are written under `Downloads/GalleryFlow/exports/`.

## FlowLink

`MEDIA_DOWNLOADED` and `GALLERY_DOWNLOADED` are persisted in SQLite and emitted through the `com.azrael.galleryflow.FLOWLINK_EVENT` Android broadcast. GalleryFlow does not depend on Asterion Core to download or catalog media.

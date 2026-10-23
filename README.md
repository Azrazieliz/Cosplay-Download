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

## Kyora 0.7.0 — direct TeraBox backup and periodic sync

### Unified TeraBox destination (no album folders)

Under **TeraBox → Configure**, enable cloud uploads and enter an approved
**TeraBox Open Platform OAuth access token** and the full path of a **pre-existing
shared folder inside that app's assigned sandbox**. Kyora does not ask for
TeraBox website login credentials. Open Platform credentials require an
authorized TeraBox developer application.

All completed media (images, videos, archives and extracted files) upload to
the **same flat folder**. E.g.:
`/From: Other Applications/YourApp-ID/Kyora/kyora_kiutaku_123_page_0_abcd....jpg`

Kyora prefixes source identity, media ID and 24 SHA-256 characters to prevent
files with identical album filenames such as `0001.jpg` from overwriting
one another. **Only the cloud path is flattened**: existing local
`Downloads/Cosplay/GalleryFlow/<source>/<entity>/<album>/` files and SQLite
records are unchanged.

A separate durable cloud SQLite outbox scans already downloaded gallery media
and runs after Live Sync / Backfill. **Upload archive** performs a foreground
cloud-only pass on existing files (up to 1000 pending per invocation).
Failures are retained for retry no sooner than 15 minutes later, and successful
uploads require TeraBox's confirmation of exact path, size and file ID.
Local gallery success/bookmark/download states are never invalidated by cloud
failures. Cloud backup is opt-in and off by default.

Uploads use the official TeraBox Open Platform
[precreate → superfile2 → create](https://www.terabox.com/integrations/docs/chinese)
API, with region-specific hosts obtained from `/oauth/tokeninfo` and
MD5-verified chunks. Access tokens are AES-GCM encrypted using Android
Keystore. Token expiry is surfaced as an upload error; **initial OAuth
authorization and token refresh are not built into the app** because
developer partner credentials are needed. This is client-device-to-cloud
upload after local download, not server-to-server source transfer. The
application's shared cloud folder must already exist.

### Automatic download + upload every ~15 minutes

The existing persistent JobScheduler job remains at **15 minutes** with a
network constraint (the OS controls its exact start). Its job handler now
executes Live Sync inside the real JobService lifecycle, rather than launching
a background foreground service and immediately marking the job complete.
Live Sync discovers/downloads new galleries from enabled sources; when
TeraBox is configured and enabled, it drains up to 12 pending uploads
after the download pass. Manual **Archive all** still backfills older
galleries; initial Live Sync establishes cursors and does not backfill
history. The Android OS may defer periodic jobs due to Doze, battery
optimization, execution timeouts and quotas. A guaranteed wall-clock
15-minute cadence is not possible. Foreground user actions remain available
for longer downloads and full archive uploads.

Kyora 0.7.0 retains `com.azrael.galleryflow`, the existing database
migration version and local download path. The GitHub debug APK is only
upgrade-compatible with an existing install **if its signing certificate
matches**; do not uninstall an existing build to work around signing errors
without first exporting application state.

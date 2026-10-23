# Kyora: flat images, exclusions, and TeraBox via Round Sync

## Gallery storage
All **new** gallery image files are written to `/storage/emulated/0/Download/GalleryFlow/`, without source/cosplayer/gallery subfolders. Existing saved files retain their recorded URIs and are never moved or deleted by this change. Filename format is `source_galleryId_p0001_mediaId.ext` (stable/collision-resistant). Exported JSON/CSV stays in its existing exports subdirectory.

## Tag exclusions
Open **Edit excluded tags**. Enter one exact gallery tag per line. Matching is case-insensitive, Unicode NFKC normalized and whitespace-normalized, not substring based. The tag check runs after reading gallery metadata and **before downloading images**. A skipped gallery is persisted as SKIPPED with the matched tag and advances Live tracking. Removing a tag allows Backfill to reconsider it. Previously downloaded files are not removed.

## TeraBox backup using Round Sync
1. Use a Round Sync build that supports your TeraBox remote, such as the third-party rclone-extra fork: https://github.com/gulp79/Round-Sync . Ordinary upstream rclone may not have a TeraBox backend.
2. Create the TeraBox remote and configure it inside Round Sync using its own authentication. The fork accepts a TeraBox `ndus` cookie or full cookie as documented at https://github.com/uiurin304/rclone-extra/blob/master/docs/content/terabox.md . **Never paste the cookie into a chat, repository, or project build.**
3. Create a Round Sync **COPY** task from local **/storage/emulated/0/Download/GalleryFlow** to the desired TeraBox folder. Do **not** use mirror/sync deletion.
4. In Round Sync open the task menu to find and copy its numeric task ID.
5. In Kyora, open **Round Sync settings**, set task ID, enable automatic backup requests, and save. Use **Run TeraBox backup now** to request a run manually.

When new images finish downloading, Kyora requests the configured Round Sync task via its published Android intent. The request is not a cloud completion receipt; verify the transfer in Round Sync. This is a **local-cache -> cloud copy** workflow, not zero-copy network streaming. If your fork uses another package name or removes its exported task service, the request may fail and the app reports the error. Local source files are retained to prevent data loss. Android periodic checks at 15 minutes are best-effort, not exact timing.

## Verification gaps
TeraBox account authentication, cookie validity, Round Sync task setup, external service invocation on the phone, cloud file integrity and signed production APK installation require device-side verification.

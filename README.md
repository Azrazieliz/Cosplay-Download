# VideoFlow — Android video archiver

Separate application ID: `com.asterion.videoflow`. Does not modify Kuroha or Kyora.

## Included

- Direct page URL download with yt-dlp 2.0.2 embedded on-device; generic media URL fallback.
- Search query → automatic first matching video queue; optional visible result selection.
- Seeded sites: Pornhub, NotFans, ArchiveBate, FreePornVideos.xxx.
- Saved URL/feed/profile/search watches with scheduled check (Android JobScheduler, OS decides exact execution).
- Persisted SQLite download queue, physical MediaStore Downloads/VideoFlow save, progress, retry, failure diagnostics and archive de-dup by URL.
- Background foreground-service transfer process for manually started downloads.
- Import browser cookies in Netscape format via Android file picker; saved in private app storage.
- Optional Round Sync COPY task request after queue processing (task ID user-configured). Requesting a task is not verification of upload to TeraBox.
- New independent release signing identity, retained for update-in-place.

## Source-specific validation (2026-10-23)

- **NotFans:** a public `/videos/` sample exposed 1 format through yt-dlp Generic metadata extraction. No Android playback/download verified.
- **FreePornVideos.xxx:** a public `/videos/` sample exposed 3 formats through yt-dlp HTML5MediaEmbed. No Android playback/download verified.
- **Pornhub:** dedicated yt-dlp extractor exists; first sampled public page returned HTTP 410 and was not downloadable in the test. Additional videos and cookies may behave differently.
- **ArchiveBate:** public homepage exposes categories/platform navigation, but no direct video page links during audit. Auto-discovery needs a viable source URL, site endpoint or authenticated feed. No site-specific media extraction verified.
- Other websites: general-purpose support is best-effort; no universal compatibility guarantee.

Test results: `docs/extractor-audit.json`. The test probes metadata, not actual downloads.

## Installation/build

On the authorized Windows machine:
1. PowerShell: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1`
2. Install `app\build\outputs\apk\release\app-release.apk` on arm64 Android 10+.
3. Retain BOTH `%USERPROFILE%\.asterion-keys\videoflow-release.jks` and `videoflow-release-password.dpapi` for all future signed updates. The password file uses user-bound Windows DPAPI; make a protected backup.
4. Configure watched page/profile URLs, optionally cookies, max per scan and Round Sync COPY task.

## Limits

- No DRM decryption, premium-access circumvention or access-control bypass.
- Best existing combined format is used by default; does not splice separate high-resolution video+audio tracks without FFmpeg.
- Background JobScheduler runs approximately every 15 minutes or later, at Android's discretion; power optimization may defer runs.
- Android runtime validation has not been performed without an attached device/emulator.
- The user must have permission to download and store content. No site account is required by the app itself.

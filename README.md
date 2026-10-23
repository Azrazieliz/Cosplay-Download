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

## Performer-first full-video watches (v0.2.0)

- Add any performer name in **Performer monitoring**. All 45 preconfigured site candidates are searched by default. A comma-separated list of domains can limit an individual watch, including custom domains outside the catalog.
- Each automatic check processes up to 4 sites per performer, rotating through all selected domains over successive JobScheduler checks; it does not bombard all sites simultaneously.
- Each discovered video page is probed with the embedded yt-dlp engine to read its actual duration and metadata. Only results at or above the configured duration threshold (default 10 minutes) with performer-name evidence are queued. Unknown-duration results are deliberately skipped.
- Discovery sites, extractor status and actual download compatibility are **not equivalent**. Many custom sites or age-gated sites may be blocked or unsupported. The original four default homepages are present but paused to prevent unrelated automatic downloads.
- The original manual URL, search, queue, storage, cookie and Round Sync workflows remain available.
- **Phone-first builds**: GitHub Actions workflow `.github/workflows/videoflow-mobile.yml` creates an installable arm64 debug APK as an artifact on branch `videoflow-mobile`. This debug build is separately signed and cannot update a previously installed production-signed v0.1 APK without the matching production key. Keep the original release signing key outside any public repository.

## Tor hidden-service discovery (v0.6.0)

- **Phone first:** Install and connect [Orbot](https://orbot.app/) on Android. VideoFlow does not install Tor or use a PC.
- In **Discover → Include dark-web (.onion) sources**, opt in, then set Orbot's local SOCKS port in **Tor connection settings** (9050 is common but not universal). The connection-test button tests only whether the local socket is accepting connections, **not** whether the Tor circuit is healthy.
- Discovery queries the publicly operated Ahmia Tor index **through the SOCKS proxy** and examines individually supplied v3 onion source pages (saved under **Sites → Add Page or Profile**). Ahmia applies its own content-safety filters and may return no results for adult queries; those filters are not bypassed.
- Results must match the user's specific keywords and prove both adult content and a video player on the hidden-service page. Listings are not verified for licensing, legality, download feasibility, or authenticity.
- Onion results are labeled TOR and their URLs can be copied to Tor Browser. **No automatic download is performed merely by searching.** Manually queued `.onion` transfers are routed with `socks5h://127.0.0.1:PORT`. If Orbot is off or the port is unavailable, the transfer fails rather than connecting directly.
- Regular open-web searches still use the normal network. Enabling onion results does not anonymously proxy all of VideoFlow. Sites may require authentication, reject automated retrieval or use media DRM that the app does not bypass.
- This feature is not a universal dark-web crawler. The public index and user-specified sources only cover a subset of hidden services; search completeness is impossible to guarantee.
- Use it only for legal, consensual media you are authorized to access and save.


## v0.7: Connection repair and honest search coverage

- The prior connection button only checked TCP 127.0.0.1:9050. That was not a Tor verification. The new **Test Tor Connection** tries the saved port plus 9050 and 9150, tests the actual Tor route using `https://check.torproject.org/api/ip` **over SOCKS**, and displays whether Tor was verified. A successful response with `IsTor: true` is required for the verified status. The test runs on a background thread.
- For proxy mode, open Orbot on Android, enable Power User Mode, connect, verify its SOCKS setting, then return to VideoFlow. A different VPN or Orbot background restrictions can prevent connection.
- Ahmia publicly states it filters sexually related queries; v0.7 does **not** try to evade those filters. Tor adult-video discovery is now limited to explicitly added v3 onion video/source pages. Without those pages the UI explains why there can be no results; it no longer treats an empty Ahmia response as an exhaustive search.
- Ordinary adult web discovery now includes more first-party search endpoints and per-provider failures/number of inspected candidates are shown, so results missing due to captcha/HTTP errors are distinguished from irrelevant matches.
- Adult-category words `porn`, `xxx`, `adult`, `nsfw` etc. are interpreted as subject constraints, not quietly dropped; specific other keywords must still match the video's title/evidence.
- No actual Tor or website compatibility is guaranteed until verified on a connected Android device. This is a separate preview app to protect earlier installed data.

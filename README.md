# VideoFlow Direct — Android video download manager

Version **0.8.0**. Phone-first, four-tab Android interface.

## Features

- **Home:** Paste a direct video page URL or MP4/WebM/HLS URL to queue one download. Android text share intent also works.
- **Downloads:** Persistent queue, progress, open saved files, retry failed items, remove history entries, human-readable failure reasons.
- **Sources:** Optional automatic checks of explicitly saved public HTTP(S) video listing/profile pages. No broad internet-wide discovery, models, keyword searches or Tor. Disabled sources stay paused.
- **Settings:** Format preference, cookies.txt for services that accept it, optional Round Sync task integration for TeraBox.
- Outputs are published to `Downloads/VideoFlow/` via Android MediaStore.
- Foreground notification service processes downloads and JobScheduler handles best-effort periodic source checks.

## Deliberately removed

The following functionality has been removed **from the source and app**: Tor/Orbot, onion access, Tor network permissions and configuration, keyword search, general search engines, adult-site catalog, and model/performer-based keyword scanning.

**Not removed:** Direct HTTP(S) video extraction and source-specific link monitoring explicitly requested by the user.

## Installation

Cloud builds publish an ARM64 debug-signed APK to GitHub Actions for the branch `videoflow-mobile`.
The experimental build uses a new package ID, `com.asterion.videoflow.direct08`, to avoid overwriting older test apps signed using different certificates. This means the older app and any of its settings/history are **not deleted**. Previously saved files remain in `Downloads/VideoFlow/`.

Future updates should use a persistent release signing key. Do not upload signing keys or credentials to GitHub.

## Known limitations

- Site extraction depends on video page layout, authentication and server behavior; not every website works.
- Automatic page monitoring only follows recognizable video links on the configured page and may miss JavaScript-based pagination or authenticated content.
- Website DRM and paywall controls are not bypassed.
- Android's 15-minute JobScheduler interval is a minimum, not a guarantee of exact execution.
- Cloud builds and unit tests do not prove on-device download completion.

Build: `gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`.

# GalleryFlow source audit — 2026-10-04

This is an implementation audit, not a license/ownership statement. GalleryFlow only uses content reachable through normal site access and does not bypass authentication or paywalls.

## Kiutaku — enabled first

- Current domain: `https://kiutaku.com/`.
- Gallery URLs are numeric, e.g. `/7333`, `/7331`.
- Stable gallery ID: the numeric path component.
- Tag/entity pages use `/tag/<numeric-id>`.
- Stable entity ID: `tag:<numeric-id>`.
- Gallery pages expose title, publication date, cosplayer/tags and paginated image sets.
- Adapter follows pagination links discovered from the page rather than hard-coding a page-count rule.
- A gallery URL can be added directly; when its cosplayer tag can be resolved it is promoted to the stable tag identity, otherwise it is treated as a gallery-only entity.

## 4KHD — audited, disabled until Kiutaku validation

- Current primary domain: `https://www.4khd.com/`.
- Listing pages include `/pages/cosplay` and `/pages/album`.
- Listing pagination observed as `?query-3-page=<N>`.
- Gallery URLs use `/content/<bucket>/<slug>.html`.
- Gallery pagination observed as `<canonical>.html/2`, `/3`, etc.
- Provisional stable gallery ID: canonical content path (`bucket/slug`).
- The models index exists, but model/entity identity must be validated before enabling automatic backfill.

## BuonDua — audited, disabled until Kiutaku validation

- Current primary domain: `https://buondua.net/`.
- Tag/entity pages use `/tags/<slug>` with optional language prefix such as `/th/tags/<slug>`.
- Tag pagination observed as `?page=<N>`.
- Gallery URLs use `/albums/<slug>-<opaque-suffix>`.
- Gallery pages expose title, tags, publication date, image count and direct image links hosted on `cdn.buondua.net`.
- Provisional stable gallery ID: opaque suffix when present, otherwise canonical album path.

## CosplayTele — audited, disabled until Kiutaku validation

- Current primary domain: `https://cosplaytele.com/`.
- Gallery posts use WordPress-style slugs such as `/yuzuriha-3/` and `/feixiao-4/`.
- Posts expose cosplayer, character/franchise metadata, photo/video counts and normal external download links when offered by the site.
- Stable gallery ID: canonical post path.
- Creator/category archive identity needs validation before automatic backfill is enabled.

## Cross-source identity

No alias is silently merged. Source-native entity IDs remain authoritative. Cross-source aliases belong in `identity_aliases` with an explicit confirmation/confidence field.

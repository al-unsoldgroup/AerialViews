# APK implementation research

## Baseline

Aerial Views is cloned at `aerialviews/`, upstream commit `a1b47e8c999d8e8e75e7b7c61236538bf512a632`. It is a Kotlin Android TV screensaver with Media3 video, Coil images, Room and source providers. It targets SDK 37 and requires JDK 21 and the Android SDK. Its project instructions select `./gradlew :app:testBetaDebugUnitTest :app:spotlessCheck :app:assembleBetaDebug` for code verification. This machine exposes Java but no `adb` or configured Android SDK in the inspected environment; no APK was built. The clone is unchanged.

The repository is GPL-3.0; preserve its license and evaluate distribution obligations before sharing a modified APK. A distinct application ID and signing identity would permit a personal build alongside upstream; screen-saver registration must reference its actual component.

## What can be reused

- The existing `DreamActivity`, test activity and settings provide screensaver entry points and remote interaction. Media3 and image playback are existing seams, not proof of the desired timing or offline semantics.
- `ImagePlayerView` and `BitmapHelper` extract date, GPS and description from image headers when present. That does not prove Google Picker returns those bytes or metadata.
- The app supports custom CSV image URLs/descriptions, Immich and other network sources. CSV is too limited to assume rich per-item context, deletion, authorization and collages; investigate a dedicated authenticated provider and versioned manifest.
- Room playlist caching exists, but is not evidence that image/video bytes are available offline. Verify actual media cache, eviction and deletion on device.

## Ingestion and privacy

Google Picker requires an OAuth user session, creation of a selection session, user selection in Google, polling, listing and downloading selected items, then cleanup. Picker URLs expire and require bearer authorization. Picker metadata documents dimensions and creation time, but does not list GPS. Test actual downloaded files and metadata before relying on EXIF. Google does not provide a supported general mechanism for continuously tracking removal from the original library. A frame-side delete must remove backend originals, transformed delivery access and local cache on reconnect. Verify Cloudflare cache purge and retention behavior separately.

The Picker policy and OAuth configuration must be reviewed for this exact long-lived personal frame use, storage and verification requirements before implementation or production use. Do not treat legacy policy pages or a working OAuth flow as proof of approval.

## First implementation slice

1. Establish a tracked project and Android build environment; build the unmodified beta debug APK and record SDK/JDK versions and baseline checks.
2. Pair the physical TV on its local network and install a separately identified debug build. Verify screensaver start, remote input and restoration. The VPS requires a network route to the device; no connection is assumed.
3. Define a minimal private manifest containing stable item ID, media type, short-lived authenticated media access, caption, focus/crop, duration and deletion revision. Test expiration and reconnect. Keep cloud credentials out of the APK.
4. Prove one manually supplied photo and video through that interface; test the requested mute and slide timing, then add bounded persistent offline caching and tombstone reconciliation.
5. Prototype Picker selection and metadata on representative personal items. Verify long-term storage policy and 4K quality before wiring Cloudflare/Krill. Compare Cloudflare auto and face gravity with manual focus using the same photos.
6. Build phone and TV controls, collage composition, trip/art matching and hosted dashboard in subsequent slices. Dashboard WebView support, private authentication, schedules and a genuinely retained offline view remain unverified. Avoid exposing device credentials or unrestricted navigation to an arbitrary page.

## Research sources

- [Aerial Views](https://github.com/theothernt/AerialViews)
- [Google Picker sessions](https://developers.google.com/photos/picker/guides/sessions)
- [Google Picker media items](https://developers.google.com/photos/picker/reference/rest/v1/mediaItems)
- [Google Photos API policy](https://developers.google.com/photos/support/api-policy)
- [Cloudflare Images transformation options](https://developers.cloudflare.com/images/optimization/features/)

This is a starting technical assessment, not a completed implementation or an on-device validation.

## Krill integration investigation

Tracked as [UNS-1745](https://linear.app/unsoldantarctica/issue/UNS-1745). The upstream app already defaults left/right to previous/next. Its existing CSV feed only supports URL, description and file-extension-based media detection. Krill's authenticated media endpoint returns paginated `data`, `total`, `offset` and `hasMore`; its public endpoint aggregates public spaces. A personal frame must not make a space public merely to feed the TV.

Requests using Python's default user agent returned Cloudflare error 1010. Retesting with an identifiable application user agent returned HTTP 200 for the health endpoint and the existing public photo API. The TV validated 4,367 public photos and reached the feed. A broad admin token must not be embedded in an APK or put in a feed URL. A separate personal library still needs narrowly scoped, revocable access and a verified private media-delivery model.

The working fork branch is `uns-1745-krill-picture-frame` in the local Aerial Views clone. A public Krill source is under development. The first TV test found filename-based deduplication collapsed the source to one image; the revised build retained all 4,367 items and rendered a photo. Photo options, up/down mode switching, composition, cache and deletion behavior remain implementation work.

The first 100 current public API results had no width or height values. The opt-in crop now measures such photos during playback and requests an automatic-focus crop only for sufficiently wide images; if the crop fails, it keeps the original. This fallback has unit coverage and still needs device verification. A sampled public image was served at 800 pixels wide; the transformation preserves that source limit rather than manufacturing 4K detail. Secure high-resolution access and subjective crop quality remain open.

On-device logs subsequently confirmed the dimension probe chose a crop. An authenticated request to Krill's download endpoint returned 2278 × 1518 for a sample capped to 800 pixels on the public proxy. A dedicated revocable device credential or a phone-based login must be designed before putting authenticated access on the TV; the available admin token is not suitable for the APK. The upstream image proxy's session-cookie presence check is not a secure authenticated delivery mechanism and must not be relied on for private media.

Clerk's device authorization discovery advertises the device-code grant for this instance. This may support TV login through a phone, but Krill currently calls `authenticateRequest` without opting into OAuth tokens, and its authorization, scopes, private-media checks and token storage must be implemented and tested before using it. [Clerk device authorization](https://clerk.com/docs/guides/configure/auth-strategies/oauth/device-authorization-grant) · [OAuth token verification](https://clerk.com/docs/guides/configure/auth-strategies/oauth/verify-oauth-tokens)

The next Krill photo now preloads into a bounded image memory cache. TV logs confirmed the next displayed image came from memory. This reduces network waiting but does not yet establish a seamless transition or offline playback. The debug app is selected as the system screensaver; internet and overlay permissions are granted and its boot preference is on. A real reboot confirmed the frame launched after the app-ops setting was explicitly persisted. Startup was slow; idle-triggered screensaver activation is not yet verified.

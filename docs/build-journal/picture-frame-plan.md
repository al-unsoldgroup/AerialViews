# Personal picture frame: product and feasibility plan

## Goal

Turn a Google TV 4K dongle connected to The Frame into a personal picture frame. Select photos from Google Photos, show curated artwork and moving pictures, and display a hosted dashboard. Favor attractive compositions that preserve the subject, with control from both the TV and a phone-friendly web app.

## Agreed experience

- **Sources:** Select Google Photos with Google's Photos Picker. Selected items join the rotation until removed. The first art milestone uses art Al personally selects and uploads; curated public collections with suitable reuse rights are a later milestone. Support photos and video.
- **Composition:** Use Cloudflare Images for appropriately sized images and automatic focus. Test automatic and face-aware cropping. Supply manual focus and crop overrides. Art fills the screen by default, with an adjustable crop.
- **Collages:** Automatically combine compatible photos when useful, favoring the same trip or occasion while allowing visually compatible alternatives. Allow users to create and adjust groupings. Show a photo alone with a tasteful border when a good composition is unavailable.
- **Context:** Extract available EXIF, capture date and GPS; show a readable location. Suggest trip matches from location and date, with correction. Suggest artwork identification and catalogue details, with correction or rejection. Omit missing or uncertain information. Show captions briefly and subtly.
- **Modes:** Photos, Art, Mixed and Dashboard. Mixed has an adjustable photo/art balance. Dashboard displays a selected hosted URL, started manually or on a schedule.
- **Video:** Start muted with an unmute control. Loop shorter videos until the slide duration expires; stop longer videos at that duration.
- **Control:** The TV handles everyday playback and current-item actions, including removal from rotation. The phone-friendly web app handles detailed library, crop, grouping, schedule and settings management, and is the only place for permanent deletion.
- **Remote:** Left and right move to the previous or next image or video. Up and down change between Photos, Art, Mixed and Dashboard modes. A long press of the centre button opens options for the current photo, including removal from the rotation. Resolve dashboard navigation and collage-editing conflicts during implementation.
- **Startup:** Make the frame the default screensaver and open it when the TV boots, while retaining a way to return to the normal TV interface. Grant only permissions needed for these functions.
- **Delivery:** Log in to Krill for the user's permitted library and higher-resolution images. Preload upcoming media and transition without loading screens. Pair portrait photos in a landscape collage where suitable.
- **Offline:** Continue displaying locally cached photos, art and videos. For a dashboard, retain its last view when that is reliable; otherwise return to cached media and indicate that the dashboard is offline.
- **Deletion:** Deleting an item in the frame app removes it from rotation, Cloudflare storage and TV caches. Try to detect Google Photos deletions; if unavailable, remove the frame copy manually. Never delete the original Google Photos item as a consequence of frame deletion.

## Proposed architecture

Google Photos Picker and selected art feed a private frame library, provisionally a personal Krill instance. Krill tracks source items, metadata, compositions, modes and deletion state. Cloudflare stores and delivers media at TV-appropriate sizes; the TV app maintains a bounded offline cache. A TV screensaver consumes the rotation and handles playback and controls. Aerial Views is the first candidate, not a committed implementation: its custom feed, metadata, controls, video, cache and dashboard support must be checked against the full experience. Compare with Immich Android TV and a purpose-built or extended client if needed.

Do not assume a Google Photos album stays synchronized: the Picker is a user-initiated selection workflow. Determine how video ingestion, authentication, storage privacy, cost and cache invalidation work before deployment.

## Feasibility gates

1. **TV access:** Pair over Wi-Fi with ADB, identify device and OS, install a test app and verify screensaver activation and recovery. The controlling machine must reach the TV's network; a remote VPS may need an authorized network path.
2. **Picker and metadata:** Select representative edited photos, portraits, landscapes and videos. Verify obtainable image quality, EXIF/GPS, dates, picker authorization, import and deletion detection. Test missing GPS and metadata fallback. Do not infer trip or location when evidence is insufficient.
3. **Cloudflare composition:** Compare automatic and face-aware crops on representative photos, including groups, landscapes and artwork. Test manual focal points, aspect ratios, resolution, cost and actual caching behavior. Define when crop loss calls for a collage or border.
4. **Library and deletion:** Inspect Krill's current upload, private access and collection capabilities. Test a private feed and confirm deletion removes stored originals, derived media and device caches, including offline devices once reconnected. Document provider cache expiry or purge limits rather than promising instant erasure.
5. **TV client:** Test Aerial Views with a private feed, photos, video, captions, controls and offline playback; compare Immich Android TV. Verify whether custom collages, dashboard and full TV settings require extension or a separate client.
6. **Dashboard:** Test hosted page authentication, rendering, schedule, refresh and whether the last view survives network loss and app restarts. Choose a fallback if not feasible.
7. **Enrichment and art:** Identify an authorized trip-record source and test matching by date and geography. Evaluate artwork recognition and trusted catalogue matches; record provenance, confidence and reuse rights. Provide corrections and avoid presenting guesses as facts.

## Delivery sequence

1. Complete the feasibility gates and choose the TV client, private library, authentication, cost envelope and dashboard fallback.
2. Deliver a vertical slice: select a small set of Google Photos, import, size and crop, display on the TV, and delete end to end.
3. Add offline cache, video playback, modes and both management interfaces.
4. Add collage suggestions and editing, then trip and artwork enrichment with correction controls.
5. Add curated art and the scheduled/manual dashboard. Validate on the actual TV, including offline and reconnect behavior.

## Acceptance criteria

- A selected photo enters and remains in rotation; removal from the app removes its stored and cached copies under the documented purge behavior.
- Representative photos demonstrate subject-aware crops; a manual adjustment persists. Collage groupings can be changed and an unsuitable pairing is not required.
- Captions show available verified context briefly; missing or rejected metadata is not invented.
- Photos, Art and Mixed modes work, including an adjustable mix. Videos loop or stop at the slide duration and default to muted.
- Dashboard mode can be selected and scheduled; its offline behavior is verified and documented.
- Both TV and web controls cover the agreed management tasks. Cached media continues offline and reconciles on reconnect.
- Test the Google TV connection, screensaver start, screen fit and remote controls on the actual device; verify privacy, access and removal end to end.

## Scoping decisions (2026-11-03)

- **Portrait pairs:** Pair automatically only on reliable same-trip or same-occasion evidence. Missing or uncertain grouping evidence shows the photo alone.
- **Dashboard offline:** Keep the last view when reliable; otherwise fall back to cached media with an offline indicator.
- **Art source:** Personal uploads first. Curated public collections later.
- **Captions:** Brief and subtle, verified information only; omit missing or uncertain details.
- **TV versus phone:** TV for playback and current-item actions; phone for library, crop, grouping and schedules.
- **Permanent deletion:** Phone only. The TV can remove an item from rotation.
- **Defaults (engineering, adjustable in settings):** 30-second slides, Mixed mode 70% photos / 30% art, offline cache bounded and sized after measuring free storage on the dongle.

## Next steps

1. **Evidence on the TV.** Install the current branch APK and record the version. Verify against representative media: smart crop, display-size delivery, portrait pairs, focus overrides, D-pad and modes, long-press options, video loop and mute, dashboard schedule, captions, deletion, offline and reconnect, and new media appearing without a restart. Reconcile the Linear checklist against this evidence.
2. **Close the gaps.** Fix what step 1 fails, and bring behavior in line with the decisions above: TV remove-from-rotation, phone-only permanent deletion, evidence-only pairs, verified-only captions, dashboard offline fallback.
3. **Personal art.** Upload art from the phone, Art and Mixed modes, fill by default with an adjustable crop.
4. **Release.** Review the Android branch, publish an APK through the authorized workflow, and finish the community guide.

**Later:** curated public art, iCloud sources, trip enrichment from an authorized trip-record source, dashboard and artwork enhancements, 4K and 1 GiB video proof once a 4K display is available.

## References

- [Google Photos API changes](https://developers.google.com/photos/support/updates)
- [Google Photos Picker media metadata](https://developers.google.com/photos/picker/reference/rest/v1/mediaItems)
- [Cloudflare Images transformations](https://developers.cloudflare.com/images/optimization/features/)
- [Aerial Views](https://github.com/theothernt/AerialViews)
- [Immich Android TV](https://github.com/giejay/Immich-Android-TV)
- [PhotoWall reference](https://play.google.com/store/apps/details?id=net.nextlayertechnology.photowall)

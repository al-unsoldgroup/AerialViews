# Draft: uns-1745-krill-picture-frame into master

Do not open this PR yet. It waits for the review (UNS-1751), the device evidence listed
under "Not yet proven", and Al's approval. Replace the bracketed items when it opens.

## Title

Krill-powered picture frame for Aerial Views [UNS-1745]

## Summary

Turns Aerial Views into a picture frame for a private Krill photo library. The TV signs in
with a device code, plays the library at screen size, caches it for offline use and is
managed from a phone. Diff against `master`: about 140 files, 11 commits, +18k/-0.4k lines.

## What changed

- **Krill source.** Device-code sign-in, library choice, private image and video delivery, sign-out cleanup, and a bounded offline cache with a playlist cache (UNS-1746, UNS-1749).
- **Fill and crop.** Single photos fill 16:9 using the server's display-size crop or a local
  focal crop; fit plus blur only when the source is smaller than the screen (UNS-1754).
- **Collages.** Two- and three-photo layouts with per-panel focal crops and a 70 px divider.
  Pairs come from server evidence only (UNS-1754, Krill PR #66).
- **Modes and schedule.** Photos, Art, Mixed and Dashboard; adjustable art share; a
  dashboard schedule with days, hours, interval and duration (UNS-1752).
- **Captions.** Brief, verified information only; refetched after an upgrade (UNS-1753).
- **Deletion.** Permanent deletion is requested only with an explicit permission and is
  confirmed by the server; the TV removes pictures from rotation. A reconciliation journal
  and cache switch exist but are off, because nothing creates a journal yet (UNS-1754).
- **Docs.** Build journal, scoping plan, community guide (`docs/community-guide.md`) and
  release checklist (`docs/release-checklist.md`) (UNS-1751).

## Companion backend

Krill, `al-unsoldgroup/krill-photo-library`, integration branch `uns-1745-frame-current`
(PR #54 into main; collage crops, migrations 056 and 057, focus API, flags). The frame
works without the new flags but gains collages and focus overrides with them. Production
deploy and migration steps are in the Krill PRs; this PR changes none of them.

## Verification

- Local: full unit suite 695 tests in 113 suites, 0 failures; `spotlessCheck` and
  `assembleBetaDebug` pass (recorded on UNS-1754 merge, PR #6).
- Release variant: `:app:minifyGithubReleaseWithR8` passes without secrets. A full
  `assembleGithubRelease` with a throwaway test key produced an APK (package
  `com.neilturner.aerialviews`, versionCode 151, versionName 1.8.6). Without any signing
  files it stops at `validateSigningGithubRelease`, as expected.
- Device (Chromecast with Google TV, Android 14, 1920x1080): sign-in, playlist, smart crop,
  D-pad, mode cycling and long-press options verified on 2026-11-04. Later fixes were
  verified as recorded in the build journal.

## Not yet proven

- [ ] Three-photo collage live on the TV
- [ ] Video loop and mute, with real videos in the library
- [ ] Offline and reconnect recovery
- [ ] New media appears without a restart
- [ ] Scheduled dashboard end to end
- [ ] Focus overrides and server crop on the TV
- [ ] Journal creation and startup recovery (not wired; reconciliation stays off)
- [ ] Guided remote voting (out of scope)
- [ ] Signed release APK built and installed (needs the signing secrets)

## Risks and rollback

- The release package name matches upstream Aerial Views. Installing over an upstream build
  signed with another key fails; uninstall first.
- Private loading is blocked while a recovery journal exists; nothing creates one today.
- Roll back by installing the previous APK, and turn off the Krill flags for server-side
  features. Details in `docs/release-checklist.md`.

## Linear

Epic UNS-1745. Children: UNS-1746, UNS-1748, UNS-1749, UNS-1750, UNS-1751, UNS-1752,
UNS-1753, UNS-1754, UNS-1755.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

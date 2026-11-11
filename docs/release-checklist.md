# Release checklist

Steps to cut an APK of the picture frame and publish it on GitHub. Docs only: this file
describes the process and creates no keys.

## 1. Version

- `versionCode` and `versionName` live in `app/build.gradle.kts` (`defaultConfig`).
  `versionCode` must increase on every release; `versionName` is `MAJOR.MINOR.PATCH`.
- The `beta` and `googleplaybeta` flavors add the `betaVersion` suffix (`-beta2` today). The
  `github` flavor, which the release workflow publishes, adds no suffix.
- The git tag is `v<versionName>` (for example `v1.8.6`). The workflow only matches
  `v[0-9]+.[0-9]+.[0-9]+`, so tags with a suffix do not trigger it.
- Bump the version in its own commit on the integration branch before tagging.

## 2. Pre-release checks (no secrets needed)

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew :app:spotlessCheck :app:testBetaDebugUnitTest :app:assembleBetaDebug
ANDROID_HOME=/opt/android-sdk ./gradlew :app:minifyGithubReleaseWithR8
```

The second command runs the R8 shrinker on the release variant and proves it compiles
without the signing files.

## 3. Signing configuration and secrets

`app/build.gradle.kts` defines two signing configs, `release` and `legacy`. Both read an
untracked properties file (the `signing/` directory is in `.gitignore`):

| File | Used by flavors |
| --- | --- |
| `signing/release.properties` | `googleplay`, `googleplaybeta`, `amazon`, `fdroid` |
| `signing/legacy.properties` | `github`, `beta` |

Each file has four keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
`storeFile` is resolved relative to `app/`. The picture frame release uses the `github`
flavor, so it needs `signing/legacy.properties`.

Without these files the build stops at `:app:validateSigningGithubRelease` with
"Keystore file not set for signing config legacy". That is the expected failure.

Where the secrets live:

- **Keystore file**: in a separate private keystore repository, checked out into
  `app/keystore` by the release workflow. Never commit a keystore to this repository.
- **GitHub Actions secrets** (repository settings, used by `.github/workflows/release.yml`):
  `KEYSTORE_GIT_REPOSITORY`, `KEYSTORE_ACCESS_TOKEN`, `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
  `RELEASE_SIGN_KEY_ALIAS`, `RELEASE_SIGN_KEY_PASSWORD`.
- **Local builds**: Al holds the keystore and passwords outside the repository (Bitwarden
  Secrets Manager). Write the two properties files by hand, build, then delete them.

Check these before the first release (they are unconfirmed for this fork):

- The six Actions secrets exist on `al-unsoldgroup/AerialViews`.
- The keystore repository is reachable with that token.
- The signing key is the one the TV's installed APK was signed with. A different key makes
  `adb install -r` fail with a signature mismatch; uninstall first, which clears app data
  and the Krill sign-in.
- The `release.yml` matrix also builds `googleplay` and `amazon` bundles, which this fork
  does not publish. Limit the matrix to `github` or accept the extra jobs.

## 4. Build the signed APK locally

With `signing/legacy.properties` in place:

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleGithubRelease
# output: app/build/outputs/apk/github/release/app-github-release.apk
```

Confirm the result:

```sh
aapt2 dump badging app/build/outputs/apk/github/release/app-github-release.apk | head -3
apksigner verify --print-certs app/build/outputs/apk/github/release/app-github-release.apk
```

Check the package name, versionCode and versionName, and that the certificate is the
expected one. The package is `com.neilturner.aerialviews`, the same as upstream Aerial
Views, so installing replaces upstream if the signatures match and fails if they do not.
The debug build uses `com.neilturner.aerialviews.debug` and installs beside it.

## 5. On-device verification (before publishing)

Install the candidate over wireless debugging and record the versionCode on the issue.
Taken from the UNS-1748 checklist:

- [ ] Smart crop fills the screen (single photo)
- [ ] Display-size delivery (image requests sized to the screen)
- [ ] Portrait pairs and three-photo collages fill edge to edge, with divider only
- [ ] Focus overrides set in the web app change the crop
- [ ] D-pad: right is next, left is previous
- [ ] Mode switching with up and down (Photos, Art, Mixed, Dashboard); Mixed uses the 30% art default
- [ ] Long-press photo options open; remove from rotation works and can be restored
- [ ] Video loops while shorter than the slide, stops at the slide duration, starts muted
- [ ] Scheduled dashboard appears and ends at the configured times; up or down overrides it
- [ ] Captions show verified information only, briefly
- [ ] Deletion: permanent deletion is on the phone only; the TV offers only removal from rotation
- [ ] Offline and reconnect: cached pictures keep playing offline and the playlist recovers
- [ ] New media appears in the running rotation without a restart
- [ ] Screensaver starts, and the app can be left to the normal TV interface
- [ ] A screen capture or log extract is attached with addresses and names removed

## 6. Cut the GitHub release

1. Merge the release commit and make sure the target branch has the version bump.
2. Tag and push:

   ```sh
   git tag v<VERSION>
   git push origin v<VERSION>
   ```

3. The `Release` workflow builds the `github` flavor and uploads `aerial-views-<VERSION>.apk`
   to the GitHub release for the tag. Watch it with `gh run watch`.
4. Edit the release notes: what changed, the minimum Android version (7.0), and the
   signature note from section 4.
5. Download the asset, run `apksigner verify`, then install it on the TV and repeat the
   short checks in section 5.
6. Link the release on the Linear issue.

If the workflow cannot run (missing secrets), build locally in section 4 and upload with
`gh release create v<VERSION> app/build/outputs/apk/github/release/app-github-release.apk --title v<VERSION>`.

## 7. Rollback

- **Bad release**: mark it as a pre-release or delete it with `gh release delete v<VERSION>`;
  delete the tag with `git push --delete origin v<VERSION>`. Do not reuse a tag.
- **Bad TV install**: reinstall the previous APK. `adb install -r` only upgrades, so a
  lower `versionCode` needs `adb install -r -d` (debuggable builds) or an uninstall first.
  Uninstalling clears the cache and the Krill sign-in; sign in again afterwards.
- Keep the previous APK file until the new one has run on the TV for a day.
- A server problem is a separate rollback. Turn off the relevant Krill flag rather than
  reverting the app.

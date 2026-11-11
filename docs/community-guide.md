# Build your own picture frame

This guide shows how to turn a Google TV into a picture frame with this app (a fork of
[Aerial Views](https://github.com/theothernt/AerialViews)) and your own Krill-style photo library.
Everything here uses placeholders. Replace `<TV_IP>:<PORT>`, `<LIBRARY_URL>` and similar
values with your own, and never post them in public.

## What you need

- A Google TV or Android TV device with Android 7 (API 24) or newer. The reference build was
  tested on a Chromecast with Google TV (Android 14, 1920x1080). Free storage is tight on
  these dongles (about 0.5 GB of 4 GB was free), so keep the offline cache small.
- A TV or monitor. A Samsung The Frame works well as a display.
- A photo library that serves the Krill frame API over HTTPS: a Krill deployment, with
  a user account, a library (space) and the frame feed enabled. Setting up the backend is
  covered by the Krill repository documentation, not here.
- A computer with `adb` (Android platform-tools) on the same network as the TV.
- A phone for signing in, uploading and managing the library.

## 1. Install the app (sideload over wireless debugging)

1. On the TV open **Settings > System > About** and tap **Android TV OS build** seven times to
   unlock developer options.
2. Open **Settings > System > Developer options** and turn on **Wireless debugging**.
3. Choose **Pair device with pairing code**. The TV shows a pairing code and `<TV_IP>:<PAIR_PORT>`.
4. On your computer:

   ```sh
   adb pair <TV_IP>:<PAIR_PORT>      # enter the pairing code when asked
   adb connect <TV_IP>:<PORT>        # the port shown on the main Wireless debugging screen
   adb devices                       # the TV should show as "device"
   ```

   The pairing port and the connect port are different. The connect port changes each time
   wireless debugging restarts, so repeat `adb connect` when it stops working.
5. Download the APK from the project's GitHub Releases page, then install it:

   ```sh
   adb install -r aerial-views-<VERSION>.apk
   ```

   If Android reports a signature mismatch, you have another build with the same package
   installed (for example the original Aerial Views). Uninstall it first.
6. Turn wireless debugging off when you are done. Do not share the pairing code, address or
   port; anyone on your network who has them can control the TV.

To build the APK yourself instead, see [the release checklist](release-checklist.md).

## 2. Make the app your screensaver

1. Open the app once from the TV launcher so it can finish first-run setup.
2. In Android TV settings choose **Device preferences > Screen saver** and select the app.
3. To start the frame without waiting for idle time, use the screen saver's **Start now** option.
   Some devices hide the screen saver picker; if so, set it over adb with
   `adb shell settings put secure screensaver_components <PACKAGE>/<PACKAGE>.ui.screensaver.DreamActivity`
   (the package is `com.neilturner.aerialviews` for release builds).

## 3. Sign in

1. In the app open **Settings > Media Sources > Krill photos**.
2. Enter your library address, for example `https://<LIBRARY_URL>`. It must be HTTPS.
3. Choose **Sign in and choose a library**. The TV shows a short code and a web address.
4. On your phone open that address, enter the code and approve the TV. Sign in with the
   account that owns the library.
5. Back on the TV pick the library to show. The app confirms "Krill library connected".

The TV stores only a device token. **Sign out of Krill** in the same menu removes it.

## 4. Sources

- **Krill photos** is the main source: your private library, served at the size of your
  screen and cached on the TV for offline playback.
- Photos reach the library from your phone: the web uploader, Google Photos selection,
  Google Drive, or the iPhone share sheet (section 8).
- The other Aerial Views sources (for example Immich, WebDAV, Samba) still exist. Turn off
  sources you do not use so they do not mix into the rotation.

## 5. Modes and remote control

| Remote | Action |
| --- | --- |
| Left / right | Previous / next picture |
| Up / down | Change mode |
| Long press centre | Options for the current picture, including removal from the rotation |

Modes: **Photos**, **Art**, **Mixed** and **Dashboard**.

- **Art** only appears once the library has art items.
- **Mixed** blends photos and art. Set the share under **Settings > Appearance > Startup > Picture frame modes**
  (**Art in mixed mode**). The intended default is 30 percent art.
- **Dashboard** shows an HTTPS web page you choose (**Dashboard URL**).
- A mode with nothing to show says "No matching pictures in this mode".

Removing a picture from the rotation on the TV is reversible. Permanent deletion belongs on
the phone, in the library web app.

## 6. The Schedule menu

Open **Settings > Appearance > Startup > Schedule** to show the dashboard at set times and
pictures the rest of the day.

- **Dashboard days**: choose the days. Clear every day to turn the schedule off.
- **Dashboard hours start / end** (`HH:mm`): the window in which the dashboard may appear.
- **Show the dashboard every**: how often it appears inside the window.
- **Keep the dashboard on screen for**: how long it stays each time.

Pressing up or down overrides the schedule until its next scheduled change. If the dashboard
URL is missing or invalid, the dashboard is skipped.

## 7. Captions and collages

- **Captions** are short and only show verified information, such as a place name or date.
  Missing or uncertain details are left out rather than guessed. The library, not the TV,
  holds the title and place; edit them in the web app.
- **Single photos** fill the screen. The server crops to the display size around the subject;
  a focus point you set in the web app overrides the automatic crop. A blurred background
  is used only when the photo is smaller than the screen.
- **Collages** put two or three related photos side by side, for example portraits from the
  same trip or day. A pair forms only when the library has reliable evidence that the photos
  belong together; otherwise the photo shows alone. There is a thin divider between panels.
  Each panel is cropped around its own focus point.

## 8. Upload from an iPhone (share sheet)

1. In the library web app open **Settings > API > Install Share to Frame** on your iPhone and
   add the shortcut when Apple asks.
2. Run the shortcut once. It shows a connection code and opens the library. Sign in, pick the
   receiving library, check the code and choose **Connect Shortcut**.
3. In Photos select pictures, tap **Share > Options**, leave **Location** on, then choose
   **Share to Frame**.
4. Photos upload one at a time. If the network drops, the run stops; share the same
   selection again and duplicates are skipped.

The shortcut keeps an upload-only credential in your iCloud Drive. Never share that file.
To stop an iPhone from uploading, revoke it under **Settings > API**.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `adb connect` fails | Same network? Wireless debugging still on? The port changes after each restart. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall the existing app with the same package, then install again. |
| Sign-in says it could not connect | The library address must be HTTPS, without a trailing path. Open it in a phone browser to confirm it loads. |
| Signed in but nothing plays | The library may be empty, or the chosen mode has no matching pictures. Try Photos mode. |
| New photos do not appear | The playlist refreshes on a schedule. Restart the screensaver, or wait for the next refresh. |
| Dashboard stays blank | The URL must be HTTPS and allow being shown inside the app. Test it in a browser. |
| Pictures stop after losing network | Cached pictures should keep playing. If the cache is empty, reconnect and let it fill. |
| Screensaver never starts | Confirm the app is selected under Screen saver, and that the TV is not set to start an ambient mode first. |
| Storage full | Lower the cache size in the app settings; small dongles have very little free space. |

If a problem remains, collect logs with `adb logcat -d` and remove addresses, tokens and
account names before sharing them.

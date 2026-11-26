<img width="500" height="500" alt="icon7" src="https://github.com/user-attachments/assets/584752ec-659b-490a-b24a-dda66364ae09" />


# ReinMusic

**ReinMusic** — a YouTube Music client for legacy iOS devices.

A native, jailbreak-only YouTube Music client for old devices running iOS 6.0+ (armv7), built with
[Theos](https://theos.dev). It talks to YouTube's unofficial InnerTube API to search, browse, and
stream music without needing a modern OS.



## Android

ReCloud for Android is a native Kotlin and Compose app for Android 8.0 or newer. It plays personal audio and licensed Internet Archive recordings through Android's media session. Public YouTube search, channel and playlist browsing use the YouTube Data API. Supported YouTube videos play in a visible embedded player. A pasted video link can open the player without a search key.

**Install.** Download the `ReCloud-debug` artifact from a successful **Android build** run in GitHub Actions, extract the ZIP, and open `app-debug.apk` on your phone. Allow installation from the app you used to open it if Android prompts. For a local build, use the APK path below. Debug builds signed by different machines or CI runs can have different certificates. Use a consistent signing key to distribute updates that retain library data.

**First listen.** Open **Home** and select an Internet Archive release, or choose **Library → Import audio** and select files. Open a song's **•••** menu to favorite it, add it to a playlist, or download a licensed recording. **Search → YouTube** accepts a video link. To search by title in the app, add your key in **Settings → YouTube Data API key** or connect an authorized Google account.

**Build locally.** Open `android/` in Android Studio Panda 3 Patch 1 or newer, or install JDK 17 and Android SDK command-line tools. Set `ANDROID_HOME` to your SDK, or give Android Studio an SDK path in the untracked `android/local.properties`. Install SDK Platform 37.0, Build Tools 36.0.0 and Platform Tools. Gradle downloads via the checked-in wrapper.

```sh
cd android
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
./gradlew testDebugUnitTest lintDebug assembleDebug
# With an emulator or test phone connected
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows PowerShell use `.\gradlew.bat` in place of `./gradlew` and `sdkmanager.bat` if needed. The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

**Configure YouTube.** In Google Cloud enable **YouTube Data API v3**. Restrict the public API key to that API and an Android application with package `org.recloud.music` and your signing certificate's SHA-1. Run the Gradle task `signingReport` to find the debug fingerprint. Set `RECLOUD_YOUTUBE_API_KEY` in the build environment to supply a distributor's restricted public key, or enter a personal key in Settings. A build-time key can be read from an APK. For account playlists, also configure the OAuth consent screen, any required test users, and an **Android OAuth client** for the same package and certificate. Connect through Google Play services on the phone. Access tokens live in memory. The on-device search key is encrypted with Android Keystore. [YouTube API setup](https://developers.google.com/youtube/v3/getting-started) · [Google authorization on Android](https://developers.google.com/identity/authorization/android)

**Google sign-in troubleshooting.** Choose **Connect Google account**, select or add the intended Google account in the system picker, then approve read-only YouTube access. ReCloud remembers the selected account in Android Keystore-encrypted storage. On restart it asks Google Play services for fresh access, then loads your playlists. If Google needs consent again, choose **Reconnect Google account** in Settings. **Sign out** forgets the selection on this device. For a registration error (Google status 10), compare the installed APK certificate against the Android OAuth client in the project with YouTube Data API v3 enabled. During OAuth testing, add the chosen Google address as a consent-screen test user. An API key alone enables public searches. Private playlists require OAuth authorization.

**Sign a release.** In Android Studio open `android/`, choose **Build → Generate Signed App Bundle or APK → APK → app**, and select or create a keystore outside the repository. Choose the release variant and retain the keystore and passwords in your own secure storage for subsequent updates. Register the release certificate for YouTube separately. Use the generated signed release APK for distribution. [Android signing guide](https://developer.android.com/studio/publish/app-signing)

The port includes original ReCloud artwork and controls from [Recilia/ReCloud](https://github.com/Recilia/ReCloud), via [iReaching/ReCloud](https://github.com/iReaching/ReCloud), under the repository's GPL-3.0-or-later terms. See the [port plan](ANDROID_PORT_PLAN.md), [feature parity and verification](android/PARITY.md), [license](LICENSE), and [disclaimer](DISCLAIMER.md).

## Features

- Search with Songs / Videos / Albums / Artists tabs (cached search history)
- **Google account sign-in** (Settings → Account): personalized Home with
  "Mixed for you" rotations, "Listen again", "New releases" and other recommendation shelves,
  your YouTube Music library playlists, and your account's listening history
- Browse albums, playlists, and artist pages
- Streaming playback via `AVAudioPlayer` with configurable quality (Low / Medium / High)
- Download tracks for offline playback
- Local playlists (create, rename, delete, add tracks via an in-app picker)
- Recently played history and per-track listening stats (most-played, top artists, total time)
- Dedicated **Now Playing** tab with full artwork, seek bar, repeat, and shuffle
- Blurred album-art background behind the player (toggleable)
- Smooth cross-fade tab transitions with a fixed tab bar, animatable / disable-able and
  speed-adjustable; the Now Playing tab icon lights up blue during playback
- Queue view, song context menus, Shuffle All / Play All
- **Peer-to-peer sync:** sync your full library (playlists, library, recents, stats) directly
  between two phones over Wi-Fi via Bonjour, with no desktop or LAN server
- Tab icons rendered from a bundled Font Awesome font

## Requirements

- [Theos](https://theos.dev) on a macOS/Linux host
- A jailbroken iPhone (armv7, iOS 6.0 or newer)
- iOS 6.0 deployment target (set in the `Makefile`)
- [TLSFix](https://github.com/nfzerox/TLSFix) installed and enabled for this app (it keeps the
  bundle id `com.legacymusic.app`, so an existing AN Legacy TLSFix entry keeps working) — legacy iOS
  versions ship outdated TLS that fails against modern HTTPS endpoints

## Building

```sh
THEOS=/path/to/theos make package
```

To build and install directly to a device over SSH:

```sh
THEOS=/path/to/theos THEOS_DEVICE_IP=<ip> make package install
```

`./make_release.py` builds all four distributables (rootful and rootless `.deb`, plus the
64-bit and 32-bit `.ipa`) and copies them into `versions/<version>/` in the repo and into your
Downloads folder.



## Syncing between two phones

In **Settings → Sync**, press **Sync with Another Phone** on *both* phones. Each phone publishes and
discovers the other over Bonjour on the same Wi-Fi network and they exchange their full library
directly — no desktop or LAN server involved. Both phones must run the same app version; if the
versions differ, the sync is refused.

## Settings

- **Playback options** — Keep Screen Awake, Show kbps Counter
- **Appearance options** — Album Art Background, Enable Animations, Transition Speed, Recents Tile
  Size
- **Data management** — Offline Downloads, Sync with Another Phone, Refresh Metadata & Artwork,
  Create Rein Mini Instance, Clear Offline Downloads
- **Account** — Sign in to Google (paste a SAPISID cookie from a desktop browser), sign out

## Project structure

- `*.m` / `*.h` — app source
- `Resources/` — bundled assets (icons, Info.plist, Font Awesome font)
- `Makefile` — Theos build configuration
- `control` — package metadata for the deb

## License

This project is licensed under the **GNU General Public License v3.0 or later**. See
[LICENSE](LICENSE) for the full text.

## Disclaimer

This project uses unofficial Google/YouTube APIs and is provided as-is. See
[DISCLAIMER.md](DISCLAIMER.md) for the full disclaimer, including the notice that the code was
AI-generated.

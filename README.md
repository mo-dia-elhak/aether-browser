# Æther Browser
Native Kotlin + Android WebView. Safari-inspired glass UI, switchable search engines,
built-in ad/tracker blocker, local-only data, zero telemetry.

## Screenshots
Interface preview (design mockup of the layout and colors).

| Start page | Tab switcher (dark mode) | Settings & search engines |
|:---:|:---:|:---:|
| <img src="C:\Users\diasc\Downloads\Telegram Desktop\photo_2026-11-15_11-51-26.jpg" width="240" alt="Æther start page with favorites and blocked counter"> | <img src="docs/screenshots/02-tab-switcher-dark.png" width="240" alt="Card-style tab grid in dark mode"> | <img src="docs/screenshots/03-settings.png" width="240" alt="Settings sheet with search engine choices and privacy switches"> |

## Requirements
Android Studio Koala (2024.1) or newer, JDK 17 (bundled), Android SDK 34. minSdk 29 (Android 10+).

## Build a debug APK (quick test)
1. Android Studio > File > Open > select this `Aether` folder; wait for Gradle sync (it creates the wrapper).
2. Build > Build Bundle(s) / APK(s) > Build APK(s).
   Output: app/build/outputs/apk/debug/app-debug.apk
3. Install: `adb install app/build/outputs/apk/debug/app-debug.apk`, or copy the file to your phone and open it.

## Build a signed release APK (daily use)
1. Create a keystore (once, and back it up: you need it for every future update):
   `keytool -genkeypair -v -keystore aether-release.jks -alias aether -keyalg RSA -keysize 2048 -validity 10000`
2. Copy `keystore.properties.example` to `keystore.properties` and fill in your values.
3. Build: `./gradlew assembleRelease`
   (CLI first-time: `gradle wrapper --gradle-version 8.7`)
   Output: app/build/outputs/apk/release/app-release.apk  (minified + signed)
4. Without a keystore.properties the release APK is produced unsigned and will not install.
   Alternative: Build > Generate Signed Bundle / APK in Android Studio.

## Shields (Brave-style)
Tap the shield chip in the address pill (shows a live blocked count for the page).
- Per site: Shields up/down, Block all scripts.
- All sites: Aggressive mode (blocks third-party scripts; common CDNs are allowed), HTTPS upgrade
  (falls back to http:// if the site has no HTTPS).
- If a site or streaming service misbehaves in Aggressive mode, switch Shields off for that site only.
- Always on: ad/tracker blocking in shouldInterceptRequest, pop-up blocking, third-party cookies blocked,
  Safe Browsing off, no analytics, no accounts, no network calls of its own.

## Customize
- Search engines: `SearchEngine.kt` (one line per engine).
- Ad blocking: `AdBlocker.kt`, or paste a hosts-format list into `app/src/main/assets/blocklist.txt`.
- Icon: `python3 tools/make_icon.py /path/to/SerifItalic.ttf` regenerates the mipmaps (needs Pillow).

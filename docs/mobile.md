# Android client

The Android app embeds X with local feed filters. This fork hardens that session and its native boundaries. Read [the Android security guide](android-security.md) before using a primary account. Package: `org.evergreenlabs.robin`.

## Requirements

- JDK 17 and Android SDK 36, with build-tools 36.0.0 and current command-line tools.
- Android Studio compatible with the pinned Android Gradle Plugin (if using the IDE).
- Device **API 33+** with an up-to-date system WebView.
- Python 3 for the Release APK verification script.
- Node.js 18+ for the shipped-JavaScript security fixtures (built-in modules only).

Gradle, AGP, Kotlin and resolved dependency versions are pinned in the repository. Use the checked-in wrapper with dependency verification enabled.

## Configure

```bash
cp android/local.properties.example android/local.properties
```

Set the local SDK directory in that private file, or supply `ANDROID_SDK_ROOT`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
robin.enableAi=false
```

The default build needs no Firebase configuration and contains no Sentry/Aptabase integration. `robin.showDebugSettings` controls development benchmark settings; Release always disables them. Never commit `local.properties`.

For AI, explicitly set `robin.enableAi=true` and add your own `android/app/google-services.json`. Configure server-side App Check enforcement, API restrictions and quotas. Debug and Release both require real Play Integrity attestation when AI is enabled; this fork removes the App Check debug provider and ignores debug tokens. Every fact-check still requires native confirmation. Read the [AI security and sideloading limitations](android-security.md#optional-ai-explicit-setup-and-per-request-consent).

## Verify the build

```bash
node scripts/test-android-filter-security.mjs
python3 scripts/test_android_release_verifier.py --sdk "$ANDROID_SDK_ROOT"
cd android
APP_CHECK_DEBUG_TOKEN=ROBIN_RELEASE_TOKEN_MUST_NOT_SHIP ./gradlew --no-daemon testDebugUnitTest lintRelease assembleDebug assembleRelease
cd ..
python3 scripts/verify-android-release.py \
  --apk android/app/build/outputs/apk/release/app-release-unsigned.apk \
  --apkanalyzer "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer"
```

Without a private signing configuration the Release APK is deliberately unsigned. The Debug APK is useful for development, but it is Android-debuggable and uses the development debug keystore. Use a privately signed **Release** for your normal signed-in session.

## Sign a personal Release

Create a private signing key on your own trusted computer and keep a backed-up copy outside the source tree. Android's `keytool` prompts for the passwords:

```bash
keytool -genkeypair -v -keystore /private/path/robin-personal.jks \
  -alias robin -keyalg RSA -keysize 3072 -validity 10000
```

Create gitignored `android/keystore.properties` with the private location and passwords:

```properties
storeFile=/private/path/robin-personal.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=robin
keyPassword=YOUR_PRIVATE_PASSWORD
```

Build and inspect the signed artifact:

```bash
cd android
./gradlew --no-daemon :app:assembleRelease
cd ..
python3 scripts/verify-android-release.py \
  --apk android/app/build/outputs/apk/release/app-release.apk \
  --apkanalyzer "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer"
```

Keep the same private key for later updates. A self-signed fork cannot update an installed upstream store build signed by a different key. Test on a device before moving a primary session. No keystore or signing password belongs in a PR, CI artifact, chat transcript or public log.

## Behaviour

| Concern | Android behaviour |
|---|---|
| Feed | X WebView with local Following/For You and content filters |
| Sign-in | Exact supported HTTPS origins; visible native origin indicator; unknown redirects fail closed |
| External links | Validated HTTPS URLs opened in a system browser |
| AI | Disabled unless explicitly configured; native consent before each request |
| Telemetry | Sentry/Aptabase removed |
| Session | Private WebView cookie store; local sign-out; excluded from cloud backup/device transfer |
| Screen | Screenshots/screen sharing and overlay windows blocked |
| Debug features | Development only; no exported benchmark broadcast receiver; WebView inspection disabled |

## Play bundle

With your own valid signing configuration, `./gradlew :app:bundleRelease` builds an AAB. Publishing and Play App Signing require an account/app identity you control. Existing upstream store listings and binaries are not automatically changed by this fork.

---

# iOS client

Primary UI is an **x.com WKWebView** with the same injected filters as Android. Bundle ID: `org.evergreenlabs.robin`. X sign-in is the WKWebView cookie session (no Robin backend). See [`docs/webview-filter.md`](webview-filter.md).

## Requirements

- Xcode 16+ / **iOS 18+**
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`) to regenerate the project from `ios/project.yml` if needed

## Configure

```bash
cp ios/Config.xcconfig.example ios/Config.xcconfig
```

Edit `ios/Config.xcconfig` (gitignored). In xcconfig files, `//` starts a comment, so write HTTPS URLs as `https:/$()/host`:

```
# Optional — leave blank (or omit) to disable Sentry
# SENTRY_DSN = https:/$()/YOUR_PUBLIC_KEY@oXXXX.ingest.us.sentry.io/PROJECT_ID
# Optional Aptabase — leave blank to disable
# APTABASE_APP_KEY = A-SH-YOUR_APP_KEY
# APTABASE_HOST = https:/$()/YOUR_APTABASE_HOST
```

Leave `SENTRY_DSN` / `APTABASE_APP_KEY` blank to disable those services. Set `SHOW_DEBUG_SETTINGS = NO` to hide Settings debug chrome (benchmark + fact-check test) when recording release-looking videos from a Debug build.

Open [`ios/Robin.xcodeproj`](../ios/Robin.xcodeproj), select your team, then Run.

```bash
cd ios
xcodegen generate   # only if project.yml changed
xcodebuild -project Robin.xcodeproj -scheme Robin \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

## Behavior

| Concern | How |
|--------|-----|
| Feed | `x.com` WKWebView + `Resources/x-filter` inject (parity with Android) |
| Sign in | On x.com inside the WebView (cookie session) |
| Settings | Gear → text size, filter toggles, sign out (`WKWebsiteDataStore`) |
| Export compliance | `ITSAppUsesNonExemptEncryption = false` in `ios/project.yml` → `Info.plist` |

# Hardened Android build for personal use

This fork hardens the Android app's account, WebView, cloud-request and build boundaries. The normal build has AI disabled and does not require a Firebase project or `google-services.json`. Sentry and Aptabase have been removed from Android, including their build plugin and dependencies.

This is risk reduction, not a guarantee of complete security. Robin still embeds an authenticated X website. X, the Android operating system, the system WebView, your keyboard and your device remain part of the trust boundary. Keep Android, Android System WebView and your browser up to date. These changes apply to source built from this fork; upstream store downloads are separate binaries.

## Recommended personal configuration

1. Build a **Release** APK from a reviewed commit. Debug APKs are for development and remain debuggable by Android even though Robin disables WebView inspection in every build.
2. Leave `robin.enableAi=false` (the default). No Firebase configuration is needed for browsing and filtering X.
3. Sign the Release APK with a keystore that you create and keep privately. The build never falls back to signing Release with the development debug keystore. An unsigned APK produced for CI inspection cannot be installed until it is signed.
4. Keep strong X account authentication enabled. Removing the old WebAuthn-suppression script does not make an arbitrary embedded app a privileged passkey browser. If a sign-in method cannot work within Android/X restrictions, use a supported client instead of weakening account security.
5. Protect the phone with a screen lock. Do not install an untrusted keyboard, accessibility service or root framework alongside a signed-in account.

See [mobile.md](mobile.md#android-client) for build and signing commands. Do not put a keystore, its password, a real App Check debug token, or live service credentials in the repository or a public CI log.

## Web and native boundaries

The app no longer registers `addJavascriptInterface` objects. AndroidX WebKit's message listener exposes one small JSON protocol only to exact HTTPS X origins. Native code verifies that the sender is the main frame and that its origin is allowed. It bounds the byte size and nesting of messages, validates their fields, and limits the rate of each operation. AI and benchmark channels are disabled when their corresponding build features are disabled. The application does not fall back to an unscoped bridge on an old WebView.

Bundled feed scripts use the same exact origins and top-frame restriction. They cannot run on an arbitrary `*.x.com` or `*.twitter.com` subdomain. X account pages remain fully privileged web content for that X session; origin checks cannot make a compromised X origin trustworthy.

Top-level navigation and sign-in popups share a strict HTTPS parser and explicit origin policy. Google/Apple authentication hosts are listed individually; broad `googleapis.com`, `gstatic.com` or `google.com` suffix rules have been removed. Popup creation requires a user gesture, unknown destinations are blocked, and the native popup chrome displays the committed origin. There are no native bridges in an authentication popup. An unusual provider redirect may therefore fail closed rather than finish sign-in.

External links are validated absolute HTTPS URLs and are opened by a system browser. Ambiguous authorities, user information, unsafe schemes, local/IP host spellings and unexpected ports are rejected. This is URL validation, not DNS pinning: a public DNS name can still resolve to a local address, and the external browser remains responsible for its network and download policies.

The X WebView needs JavaScript and local website storage. File/content access, mixed content, automatic popup creation, WebView inspection and third-party cookies are disabled. Safe Browsing stays enabled. TLS failures and dangerous-page callbacks fail closed. Permission requests, file pickers and downloads are not granted to page content. These restrictions may affect some media or sign-in features.

## Local session privacy

Raw cookies, authentication URLs, page console messages and page-originated error telemetry are not sent to application logs. The Android app contains no Sentry/Aptabase transport or build-time telemetry instrumentation.

The activity and sensitive sheets prohibit screenshots/screen sharing with `FLAG_SECURE`. They hide overlay windows; the activity, native consent/settings dialogs and WebViews reject both fully and partially obscured touches. These controls protect ordinary Android interaction paths, not a compromised operating system or a trusted-but-malicious accessibility service.

Cloud backup and device-transfer rules exclude all app-data domains, including the WebView cookie store. The network security configuration explicitly permits only system certificate authorities and denies cleartext traffic. No custom trust-all certificate handler or user-CA exception is installed.

Signing out first closes authentication popups and discards running page scripts, blocks service-worker network access, then uses AndroidX WebKit’s complete browsing-data deletion API to clear cookies, all website storage, network caches and installed service workers. History and retained screen content are also cleared. An old WebView without the required deletion and message APIs is refused before X loads. It does not promise server-side revocation of a session copied before sign-out. Use X's own session management to revoke other sessions when necessary.

## Optional AI: explicit setup and per-request consent

To enable AI, set `robin.enableAi=true` in your private `android/local.properties`, or pass `-Probin.enableAi=true`, and provide **your own** Firebase configuration for this app. An enabled build still requires a native **Send to Google** action for each selected post; opening a sheet does not submit a request. The confirmation shows the post and the links that will be sent. Dismissing it cancels the local request; cancellation cannot retract data or charges already accepted by the provider.

Input and output sizes, external-link counts, JSON nesting and source lists are bounded. Requests are serialised and time limited. Arbitrary provider errors are replaced with safe messages. Model-generated source links pass the same HTTPS validation before they become clickable. The model has no native file, shell or cookie tools.

Google Search attribution HTML is displayed only in an inert, offline WebView with an opaque origin. JavaScript, storage, file/content access, network resource loads, popups, forms, downloads and permission requests are blocked. Valid HTTPS suggestion links open in the system browser only after a user action. This retains the provider's attribution display while separating it from the authenticated X view. See [Firebase's grounding requirements](https://firebase.google.com/docs/ai-logic/grounding-google-search).

Publisher credibility data is downloaded from one fixed HTTPS endpoint after an authorised fact-check. Redirects, oversized responses and unbounded catalogue entries are rejected. Robin does not fetch arbitrary article URLs on behalf of page JavaScript.

Firebase's automatic initialisation provider is removed. The application initialises Firebase only for an explicitly AI-enabled build. The App Check debug provider has been removed from all Android variants because its SDK can log an attestation bypass token. Debug and Release both use Play Integrity; no App Check debug token is read, generated or stored by this app. The APK verification step also searches for a distinctive test token to catch packaging regressions. Production API restrictions, App Check enforcement and quotas must be configured in **your** Firebase project; a client-side flag cannot enforce a cloud policy. For a sideloaded personal build or a Debug test build, confirm the project's Play Integrity/App Check support before enabling AI. Never solve an attestation failure by distributing a registered debug token in Release.

## Build integrity and checks

The Android toolchain is pinned to a patched Gradle release with the official distribution SHA-256. The wrapper JAR is also matched to its official release checksum. AGP and Kotlin versions are pinned together and verified by compiling both Android variants.

Dependencies use explicit versions, checked-in strict lock files and SHA-256 verification metadata. Google-owned/AndroidX groups resolve exclusively from Google's repository; the remaining supported artifacts resolve from Maven Central. JitPack has been removed. Checksums pin the reviewed dependency bytes; they do not independently prove that upstream maintainers or their original publication systems were uncompromised.

The Android CI workflow uses immutable action commit IDs, read-only repository permissions and no persisted checkout credentials. It runs adversarial JVM tests, lint, Debug/Release builds and an inspection of the resulting Release APK. No real X login, Firebase key or signing secret is required for those checks. AI requests themselves are not exercised by CI.

`scripts/verify-android-release.py` uses the official Android SDK `apkanalyzer` to inspect the **packaged** manifest and XML policies, plus APK entries and DEX strings. It rejects debuggable releases, unexpected permissions/exported components, Firebase automatic startup, missing backup/TLS protections, known telemetry packages, private-key file patterns and the test App Check token (including UTF-16 resource strings). These are checks for known markers, not a proof that arbitrary obfuscated code or secrets are absent. Manifest resource references are bound to the XML files actually inspected after resource shrinking. The AndroidX profile installer is permitted only when protected by Android's signature-level `DUMP` permission.

Run the same checks locally:

```bash
cd android
APP_CHECK_DEBUG_TOKEN=ROBIN_RELEASE_TOKEN_MUST_NOT_SHIP ./gradlew --no-daemon testDebugUnitTest lintRelease assembleDebug assembleRelease
cd ..
python3 scripts/verify-android-release.py \
  --apk android/app/build/outputs/apk/release/app-release-unsigned.apk \
  --apkanalyzer "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer"
```

Use the actual signed APK path if you supplied a Release signing configuration. Run checks with strict verification enabled; do not use `--dependency-verification off` or regenerate verification metadata merely to silence an unexpected checksum failure. Review and verify changed dependencies before intentionally updating locks/checksums.

## Device validation before a primary account

JVM tests and compilation cannot prove real OAuth, X page compatibility or Android WebView behaviour. On an up-to-date test device, first verify startup, basic X sign-in with strong authentication, feed filtering, external links, sign-out, blocked popups and the screenshot/overlay controls. In an explicitly AI-enabled test build, check that opening/cancelling a fact-check sends no request and that only native confirmation submits it. Do not include cookies, full authentication URLs, real provider keys or private post content in a bug report.

The hardening changes do not include an authenticated penetration test against X or Google, a proof that no browser-engine vulnerability exists, or changes to the iOS and desktop tools. The source policy and tests should be maintained as X, Android WebView and the build dependencies evolve.

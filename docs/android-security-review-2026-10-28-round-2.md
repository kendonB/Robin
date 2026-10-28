# Android security review: second pass

Date: 28 October 2026. Baseline: `3bb96c8a34287dfcbf4a443a56767ec466805d42`, the first Android hardening merge. This review and its fixes identify the Android build as **0.2.32-hardened**, version code **61**.

## Scope and conclusion

This pass independently re-examined Android's native/web boundary, authentication and session lifecycle, bundled feed JavaScript, optional AI consent and data handling, local storage, manifest policies, dependency integrity and packaged-APK checks. It included source review, Android platform documentation/source, adversarial local fixtures and a fresh advisory query. No real X account, functioning Firebase credentials, signing keys or paid cloud requests were used.

The review found further weaknesses in automatic page interaction, resource bounds, renderer failure handling and build verification. The changes accompanying this report address those findings. No new arbitrary-origin native-bridge escape, session-cookie disclosure, native SSRF, consent bypass or remote code execution was demonstrated. That observation is limited to the reviewed code and tests; it is not a guarantee that no vulnerability exists.

## Findings

Severity describes the demonstrated effect and prerequisites, rather than treating every warning as an exploitable account compromise.

| ID | Finding | Demonstrated impact | Change |
|---|---|---|---|
| A2-01 | Page-wide text heuristics could click post content as if it were a feed control | UI integrity and availability: automatic navigation or an unintended page action; no credential theft demonstrated | Remove unattended new-post clicking; scope other controls and fixed compose navigation; test the shipped Android asset |
| A2-02 | Catalogue row limit applied after allocating the decoded list | Denial of service if the fixed catalogue endpoint returns allocation-heavy data, with optional AI enabled | Count top-level rows and enforce structure limits before calling the decoder |
| A2-03 | Incomplete renderer-loss and dead-view cleanup | Renderer failure could terminate the host or leave delayed operations targeting a dead view | Explicit failure state, idempotent release, cancelled/guarded pending operations, no automatic reload of crashing HTML |
| A2-04 | Resource references could bypass exported-component and policy checks | A future dependency/build regression could pass APK inspection while weakening component, TLS or backup policy | Reject unresolved/nonliteral component flags and policy aliases/configuration variants |
| A2-05 | Known-secret marker scan omitted some APK entries and encodings | A known token/private-key marker could evade the regression gate | Scan every entry in three text encodings, with bounded reads and cross-chunk matching |
| A2-06 | Optional-AI Release was compiled but not inspected after packaging | Configuration-specific packaging/manifest regressions could escape the gate | Assemble and inspect both default and optional-AI Release configurations |

### A2-01: automatic interaction with untrusted page content

The baseline Android `filter-core.js` searched the entire document for buttons and links with labels such as “new posts”, then called `click()` without a user action. The search did not exclude tweet articles, cards or quoted content. Related tab, sort and compose selectors also needed a narrower relationship to the intended X controls.

The Android fix removes unattended new-post-pill clicking. Native refresh remains available. Following/For You selection is restricted to exact tab labels within the expected primary-column tab list, excluding post/card/dialog content and revalidating cached elements. Sort handling requires the recognised control/menu relationship and cancels on unrelated interaction. Compose navigation uses explicit controls outside post content with approved fixed destinations, or the fixed `/compose/post` route; it does not adopt a post's arbitrary compose link or parameters.

`scripts/test-android-filter-security.mjs` exercises the JavaScript asset actually packaged by Android with controlled hostile and legitimate DOM fixtures. These are code-level interaction tests, not evidence that every current X page layout works. Android's asset intentionally diverges from the other platform copies; overwriting it during an upstream asset sync must pass these tests.

### A2-02: catalogue allocation before its row check

The baseline checked the 5 MiB byte limit and JSON nesting before deserialisation, but checked `MAX_ROWS` only after constructing `List<MBFCRow>`. A valid array containing 1,700,000 empty objects is **5,100,001 bytes**, below the byte limit. A standalone reproduction using the app's Kotlin/serialization versions and a **64 MiB JVM heap** passed the byte/depth guards and raised `OutOfMemoryError` before reaching the row check.

The fixed `FactCheckCataloguePolicy.decodeBounded` checks one root array, nesting and the maximum top-level item count before invoking the allocating decoder. The same reproduction is rejected with `decoderCalled=false`. Regression tests also use a decoder that fails the test if rejected data reaches it. The existing post-decode row check remains as a second check.

This requires malformed or malicious data from the fixed HTTPS publisher catalogue. A post author cannot supply an arbitrary native fetch URL through this path. The finding demonstrates an allocation risk, not native SSRF or a proven crash on every Android device.

### A2-03: renderer failure and lifecycle cleanup

Android documents that each WebView affected by renderer loss must handle the callback, and that an affected view cannot be reused. The optional offline attribution renderer previously lacked that handler. Its failure could therefore terminate the app even though its JavaScript, storage and network privileges were already disabled.

The optional renderer now enters a native unavailable state, drops its retained document and releases the affected view without automatically loading the same HTML again. The feed and authentication views use idempotent release and guard delayed operations against released views. Cleanup steps are independent so a failed stop/settings operation cannot skip destruction. Origin, permission, storage and offline-rendering restrictions remain in force.

Primary reference: [Android WebViewClient](https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)). Physical renderer-loss and rotation/sign-out lifecycle tests remain part of device validation.

### A2-04 and A2-05: verification must inspect Android's actual representations

Two offline fixtures compiled with Android's AAPT2 were accepted by the baseline verifier:

- A service declared `android:exported` through a Boolean resource whose value was `true`. `apkanalyzer` represented it as a resource reference, which the old string comparison treated as unexported.
- A safe default TLS XML file accompanied by a `values-v35` alias to an unsafe policy. The old resource parser collected file values but skipped aliases, so it inspected the default while Android could select the override.

The reviewed application's policies did not contain those unsafe declarations. These findings concern gaps in detecting future dependency or build regressions. The updated verifier rejects ambiguous component values and policy aliases/configuration overrides, checks package/SDK floors, and retains manifest-to-resource binding.

The marker scan now covers raw resources, native libraries and root archive entries as well as DEX, resources and assets. Known textual markers are checked in UTF-8, UTF-16LE and UTF-16BE. Reads use 64 KiB chunks with overlap, and tests place a marker across a chunk boundary. This remains known-marker screening; it cannot prove arbitrary secrets or obfuscated malicious code are absent.

`scripts/test_android_release_verifier.py` contains **22** offline fixture tests, including positive safe policies and deliberately unsafe compiled representations. The fixtures contain a DEX marker only and are never installed or executed. They test policy inspection, not application runtime behaviour.

### A2-06 and additional platform protection

CI now assembles the optional-AI Release APK with deliberately nonfunctional Firebase configuration and runs the same packaged-security checks as the default build. Both verifier JSON outputs are retained with test/lint evidence. No real AI request is made.

The manifest also explicitly opts into `allowCrossUidActivitySwitchFromBelow="false"`, and the APK check preserves that setting. This enables the restriction on platform implementations where it is supported and enabled. Android release sources differ in compatibility/runtime gating, so this report does not claim universal enforcement on every supported device. The older StrandHogg issue is not reported as a new vulnerability: Android's [mitigation guidance](https://developer.android.com/privacy-and-security/risks/strandhogg) places its fixes below Robin's API 33 minimum.

## Dependency evidence

The fresh OSV query at **2026-10-28 03:44:23 UTC** covered **416 unique selected locked Maven coordinates**, including **201 Release runtime coordinates**. It found **zero matching advisory coordinates**. The [machine-readable results](security/android-dependency-scan-2026-10-28.json) retain the coordinates, scope and query time.

All selected coordinates are represented in verification metadata; its **850 artifact records** have SHA-256 values. No dynamic locked versions or trusted-artifact bypass entries were found. The reviewed October Gradle advisories identify fixes in 9.8.1: [repository TLS failure handling](https://github.com/gradle/gradle/security/advisories/GHSA-j5m7-59rp-24f5), [client/daemon deserialisation](https://github.com/gradle/gradle/security/advisories/GHSA-xwqc-3h47-hg64) and [worker/daemon authentication](https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8). The committed 9.8.2 distribution and its official checksum remain suitable for those specific advisories. This review does not infer vulnerability from the mere availability of a newer dependency.

OSV matching is not an APK SBOM, and does not cover the phone's OS/WebView or all JDK, SDK, Gradle-distribution and unknown vulnerabilities.

## Validation and use

The workflow runs the shipped-JavaScript fixtures, real AAPT2 verifier fixtures, JVM policies, Debug/Release lint, default Debug/Release builds, inspection of the default Release and build/inspection of the optional-AI Release. Dependency checksums, strict locks, minification and resource shrinking stay enabled. The pull request and its workflow run record the final revision and results; a test's presence alone is not a passing build result.

Use a privately signed Release from the reviewed fork, initially with AI disabled. Follow the [build guide](mobile.md#android-client) and [security/device-validation guide](android-security.md). Strict authentication origins and cookie restrictions can reject unsupported login flows. Keep strong account authentication enabled rather than weakening it to fit a flow.

No physical-device login, passkey, Android renderer crash, interrupted sign-out, successful cloud attestation or paid AI request was performed during this review. Browser-engine vulnerabilities, a compromised X origin, malicious privileged device software and session copies obtained before local sign-out remain outside the guarantees these app changes can provide. iOS and desktop implementations are outside this Android review.

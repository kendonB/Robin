# WebView filter (Android + iOS)

> **Android security changes in this fork:** [android-security.md](android-security.md) describes the current origin-scoped bridge, strict navigation, default-off AI and removed telemetry. The upstream platform overview below also describes iOS and historical behaviour; use the Android security guide for Android trust and build decisions.

Primary mobile UI is an in-app `x.com` session with an injected filter core (Following by default, optional For You; hide ads / page chrome), adapted from [minimal-twitter](https://github.com/typefully/minimal-twitter) (MIT).

Desktop remains a normal browser + extension (out of scope).

## Shared filter core

Keep these trees in sync (same JS/CSS behavior):

| Platform | Path |
|----------|------|
| Android | [`android/app/src/main/assets/x-filter/`](../android/app/src/main/assets/x-filter/) |
| iOS | [`ios/Robin/Resources/x-filter/`](../ios/Robin/Resources/x-filter/) |

Host sets `window.__ROBIN_SETTINGS__` before `filter-core.js` runs. CSS id: `robin-x-filter-css`.

## Platform hosts

| | Android | iOS |
|--|---------|-----|
| Screen | `XWebFeedScreen` | `XWebFeedView` |
| Inject | `XFilterInjector` + document-start (`androidx.webkit`) | `XFilterInjector` + `WKUserScript` `.atDocumentStart` |
| Settings | `FilterSettingsStore` (DataStore) | `FilterSettingsStore` (UserDefaults) |
| Text size | `FontScaleStore` → `html` zoom | `FontScaleStore` → `html` `-webkit-text-size-adjust` (WKWebView text ignores `zoom`); on iOS-app-on-Mac also scales X's fixed px `line-height` rules |
| Sign out | clear cookies / `WebStorage` → login URL | `WKWebsiteDataStore` → login URL |
| Layout | Mobile Chrome UA (best-effort) | iPhone Safari UA + `preferredContentMode = .mobile` + document-start mobile identity spoof; on device viewport uses `device-width` (desktop ≥1000px breakpoints forced off); on Mac (`isiOSAppOnMac`) layout identity is frozen at 390px so X mounts the mobile shell |
| Boot cover | Native overlay + `RobinBoot.ready()` | Native overlay + `mtBoot` script message |
| Benchmark | `BenchmarkStore` (SharedPreferences) + ADB broadcast | `RobinBenchmarkStore` (UserDefaults) + ScriptMessageHandler |

X may still try **desktop** chrome on wide WebViews. iOS injects a force-mobile script (mobile UA; blocks ≥1000px desktop breakpoints). Pointer / hover media queries are **not** spoofed — mice and trackpads on phones, iPads, and Mac must report real `(hover: hover)` / `(pointer: fine)` so mouseover and CSS `:hover` work. **Phones/iPads** lay out to the real WebView width (landscape / tablet widen up to ~840px). **Mac Designed-for-iPhone/iPad** freezes `innerWidth` / viewport at phone size — a wide Mac WebView otherwise mounts desktop/hybrid DOM while Robin hides those rails, which blanks the feed. On Android, Compose’s `AndroidView` interop can drop mouse hover; the feed screen synthesizes `ACTION_HOVER_*` into the WebView from Compose pointer events. On home, X’s avatar / logo / Subscribe row is always hidden — the native Robin header replaces it. That row and the category tab strip often share `TopNavBar` / `header[role="banner"]` (the strip mounts after first paint), so those containers are hidden only when they do not contain the tabs; otherwise JS removes the avatar row and leaves the strip. `hidePageHeader` gates the category tab list (`ScrollSnap-List`); Settings “Show Feed Tab” (default off) shows those tabs without bringing back the avatar row. Desktop left/right rails stay hidden and `primaryColumn` expands. Home tab forcing only clicks label-verified For You / Following controls.

### Boot gate (initial launch)

While filter-core settles the chosen home tab (Following or For You) / Latest / chrome hides, `html:not([data-mt-boot-ready="1"]) body` keeps the page invisible. Bootstrap removes `data-mt-boot-ready` on every full navigation; `applySettingsOnly` must not. When ready (or after a ~5s hard timeout / non-home / login CTA), JS sets `data-mt-boot-ready="1"` and notifies the host once (`webkit.messageHandlers.mtBoot` / `RobinBoot.ready()`). Hosts show a brief “Preparing feed…” cover over the WebView until that signal (native timeout ~5.5s as a backstop).

iOS arms the cover from the **pending main-frame URL** (captured in `decidePolicyFor`) as well as `webView.url`, because during back-to-home provisional navigation `webView.url` can still be the status page.

A cache-bypassing `/home` load sometimes commits X’s logged-in empty state (“Welcome to X!” / “Let’s go!”) instead of the timeline; the next full load returns the feed. Filter-core waits until the first home-timeline **response** arrives before clicking Following or Recent, so a tab click cannot abort that response into the empty state. The welcome interstitial is not treated as a settled feed. If that copy is still up after ~1.5s with no posts, filter-core asks the host once (`mtRetry` / `RobinBoot.retry()`) to load `/home` again under the boot cover or warm snapshot already showing. A second welcome in a row is left up when the cover times out.

### Near-top auto-refresh (newer posts)

X’s mobile web does not reliably surface newer posts while Robin sits at the top of Following. Hosts auto-refresh when:

- The app returns to the foreground (`scenePhase` / `ON_RESUME`), or
- The user taps the Robin title while already near the top (`scrollY ≤ ~80`), or
- While foregrounded and near the top, a ~15s poll tick runs the same refresh path (no user action required)

Guards: feed home only, near top, not mid-load / boot cover, cooldown equal to the Settings **Auto-refresh** interval (Off / 30s / 1 min / 2 min / 5 min; default 5 min). Automatic triggers (poll, foreground return) also require the user to be idle: no touch, scroll, key, wheel, or pointer movement in the feed for ~20s (reported by the scroll probe via `mtInteract` / `RobinInteract`, plus a native touch listener on Android), and Settings not open. Title tap is user-initiated and skips the idle check. Off disables poll and foreground auto-refresh; title tap and the toolbar Refresh button still work.

Flow: warm hard-reload of `https://x.com/home` with cache bypass (`reloadIgnoringLocalCacheData` / `LOAD_NO_CACHE`). Soft “Show N posts” clicks stay in filter-core’s MutationObserver auto-click path — host-driven refresh no longer goes through `__ROBIN_SOFT_REFRESH_FEED__`, because SPA `reload()` often reused a cached document and left the same stale Following posts in place. Warm reloads snapshot the current feed (`WKWebView.takeSnapshot` / `WebView.draw` into a `Bitmap`), overlay that image on the WebView, load underneath, then crossfade the snapshot away (~0.45s) on boot-ready (5.5s fallback). The boot-gated blank document is never visible, so there is no white flash.

While near the top of Following, filter-core also **auto-clicks** X’s own “Show N posts” pill as soon as it appears (MutationObserver tick, 2.5s throttle) — same affordance as desktop/mobile web, without waiting for a native refresh. That control often sits inside TopNavBar / sticky chrome that Robin hides; discovery ignores visibility and briefly clears those hides (`data-mt-reveal-feed-chrome`) for the click, then restores chrome.

Title tap while scrolled down still only scrolls to top. The toolbar Refresh button always reloads (warm snapshot crossfade after first reveal).

**Mac (iOS app on Mac):** keep polling while `.inactive` as well (window often stays visible without being key). Stop only on `.background` / disappear.

### Leave-home chrome cleanup

`hidePageHeaderFallback` / `hideComposeButtonFallback` mark nodes with `data-mt-robin-hide` when applying inline `display: none`. On SPA leave-home, `clearRobinHides()` removes those styles so a shared timeline ancestor is not left blank after back from a post.

Opening a post snapshots the feed scroll offset, and the tapped row’s distance from the top of the viewport, on pointer-down — before the tap scrolls that row upward. Returning to home puts the header-hide flags back immediately and pins that snapshot until the home tab row is gone again. Scroll-idle used to replace the snapshot with a raw scroll offset before that row was removed, so the posts landed about one tab-row higher. Scrolling the open post does not discard the snapshot. A real drag, wheel, or key on the feed cancels the pin.

### Caught-up marker (Instagram-style)

When browsing the Following timeline, filter-core tracks the most recent (topmost) post viewed during each session. On subsequent visits (after an app restart, feed refresh, or >15m idle backgrounding), an in-feed marker is placed right above that post, but only when newer posts sit above it. If the user opens the feed already caught up, the banner is not shown at the top:
- Checkmark icon with copy: **You're all caught up** / *You've seen all new posts from last time*.
- Clearly demarcates newly posted tweets from tweets already viewed in the previous visit.
- Seamlessly attached to the target post and virtualizes with the feed.
- If the exact post was deleted or removed, gracefully falls back to the first post older than the target timestamp.
- Automatically cleans up when navigating away from the home timeline.

### Long-press to fact-check

Long-pressing on any tweet article (`article[data-testid="tweet"]`) triggers an AI-powered credibility analysis:
- **Gesture timing**: ~400ms hold timer initiated on `pointerdown` (or native `contextmenu` hold event on Android). Automatically cancelled if the finger moves >18px (`pointermove`), if the user scrolls/wheels, or on `pointerup` / `pointercancel`. Ignored if tapping input, textarea, or video elements.
- **Visual feedback**: Injected CSS class `.robin-factcheck-pulse` performs a subtle breathing scale and opacity pulse on the target article for 600ms. Native context menu is suppressed for 800ms.
- **Tweet data extraction**: Extracts text (including nested quoted tweets via `data-testid="quoteTweet"`), author user name/handle, canonical tweet URL (`https://x.com/i/status/<statusId>`), and out-links (`t.co` and card URLs).
- **Native bridge**: Sends the structured JSON payload to the native platform via `window.webkit.messageHandlers.robinFactCheck.postMessage(payload)` on iOS and `window.RobinFactCheck.factCheck(jsonString)` on Android.
- **Native feedback & sheet**: Host fires medium haptic feedback on iOS (`UIImpactFeedbackGenerator`) / haptic vibration on Android, and presents a native fact-check sheet that contacts Gemini 3.8 Flash via Firebase AI Logic.

## Defaults

All filter toggles default **on**; page header hide **on** (default to hide in-webview category tabs; toggle “Show Feed Tab” in Settings to keep those tabs — avatar / logo / Subscribe stay hidden either way); X’s compose FAB is always hidden (native header New post); live / Spaces / broadcast promo hide **on** (`hideLiveContent`). Home feed defaults to **Following (by time)** (`forceFollowing`, `hideForYouTab`, `preferLatest` all true). Settings offers two modes: Following (by time) and For You (clears `forceFollowing` / `hideForYouTab`; `preferLatest` is Following-only). Font scale defaults to **1.0** (100%), max **2.0**.

`hideKeywords` is a device-local list of words and phrases (default empty, so nothing extra is hidden). A post is hidden when its text includes one as a whole word or phrase: `cat` hides `the cat.` and does not hide `category`, `cats`, or `bobcat`. The status permalink you opened stays visible; matching replies still hide.

On home, `hideLiveContent` adds `mt-hide-live` on `html` and hides live / broadcast / Spaces / event promo chips (including magenta “+N · Event” banners inside tweets) plus dedicated live promo rows, while leaving normal tweet bodies intact.

`preferLatest` applies only in **Following (by time)** mode. It watches the home feed request X sends. Following → Popular and Following → Recent both call the `HomeLatestTimeline` GraphQL op; only the `enableRanking` variable differs (`true` = Popular, `false` = Recent). `HomeTimeline` (For you) counts as ranked. When the feed is ranked, it follows Minimal Twitter’s click chain: open the Following sort control and choose **Recent** (desktop: Timeline options / Top Tweets). Until an unranked request is seen, `data-mt-defer-feed-chrome` keeps `TopNavBar` visible so that control can be clicked.

Recent is ordered by *activity* time. Reposts show the original post’s age and reply threads show their older parent first, so visible timestamps can look out of order even when the feed is chronological.

## Login / SSO

Google and Apple sign-in use `window.open`. Hosts present a **real in-app child WebView** (`WKUIDelegate.createWebViewWith` / `WebChromeClient.onCreateWindow`) so `window.opener` works — do **not** fold into the main frame or hand off to Safari/Chrome. Filter scripts stay off the OAuth window; stock user agent on the popup.

## External links

Main-frame http(s) navigations stay in the WebView only for allowlisted hosts (x.com / twitter.com + Google/Apple OAuth CDNs). **`t.co` is not allowlisted** — X opens tweet links via `window.open(https://t.co/…)`, and hosts present an **in-app system browser sheet** (`SFSafariViewController` / Chrome Custom Tabs) instead of spawning the OAuth child WebView. Done / back returns to the feed. Other destinations (articles, link previews) behave the same. Custom schemes (`twitter://`, `x://`, `intent://` nags) stay blocked or rewritten in-app as before.

## Performance Benchmarking

Robin includes built-in in-app benchmarking instrumentation across Android and iOS to measure and protect WebView rendering performance (initial load time, fast scroll settle time, and layout reflow counts).

See [performance-benchmark.md](performance-benchmark.md) for full baseline data, regression thresholds, and execution steps (via in-app Settings UI, automated ADB broadcast, or headless synthetic engine).


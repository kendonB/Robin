# Robin

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
> Unofficial project. Not affiliated with, endorsed by, or sponsored by X Corp. or Twitter.

**Personal Android fork:** start with [the Android security and build guide](docs/android-security.md). Android AI is disabled by default, requests require native confirmation when enabled, and Android telemetry has been removed. Build this fork's Release variant for these protections; the upstream store links below refer to separate binaries. The security changes cover Android; iOS and desktop retain upstream behaviour.

<p>
  <a href="https://apps.apple.com/us/app/robin-non-algorithmic-x/id6817063233"><img src="https://tools.applemediaservices.com/api/badges/download-on-the-app-store/black/en-us?size=250x83" alt="Download on the App Store" height="40"></a>
  &nbsp;
  <a href="https://play.google.com/store/apps/details?id=org.evergreenlabs.robin"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="60"></a>
</p>

## Why

X is still one of the fastest places to follow live events and hear directly from domain experts. But the default experience has devolved into heavy information pollution: algorithmic clickbait, sponsored posts, bot amplification, and endless replies from accounts you never chose to follow.

When our daily information diet shapes our worldview, that noise takes a real toll. Half-truths spread faster than corrections, and recommendation engines are optimized to provoke emotional reactions rather than keep us informed. Robin is built to fight this information pollution: preserve the essential signals you actually care about, strip away algorithmic distractions, and give you back control over your attention.

Robin is guided by a few core principles:

1. **Chronological signal over algorithmic manipulation**: A pure Following feed in true reverse-chronological order. No recommendation engine deciding what you see next.
2. **Promotion-free timeline**: Promoted posts and ad clutter are removed so your timeline only reflects the people and publications you follow.
3. **Keyword filtering**: Filter out topics, rage bait or noise by hiding posts containing your choice of keywords.
4. **Less doomscrolling**: An in-feed marker ("You're all caught up") indicates when you have reached posts from your previous visit, giving you a clear stopping point instead of scrolling forever.
5. **On-demand fact-checking**: When a post seems dubious or sensational, long-press it to check claims against live web search evidence and credibility scores powered by generative AI.

Robin is a native **Android** and **iOS** client: an x.com WebView with client-side filters. Sign-in happens directly inside the WebView with your normal X cookie session.

### How much of For You is actually from people you follow?

To put a number on the noise: we scrolled one logged-in **For You** home timeline (~87 posts), labeled each item as an ad (explicit "Ad"), checked each author against that account's follow graph, and tallied the mix.

| What you see on For You | Share of posts |
| --- | ---: |
| People you follow | **~10%** |
| Algorithmic inserts (accounts you do not follow) | **~70%** |
| Network inserts (e.g. "X reposted") | **~1%** |
| Ads | **~18%** |

In other words, only about **one in ten** posts on For You came from someone that account chose to follow. Roughly **nine in ten** were algorithm add-ons, ads, or similar. (One scroll session: your mix will vary, but the shape of the problem is hard to miss.)

I hope Robin helps you stay informed without the mental exhaustion of sorting through noise and distractions.

**Architecture:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · **Mobile setup:** [docs/mobile.md](docs/mobile.md) · **WebView filters:** [docs/webview-filter.md](docs/webview-filter.md) · **Contributing:** [CONTRIBUTING.md](CONTRIBUTING.md) · **Security:** [SECURITY.md](SECURITY.md)

## Features

- Following-first feed via filters on x.com (force Following / Latest, hide For You)
- Hide promoted posts, page chrome, compose prompts, and related clutter
- Keyword filtering to hide posts containing specified words or phrases
- In-feed "You're all caught up" divider marking where you left off last visit to reduce doomscrolling
- Long-press a post to request AI fact-checking (Android requires explicit build opt-in and per-request confirmation)
- Text size controls
- Sign out clears the X WebView cookie session
- Android has no Sentry or Aptabase; iOS retains its optional configuration

## Stack

- **Android** — Jetpack Compose, WebView, minSdk 33
- **iOS** — SwiftUI, WKWebView, iOS 18+
- Shared filter core adapted from [Minimal Twitter](https://github.com/typefully/minimal-twitter) (MIT)

## Get started

| Platform | Guide |
|----------|--------|
| Android | [docs/mobile.md](docs/mobile.md#android-client) |
| iOS | [docs/mobile.md](docs/mobile.md#ios-client) |
| Store builds | Homebrew Fastlane (`fastlane/`) — see Fastfile |

Quick Android development build (use a privately signed Release build for your primary account):

```bash
cp android/local.properties.example android/local.properties
# set sdk.dir=…
cd android && ./gradlew :app:assembleDebug
```

Quick iOS:

```bash
cp ios/Config.xcconfig.example ios/Config.xcconfig
open ios/Robin.xcodeproj
```

## Project layout

```
android/                        # Jetpack Compose + x.com WebView
ios/                            # SwiftUI + WKWebView
fastlane/                       # Play / TestFlight release lanes
docs/mobile.md                  # Build & configure Android / iOS
docs/webview-filter.md          # Injected filter behavior
docs/ARCHITECTURE.md
scripts/feed-credibility/       # Collect + score For You vs Following
web/feed-compare/               # Local web UI for that comparison
LICENSE / CONTRIBUTING.md / SECURITY.md / CODE_OF_CONDUCT.md
```


## Compare Following and For you

The public X API does not expose For you (its home timeline is reverse-chronological only). This tool reads both timelines from a Chrome window you log into, fact-checks each post with the [Credibility Analyzer](https://github.com/billylo1/Simplifier) prompt (Gemini + Google Search), and counts how many it marks misleading.

```bash
GEMINI_API_KEY=... node web/feed-compare/server.cjs
```

Open http://127.0.0.1:8787. The server listens on localhost only. The X login stays in `~/.cache/feed-credibility-profile`, and a pasted Gemini key stays in that process.

## License

[MIT](LICENSE). Feed filters include adaptations from Minimal Theme for Twitter / X (MIT).

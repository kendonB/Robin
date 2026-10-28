# Security Policy

## Supported versions

Security fixes are applied to the latest commit on the default branch.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security problems (especially anything involving credentials, tokens, or auth bypass).

Prefer one of:

1. GitHub's private vulnerability reporting for the affected repository, if enabled, or
2. Email the maintainer via the contact listed on the GitHub profile for this repository.

Include a short description, steps to reproduce, and impact. We will acknowledge reports and work on a fix as soon as practical.

## Secrets hygiene

Never commit `android/local.properties`, `android/keystore.properties`, `ios/Config.xcconfig`, `fastlane/.env`, upload keystores, or live Sentry / Aptabase credentials. See `.gitignore` and the examples under `android/`, `ios/`, and `fastlane/.env.example`.

## Privacy model

Robin does not run a custom backend. The apps load x.com in a WebView and keep the session in the WebView cookie / website data store.

This fork's Android app has no Sentry or Aptabase. AI is disabled by default; an explicitly configured build requires native confirmation before sending selected post data to Google. Read [the Android security model, build guidance and validation limits](docs/android-security.md). iOS retains upstream's optional telemetry configuration. Changes to this fork do not alter binaries distributed through upstream app-store accounts.

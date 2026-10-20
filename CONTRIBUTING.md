# Contributing to Yomira

Yomira is an independent Suwayomi client forked from [Komikku](https://github.com/komikku-app/komikku). Komikku, Mihon, TachiyomiSY, Tachiyomi, and Suwayomi remain credited in the app, README, and [NOTICE](./NOTICE).

Report Yomira bugs and request features in [this repository](https://github.com/MathisVerstrepen/komikku-suwayomi/issues). Include the Yomira version, Android version, Suwayomi version, authentication mode, reproduction steps, and redacted logs when relevant. Server source extensions execute on Suwayomi; include the source and extension version for provider-specific issues.

## Development

Use JDK 21 and Android SDK 36. Read [AGENTS.md](./AGENTS.md) for architecture, source markers, and build rules. Before submitting Kotlin or Android XML changes, run:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
```

Run `./gradlew testReleaseUnitTest` for the release unit-test suite. Live tests that mutate server data require a disposable test server.

Keep fork-specific strings in the base locale of `i18n-kmk`, and retain `KMK`, `SY`, and upstream provenance markers. Existing Komikku translations remain inherited; Yomira has no separate Weblate project. Do not send fork-specific issues or translation requests to upstream maintainers.

## Fork identity and attribution

The [original Komikku contribution and fork guide](./docs/UPSTREAM_KOMIKKU_CONTRIBUTING.md) is preserved verbatim. Its fork guidance requires a distinct app name and icon, a separate application ID, and independent update and reporting services.

Yomira uses its own name and artwork. Its existing `app.komikku.suwayomi` application ID is already separate from Komikku's `app.komikku` and is retained so existing users can upgrade. Upstream update checking and telemetry are disabled. Yomira releases use this repository's signing workflow and the existing fork signing key.

Retain the Apache-2.0 [LICENSE](./LICENSE), original copyright notices, source history, and upstream credits. Historical releases and compatibility identifiers may still say Komikku Suwayomi. See [branding and compatibility](./docs/BRANDING.md) before changing those identifiers.

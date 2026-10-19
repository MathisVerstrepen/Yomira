# Komikku Suwayomi

<img src="./.github/readme-images/app-icon-suwayomi.png" alt="Komikku Suwayomi icon" width="120" height="120" />

An Android client for **Suwayomi-Server**, adapted from [Komikku](https://github.com/komikku-app/komikku). It retains Komikku's native navigation, library, manga details, and configurable reader while using Suwayomi for the library, sources, reading progress, trackers, and downloads.

This fork requires a reachable server and uses one active server/account connection. Android 8.0 (API 26) or later is required. The upstream Komikku APK is a separate application; build this repository to use this adaptation.

## How it works

The app keeps Komikku's **Library, Updates, History, Browse, and More** tabs. Existing content and scroll position remain visible while background refreshes fetch updated server data.

Suwayomi owns the library and executes source extensions. The phone displays temporary server projections and streams chapter pages through the authenticated connection. Existing phone-local Komikku libraries are not imported into the server.

On servers with a shared library, changes to manga, categories, reading state, settings, trackers, and the download queue can affect other clients. Per-user isolation depends on the server's API and permissions.

## Supported workflows

- **Library:** native cover grids and lists, badges, search, filters, sorting, grouping, category management, and bulk actions.
- **Reading:** Komikku's reader modes, themes, gestures, chapter controls, server progress/resume, read/unread state, and bookmarks.
- **Updates and history:** server-backed chapter updates and recent reading with pagination.
- **Browse:** authenticated provider logos, individual source browsing and search, popular/latest lists, source filters, and global manga search across providers. Browse and global search respect the server WebUI's saved language and enabled-source restrictions.
- **Downloads:** view and control the server queue, including start/pause, reorder, cancel, and clear. Files remain on the server.
- **Trackers:** use server-managed accounts, manga bindings, search, and record updates.
- **Administration:** manage server extensions, repositories, source preferences, and supported update/download settings. Some administration opens the server WebUI, which authenticates separately.
- **Synchronization:** background revalidation preserves loaded content. Reading changes use a persistent, connection-specific queue; failures or conflicting results remain visible for retry or discard.

## Connect to a server

1. Set up Suwayomi-Server and install/configure its source extensions. Configure the languages and providers you want to browse in the server WebUI.
2. Open the app and enter the full server URL, including any reverse-proxy path prefix.
3. Select the authentication mode configured on the server: `NONE`, `BASIC_AUTH`, `SIMPLE_LOGIN`, or `UI_LOGIN`. Enter credentials when required.
4. Connect to load the server library. Use Browse to find manga through the server's providers.

Use HTTPS outside a trusted network. Connection credentials are encrypted with Android Keystore and stored in the app's no-backup directory. Authentication also applies to images and subscriptions.

### Server compatibility

The implementation has been validated against these API contracts:

| Server | Library model |
| --- | --- |
| Suwayomi-Server **v2.4.2366** | One shared library |
| Suwayomi-Server **v2.4.2424**, commit `e2ce828abb81f0f5ce1a3fb86194a2746b519c85` | Per-user API inspected on 2026-10-18 |

Connection setup checks actual schema capabilities and protected reads. These are tested contracts, not a guarantee that every server release supports every operation. Available features depend on the server version and account permissions. Authentication mode must be selected during setup.

## Current limits

- **No phone offline-reading mode.** Server downloads are not device downloads; a reachable server is required. Image caching and queued reading changes do not provide an offline library.
- Android source APKs are not executed by this client. Install extensions on the server.
- Upstream workflows such as feeds, merged manga, recommendations, source migration, widgets, and phone-local backup/cloud-sync are not fully integrated into the server-backed app.
- Independent history deletion and reading-duration statistics require further server API integration.
- Only selected reading preferences synchronize through the server. Presentation and device settings, such as brightness and orientation, remain local.
- Incognito suppresses new personal reading-state writes from the app, but page requests and server-managed activity can still be observed by the server.

## Build and install

Use **JDK 21** and **Android SDK 36**. The repository includes the Gradle wrapper; bytecode targets Java 17. Configure the SDK through Android Studio or `sdk.dir` in your local `local.properties`.

```sh
git clone https://github.com/MathisVerstrepen/komikku-suwayomi.git
cd komikku-suwayomi
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
```

Debug APKs are written under `app/build/outputs/apk/debug/`. To install on a connected device or emulator:

```sh
./gradlew installDebug
```

The debug package is `app.komikku.dev`. The inherited release package is `app.komikku`; consider package identity before installing alongside another Komikku build. Default builds do not require Firebase or Google OAuth secrets.

### Tests

Run the Suwayomi unit tests with:

```sh
./gradlew :app:testDebugUnitTest --tests '*Suwayomi*Test'
```

Live-server tests are opt-in and skip without their environment configuration. `SuwayomiReadOnlyServerTest` exercises authenticated reads. **`SuwayomiLiveServerTest` changes server data and must use a disposable test server.** The [acceptance fixture script](./scripts/suwayomi_acceptance_fixture.py) supports isolated acceptance testing.

## Issues and contributions

Report adaptation bugs and feature requests in [this repository's issue tracker](https://github.com/MathisVerstrepen/komikku-suwayomi/issues). Include the app version or commit, server version and authentication mode, Android version, reproduction steps, and relevant redacted logs/screenshots. Keep passwords, tokens, and private server data out of reports.

Follow [AGENTS.md](./AGENTS.md) for repository conventions and required checks. Kotlin/XML changes require `spotlessApply`, `spotlessCheck`, and `assembleDebug`, in that order. New fork-specific strings belong in the base locale of `i18n-kmk`; changes to existing code use `KMK` markers. The inherited [contribution guide](./CONTRIBUTING.md) provides upstream development background; its upstream community links and standalone feature instructions may not apply to this adaptation. See also the [Code of Conduct](./CODE_OF_CONDUCT.md).

## Credits and license

Built on [Komikku](https://github.com/komikku-app/komikku), [Mihon](https://github.com/mihonapp/mihon), [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY), and Tachiyomi, with thanks to their contributors. Server functionality is provided by [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server).

This application hosts no manga content and is not affiliated with the content providers available through server extensions.

Licensed under the [Apache License 2.0](./LICENSE).

Copyright 2015 Javier Tomás. See the license and source files for additional notices.

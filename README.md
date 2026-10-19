# Komikku Suwayomi

<img src="./.github/readme-images/app-icon-suwayomi.png" alt="Komikku Suwayomi icon" width="120" height="120" />

An Android client for **Suwayomi-Server**, adapted from [Komikku](https://github.com/komikku-app/komikku). It retains Komikku's native navigation, library, manga details, and configurable reader while using Suwayomi for the library, sources, reading progress, trackers, and downloads.

This fork uses one active server/account connection. A reachable server is required for browsing, synchronization, and fetching new chapters; chapters downloaded to the phone can be read offline. Android 8.0 (API 26) or later is required.

Download this adaptation from [GitHub Releases](https://github.com/MathisVerstrepen/komikku-suwayomi/releases/latest); choose the universal APK if you are unsure which architecture to use. Install a newer signed release over the existing release to keep your app data. APK checksums are included in each release as `SHA256SUMS.txt`.

## How it works

The app keeps Komikku's **Library, Updates, History, Browse, and More** tabs. Existing content and scroll position remain visible while background refreshes fetch updated server data.

Suwayomi owns the library and executes source extensions. The phone displays server projections and streams chapter pages through the authenticated connection, or reads completed device downloads from private storage. Existing phone-local Komikku libraries are not imported into the server.

On servers with a shared library, changes to manga, categories, reading state, settings, trackers, and the download queue can affect other clients. Per-user isolation depends on the server's API and permissions.

## Supported workflows

- **Library:** native cover grids and lists, badges, search, filters, sorting, grouping, category management, and bulk actions.
- **Reading:** Komikku's reader modes, themes, gestures, chapter controls, server progress/resume, read/unread state, and bookmarks.
- **Updates and history:** server-backed chapter updates and recent reading with pagination.
- **Browse:** Sources and Extensions tabs with provider logos, source browsing and filters, popular/latest lists, and global manga search. Browse and search respect the server WebUI’s saved language and enabled-source restrictions. Install, update, or remove server extensions, configure source preferences, and manage repositories from the Extensions menu. Extensions and source preferences are shared server settings.
- **Downloads:** save chapters to the phone for offline reading, with a persistent queue, pause/resume, resumable page transfers, Wi-Fi-only downloads, and a storage limit. Separately view and control the server queue, including start/pause, reorder, cancel, and clear.
- **Trackers:** server-managed accounts with service logos and connection status, manga bindings, search, and record updates.
- **Server settings:** grouped library update rules, shared update schedules, automatic server downloads, and phone notification/cache controls. Additional administration opens the server WebUI, which authenticates separately.
- **Synchronization:** background revalidation preserves loaded content. Reading changes use a persistent, connection-specific queue; failures or conflicting results remain visible for retry or discard.

## Connect to a server

1. Set up Suwayomi-Server and install/configure its source extensions. Configure the languages and providers you want to browse in the server WebUI.
2. Open the app and enter the full server URL, including any reverse-proxy path prefix.
3. Choose the **Sign-in method** that matches the server configuration: **Server account** (`UI_LOGIN`), **No sign-in** (`NONE`), **HTTP Basic authentication** (`BASIC_AUTH`), or **Simple login (legacy)** (`SIMPLE_LOGIN`). Enter credentials when required.
4. Connect to load the server library. Use Browse to find manga through the server's providers.

Use HTTPS outside a trusted network. Connection credentials are encrypted with Android Keystore and stored in the app's no-backup directory. Authentication also applies to images and subscriptions.

Use **Settings → Server connection** to check the connection, sign in again, or copy reader defaults between the phone and server. **Server settings** controls updates and downloads; **Server tracking** connects tracking accounts. The automatic update schedule applies to the whole server: use 0 to disable it or an interval of at least 6 hours.

### Server compatibility

The implementation has been validated against these API contracts:

| Server | Library model |
| --- | --- |
| Suwayomi-Server **v2.4.2366** | One shared library |
| Suwayomi-Server **v2.4.2424**, commit `e2ce828abb81f0f5ce1a3fb86194a2746b519c85` | Per-user API inspected on 2026-10-18 |

Connection setup checks actual schema capabilities and protected reads. These are tested contracts, not a guarantee that every server release supports every operation. Available features depend on the server version and account permissions. The sign-in method must match the server configuration.

## Read offline

1. Open a title and use a chapter’s download action, or select chapters/titles in bulk. Choose **Download to phone**. **Download on server** manages the server’s separate copies. Chapter icons distinguish server downloads from phone downloads; tap the icon to choose another destination even when a copy already exists.
2. Open **More → Phone downloads** to check progress, pause/resume, retry, read saved chapters, or remove device copies.
3. Phone downloads default to **Wi-Fi only** and a **2 GiB storage limit**. The limit is editable in MiB (1024 MiB = 1 GiB). Transfers pause when Wi-Fi is lost or storage reaches the limit; freeing space or changing the settings allows them to continue. Completed pages are retained when a transfer is interrupted.
4. A chapter is **Available offline** only after every page is saved. Completed downloads include title/chapter metadata and a cover when available, so they remain accessible after restarting without a server connection. **Downloaded on phone only** in More filters the library to device copies.
5. Read normally in Komikku’s reader. Reading progress and bookmarks are saved locally and queued for synchronization after reconnecting, including after process restart. Uncertain mutations or account/identity conflicts remain visible for deliberate retry or discard.

Downloaded chapters use private app storage, outside Android’s disposable image cache, and are excluded from Android backup. Storage accounting includes saved pages, partial transfers, covers, and metadata. Lowering the limit preserves existing downloads; new transfers wait until there is room. Deleting a phone copy preserves server files and reading progress. Replacing/disconnecting the server account clears its phone downloads and pending changes; reauthenticating the same account preserves them.

## Current limits

- Offline availability applies to completed phone downloads. Server downloads alone do not make a chapter available on the phone. Browsing providers, fetching additional chapters, and server administration still require a connection.
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

The release package is `app.komikku.suwayomi`; debug builds use `app.komikku.suwayomi.dev`. This adaptation can install alongside upstream Komikku. Earlier development builds using `app.komikku.dev` remain separate and do not transfer their connection automatically. Default builds do not require Firebase or Google OAuth secrets. The upstream app updater is disabled in published builds.

### Tests

Run the Suwayomi unit tests with:

```sh
./gradlew :app:testReleaseUnitTest --tests '*Suwayomi*Test'
```

The release workflow runs the full unit-test suite with `./gradlew testReleaseUnitTest`. Live-server tests are opt-in and skip without their environment configuration. `SuwayomiReadOnlyServerTest` exercises authenticated reads. **`SuwayomiLiveServerTest` changes server data and must use a disposable test server.** The [acceptance fixture script](./scripts/suwayomi_acceptance_fixture.py) supports isolated acceptance testing.

## Issues and contributions

Report adaptation bugs and feature requests in [this repository's issue tracker](https://github.com/MathisVerstrepen/komikku-suwayomi/issues). Include the app version or commit, server version and authentication mode, Android version, reproduction steps, and relevant redacted logs/screenshots. Keep passwords, tokens, and private server data out of reports.

Follow [AGENTS.md](./AGENTS.md) for repository conventions and required checks. Kotlin/XML changes require `spotlessApply`, `spotlessCheck`, and `assembleDebug`, in that order. New fork-specific strings belong in the base locale of `i18n-kmk`; changes to existing code use `KMK` markers. The inherited [contribution guide](./CONTRIBUTING.md) provides upstream development background; its upstream community links and standalone feature instructions may not apply to this adaptation. See also the [Code of Conduct](./CODE_OF_CONDUCT.md).

## Credits and license

Built on [Komikku](https://github.com/komikku-app/komikku), [Mihon](https://github.com/mihonapp/mihon), [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY), and Tachiyomi, with thanks to their contributors. Server functionality is provided by [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server).

This application hosts no manga content and is not affiliated with the content providers available through server extensions.

Licensed under the [Apache License 2.0](./LICENSE).

Copyright 2015 Javier Tomás. See the license and source files for additional notices.

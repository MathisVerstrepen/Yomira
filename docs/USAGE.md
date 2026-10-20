# Using Yomira

[← Project overview](../README.md)

Yomira connects to one active Suwayomi server/account. Browse and fetch new chapters while connected, or read completed phone downloads offline.

## Find your way around

| Screen | What you can do |
| --- | --- |
| **Library** | View covers or lists; search, filter, sort, group, organize categories, and select titles in bulk |
| **Updates / History** | Check server chapter updates and recent reading |
| **Browse** | Search across sources; use popular/latest lists and source filters; install/update/remove server extensions; configure source preferences and repositories; migrate library titles to other providers |
| **Title details** | Read/resume, bookmark, select chapters, choose a download destination, manage tracker bindings, and start migration from More → Migrate |
| **More** | Open the server queue, phone downloads, categories, settings, and account controls |

Source browsing and search follow WebUI's saved language and enabled-source restrictions. Extensions and source preferences are shared server configuration.

## Connect to a server

1. Set up Suwayomi-Server and install/configure its source extensions. Configure the languages and providers you want to browse in the server WebUI.
2. Open the app and enter the full server URL, including any reverse-proxy path prefix.
3. Choose the **Sign-in method** that matches the server configuration: **Server account** (`UI_LOGIN`), **No sign-in** (`NONE`), **HTTP Basic authentication** (`BASIC_AUTH`), or **Simple login (legacy)** (`SIMPLE_LOGIN`). Enter credentials when required.
4. Connect to load the server library. Use Browse to find manga through the server's providers.

Use HTTPS outside a trusted network. Connection credentials are encrypted with Android Keystore and stored in the app's no-backup directory. Authentication also applies to images and subscriptions.

Use **Settings → Server connection** to check the connection, sign in again, or copy reader defaults between the phone and server. **Server settings** controls updates and downloads; **Server tracking** connects tracking accounts. The automatic update schedule applies to the whole server: use 0 to disable it or an interval of at least 6 hours.

### Server compatibility

Connection setup checks actual schema capabilities and protected reads. Recorded validation has different scopes:

| Server | Scope |
| --- | --- |
| Suwayomi-Server **v2.4.2366** | Integration work against a shared-library server |
| Suwayomi-Server **v2.4.2422** | Local library, title details, and reader checked on an emulator on 2026-10-20 |
| Suwayomi-Server **v2.4.2424**, commit `e2ce828abb81f0f5ce1a3fb86194a2746b519c85` | Per-user API inspected on 2026-10-18 |

These are recorded checks, not a guarantee that every server release supports every operation. Available features depend on the server version and account permissions. The sign-in method must match the server configuration.

## Read offline

1. Open a title and use a chapter’s download action, or select chapters/titles in bulk. Choose **Download to phone**. **Download on server** manages the server’s separate copies. Chapter icons distinguish server downloads from phone downloads; tap the icon to choose another destination even when a copy already exists.
2. Open **More → Phone downloads** to check progress, pause/resume, retry, read saved chapters, or remove device copies.
3. Phone downloads default to **Wi-Fi only** and a **2 GiB storage limit**. The limit is editable in MiB (1024 MiB = 1 GiB). Transfers pause when Wi-Fi is lost or storage reaches the limit; freeing space or changing the settings allows them to continue. Completed pages are retained when a transfer is interrupted.
4. A chapter is **Available offline** only after every page is saved. Completed downloads include title/chapter metadata and a cover when available, so they remain accessible after restarting without a server connection. **Downloaded on phone only** in More filters the library to device copies.
5. Read normally in Yomira’s inherited Komikku reader. Reading progress and bookmarks are saved locally and queued for synchronization after reconnecting, including after process restart. Uncertain mutations or account/identity conflicts remain visible for deliberate retry or discard.

Downloaded chapters use private app storage, outside Android’s disposable image cache, and are excluded from Android backup. Storage accounting includes saved pages, partial transfers, covers, and metadata. Lowering the limit preserves existing downloads; new transfers wait until there is room. Deleting a phone copy preserves server files and reading progress. Replacing/disconnecting the server account clears its phone downloads and pending changes; reauthenticating the same account preserves them.

## Migrate providers

1. Open **Browse → Migrate**. Providers are listed with their library title counts, including disabled or unavailable original providers. Search or sort by name/title count, open a provider, then select titles. **Select matching titles** applies to the current search while retaining other selections. For a single library title, use its **More → Migrate** menu instead.
2. Choose a replacement provider or **Search all providers**. Replacement searches follow the server’s enabled-provider and language settings. In all-provider search, each provider initially shows up to three results; expand a group to see more.
3. Choose a replacement title to load its chapter preview. Cards show chapter counts when available and check missing counts as results become visible. Review the chapter matches, using the **Unmatched** and **Saved** filters when helpful.
4. Tap **Continue** and choose whether to transfer reading progress, bookmarks, and categories. **Keep original in library** is **off by default**. Enable it to keep both entries after success. If unmatched chapters contain selected progress or bookmarks, retaining the original is required unless you choose a better replacement or change the transfer options.
5. Confirm the transfer. Existing replacement progress, bookmarks, and categories are preserved. The app verifies the selected data before removing the original from the library. For several selected titles, review each replacement in turn; use **Skip title**, **Next title**, or **Finish** to move through the queue.

Read chapters transfer their completed status. For unfinished chapters, saved page positions are limited to the replacement chapter’s length. Downloads remain attached to the original entry and are not moved to the replacement.

If a request fails or the saved state changes, **Reload preview** before retrying. A failed or unverified transfer keeps the original in the library. Transfers require a server connection and permission to change library/reading state; they are unavailable in incognito mode.

## Current limits

- Offline availability applies to completed phone downloads. Server downloads alone do not make a chapter available on the phone. Browsing providers, fetching additional chapters, and server administration still require a connection.
- Android source APKs are not executed by this client. Install extensions on the server.
- Upstream workflows such as feeds, merged manga, recommendations, widgets, and phone-local backup/cloud-sync are not fully integrated into the server-backed app.
- Independent history deletion and reading-duration statistics require further server API integration.
- Only selected reading preferences synchronize through the server. Presentation and device settings, such as brightness and orientation, remain local.
- Incognito suppresses new personal reading-state writes from the app, but page requests and server-managed activity can still be observed by the server.

## Shared state and local settings

Suwayomi stores the library, categories, source configuration, tracker accounts, and server download queue. On shared-library servers, changing these can affect other clients. Account isolation depends on the server API and permissions.

Yomira keeps its reader interface, presentation settings, private phone downloads, and pending offline reading changes on Android. Brightness and orientation remain device settings. Existing phone-local Komikku libraries are not imported into the server.

Loaded content and scroll position remain visible during background refreshes. Reading changes use a persistent queue tied to the active connection. Failed or conflicting changes remain visible for retry or discard.

## More help

- [Architecture and data ownership](./ARCHITECTURE.md)
- [Privacy](./PRIVACY.md)
- [App identity and upgrade compatibility](./BRANDING.md)
- [Local screenshot server](../tools/screenshots/README.md)

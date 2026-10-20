# Client comparison: scope and sources

[← Comparison table](../README.md#compare-the-clients)

Reviewed **20 October 2026**. This compares the projects' own documentation and source code, with a focused WebUI browser check. It is not a benchmark or an exhaustive runtime test of every client. Stable releases and previews may differ. Server API compatibility and account permissions also affect what a client can do.

## What the rows mean

- **Primary library** describes the normal place the app stores its library and reading state. Original Komikku is a standalone Android app; its optional tracker and cloud integrations do not make its main library a Suwayomi client library.
- **Server downloads** are files managed by Suwayomi's downloader. They still require a connection between the reading device and server.
- **Offline reading from device downloads** means the user can save complete chapters on the reading device and open them after Suwayomi becomes unreachable. Image caching and preloading are useful, but do not count as managed chapter downloads. A “No” in this row does not mean every cached page immediately disappears when disconnected.
- **Tracker account management** means the client provides a UI to connect or disconnect tracking-service accounts through its own screens. Server support, API schema definitions, or accounts configured in another client do not establish that UI.
- **Yes** refers to the feature being present, not identical behavior, setting coverage, performance, or reliability across projects.

## Original Komikku

Snapshot: [`1656575e8`](https://github.com/komikku-app/komikku/tree/1656575e8fdaaef010c652f9758130c2a01d8890).

Its [feature list](https://github.com/komikku-app/komikku/blob/1656575e8fdaaef010c652f9758130c2a01d8890/README.md#features) describes local reading, its configurable reader, tracking, categories, and source migration. Yomira inherits that Android reader and interface, but its server mode does not implement every upstream workflow.

## Tachidesk-Sorayomi

Snapshot: [`df37f4ce7`](https://github.com/Suwayomi/Tachidesk-Sorayomi/tree/df37f4ce700a7db7c971301d0adc65b4f0721a6f). GitHub marks the [upstream repository](https://github.com/Suwayomi/Tachidesk-Sorayomi) as archived on 20 August 2026. This column documents its archived implementation, rather than implying ongoing maintenance or compatibility with every current server release.

- The [README](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/README.md) establishes Flutter, the server requirement, supported platforms, extension management, browsing, library access, and chapter reading.
- [Global search](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/browse_center/presentation/global_search/global_search_screen.dart) provides search across sources.
- The [download repository](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/manga_book/data/downloads/downloads_repository.dart) calls Suwayomi's enqueue/start/stop/reorder operations. This verifies server queue management, not persistent downloads on the client.
- The [migration controller](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/migration/controller/migration_controller.dart) and [selection screen](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/migration/presentation/screens/migration_source_selection_screen.dart) implement its migration workflow.

### Offline reading audit

**Conclusion: server downloads and reader image caching, without managed device-offline chapters.** This is an inference from the connected download, metadata, image, and progress paths in the archived source:

1. The download repository above enqueues chapters in Suwayomi. Even the [download-location setting](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/settings/presentation/downloads/data/downloads_settings_repository.dart) changes the server's `downloadsPath`; it does not select storage on the phone.
2. The [reader controller](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/manga_book/presentation/reader/controller/reader_controller.dart) loads chapter metadata and page lists through [MangaBookRepository](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/manga_book/data/manga_book/manga_book_repository.dart#L120-L162). Page lists use the server's `fetchChapterPages` mutation. The [GraphQL client](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/global_providers/global_providers.dart#L29-L80) defaults queries to `FetchPolicy.noCache`, despite configuring a Hive-backed cache.
3. [ServerImage](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/widgets/server_image.dart#L26-L133) uses `CachedNetworkImage` and `DefaultCacheManager`. The [single-page reader](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/manga_book/presentation/reader/widgets/reader_mode/single_page_reader_mode.dart#L47-L85) also preloads the previous page and next two pages. Some already loaded or prefetched images may therefore remain usable in an open reader. That is not a saved, complete chapter library.
4. [Reader progress](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/manga_book/presentation/reader/reader_screen.dart#L38-L109) goes to `putChapter`, which sends an `UpdateChapter` mutation. This path has no persistent offline progress outbox or reconnect synchronization workflow.

### Tracker management audit

**Conclusion: no built-in tracker-account management UI.** The [settings screen](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/features/settings/presentation/settings/settings_screen.dart) and [route registry](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/routes/router_config.dart) expose no tracking settings or login route. Searching the feature and route code for tracker login/logout, bindings, tracker lists, and tracking-service names finds no implementation. The tracker operations occur in the bundled [server API schema](https://github.com/Suwayomi/Tachidesk-Sorayomi/blob/df37f4ce700a7db7c971301d0adc65b4f0721a6f/lib/src/graphql/schema.graphql), without corresponding feature operations or UI.

This finding is about account management in Sorayomi. It does not mean Suwayomi lacks tracking or that accounts configured in WebUI cease to exist. Use WebUI to manage them. Sorayomi's offline and tracker conclusions are source audits; no Sorayomi binary was runtime-tested for this review.

## Suwayomi WebUI

Snapshot: [`8ab2f3385`](https://github.com/Suwayomi/Suwayomi-WebUI/tree/8ab2f3385d643f36a415bc8df0c414f86c8a97b1).

- The [README](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/README.md#features) documents library and chapter management, its desktop/mobile reader, download queue, and migration.
- [Global search](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/features/global-search/screens/SearchAll.tsx) covers multiple sources.
- [Tracking settings](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/features/tracker/screens/TrackingSettings.tsx) manages tracker connections through Suwayomi.

### Offline reading audit

**Conclusion: browser image caching, without managed device-offline chapters.** The PWA does have a service worker; describing it as having no local cache would be incorrect.

- [Chapter download actions](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/features/chapter/services/Chapters.ts#L189-L196) enqueue the server downloader. They do not save complete chapters into a browser offline library.
- [Workbox configuration](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/vite.config.ts#L79-L143) sets up **image-only**, cache-first runtime caching, including chapter pages. The chapter-page cache has a one-day expiry, a 2,500-entry limit, and quota-error purging. It has no application-shell precache (`globPatterns: []`). [ImageCache](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/lib/service-worker/ImageCache.ts) exposes those image caches; a PWA installation does not turn them into complete offline chapters.
- The [reader's page-list request](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/lib/requests/RequestManager.ts#L2794-L2806) remains a GraphQL mutation against Suwayomi. The [GraphQL client](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/lib/requests/client/GraphQLClient.ts#L461-L468) uses an in-memory Apollo cache, not a persistent chapter catalog.
- [Progress updates](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/features/reader/services/ReaderService.ts#L211-L287) call the server through an [in-memory queue](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/lib/Queue.ts). That queue schedules requests; it is not a persistent offline outbox.

Together these paths support the table's “No” for managed device downloads. An already open reader can retain cached images, but that is a narrower capability than reopening downloaded chapters while the server is unreachable.

### Browser disconnect check

A separate runtime check used the bundled **WebUI r3518**, **Suwayomi v2.4.2422**, and a fresh **Chromium 151.0.7922.34** context against the local SFW screenshot server. This bundled WebUI is older than the source snapshot above; the observations apply to r3518, while the source audit covers `8ab2f3385`.

1. Open the library and wait for the service worker to control the page.
2. Open Yotsuba&! chapter 1 (48 pages). Six reader images loaded and appeared in `image-cache-chapter-pages`.
3. Set the browser context offline, leaving the server running. The six loaded images remained; fetching a cached image still returned its 160,168-byte body. A GraphQL chapter request failed with `TypeError: Failed to fetch`.
4. Reload the reader while still offline. Navigation failed with `net::ERR_INTERNET_DISCONNECTED`.

This confirms useful image caching, not a complete offline reading mode. The test did not cover every browser's HTTP-cache behavior, installed-PWA lifecycle, cache eviction, or every reading mode. “Offline” here means the reading device cannot reach Suwayomi; a reachable server serving its own downloaded chapters without internet access is a different scenario.

## Yomira

Baseline: Yomira v0.4.0, including provider migration and the existing documentation and screenshots.

- [Phone download storage](../app/src/main/java/eu/kanade/tachiyomi/data/suwayomi/SuwayomiOfflineStore.kt) and [download worker](../app/src/main/java/eu/kanade/tachiyomi/data/suwayomi/SuwayomiOfflineWorker.kt) implement persistent device copies, transfer rules, and storage accounting.
- [Source policy](../app/src/main/java/eu/kanade/tachiyomi/data/suwayomi/SuwayomiSourcePolicy.kt) applies server browsing restrictions.
- [Source migration](../app/src/main/java/eu/kanade/tachiyomi/data/suwayomi/SuwayomiMigration.kt) and the [Browse migration screens](../app/src/main/java/eu/kanade/tachiyomi/ui/suwayomi/SuwayomiMigrationLibraryScreen.kt) provide library-provider selection, replacement search, chapter review, and verified progress/bookmark/category transfers. Originals remain in the library until verification succeeds.
- The [user guide](./USAGE.md) records current server workflows, offline progress synchronization, permissions, and limits.

The SFW README screenshots were captured from a real local Suwayomi v2.4.2422 instance. They illustrate Yomira's UI; they do not test the other clients or demonstrate parity with all of Komikku's features.

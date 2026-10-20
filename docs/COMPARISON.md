# Client comparison: scope and sources

[← Comparison table](../README.md#compare-the-clients)

Reviewed **20 October 2026**. This compares the projects' own documentation and selected source code, not a benchmark or an exhaustive runtime test of every client. Stable releases and previews may differ. Server API compatibility and account permissions also affect what a client can do.

## What the rows mean

- **Primary library** describes the normal place the app stores its library and reading state. Original Komikku is a standalone Android app; its optional tracker and cloud integrations do not make its main library a Suwayomi client library.
- **Server downloads** are files managed by Suwayomi's downloader. They still require a connection between the reading device and server.
- **Offline reading** means chapters remain readable after the Suwayomi server becomes unreachable, using a completed copy stored on the reading device. A server queue or browser image cache alone does not establish that capability.
- **Not verified** means the inspected sources did not establish support. It deliberately does not mean “No.” No server-independent download/progress-sync workflow or tracker-account management UI was established for Sorayomi in this review. A server-independent browser offline workflow was not established for WebUI.
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

## Suwayomi WebUI

Snapshot: [`8ab2f3385`](https://github.com/Suwayomi/Suwayomi-WebUI/tree/8ab2f3385d643f36a415bc8df0c414f86c8a97b1).

- The [README](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/README.md#features) documents library and chapter management, its desktop/mobile reader, download queue, and migration.
- [Global search](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/features/global-search/screens/SearchAll.tsx) covers multiple sources.
- [Tracking settings](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/src/features/tracker/screens/TrackingSettings.tsx) manages tracker connections through Suwayomi.
- Download actions use the server's downloader. The table does not treat server-side files as browser-local offline storage.

## Yomira

Baseline: rebrand `d716da67b`, plus the current branch's documentation and screenshots.

- [Phone download storage](../app/src/main/java/eu/kanade/tachiyomi/data/suwayomi/SuwayomiOfflineStore.kt) and [download worker](../app/src/main/java/eu/kanade/tachiyomi/data/suwayomi/SuwayomiOfflineWorker.kt) implement persistent device copies, transfer rules, and storage accounting.
- [Source policy](../app/src/main/java/eu/kanade/tachiyomi/data/suwayomi/SuwayomiSourcePolicy.kt) applies server browsing restrictions.
- The [user guide](./USAGE.md) records current server workflows, offline progress synchronization, permissions, and limits.

The SFW README screenshots were captured from a real local Suwayomi v2.4.2422 instance. They illustrate Yomira's UI; they do not test the other clients or demonstrate parity with all of Komikku's features.

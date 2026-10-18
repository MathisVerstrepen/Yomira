# Suwayomi backend implementation

The implementation follows `SUWAYOMI_IMPLEMENTATION_HANDOFF.md`. The application is cloud-only and uses one server/account. Existing phone-local libraries are neither imported nor exposed through the cloud home screen.

## Supported contract

The baseline is Suwayomi-Server commit `e2ce828abb81f0f5ce1a3fb86194a2746b519c85`, inspected on 2026-10-18. This is a source contract, not a claim that every published server version supports it. Connecting probes `user`, `aboutServer`, per-user manga state, and cursor pagination. An incompatible GraphQL schema fails explicitly. Authentication mode is selected during setup: the inspected server does not expose a public auth-mode discovery operation to an unauthenticated visitor.

HTTP GraphQL, image requests, and the subscription transport share a dedicated connection client. NONE, BASIC_AUTH, SIMPLE_LOGIN cookies, and UI_LOGIN access/refresh tokens are implemented. Refreshes are serialized and requests retry at most once following authentication renewal. HTTP redirects are refused to avoid sending session credentials to an unexpected host. Root-relative server page/cover URLs are resolved beneath the configured reverse-proxy prefix. Credentials are encrypted with Android Keystore and stored atomically in the app's no-backup directory.

## Implementation stages

- Authenticated transport, string Long scalar, URL normalization, isolated sessions, and durable chapter intent ordering: implemented; targeted tests cover these policies.
- Cloud setup/library/detail screens and the existing Komikku reader: implemented; verified against the reference server and an Android 36 emulator.
- Server chapter IDs remain distinct from negative reader presentation IDs. No remote identity is inserted into the old SQLDelight library. The cloud screens expose in-memory server projections through StateFlows; there is no offline launch or navigation mode.
- Resume, exact chapter read/unread/bookmark patches, coalesced progress, and server conflict notices: implemented; page 3 was saved as zero-based position 2, a separate GraphQL client advanced it to position 4, and Android resumed on page 5. Mark-unread then reset the server position to 0.
- Existing Android source discovery, sync-at-startup, old library widgets, and migration/job scheduling are bypassed. Previously scheduled Android background jobs are retired once. The cloud path retains Android app-update checks and optional server chapter-notification checks. The cloud path never falls back to executing an Android extension. Old Android extension installation, tracker/OAuth, source interception, and widget entry points are disabled in the manifest. Legacy source deep links and widgets stay disabled; portable cloud manga links use a separate verified entry point.

## Mutation semantics and limits

Chapter intent is persisted before network dispatch in an atomic, session-specific outbox. Every write verifies the original source ID, manga URL, chapter URL, and remote manga ID; a reused database ID after a server reset cannot silently receive an old queued change. Progress coalesces only within a reading session. Explicit mark-read and mark-unread create barriers against older reading tickets; mark-unread also sends `lastPageRead: 0`. Bookmark removal is an explicit boolean. Completion changes flush without the ordinary progress delay. Activity exit triggers a flush, and process restart restores unresolved operations.

A request interrupted while in flight becomes ambiguous. A later refresh compares the exact remote chapter state with the requested fields. A matching result is acknowledged; a differing result stays visible for explicit reapplication or discarding. `clientMutationId` is correlation only. The server offers no expected-revision field, so this cannot prevent all races with a different client. The reader keeps its current position during reconciliation. Incognito prevents new Android personal progress/bookmark/history/tracker intents and pauses undispatched chapter writes; in-flight requests cannot be recalled. Fetching server images/pages can still cause server access activity and server-managed synchronization.

Replacing the connection signs out, cancels its calls, and deletes its pending intents and local reader flags with a visible warning. Late responses cannot populate a different connection. Same-account reauthentication preserves the logical connection, pending intents, and local flags after verifying the server user ID. HTTP pools and page caches still use distinct generations, so late responses and old reader cleanup cannot overwrite a new transport. Sessions saved before user-ID verification was introduced require an explicit replacement.

## Daily workflows

Source browsing/search/popular/latest, positional source filters, server category creation/rename/reorder/deletion and membership, server download queue controls, recent reading, library-update requests, server tracker accounts/bindings/search/record edits, and a reading-preference allowlist are implemented. Download state subscribes to server events. Activity/history and background update checks use explicit timestamp/ID keysets to work around an observed descending-time cursor tie defect in the reference server; raw time cursors repeated and skipped IDs. No server patch is required. Active library updates use bounded foreground refreshes. Reader completion invokes the server's tracking operation; Android tracker credentials and jobs are bypassed.

The library loads all cursor pages into its temporary projection. Search covers title, author, artist, description, and genres. Read/unread/server-download/bookmark filters, title/unread/download/date-added/last-read sorting, category/source/status grouping, and bulk category/library/read/reset actions use remote identities. Per-category layouts, ordering, and hidden flags are account-session-local presentation data. Failed bulk actions retain unprocessed selections; already applied operations are not rolled back.

Server download controls include clear and move-to-front, with shared-queue effects labeled explicitly. Clearing the queue cancels work for all users without deleting previously downloaded files. Server chapter file deletion remains separate. Manga sharing uses `komikku-suwayomi://manga/...` links with the normalized server address and exact source/manga URL identity. Opening a link requires a matching configured connection; links never select or replace a different server automatically. Covers include the server thumbnail-fetch revision in their authenticated request URL.

Common administration lists server extensions and repositories, executes extension install/update/uninstall and repository changes, renders switch/checkbox/text/list/multiselect source preferences, and edits account update/download settings. Source preferences are validated against their current schema before positional changes are sent. Shared server effects and permissions are explicit. Shared update intervals can be disabled or set to at least six hours. Backup/restore, manual extension uploads, and server-cache management use the WebUI. Phone-wide storage, local extension discovery/removal, and battery-exemption permissions are removed; application updates and page-image export retain their required permissions.

Optional WorkManager checks look for new server chapters approximately hourly. The first run creates a silent baseline; a durable timestamp/ID checkpoint deduplicates ties across pages and process restarts. A checkpoint is written before posting, favoring no repeat alerts over guaranteed delivery after a crash. Android may defer jobs; this is not push delivery and does not start source/library updates. Server replacement cancels checks and clears the previous notification before switching accounts.

Per-manga reading mode synchronizes through `komikku.reader.v1.mode`. Default reading mode and pager/webtoon/continuous crop settings have explicit upload/apply controls with individual namespaced metadata fields. Brightness and orientation stay local. The same-account reauthentication and source-browsing flows were exercised in the Android emulator.

## Remaining handoff scope

The complete handoff is not yet implemented. Remaining work includes home-screen widgets, extended Komikku features, and final removal of obsolete compiled dependencies/dead paths. Feature availability is explained in the cloud settings; unavailable controls are not exposed as working features. Common administration is implemented; uncommon controls and manual extension uploads use the WebUI. Real extension installation and nonempty online-source preference schemas have not been acceptance-tested. Server WebUI access is available with separate browser authentication. Source filtering validates positional schemas before applying choices and clears stale choices when types, labels, ordering, or nested options change. It supports the inspected filter union and groups up to four levels; deeper or unknown schemas display an unsupported state. Real external tracker accounts, online extension sources, live remote download completion/deletion, and every reader gesture/layout have not been acceptance-tested.

Phone downloads, destination selection, and airplane-mode reading remain explicitly deferred. The current reader hides local chapter-download and cover-edit actions. Independent history deletion and cloud session-duration statistics require server API work and are not exposed as working controls.

## Validation

Run with JDK 21 and Android SDK 36:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest --tests '*Suwayomi*Test'
```

The first three commands and the targeted protocol/transport/preference tests passed during implementation. An opt-in `SuwayomiLiveServerTest` also passed against the actual reference server in NONE mode, covering user/library queries, authenticated cover/page transport, subscription initialization, exact progress, bookmark add/remove, and reset. An Android 36 emulator exercised connect, cover rendering, chapter reading, cross-client resume, mark-unread, and encrypted-session restoration after app replacement. The same real-server authentication/query/image/subscription/state test subsequently passed in BASIC_AUTH, SIMPLE_LOGIN, and UI_LOGIN modes. The corrected live-server tests pass for source browsing, category CRUD/membership, recent reading, queue queries, reading metadata, a 66-title library, and a 122-chapter manga. Cursor traversal returns every ID exactly once. Real server builds used only a larger heap; no reference-server source patch was required. The full app and domain unit suites also pass. Final targeted/live checks pass after the link, queue, cover revision, privacy, and filter-schema changes. The expanded live suite was repeated successfully in all four authentication modes. A native incognito reader swipe advanced the displayed page while leaving server progress, read state, and bookmarks unchanged. Common administration queries, source preference schema queries, empty-repository catalogue refresh, empty shared-queue clearing, and reversible per-user settings writes also passed against the reference server. The emulator opened a valid portable manga link, refused to switch automatically for a different-server link, and showed tracker mutation buttons disabled during incognito. Native administration navigation and notification permission/enable controls were exercised. A real WorkManager run persisted all 184 timestamp-tied fixture chapter IDs without an initial notification. Adding and fetching a new fixture chapter produced its own notification and advanced the durable checkpoint. A subsequent unchanged check did not repost the alert. Compilation and HTTP fixtures alone do not establish interoperability.

### Reproduce live tests

Start a fresh, disposable reference server in `NONE` mode with an empty data directory, no extension repositories, and its browser/tray disabled. Never use a personal server or library for these tests. Seed it using the standard-library-only fixture script:

```sh
python3 scripts/suwayomi_acceptance_fixture.py \
  --server-data-dir /tmp/komikku-suwayomi-test \
  --url http://127.0.0.1:4579 --disposable
KOMIKKU_SUWAYOMI_TEST_URL=http://127.0.0.1:4579 \
  ./gradlew :app:testDebugUnitTest --tests '*Suwayomi*Test' --rerun-tasks
```

Set `KOMIKKU_SUWAYOMI_TEST_AUTH` to `BASIC_AUTH`, `SIMPLE_LOGIN`, or `UI_LOGIN` and supply `KOMIKKU_SUWAYOMI_TEST_USER` and `KOMIKKU_SUWAYOMI_TEST_PASSWORD` to test a configured authentication mode. Optional live tests are skipped when the URL is absent. The regular protocol, ordering, transport, and preference tests require no server.

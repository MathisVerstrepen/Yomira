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
- Existing Android source discovery, sync-at-startup, old library widgets, and migration/job scheduling are bypassed. Previously scheduled Android background jobs are canceled. The cloud path never falls back to executing an Android extension.

## Mutation semantics and limits

Chapter intent is persisted before network dispatch in an atomic, session-specific outbox. Progress coalesces only within a reading session. Explicit mark-read and mark-unread create barriers against older reading tickets; mark-unread also sends `lastPageRead: 0`. Bookmark removal is an explicit boolean. Completion changes flush without the ordinary progress delay. Activity exit triggers a flush, and process restart restores unresolved operations.

A request interrupted while in flight becomes ambiguous. A later refresh compares the exact remote chapter state with the requested fields. A matching result is acknowledged; a differing result stays visible for explicit reapplication. `clientMutationId` is correlation only. The server offers no expected-revision field, so this cannot prevent all races with a different client. The reader keeps its current position during reconciliation. Incognito suppresses Android personal progress/bookmark/history/tracker writes; fetching server images/pages can still cause server access activity and server-managed synchronization.

Replacing the connection signs out, cancels its calls, and deletes its pending intents and local reader flags with a visible warning. Late responses cannot populate a different connection. Reauthentication currently uses this setup flow; preserving unresolved operations during reauthentication needs further work.

## Outstanding handoff scope

The complete handoff is not yet implemented. Daily-use category management, source browsing/filter schemas, update jobs, server download queue UI/subscriptions, server tracker management, and synchronized reading preference metadata remain. Per-manga rendering flags currently persist on this device within the connection namespace; brightness and orientation remain local. Background notification delivery, account-preserving reauthentication, richer library presentation/multi-selection, deep links/widgets, extended Komikku features, and final dependency/permission cleanup remain separate work.

Phone downloads, destination selection, and airplane-mode reading remain explicitly deferred. The current reader hides local chapter-download and cover-edit actions. Independent history deletion and cloud session-duration statistics require server API work and are not exposed as working controls.

## Validation

Run with JDK 21 and Android SDK 36:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest --tests '*Suwayomi*Test'
```

The first three commands and the nine protocol/transport tests passed during implementation. An opt-in `SuwayomiLiveServerTest` also passed against the actual reference server in NONE mode, covering user/library queries, authenticated cover/page transport, subscription initialization, exact progress, bookmark add/remove, and reset. An Android 36 emulator exercised connect, cover rendering, chapter reading, cross-client resume, mark-unread, and encrypted-session restoration after app replacement. The other authentication modes still require real-server verification. Compilation and HTTP fixtures alone do not establish interoperability.

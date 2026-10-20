# Smart phone downloads verification

Verified on 2026-10-20 using the debug APK from `codex/smart-phone-downloads`.

## Build and unit checks

Ran `spotlessApply`, then `spotlessCheck`, then `assembleDebug`. All passed.

Both `:app:testDebugUnitTest --tests '*suwayomi*'` and `:app:testReleaseUnitTest --tests '*suwayomi*'` passed: 154 tests passed, four environment-dependent tests skipped in each variant. The ten new `SuwayomiSmartDownloadTest` cases cover opt-in, reading order, idempotent queues, pending progress and mark-unread protection, manual-copy preservation, cleanup settings, active reader leases, restart/update discovery, pause/disable, identity replacement, and explicit removal.

## ADB devices

- OnePlus 8T (KB2003), Android 14: installed the arm64 debug APK and opened the title menu and Smart phone downloads dialog. Existing library progress and selection settings were left unchanged.
- Android emulator, Android 16: installed the x86_64 debug APK and used a separate Android user with a disposable Suwayomi protocol fixture. The fixture served one-page chapters and recorded image requests and progress mutations. All mutation and deletion scenarios below used this isolated account.

## Observed results

| Scenario | Result |
| --- | --- |
| Enable one title with the default buffer | Chapters 1–3 completed on the phone; the unselected title made no image requests. |
| Read chapter 1 while progress mutations return HTTP 503 | The durable outbox retained an ambiguous read intent and chapter 1 remained available offline. |
| Restore the server and reapply pending changes | The server confirmed chapter 1 read, the outbox emptied, chapter 1 was removed, and chapter 4 completed. Chapters 2–4 remained available. |
| Disable Wi-Fi and increase the buffer to five | No additional image requests; existing files remained available. |
| Restore Wi-Fi with the phone quota occupied | The next automatic chapter entered `STORAGE`, without publishing a partial chapter. Existing chapters remained complete. The test used a sparse quota blocker inside the isolated store and a 128 MiB configured limit. |
| Remove the quota blocker and retry | Chapters 5 and 6 completed, producing the next five unread copies, chapters 2–6. |
| Pause, mark chapters read on the fixture, add chapter 9, and change the buffer to three | While paused, all existing copies remained and chapter 9 was not queued. |
| Resume and restart the app during upkeep | Confirmed read copies were removed and chapter 9 completed after restart. The selected title, three-chapter buffer, Wi-Fi-only setting, 128 MiB quota, and cleanup preference persisted. |

The temporary quota file, Android user, server process, and ADB reverse forwarding were removed after verification. Hourly Android scheduling was not tested by waiting an hour; the same worker was exercised through its immediate triggers. Android controls periodic execution timing.

## Modal UI follow-up

The compact modal was built with `spotlessApply`, `spotlessCheck`, and `assembleDebug`, then installed on the same OnePlus and emulator. Verified the OnePlus title dialog and its route to Phone downloads. On a disposable emulator user, verified enable/disable persistence and an inline save error by temporarily removing write permission from the offline-store directory; restoring permission and retrying cleared the error. At 1.5× font scale, portrait content remained visible, and landscape content scrolled while the title and Close action stayed accessible. The single-chapter summary uses its singular plural-resource entry. No personal library settings were changed.

## Phone download page follow-up

Reorganized the page into Downloads and Settings tabs, with title-grouped chapter rows, storage usage, compact transfer rules, and per-chapter overflow actions. The title modal opens the Settings tab directly. Re-ran `spotlessApply`, `spotlessCheck`, `assembleDebug`, and the 26 `SuwayomiSmartDownloadTest` / `SuwayomiOfflineTest` cases; all passed with no skips.

Installed and inspected the final APK on the OnePlus. In an isolated Android 16 user, verified the empty view, the modal-to-settings route, persisted Wi-Fi/buffer/cleanup/storage changes, rejection of a 64 MiB quota, canceling and confirming single-chapter removal, offline reading from a chapter row, pause/resume, snackbar feedback for a failed settings write and successful retry, and independent tab scroll positions. Inspected settings at 1.5× font scale in portrait and landscape. Personal phone preferences and chapters were preserved.

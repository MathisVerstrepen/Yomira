# Yomira privacy

Yomira is an independent fork of Komikku for Suwayomi-Server. This page describes the fork's current behavior; upstream Komikku's website and services are not Yomira's support or reporting services.

## On your device

The app stores its connection credentials in Android Keystore-protected private storage. Phone chapter downloads, covers, preferences, and pending reading changes are stored locally. The app disables Android backup for its private data. Reauthenticating the same account keeps its downloads and queued progress; disconnecting or replacing the account clears them.

## Your server and connected services

Browsing, reading online, downloads from the server, and synchronization send requests to your configured Suwayomi server. Source extensions and tracker accounts run on that server. The server, source providers, tracking services, and their operators have their own data practices. Shared-library servers share library and reading state across clients.

Incognito mode suppresses new personal reading-state writes from the app. It does not hide network requests or server-managed activity from the server operator. Opening authorization or administration in a browser uses that browser's separate session.

## Reporting and updates

Upstream analytics, telemetry, and automatic app-update integration are disabled in this fork. No Yomira analytics or crash-reporting endpoint is configured. Opening a release, source-code, or issue link contacts GitHub through your browser.

Crash logs and debug information are shared only when you choose the app's export/share action. They can contain device details and diagnostic context; review and redact them before posting publicly.

Report fork-specific questions in the [Yomira repository](https://github.com/MathisVerstrepen/komikku-suwayomi/issues). See [NOTICE](../NOTICE) for the upstream projects and authors credited by this fork.

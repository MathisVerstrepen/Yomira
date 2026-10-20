# How Yomira and Suwayomi fit together

[← Project overview](../README.md#how-it-works)

![Server and client architecture](../.github/readme-images/yomira/architecture.png)

## Server

Suwayomi-Server runs on a computer, home server, NAS, or hosted machine. It runs source extensions, fetches manga metadata and pages, manages the library and reading state, connects tracker accounts, and handles server downloads.

Yomira uses Suwayomi's API. It does not execute Android source extensions itself. Configure source repositories and preferences on the server, either through Yomira's Browse interface or through WebUI.

## Clients

Yomira, Suwayomi WebUI, and Tachidesk-Sorayomi are separate clients. You can choose one or use several against the same server; none requires the other clients to be installed.

Yomira provides Komikku's Android library interface and reader. WebUI provides browser access, and Sorayomi offers a Flutter client on multiple platforms. Original Komikku's standalone phone library is a different setup, described in the [comparison](./COMPARISON.md).

Reading state and other server data can be visible to other clients. Shared-library servers share state across clients; per-user behavior depends on the server's API, account, and permissions. The diagram does not imply all users share one unrestricted account.

## Two download destinations

| Destination | Where the files live | Can Yomira read them with the server unreachable? |
| --- | --- | --- |
| **Download on server** | Suwayomi's storage | No, unless a separate phone copy is complete |
| **Download to phone** | Private Android app storage | Yes, after every page is saved |

When reading a completed phone download offline, Yomira queues progress and bookmarks locally. It synchronizes changes after reconnecting to the same account. See the [offline guide](./USAGE.md#read-offline) for storage limits, retries, and changing accounts.

## Diagram assets and attribution

The [editable SVG](../.github/readme-images/yomira/architecture.svg) and [PNG export](../.github/readme-images/yomira/architecture.png) are stored together. The README uses PNG so project logos render consistently on GitHub and other Markdown viewers. The SVG includes its own title and text description.

- Yomira's ヨ mark is original artwork for this fork.
- The Suwayomi logo comes from [Suwayomi WebUI's favicon](https://github.com/Suwayomi/Suwayomi-WebUI/blob/8ab2f3385d643f36a415bc8df0c414f86c8a97b1/public/favicon.svg).
- The Sorayomi logo uses the project icon supplied as the design reference; the original project is [Tachidesk-Sorayomi](https://github.com/Suwayomi/Tachidesk-Sorayomi).

These logos identify their respective projects. Yomira remains an independent fork of Komikku and is not an official Suwayomi or Sorayomi release.

The architecture SVG and PNG are distributed under [MPL-2.0](../.github/readme-images/yomira/architecture-LICENSE.txt), retaining the upstream logo material’s license. This asset-specific license does not change Yomira’s Apache-2.0 application license.

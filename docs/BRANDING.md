# Yomira identity and compatibility

Yomira is an independent fork of Komikku for Suwayomi-Server. Its name and custom katakana ヨ icon distinguish it from upstream, while its About page, README, NOTICE, license, and source history retain upstream attribution.

## Artwork

The canonical icon is [icon.svg](../.github/readme-images/yomira/icon.svg), with a [PNG export](../.github/readme-images/yomira/icon.png). The mark uses deep plum (`#251329`), warm ivory (`#FFF8F0`), and a magenta tab (`#F00995`). A transparent monochrome mark supports Android themed icons and notification silhouettes.

Android resources use `ic_yomira*` names. The launcher foreground fits inside the adaptive icon's central 66 dp safe circle. Both release and development variants use the new resources, so inherited Komikku debug/beta overrides do not replace them. Original `ic_komikku*` resources and previous concept artwork remain available for attribution and history.

## Stable identifiers

- **Application ID:** retain `app.komikku.suwayomi` and its existing build suffixes. This is already distinct from upstream Komikku and preserves upgrades, private phone downloads, stored credentials, and reading-state queues.
- **Signing key:** keep the fork's existing key. A visual rebrand does not require reinstalling the app.
- **Repository URL:** keep working links to `MathisVerstrepen/komikku-suwayomi` until an explicit repository rename. The Gradle project and user-facing identity are Yomira.
- **Shared links:** new manga links use `yomira://manga/...`; links using `komikku-suwayomi://manga/...` remain accepted with the same server and source-identity checks.
- **Storage, metadata, preferences, package namespaces, and extension APIs:** retain compatibility identifiers. Renaming these could lose data or break other clients and is not needed for branding.
- **Source markers and translations:** retain `KMK`, `KMR`, and inherited resource namespaces for upstream provenance and merging. Fork-specific display strings live in the base locale of `i18n-kmk`.

## Releases and upstream boundaries

Future signed APKs use `Yomira-vX.Y.Z-<architecture>.apk`. Historical v0.1.0 and v0.2.0 release notes and assets retain the Komikku Suwayomi name under which they were published.

Upstream telemetry and the upstream app updater are disabled, including when inherited build flags are passed. About links open this fork's releases and issue tracker. Inherited Discord rich presence is disabled in server mode rather than publishing under Komikku’s Discord application identity. Workflows that mirror or dispatch builds to upstream repositories remain restricted to upstream.

The original [Komikku fork guidelines](./UPSTREAM_KOMIKKU_CONTRIBUTING.md#forks), Apache-2.0 [LICENSE](../LICENSE), and copyright notices remain intact. Use Yomira's issue tracker for fork issues and identify Komikku explicitly when crediting its original work.

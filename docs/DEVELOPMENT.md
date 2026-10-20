# Build and test Yomira

[← Project overview](../README.md) · [Contributing](../CONTRIBUTING.md) · [Agent guide](../AGENTS.md)

Use **JDK 21** and **Android SDK 36**. The repository includes the Gradle wrapper; bytecode targets Java 17. Configure the SDK through Android Studio or `sdk.dir` in your local `local.properties`.

```sh
git clone https://github.com/MathisVerstrepen/komikku-suwayomi.git yomira
cd yomira
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
```

Debug APKs are written under `app/build/outputs/apk/debug/`. To install on a connected device or emulator:

```sh
./gradlew installDebug
```

Yomira retains the release package `app.komikku.suwayomi`; debug builds use `app.komikku.suwayomi.dev`. These existing fork IDs and the signing key are kept for upgrades from Komikku Suwayomi without losing app data. This adaptation can install alongside upstream Komikku. Earlier development builds using `app.komikku.dev` remain separate and do not transfer their connection automatically. Default builds do not require Firebase or Google OAuth secrets. Upstream telemetry and app-update services are disabled. Use **About → Yomira releases** to download updates. Future APKs are named `Yomira-vX.Y.Z-<architecture>.apk`; historical releases retain their original names. See [branding and compatibility](./BRANDING.md) and [privacy](./PRIVACY.md).

### Tests

Run the Suwayomi unit tests with:

```sh
./gradlew :app:testReleaseUnitTest --tests '*Suwayomi*Test'
```

The release workflow runs the full unit-test suite with `./gradlew testReleaseUnitTest`. Live-server tests are opt-in and skip without their environment configuration. `SuwayomiReadOnlyServerTest` exercises authenticated reads. **`SuwayomiLiveServerTest` changes server data and must use a disposable test server.** The [acceptance fixture script](../scripts/suwayomi_acceptance_fixture.py) supports isolated acceptance testing.


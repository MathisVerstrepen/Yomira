# SFW screenshot server

A separate, local **Suwayomi v2.4.2422** instance for Yomira's README screenshots. It uses a pinned official Docker image, its own data directory, and a curated 12-title MangaDex library. The catalog includes Yotsuba&!, Frieren, Witch Hat Atelier, Haikyu!!, and Bocchi the Rock!.

## Start or recreate

Requirements: Docker Compose and Python 3. No Python packages are required.

From the repository root:

```sh
tools/screenshots/start.sh
```

Open **<http://127.0.0.1:4569>**. Authentication is **No sign-in**. The port is published on loopback only, not the LAN. The instance has no personal server credentials, trackers, or library data.

The seeder verifies each exact MangaDex UUID is rated **Safe**, sets the English MangaDex source's default rating to **Safe only**, selects English browsing languages, and requests first-volume covers. It refuses to seed a nonempty library without its fixture marker. Rerunning it reuses existing entries; only previously managed titles removed from `catalog.json` leave the library.

Categories are **My shelf**, **Fantasy**, and **Everyday stories**. Select **My shelf** in the app to see all 12 titles. Covers and available English chapter metadata are cached; full chapter images are fetched only if you open or download a chapter. Several licensed series have catalog entries but no English chapters on MangaDex. No fake chapters or reading counts are added.

The database, extension, cached covers, logs, and `seed-report.json` stay under the gitignored `.local/sfw-screenshots/` directory. Source availability can change; the report records the actual titles and chapter counts for each session. Covers in `covers/` can be reviewed before capture.

## Connect a screenshot device

Use a separate emulator or app profile so screenshot preparation does not replace a personal connection. For an ADB-connected device:

```sh
adb -s DEVICE_SERIAL reverse tcp:4569 tcp:4569
```

In Yomira, enter **`http://127.0.0.1:4569`** and choose **No sign-in**. Keep ADB connected while using this address. An Android emulator can also use `http://10.0.2.2:4569`.

The prepared local AVD is named **YomiraScreenshots**, currently at **emulator-5560**, with a separate Yomira debug install. To launch it again:

```sh
"$ANDROID_HOME/emulator/emulator" -avd YomiraScreenshots -port 5560 -no-snapshot
adb -s emulator-5560 reverse tcp:4569 tcp:4569
```

Use Android's System UI demo mode for a consistent clock, battery, and notification bar. Capture directly with ADB, for example:

```sh
adb -s emulator-5560 exec-out screencap -p > library.png
```

The README includes library, title-detail, and reader captures under `.github/readme-images/yomira/screenshots/`. They show real app UI and provider metadata. Artwork belongs to its respective creators; it is not Yomira artwork or covered by this repository's software license. The catalog's MangaDex UUIDs provide the original title references.

## Stop

```sh
docker compose -f tools/screenshots/compose.yaml stop
```

Start again with `tools/screenshots/start.sh`. Stopping or removing the container preserves the data directory. This fixture is for screenshots, not the unrelated destructive acceptance-test suite.

References: [official Docker setup](https://github.com/Suwayomi/Suwayomi-Server-docker), [MangaDex](https://mangadex.org/), [Keiyoushi extension repository](https://github.com/keiyoushi/extensions).

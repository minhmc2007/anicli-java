# AniCli Java

AniCli Java is an Android client for the HiAnime scraping flow used by
[`ani-cli`](https://github.com/pystardust/ani-cli). It has a compact,
Android 7-era interface and keeps all app data on the device.

| | |
| --- | --- |
| Package | `com.minhmc2077.aniclijava` |
| Minimum Android | 8.0 (API 26) |
| Target Android | API 36 |
| UI | Android 7-era flat design, Roboto, light/dark/system themes |

## Download

Install the latest universal APK from
[Releases](https://github.com/minhmc2007/anicli-java/releases/latest).

Release builds use a fixed project keystore and a constant version code, so a
build can be installed over another without uninstalling the app, including an
older release.

## Features

- **Home** — trending anime from HiAnime.
- **Search** — search by title.
- **Favourites** — save anime locally.
- **History** — resume the last watched episode and position.
- **Detail** — poster, score, genres, synopsis, SUB/DUB switch, and an episode grid.
- **Player** — quality selection, subtitles, previous/next episode, auto-next, and resume.
- **AniSkip** — a skip button for openings and endings, powered by community timestamps from AniSkip. The timing can be offset two seconds early, kept on time, or turned off in Settings → Playback.
- **Offline downloads** — download the next episode from the player or Settings → Playback. Downloads run in a foreground service with a progress notification, keep the four newest episodes, and play from the cache. A partially downloaded episode plays its cached parts and fetches the rest online when a connection is available.
- **Buffer ahead** — Settings → Playback sets how far ahead ExoPlayer loads the episode, from 1 to 10 minutes.
- **Player gestures** — double-tap the left half of the player to go back 5 seconds, or the right half to go forward 5 seconds. Swipe up or down on the left half to change the app window's brightness, or on the right half to change playback volume.
- **Airing alerts** — tap the megaphone on an anime page to get a notification when a newer episode appears upstream. Episode labels such as `5.5` or `2.5` are compared numerically.
- **New episode badge** — favourites with a newer upstream episode than the last watched one show a small `New episode` label on the poster.
- **Online services** — connect MyAnimeList or AniList to pull remote watch progress into History and upload local progress.
- **Settings** — accent color, light/dark/system theme, skip button timing, buffer-ahead size, next-episode downloads, MyAnimeList/AniList connections, app version, GitHub link, and update checks.
- **Updates** — checks the latest GitHub release on startup when enabled, shows the release notes, downloads the APK, opens the system installer, and removes cached APKs once the installed version is current.

## Interface

The app uses an Android 7-era flat design: Roboto, a solid app bar, 2dp corners,
flat surfaces, and divider lines instead of elevated cards. There is no dynamic
color or Material You theming.

The default accent is classic blue. Settings → Appearance also offers indigo,
teal, green, red, and orange, plus Light, Dark, and System themes.

The player adjusts brightness through its own window and volume through the
player instance. Neither changes a system-wide setting or asks for a permission.

## How it works

1. Trending cards are parsed from the HiAnime home page.
2. Search results come from the search page, and episode lists from the episode API.
3. The ZokoAnime embed blob is base64-decoded and deobfuscated to obtain the stream.
4. The ZokoAnime embed URL carries the anime's MyAnimeList ID, which is cached per HiAnime ID and used to request AniSkip times for the current episode.
5. The HLS master playlist is parsed for qualities, optional audio, and subtitles.
6. Playback uses Media3/ExoPlayer with a bundled Cronet engine for HTTP.
7. Downloads and playback share one Media3 `SimpleCache`. Playback opens it read-only, so a partially downloaded episode serves its cached segments locally and fetches the rest online.

Favourites, history, settings, and sync tokens are stored in
`SharedPreferences`. Downloaded media lives in the app's private files
directory, with per-episode stream metadata in `SharedPreferences`. Sync is
optional; without a connected service there is no account, server, or analytics
service.

## Airing checks

Airing alerts live behind the megaphone button on an anime page. When enabled,
the app compares the latest upstream episode with the last watched episode and
posts a local notification when something new appears. Only anime currently
marked as airing upstream are polled; finished and unreleased titles are skipped.
The check runs from an inexact repeating alarm about every 10 minutes, plus once
on app start. Android may delay the alarm in Doze, so the interval is not
guaranteed.

Notifications need the Android 13+ notification permission. The app asks for it
when alerts are enabled and only uses it for these local airing messages. The
same background check keeps the `New episode` badge on the Favourites tab fresh.

## Online services

Settings → Online services can connect MyAnimeList and AniList. Sync is
two-way: the app pulls remote watch progress into History and uploads local
history to every connected service. Starting an episode uploads its progress,
and finishing the last known episode marks the entry completed. Manual
`Sync now` and the app start (at most once every six hours) run a full sync;
starting or finishing an episode uploads that progress right away.

Both services need an OAuth client. Register a developer app with the redirect
URI shown in the connect dialog (`aniclijava://oauth/mal` or
`aniclijava://oauth/anilist`), then paste the client ID. MyAnimeList also
accepts a client secret when the registered app has one. Builds can provide
default values through the `MAL_CLIENT_ID`, `MAL_CLIENT_SECRET`, and
`ANILIST_CLIENT_ID` environment variables, or through the `mal.clientId`,
`mal.clientSecret`, and `anilist.clientId` entries in `local.properties`.

The app matches local anime to remote entries through the MyAnimeList and
AniList search APIs and caches the resulting IDs. Only anime in History or
Favourites are matched; remote entries without a local counterpart are skipped.
MyAnimeList access tokens are refreshed automatically, while AniList tokens are
long-lived and require reconnecting after they expire.

## Versioning and releases

The app version lives in
[`Version.java`](app/src/main/java/com/minhmc2077/aniclijava/Version.java):

```java
public static final String NAME = "0.0.4";
```

Gradle reads this value for the APK `versionName`, so the Java constant is the
single source of truth. The `versionCode` stays at `1`; this is what allows a
release to replace any other build signed with the same key.

Release tags use the same value as `Version.NAME` (for example `0.0.2`). The app
compares its own version with the latest release tag to decide whether an update
is available.

### GitHub Actions

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `build.yml` | Push to `main`, manual | Builds debug and release APKs, prints the signing key, and uploads debug artifacts. |
| `release.yml` | Manual, `version` input required | Aborts if `version` does not match `Version.java`. Otherwise builds the release APK and creates the GitHub release and tag. |
| `bump-version.yml` | Manual, `version` input required | Replaces `Version.NAME`, builds the release, creates the release and tag, then opens a pull request with the version bump. |

The release and bump workflows fail if the version is not in `MAJOR.MINOR.PATCH`
form or if the release already exists.

## Building

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

CI uploads each debug APK as its own artifact (`anicli-java-debug-<abi>`), so
the ABI builds are not bundled into one archive. GitHub always wraps downloaded
artifacts in a zip, but each artifact now contains a single APK.

GitHub releases publish the universal `anicli-java-<version>-universal.apk`
plus per-ABI APKs for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`. The
in-app updater downloads the universal build.

## Project layout

```text
app/src/main/java/com/minhmc2077/aniclijava/
├── App.java                 Application, Cronet engine, async helper
├── MainActivity.java        Bottom navigation shell and update check
├── Version.java             App version and version comparison
├── data/                    Scraping repository and local stores
├── model/                   Anime, Episode, StreamInfo, ReleaseInfo
├── net/                     Cronet HTTP, GitHub releases, image loader
├── sync/                    OAuth and MyAnimeList/AniList API clients
├── ui/                      Activities, fragments, adapters, settings, update prompt
└── util/                    HTML, time, settings, and UI helpers
```

## Disclaimer

This project is an unofficial client and is not affiliated with HiAnime or
ani-cli. It only displays publicly available pages from the site. Respect the
laws and terms of service that apply to you. The app does not host or distribute
video content.

## License

This project is licensed under the **GNU General Public License v3.0** because it
is derived from the GPLv3-licensed `ani-cli` script. See [LICENSE](LICENSE).

Original `ani-cli`: <https://github.com/pystardust/ani-cli>

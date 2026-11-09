# AniCli Java

A small, fast, native Android client for the same HiAnime scraping flow used by
[`ani-cli`](https://github.com/pystardust/ani-cli). The app is written in pure
Java (no Kotlin) and uses **Cronet** for HTTP instead of OkHttp.

- Package: `com.minhmc2077.aniclijava`
- App name: **AniCli Java**
- Min SDK: 26 (Android 8.0)
- Target SDK: 36
- UI: simple YouTube-like Material 3 interface with dynamic color support
- Icon: adaptive Material You play-logo icon with monochrome support

## Features

- **Home** — trending anime scraped from `https://hianime.at/home`
- **Search** — search HiAnime by title
- **Favourites** — saved anime, stored locally in `SharedPreferences`
- **History** — one resume entry per anime, with watch position
- **Detail** — poster, score, genres, synopsis, SUB/DUB switch, episode grid
- **Player** — ExoPlayer with Cronet networking, quality selection, previous/next,
  auto-next, subtitles, and resume-from-history

## How it works

The scraping logic is a direct port of the shell script:

1. Trending cards are parsed from the `#anime-trending` section of the home page.
2. Search results are parsed from `flw-item` cards on the search page.
3. Episodes come from `/api/theme/episode/list/<numeric-id>`.
4. The ZokoAnime server hash is read from `/api/theme/episode/servers?episodeId=...`.
5. The embed blob is base64-decoded and XOR-deobfuscated with `otaku-embed-v1`.
6. The HLS master playlist is parsed for quality variants, optional audio, and subtitles.

All requests go through a bundled Cronet engine. No OkHttp, Retrofit, Coil, or
Glide is used.

## Building

The project is built on **GitHub Actions** only. Push to `main` or run the
`Build APK` workflow manually:

```bash
gh workflow run build.yml
gh run watch
```

The debug APK artifacts are uploaded as `anicli-java-debug` and include the ABI
splits plus a universal APK.

To build from a machine with the Android SDK and JDK 17:

```bash
./gradlew :app:assembleDebug
```

## Project layout

```
app/src/main/java/com/minhmc2077/aniclijava/
├── App.java                 Application, Cronet engine, stores, async helper
├── MainActivity.java        Bottom navigation shell
├── data/                    Scraping repository and local stores
├── model/                   Anime, Episode, StreamInfo, HistoryEntry
├── net/                     Cronet HTTP wrapper and image loader
├── ui/                      Activities, fragments, adapters
└── util/                    HTML, time, and UI helpers
```

## Disclaimer

This project is an unofficial client and is not affiliated with HiAnime or
ani-cli. It only displays publicly available pages from the site. Respect the
laws and terms of service that apply to you. The app does not host or distribute
any video content.

## License

This project is licensed under the **GNU General Public License v3.0** because it
is derived from the GPLv3-licensed `ani-cli` script. See [LICENSE](LICENSE).

Original `ani-cli`: <https://github.com/pystardust/ani-cli>

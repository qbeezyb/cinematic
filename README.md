# Cinematic

Shared cinema screens for Minecraft Java **26.3** and **NeoForge 26.3.0.95**. Install the same mod on the server and every viewing client. Singleplayer runs the media service locally. A separate VLC or FFmpeg installation is not needed.

**Status:** main and test sources compile against the cached NeoForge 26.3.0.95 APIs. Twenty-five JUnit tests pass, including native WAV/MP4/MKV decoding and seeking on macOS ARM64; one live HTTP test is blocked by sandbox restrictions. A complete Gradle build, packaged FML startup, rendering, sound, multiplayer, and the platform matrix remain unverified. See [verification results](docs/verification.md).

## Play

1. Put media in `cinematic/media/` beneath the server's working directory, or the singleplayer game directory. Subdirectories are supported. Run `/cinematic reload` as an operator after adding or replacing files; startup also scans automatically.
2. Craft a **Screen Block** with glass in the center and eight black concrete blocks around it. Craft a **Remote** from an iron ingot, redstone, and a stone button, in any arrangement. Both items also appear in the Cinematic creative tab.
3. Place Screen Blocks in a flat, filled rectangle facing the viewers. Touching blocks facing the same direction automatically form one screen, up to 40×40 blocks per panel. Blocks placed against an existing Screen Block inherit its facing. Every tile is a physical block; add or remove tiles to resize. Walls with gaps split into smaller filled panels until the gaps are filled.
4. Right-click any block in the screen. Hold a Remote in either hand within 16 blocks of the wall to choose media, play/pause, stop, seek, skip ten seconds, or loop. The interface shows the wall’s dimensions. After selecting media, press **Play**; selection initially pauses on a preview.

Growing a screen preserves its selected media and timeline. When selected screens merge, an actively playing screen takes priority. Separate facing directions and parallel walls stay independent. Existing controller items keep their registry ID and recipe; old virtual panels resize to the physical blocks present when their chunks load.

Every viewer can set personal volume and mute without a Remote. These settings persist per server and screen. Master and Jukebox/Note Blocks sound settings also apply. Sound is positioned at the panel center and fades over 64 blocks by default. Keyboard focus, arrow-key sliders, native Minecraft widgets, and tooltips are supported. Audio-only files show their filename and progress on the panel.

Common MP4, MKV, WebM, AVI, MP3, WAV, and Ogg containers are accepted when their codecs are supported by the bundled FFmpeg build. MOV, M4A, and FLAC are also indexed. The default audio track is used. Uploads, external media URLs, subtitles, playlists, transcoding, and editing are outside this version's scope.

## Install and configure

Use Java 25, Minecraft 26.3, and NeoForge 26.3.0.95. After building and verifying the mod, copy `build/libs/cinematic-0.1.0.jar` into each installation's `mods/` directory. Keep the three embedded media libraries inside the JAR.

Add `--add-modules=jdk.httpserver` to Java launch arguments on the dedicated server and viewing clients (including singleplayer). For a NeoForge dedicated installation, place it in `user_jvm_args.txt`; use the launcher's JVM arguments field on clients. This resolves the bundled JDK HTTP service in modular launchers. Development runs set it automatically.

The intended native targets are Windows x64, macOS Intel and Apple Silicon, and Linux x64/ARM64 with a compatible system C runtime. These are packaging targets; none has completed packaged gameplay validation yet. Windows ARM64 and 32-bit systems are not included.

Dedicated servers listen on TCP **25566** in addition to the Minecraft connection. Forward that port or configure a reverse proxy. Edit `config/cinematic-local.toml` after the first launch, then restart. See [the example configuration](docs/cinematic-local.example.toml) and [hosting and troubleshooting](docs/server-setup.md). No environment variables or manually configured access tokens are required. Credentials are issued and renewed over the Minecraft connection.

Singleplayer uses `127.0.0.1` and an automatically assigned port. Opening that world to LAN is not supported by the loopback-only media endpoint; use a dedicated server for remote viewers.

## Develop

The toolchain is Java 25, Gradle 9.2.1, and ModDevGradle 2.0.148. Supporting scripts use Python 3.11 or newer. Set `JAVA_HOME` to a JDK 25 installation and put its `bin` directory on `PATH`. On Windows, use `gradlew.bat` in place of `./gradlew`.

| Purpose | Command |
| --- | --- |
| Resolve dependencies and compile | `./gradlew classes testClasses` |
| Format source whitespace | `./gradlew spotlessApply` |
| Lint, format check, and JUnit tests | `./gradlew check` |
| Production build, including checks | `./gradlew build` |
| JUnit only, including native media tests | `./gradlew test` |
| Development client | `./gradlew runClient` |
| Development dedicated server | `./gradlew runServer` |
| Seven NeoForge GameTests | `./gradlew runGameTestServer` |
| Test embedded natives from the built JAR | `python3 scripts/test-packaged.py build/libs/cinematic-0.1.0.jar` |
| Export original media artifacts for redistribution review | `./gradlew exportMediaArtifacts` |
| Collect upstream source and notices | `python3 scripts/collect-redistribution.py` |

The first Gradle invocation downloads the toolchain dependencies; there is no separate package installation step. Development runs use `run/`. A normal dedicated-server run requires accepting Minecraft's EULA in the generated `run/eula.txt`. Checkstyle enforces naming and import rules; Spotless normalizes whitespace, with indentation defined in `.editorconfig`.

The wrapper scripts and properties follow the official [26.3 MDK](https://github.com/NeoForgeMDKs/MDK-26.3-ModDevGradle). The checked-in launcher JAR is a bootstrap from the locally installed Gradle 9.4.1, targeting the pinned 9.2.1 distribution. Before a release, replace it with the official MDK binary using `python3 scripts/restore-wrapper.py --ref main`; use an immutable MDK commit instead of `main` for reproducibility. The script checks the distribution version and records downloaded hashes.

For a dependency-cached, socket-restricted environment:

```sh
export JAVA_HOME=/path/to/jdk-25
python3 scripts/test-offline.py
# Also run the decoder and native tests if build/embedded contains the media jars:
python3 scripts/test-offline.py --media
"$JAVA_HOME/bin/java" scripts/CheckJavaSyntax.java
```

The offline runner needs cached JUnit 6.0.3 artifacts. Its default run excludes native media tests; `--media` includes decoder regressions and generated MKV/WAV/MP4 fixtures using the three existing `build/embedded/` JARs. The syntax check parses Java without type checking. Neither replaces `./gradlew build`.

## Implementation and limits

The server owns screen identities, revisions, dimensions, media selection, and monotonic timelines. Playback continues while controller chunks are unloaded. Sessions save periodically under `<world>/data/cinematic-sessions.dat` and restore **paused** after restart. Personal audio preferences live in `config/cinematic-audio.properties` on each client.

Clients target at most 1920×1080 and 30 decoded video frames per second, preserve the video aspect ratio with black bars, and correct timeline drift exceeding 250 ms. Each client decodes its four nearest selected screens, with five queued video frames per decoder and bounded PCM buffers. Each player receives at most 64 nearby screen snapshots within 256 blocks. The media index is limited to 4,096 supported files, eight directory levels deep. These bounds keep resource use finite; the 1080p/30 target is not a measured performance guarantee.

See [architecture](docs/architecture.md), [verification and manual test matrix](docs/verification.md), and [third-party notices and redistribution](THIRD_PARTY_NOTICES.md). The existing `AGENTS.md` is preserved; the current project layout and commands are documented here.

Application code is under `src/main/java/`, runtime assets and data under `src/main/resources/`, JUnit tests under `tests/java/`, and supporting tools under `scripts/`.

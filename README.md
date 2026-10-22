# Cinematic

Shared cinema screens for Minecraft Java **26.3** and **NeoForge 26.3.0.95**. Install the same mod on the server and every viewing client. Singleplayer runs the media service locally. A separate VLC or FFmpeg installation is not needed.

**Status:** Java 25 compilation, formatting/style checks, 45 JUnit tests, twelve Cinematic GameTests (plus the vanilla smoke test), production packaging, and the packaged native smoke test pass on macOS ARM64. A 30-second native 1080p/30 run delivers 100% of expected frames after warmup without recovery seeks. Minecraft gameplay and two-client verification limits are recorded in [verification results](docs/verification.md).

## Play

1. Put media in `cinematic/media/` beneath the server's working directory, or the singleplayer game directory. Subdirectories are supported. Run `/cinematic reload` as an operator after adding or replacing files; startup also scans automatically.
2. Craft a **Screen Block** with glass in the center and eight black concrete blocks around it. Craft a **Remote** from an iron ingot, redstone, and a stone button, in any arrangement. Both items also appear in the Cinematic creative tab.
3. Place Screen Blocks in a flat, filled rectangle facing the viewers. Touching blocks facing the same direction automatically form one screen, up to 40×40 blocks per panel. Blocks placed against an existing Screen Block inherit its facing. Every tile is a physical block; add or remove tiles to resize. Walls with gaps split into smaller filled panels until the gaps are filled.
4. Hold a Remote in either hand and right-click to open shared controls from anywhere on the server, including other dimensions. First use opens a searchable screen picker. Right-clicking a Screen Block targets that screen directly; air or other blocks reopen your last selection, remembered through reconnects and respawns. Use **Switch screen…** beside **Done** to change targets. Choose media, play/pause, stop, seek, skip ten seconds, or loop. The interface shows the wall’s dimensions. After selecting media, press **Play**; selection initially pauses on a preview. **Audio…** selects a language track for everyone watching that screen; it is disabled when no alternative exists. Play/resume, playing seeks, and playing language changes prepare for 1.5 seconds before the shared timeline advances.

Growing a screen preserves its selected media and timeline. When selected screens merge, an actively playing screen takes priority. Separate facing directions and parallel walls stay independent. Existing controller items keep their registry ID and recipe; old virtual panels resize to the physical blocks present when their chunks load.

Elapsed and total time have a dedicated row above the seek slider, including hour-long durations and the proposed position while dragging or using arrow keys. Arrow keys seek in one-second steps. The native widgets fit Minecraft’s minimum 320×240 GUI.

Right-click a Screen Block without a Remote to set personal volume and mute. Every viewer can set these preferences independently. These settings persist per server and screen. Master and Jukebox/Note Blocks sound settings also apply. Sound is positioned at the panel center and fades over 64 blocks by default. Keyboard focus, arrow-key sliders, native Minecraft widgets, and tooltips are supported. Audio-only files show their filename and progress on the panel.

Common MP4, MKV, WebM, AVI, MP3, WAV, and Ogg containers are accepted when their codecs are supported by the bundled FFmpeg build. MOV, M4A, and FLAC are also indexed. The container’s default audio track is selected initially, falling back to its first audio stream. Language choices persist with the screen; selecting different media resets to its default. Audio track metadata is limited to 64 tracks per file. Uploads, external media URLs, subtitles, playlists, transcoding, and editing are outside this version's scope.

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
| Isolated UI screenshots and seek checks | `./gradlew -PverifyUi runClient` |
| Development dedicated server | `./gradlew runServer` |
| NeoForge GameTests | `./gradlew runGameTestServer` |
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

The server owns screen identities, revisions, dimensions, media selection, and monotonic timelines. Playback continues while controller chunks are unloaded. Sessions save periodically under `<world>/data/cinematic-sessions.dat` and restore **paused** after restart. Session storage version 2 retains the selected audio stream and loads version-1 saves with default audio. Networking requires protocol 3 on every client and the server; upgrade them together. Personal audio preferences live in `config/cinematic-audio.properties` on each client.

Clients target at most 1920×1080 and 30 decoded video frames per second, preserve the video aspect ratio with black bars, and correct timeline drift exceeding 250 ms. Each client decodes its four nearest selected screens, with approximately 500 ms of prebuffering, at most 16 queued video frames (under 128 MiB), and two seconds of PCM per decoder. Each player receives at most 64 nearby screen snapshots within 256 blocks. Remote controls use separate metadata snapshots: distant controls refresh once per second and after changes, without starting a decoder, sound, or texture. Closing the interface or putting away the Remote stops those requests. Persisted screens remain controllable in unloaded chunks without loading them; removed screens are rejected when their loaded structure is reconciled. The picker returns at most 20 entries per response and identifies screens by dimension, coordinates, size, and selected media, with full details in tooltips. The media index is limited to 4,096 supported files, eight directory levels deep. These bounds keep resource use finite. The synthetic native playback measurement does not guarantee the same performance for every codec or Minecraft rendering load.

See [architecture](docs/architecture.md), [verification and manual test matrix](docs/verification.md), and [third-party notices and redistribution](THIRD_PARTY_NOTICES.md). The existing `AGENTS.md` is preserved; the current project layout and commands are documented here.

Application code is under `src/main/java/`, runtime assets and data under `src/main/resources/`, JUnit tests under `tests/java/`, and supporting tools under `scripts/`.

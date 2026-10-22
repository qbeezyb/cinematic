# Verification record

Implementation environment: macOS Apple Silicon, 2026-10-22. System-default Java is 17; checks explicitly use `/opt/homebrew/opt/openjdk@25`. Following the user's Gradle build, the generated patched Minecraft/NeoForge 26.3.0.95 artifacts and media dependencies are available in the local cache.

## Current playback and connected-wall checks

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 python3 scripts/test-offline.py --media
/opt/homebrew/opt/openjdk@25/bin/javac @build/api-compile/javac-main.args
/opt/homebrew/opt/openjdk@25/bin/java scripts/CheckJavaSyntax.java
```

The current suite discovers **26 tests: 25 pass, one is aborted, none fail**. The same sandbox restriction aborts the live HTTP listener test; the real HTTP handler still passes its in-memory protocol tests. The default offline runner omits the native tests; `--media` uses the three existing `build/embedded/` JARs and the local JavaCPP extraction cache. The main response file is a local generated artifact containing the cached NeoForge/dependency classpath and all current main source paths, not a portable build command. All 32 main Java sources compile against the patched NeoForge 26.3.0.95 artifact. The parser accepts all **42 Java files**. Checkstyle 10.26.1 also completes with no violations when invoked directly against all main/test sources with cached dependencies; the Gradle tasks themselves remain blocked.

Before the decoder fix, the real worker loop with a deterministic grabber delivering audio at 200 ms and its first image at 240 ms failed with `Playback remained in Buffering instead of decoding the first video frame`. Both the initial-preview and backward-seek tests failed. They now pass. Normal decode-ahead throttling waits until the first playable image/audio-only frame is ready. A generated native Matroska file beginning at timestamp 1 second also passes opening, paused/playing frame delivery at timeline zero, RGBA conversion, and seeking to 1 second through `MediaDecoder`, using production decoder settings except for permitting local-file transport. Frame timestamps subtract the container start offset. Native WAV/MP4 tests continue to pass. These checks do not exercise HTTP transport through FFmpeg, Minecraft texture upload, or Minecraft sound channels.

Pure-Java connected-wall tests cover a filled rectangle, a hole and its repair, oversized-wall partitioning at 40 blocks per dimension, exact non-overlapping physical coverage, all four direction bases, moved-anchor identity/timeline preservation, and permissions near a large wall's far edge. The seven existing GameTests have been updated to assert shared panels in all four facings, growth, split/rejoin, independent opposite facings, persistence, and rejection of network-created virtual dimensions. Their sources compile; GameTests and visual checks have not run here.

The reported `xyz.mkv` is not available in the workspace or the inspected standard Minecraft locations. The generated MKV and deterministic worker tests establish the corrected failure patterns, not verification of that exact media file. A real client should retry it and report the new opening/seeking/first-frame state if it still cannot play. Stalled startup/seek attempts now report an actionable timeout after 30 seconds instead of remaining indefinitely in buffering.

The current wrapper attempts are blocked before build execution: the default Gradle cache is read-only in this sandbox, and a workspace-local wrapper cache cannot download Gradle 9.2.1 because DNS is unavailable. No new mod JAR, packaged gameplay, or screenshots were produced by this agent. Build and launch in a normal Java 25 environment using the commands below.

## Earlier foundation checks

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 python3 scripts/test-offline.py
/opt/homebrew/opt/openjdk@25/bin/java scripts/CheckJavaSyntax.java
```

The offline JUnit run discovers **15 tests: 14 pass, one is aborted, none fail**. The aborted test starts an actual local HTTP listener; the sandbox rejects that socket with `Operation not permitted`. The same HTTP handler is separately exercised through an in-memory `HttpExchange`, including authenticated full/range/HEAD responses, invalid ranges, traversal attempts, missing media, invalid credentials, and logout revocation.

The compiled pure-Java suite covers timeline play/pause/end/loop/seek behavior, independent dimensions, Remote permission policy including dimension/distance/revision, session persistence and corrupt-file handling, aspect fit and all four panel bases, clock estimation, file indexing/symlink rejection, token expiry/renewal, HTTP ranges, local audio preferences, PCM bounds, and invalidation/coalescing of rapid decoder seeks.

The JDK parser accepts **39 Java files** with no syntax errors. This is a parse-only check: it does not verify Minecraft/NeoForge types, FFmpeg APIs, runtime linkage, or rendering/sound behavior. Resource JSON/TOML, PNG/Ogg headers, compressed structure data, and script syntax are checked separately during implementation.

### Compiler regression and native tests

The user's `compileJava` log reported eleven errors. Direct Java 25 compilation of the actual main sources against `build/moddev/artifacts/minecraft-patched-26.3.0.95.jar` and cached dependency JARs reproduced all eleven before editing. The same compiler invocation passes after these corrections:

- Horizontal facing uses `EnumProperty<Direction>`; `DirectionProperty` no longer exists.
- Client payload handler registration is under `net.neoforged.neoforge.client.network.event`.
- Serverbound client sends use `ClientPacketDistributor` in `net.neoforged.neoforge.client.network`; common `PacketDistributor` remains for server sends.
- `EditBox.setFilter` no longer exists. Dimension fields override `insertText` to preserve numeric-only typing/paste and retain the two-character limit and 1–40 validation.

The GameTest `setBlock` diagnostic was a cascade from the invalid property type; its existing `BlockState` overload compiles unchanged. Main and all JUnit sources now type-check with `javac -proc:none`, including client classes, server classes, GameTests, and native media tests. This is an actual compile against resolved APIs, separate from the parse-only check above; Gradle's task orchestration and annotation processing remain untested here.

Running the complete JUnit suite against these classes and the three existing `build/embedded/` media JARs found **17 tests: 16 pass, one is aborted, none fail**. The same socket restriction aborts the live HTTP test. Both `NativeMediaTest` cases pass on macOS ARM64: loading headless FFmpeg, probing/decoding/seeking WAV, and encoding/probing/decoding/seeking MP4. Native extraction used a workspace-local JavaCPP cache. This does not verify a final mod JAR, FML module loading, Minecraft video upload/audio channels, or other platforms.

A JDK-only automatic-module launch experiment confirmed that `jdk.httpserver` is not automatically resolved in that launch mode. Development runs now add `--add-modules=jdk.httpserver`; the same installation argument is documented for packaged launches. Actual FML startup remains pending.

## Blocked checks

The initial wrapper attempt could not download Gradle 9.2.1 because DNS resolution failed. The current `JAVA_HOME=/opt/homebrew/opt/openjdk@25 GRADLE_USER_HOME="$PWD/.gradle-home" /opt/homebrew/bin/gradle --offline --no-daemon compileJava` attempt still fails before project evaluation because the sandbox prohibits Gradle's internal file-lock socket. Direct compilation and JUnit now succeed using the user's cached artifacts, but normal Gradle compilation, Checkstyle/Spotless, packaging, and GameTests have not been run successfully by the agent.

The manually seeded media lockfile pins the non-transitive media configurations only. It has not been regenerated by Gradle. The launcher JAR is a local Gradle 9.4.1 bootstrap targeting 9.2.1; replace it with the official MDK wrapper before release. API research in `docs/api-research.md` identifies relevant current APIs but does not substitute for compilation.

## Next executable checks

In a network- and socket-capable Java 25 environment, restore the official wrapper, then run:

```sh
python3 scripts/restore-wrapper.py --ref main
./gradlew classes testClasses
./gradlew spotlessCheck checkstyleMain checkstyleTest test build
./gradlew runGameTestServer
python3 scripts/test-packaged.py build/libs/cinematic-0.1.0.jar
```

Use an immutable MDK commit in place of `main` for a release. `NativeMediaTest` probes/decodes/seeks a generated WAV and records/decodes/seeks a generated MP4; those tests pass in the direct full-suite run and are included by `test-offline.py --media`. The packaged test loads only the embedded libraries, verifies platform resource presence, and performs headless native PCM decoding/seeking through named modules. Passing it still does not test FML or Minecraft audio/video integration.

Seven registered GameTests now cover registration, four-facing connected placement, immediate replacement, recipes, rectangular dimensions and gap repair, paused persistence, and the server control validation path (missing Remote, offhand Remote, stale revision, excess distance, and rejected virtual resizing). These tests have not run.

The CI workflow describes a Java 25 build/native-test matrix and Linux GameTests. It has not executed; its presence is not a passing CI result.

## Manual integration matrix

Record actual game/NeoForge/JDK versions, artifact hashes, media codecs, measurements, logs, and screenshots for each run. Every row below is **pending**.

| Scenario | Required observation |
| --- | --- |
| Packaged dedicated server, headless | Starts without graphics/audio initialization; probes media; unavailable port reports an error while Minecraft stays running. |
| Packaged singleplayer | Loopback ephemeral service, controller UI, native video upload, positional PCM, exit/rejoin cleanup. |
| Four facings; 1×1, 40×40, and asymmetric walls | Filled same-facing rectangles connect; holes split and repair rejoins them; aspect ratio/black bars and full-panel visibility remain correct. |
| Crafting, creative, survival placement/destruction | Both recipes and entries work; every screen tile is physical; adding/removing tiles resizes; removing the final tile resets identity/state. |
| Two clients, independent audio | Shared selection/play/pause/loop/seek; volume and mute differ locally and persist after reconnect. |
| Rapid repeated seeks and pause/resume | Old frames/audio do not reappear; final seek wins; no accumulating native objects or channels. |
| Late join and artificial buffering/connection loss | Client catches up to server position without pausing other viewers; retry recovers. |
| Remote removal, offhand use, distance and dimension | Removing a Remote disables/rejects changes immediately; offhand works; out-of-range/dimension/stale updates cannot mutate state. |
| Multiple screens | Independent timelines/audio, bounded workers/queues, four-nearest limit, resources released on eviction. |
| Chunk unload and server restart | Timelines advance while unloaded; restart retains identity/dimensions/selection and pauses at saved position. |
| Invalid/missing/corrupt files and reload | Invalid entries are omitted, actionable errors appear, other screens/Minecraft continue. |
| Media authentication and renewal | Unauthorized fetches fail; range seeks work; renewal recovers at the shared timeline; logout revokes grants. |
| UI keyboard/navigation and all GUI scales | Focus, tooltips, search, library pages, sliders, loading/errors, and the automatic size display work without clipping. |
| 1080p/30 representative clips | Measure frame rate, render-thread upload cost, CPU/memory, A/V drift and network load. No target throughput is established yet. |

Repeat packaged native and gameplay checks on Windows x64, macOS x64, macOS ARM64, Linux x64, and Linux ARM64. Include video-only, audio-only, and combined streams across the supported common containers. Release remains blocked until compilation, native embedding, FML startup, rendering/audio, multiplayer synchronization, cross-platform checks, and exact native redistribution materials have been completed.

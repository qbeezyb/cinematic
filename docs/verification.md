# Verification record

Environment: macOS ARM64 (Apple M3 Max), 2026-10-22; Java 25.0.2,
Gradle 9.2.1, Minecraft 26.3, NeoForge 26.3.0.95. Commands below explicitly
select `/opt/homebrew/opt/openjdk@25`. Escalated execution allowed Gradle cache
writes, sockets, and dependency/asset downloads; earlier sandbox-only failures
recorded in this repository no longer describe the current build results.

## Automated results

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew spotlessApply check build
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runGameTestServer
JAVA_HOME=/opt/homebrew/opt/openjdk@25 python3 scripts/test-packaged.py build/libs/cinematic-0.1.0.jar
```

- Java 25 main/test compilation, Checkstyle, Spotless, JUnit, and production
  packaging pass. The suite contains **40 passing JUnit tests**, with no skipped
  or aborted tests. This includes the real authenticated HTTP listener test.
- **All nine server GameTests pass**: eight Cinematic tests and the vanilla
  smoke test. Language controls exercise real indexed media through the server
  permission path, including paused/playing switches, scheduled preparation,
  stale revisions, wrong media, nonexistent streams, and excess distance.
  Existing tests also cover Remote possession, offhand use, wall growth,
  split/rejoin, facings, removal, recipes, and paused persistence with audio.
- The packaged smoke test verifies the three embedded libraries, resources for
  all five intended native platforms, and actual named-module FFmpeg loading,
  WAV decoding, and seeking on this host. Other platforms have not executed.
- A development FML server starts and probes the language fixture without
  graphics/audio initialization. `runClient` also starts successfully with
  Apple OpenGL and OpenAL (`MacBook Pro Speakers`), then shuts down cleanly.
  Client startup alone does not establish movie rendering or audible playback.

GameTests automatically copy `tests/fixtures/languages.mkv` into
`run/cinematic/media/`; fixtures are excluded from release jars. Reports are in
`build/reports/tests/test/` and `build/reports/checkstyle/`.

## Pacing regression and native measurement

Before the pacing fix, this command failed the new real-worker regression:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 python3 scripts/test-offline.py --media
```

The deterministic trace alternates 30 fps video with audio timestamps 500 ms
ahead. It failed with `Audio at +500ms blocked video prebuffer: 1 frames`.
The same test now prebuffers at least fifteen video frames without requesting
resync. Independent audio/video timestamps eliminate the throttle that stalled
video after each early audio packet. Earlier offset-MKV paused-preview and
backward-seek regressions remain covered.

`native1080p30DeliversNinetyPercentOfFramesForThirtySecondsWithoutRecovery`
generates a native 1920×1080/30 MPEG-4 Matroska clip with PCM audio, prebuffers,
and drives production `MediaDecoder` with a wall clock. It models 60 Hz frame
consumption and four queued 100 ms PCM buffers. After two seconds of warmup,
the thirty-second measurement reports:

```text
1080p30: 900/900 frames after warmup (100.0%), recoveries=0, short PCM reads=0
```

Acceptance is at least 810 of 900 frames, zero recovery seeks, and no short PCM
reads after warmup. Queue assertions check at most sixteen images and 192,000
PCM bytes throughout. Sixteen maximum-size RGBA images occupy 126.6 MiB,
below the 128 MiB video-queue budget. This is a synthetic native-decoder
measurement with file transport, not a Minecraft render benchmark or a promise
for every codec. The original reported media file is unavailable here.

Additional tests cover 150 ms decoder stalls without seeking, timestamped PCM
gaps/stale-sample trimming, EOF and short tails, rapid seeks and old-generation
invalidation (including retired sound readers leaving new PCM intact), audio-only readiness, video-only prebuffering, paused previews,
and reopening after EOF for local loop wrapping. Scheduled timeline tests
verify play/resume, playing/paused seeks, preparation cancellation by pause/stop,
loop wrapping without a new preparation period, and snapshot anchors.

## Audio selection and compatibility

The documented two-second [language fixture](../tests/fixtures/README.md) has
German 440 Hz audio at global stream index 1 and default English 880 Hz audio
at index 2. Native probing checks indexes, language, title, codec, channels, and
default disposition. Zero-crossing measurements verify both selected streams
through FFmpeg and the production decoder configuration. The default being the
second audio track catches accidental first-track selection.

Tests also cover single-audio WAV, video-only MP4/MKV, duplicate/missing language
labels, metadata bounds, default fallback, bounded protocol collections, and
protocol-2 round trips retaining the resolved stream and future timeline anchor.
Version-1 binary sessions load paused with default audio; version-2 persistence
retains the selection. Wall copies and actual GameTests retain shared selection
through growth, splitting, merging, and snapshots.

The first executed wall GameTest exposed an existing intermittent identity loss:
intermediate pieces with identical selected media used a random UUID tie-break
on merge. The donor order now prefers the lower/left anchor after playing/media
priority, preserving the original screen through incremental construction.
The GameTests passed after this correction.

## Remaining integration checks

These checks are **pending**, with no passing result claimed:

| Check | Limit or next observation |
| --- | --- |
| Actual Minecraft movie texture upload and audible sound | Client graphics/audio startup succeeded, but no in-world playback scenario or screenshot was driven. Check RGBA colors, smoothness, retained image during recovery, and sound continuity. |
| Remote interaction at all GUI scales | Widgets compile; verify keyboard focus, narration, tooltips, pagination, loading/errors, selected rows, and disabled single/no-audio selection in a live world. |
| Two clients watching one screen | No two-client gameplay session was available. Verify shared language/seek/play changes while volume and mute remain independent, including late joins. |
| Packet timing and loop boundary snapshots | Codecs and timelines pass automated checks; exercise latency, late delivery, loop wraps during snapshots, rapid playing track changes, pause, and stop in multiplayer. |
| Packaged FML gameplay | Development FML and packaged named-module media smoke pass separately. Launch the built mod in normal client and dedicated installations. |
| Other operating systems/architectures | This host executes macOS ARM64 only. Run native/gameplay checks on Windows x64, macOS x64, Linux x64, and Linux ARM64. |
| Representative high-motion/network media | Synthetic native throughput passes. Measure real HTTP playback, upload cost, A/V drift, CPU/memory, and four-screen load. |

Use protocol 2 on both server and clients. Back up world sessions before testing
storage migration. `AGENTS.md` remains unchanged.

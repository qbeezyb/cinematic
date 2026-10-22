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
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew -PverifyUi runClient
JAVA_HOME=/opt/homebrew/opt/openjdk@25 python3 scripts/test-packaged.py build/libs/cinematic-0.1.0.jar
```

- Java 25 main/test compilation, Checkstyle, Spotless, JUnit, and production
  packaging pass. The suite contains **45 passing JUnit tests**, with no skipped
  or aborted tests. This includes the real authenticated HTTP listener test.
- **All thirteen server GameTests pass**: twelve Cinematic tests and the vanilla
  smoke test. Language controls exercise real indexed media through the server
  permission path, including paused/playing switches, scheduled preparation,
  stale revisions, wrong media, nonexistent streams, and successful distant control.
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

## Remote and timestamp verification

Protocol 3 adds request-correlated screen lists (at most 20 entries) and individual
metadata responses. JUnit checks their round trips, oversized/negative collection
counts, mismatched identities, superseded request IDs, older revisions and older
same-revision timestamps, closing/invalidation, removed responses, and retention
of the newest target through nearby snapshot replacement. The real `ClientPlayback`
regression opens distant metadata and replaces the nearby set: no playback entry
is created, so no decoder, sound, or texture can be allocated by opening controls.

Server GameTests cover controls across dimensions at a 50,000-block offset,
missing Remote, offhand possession, stale revisions, invalid targets and action
parameters, picker search/pagination/empty results, serialized player NBT and
respawn cloning, and removed targets. An injected restored-session fixture at
20,000,000 / 80 / 20,000,000 accepts seek and loop while `hasChunkAt` stays false.
The item/event test exercises first air use, main/offhand reopening, direct Screen
Block targeting, successful event consumption before block/item handling can
repeat, other-block reopening, and removal fallback. The initial pagination
fixture overlapped neighboring test areas; moving its blocks to an isolated
vertical column corrected the fixture and all thirteen tests pass.

The opt-in UI fixture uses production widgets in a real Minecraft client with
controlled metadata and no world connection. It checks every playback widget's
bounds at GUI scales 1, 2, and 4 (1280×960, 640×480, and **320×240** GUI pixels),
empty seek-slider text, an exact one-second right-arrow step, and the proposed
75% drag position. Captured screenshots were visually inspected: elapsed/total
hours are readable above the slider and controls fit the minimum GUI. The
keyboard case shows **1:01:06 / 2:00:00**; dragging shows **1:30:00 / 2:00:00**.
The fixture enables the slider directly for these input checks; gameplay Remote
possession is covered separately by server tests. An initial harness assertion
repeated an unchanged drag after the no-Remote UI cleared its preview; resetting
the fixture slider before each sample fixed the harness, and the final run exits
successfully after all five cases.

Reproduce with `./gradlew -PverifyUi runClient`. This uses the isolated
`run-ui-verification/` directory, handles first-run accessibility onboarding,
captures five PNGs, and exits automatically. Test classes under `tests/client/`
are included only with this property and never enter release JARs. Local artifacts:

- [Minimum GUI](../run-ui-verification/screenshots/remote-minimum-gui.png)
- [GUI scale 1](../run-ui-verification/screenshots/remote-scale-1.png)
- [GUI scale 2](../run-ui-verification/screenshots/remote-scale-2.png)
- [Keyboard preview](../run-ui-verification/screenshots/remote-keyboard-seek.png)
- [Drag preview](../run-ui-verification/screenshots/remote-drag-seek.png)

These generated artifacts remain outside version control. The fixture proves
rendering and input behavior without a connected world. In-world picker focus,
narration, actual reconnects, and two-client shared playback with independent
volume remain integration checks below; no two-client gameplay session was
available in this environment.

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
protocol-3 round trips retaining the resolved stream and future timeline anchor.
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
| In-world Remote/picker interaction | Real-client playback screenshots and seek checks pass at scales 1/2/4; server item, search/page and permission paths pass GameTests. Exercise picker keyboard focus/narration, tooltips, loading/errors, switching targets, actual logout/reconnect and disabled single/no-audio selection in a connected world. The screenshot fixture has no world connection. |
| Two clients watching one screen | No two-client gameplay session was available. Verify shared language/seek/play changes while volume and mute remain independent, including late joins. |
| Packet timing and loop boundary snapshots | Codecs and timelines pass automated checks; exercise latency, late delivery, loop wraps during snapshots, rapid playing track changes, pause, and stop in multiplayer. |
| Packaged FML gameplay | Development FML and packaged named-module media smoke pass separately. Launch the built mod in normal client and dedicated installations. |
| Other operating systems/architectures | This host executes macOS ARM64 only. Run native/gameplay checks on Windows x64, macOS x64, Linux x64, and Linux ARM64. |
| Representative high-motion/network media | Synthetic native throughput passes. Measure real HTTP playback, upload cost, A/V drift, CPU/memory, and four-screen load. |

Use protocol 3 on both server and clients. Back up world sessions before testing
storage migration. `AGENTS.md` remains unchanged.

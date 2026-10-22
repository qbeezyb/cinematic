# Architecture

## Server ownership

`Cinematic` registers the controller block and item, Remote, creative tab, block entity, media sound event, configuration, payloads, and GameTest functions. `CinematicClient` is a physical-client entry point; server initialization has no client renderer, OpenGL, or sound-engine references. Headless JavaCV probing does not instantiate AWT or an audio device.

`CinemaServer` owns sessions on the Minecraft server thread. A `ScreenKey` combines dimension and block coordinates; each rectangular panel has a UUID shared by its member blocks. A session's immutable `Timeline` records duration, position, monotonic anchor, playing, and looping. Every mutation advances its revision. Possession of a Remote in either hand, valid screen identity, current revision, and action-specific bounds are checked before mutation and protected library/audio metadata access. Controls work server-wide across dimensions. Physical checks use the screen’s dimension and inspect only loaded chunks; persisted sessions in unloaded chunks remain controllable without loading those chunks. Loaded missing anchors trigger wall reconciliation and removed identities are rejected. Requests are capped at twenty per player per second.

`ScreenLayout` partitions coplanar, equally facing, edge-connected blocks into filled rectangles, each at most 40×40. The deterministic partition starts at the lowest row and extends complete rows; holes never produce phantom pixels or blocks. A bounded flood fill inspects at most 6,400 blocks per connected component; larger components defer grouping until split. Discovery waits for adjacent chunks to be available rather than loading them or splitting a partly unloaded wall. Pending changes retry on server ticks. After one successful wall scan, member block-entity loads reuse that result.

The session map indexes every physical member. Placement/removal rebuilds the affected components, retains the selected/playing donor session on a merge, moves its origin without changing its UUID, and copies playback and the selected audio stream into new pieces on a split when necessary. Entirely removing a panel drops its identity. Clicking any member opens the shared controls. Network RESIZE requests are rejected: adding or removing blocks determines dimensions. The renderer draws one full-panel quad from the origin block entity, with full-panel bounds; member blocks do not draw duplicate video overlays. Older overlapping virtual sessions are accepted and resolved to physical walls as their chunks load. Sessions in unloaded chunks retain their timelines.

`SessionStore` writes a bounded, versioned binary capture with atomic replacement where supported. The server captures on its own thread and saves on a single background worker every five seconds. Final shutdown captures again. Version 2 also stores the selected global audio stream. Version-1 captures load with default audio. Restored timelines always pause at their saved position. Corrupt persistence is preserved and disables saving until repaired.

## Library and transport

`MediaLibrary` publishes immutable snapshots from one scanning worker. It walks at most eight levels without following symlinks, indexes up to 4,096 files, reuses unchanged probe results, and rejects invalid files individually. IDs derive from relative path, size, and modification time; filesystem names and paths are never request parameters. `NativeMediaProbe` restricts local FFmpeg input protocols and container formats.

`MediaHttpServer` uses JDK HTTP facilities and bounded workers. `MediaHttpHandler` checks bearer authentication before lookup, accepts only exact `/media/<32 hex digits>` paths, and serves only current indexed files after rechecking containment/metadata. It implements GET/HEAD and a single byte range per request. The `Credentials` store holds SHA-256 token hashes, limits overlapping leases, and expires/revokes them.

The JDK HTTP API belongs to `jdk.httpserver`, which is not automatically resolved in every modular Java launch. Development runs explicitly add it; packaged installations must include `--add-modules=jdk.httpserver` in JVM arguments. A missing module is reported without terminating Minecraft.

Singleplayer binds loopback on an ephemeral port. Dedicated servers use configured bind/advertised addresses. A media-service failure is reported through snapshots and access responses without aborting Minecraft.

## Connection protocol

NeoForge payload identifiers carry a `v3` suffix and the registrar requires matching protocol version `3`. Codecs bound strings, lists, and enum values. Client handlers are registered from the client entry point.

| Payload | Direction | Role |
| --- | --- | --- |
| Request | Client → server | Library search/page, audio-track metadata, screen search/page, target selection/refresh, media lease, clock sample |
| Control | Client → server | Screen UUID, expected revision, action and validated parameters |
| Snapshots | Server → client | Complete nearest set, timeline anchor, media metadata, dimensions and revision |
| Library | Server → client | Twenty bounded entries; echoes request identity so stale searches are ignored |
| AudioTracks | Server → client | Up to 64 bounded tracks; echoes screen, media and request identity so stale results are ignored |
| Access | Server → client | Advertised base URL, renewable bearer token and expiry, or actionable service error |
| Clock | Server → client | Echoed client send time and monotonic server time |
| ScreenList | Server → client | At most 20 screen metadata snapshots with request identity, offset, total and error status |
| ScreenState | Server → client | Request-correlated individual target metadata or missing/error status |
| Open / Notice | Server → client | Open with initial target metadata (null means picker), or report a rejected action |

Each second the server sends at most 64 nearest snapshots within 256 blocks in the same dimension. Updates also follow controls. The client rejects older per-screen revisions. Lowest-round-trip midpoint samples estimate the clock offset; local timelines advance between snapshots. Snapshots carry the timeline’s base position and actual anchor, including future anchors. Play/resume, playing seeks, and playing track changes schedule the anchor 1.5 seconds ahead; position remains fixed until then. Paused seeks remain paused and stop cancels preparation. Late/buffering clients recover individually. Natural loop wraps seek locally without adding a scheduled pause.

`RemoteItem` consumes block interactions before another block menu can open and consumes air use through the item API. Direct Screen Block use selects that panel; other uses resolve the remembered UUID. The UUID lives under `PlayerPersisted` in player NBT, which NeoForge saves and copies on respawn. Session storage remains version 2.

`RemoteTarget` holds one snapshot independently of nearby `ClientPlayback.Entry` instances. Opening distant controls allocates no playback entry, decoder, sound or texture. The UI chooses the newest local/remote state and uses local decoder status when available, otherwise shared playing/paused/preparing/error status. One-second refreshes and immediate refresh after controls are scoped to the current UI and Remote possession. Closing, switching, or putting away the item invalidates outstanding responses. Target and picker responses echo unique request IDs; superseded responses and older revisions are ignored. Picker search is debounced, pages use stable dimension/coordinate/UUID order, and native widgets supply focus, keyboard navigation, narration and tooltips.

Playback controls use a 230-pixel panel. Elapsed/proposed and total timestamps occupy a separate row above the text-free seek slider. Narration retains the seek position, arrows use one-second steps, and seeks debounce for 250 ms and wait for drag release.

## Playback and lifetime

Each client activates at most four nearest selected screens. `MediaDecoder` owns FFmpeg on one background thread per decoder; native objects never cross into render/audio callbacks. Four workers and a four-job pending queue bound work. Video is converted to RGBA at no more than 1920×1080 and 30 fps, with at most 16 copied frames queued (126.6 MiB at maximum resolution). Mono signed 16-bit 48 kHz PCM has a two-second maximum queue. Normal decoding stays about 500 ms ahead. Video and audio timestamps are independent: only video PTS controls video pacing and sustained drift detection. Audio ahead of video cannot throttle decoding or trigger video recovery. Audio-only files pace from PCM timestamps; short tails can start with less than a full prebuffer.

The container start timestamp is subtracted from frame timestamps. Paused previews use the requested position even if the first video packet is later. Sustained presentation lag exceeding 250 ms for 750 ms can request recovery, with at least five seconds between attempts. Brief audio starvation inserts silence and trims stale samples on recovery, without seeking the movie.

`SeekMailbox` coalesces requests and changes a generation before clearing queues. A decode already in progress may publish only into its original generation. Opening, seeking, preparing, and buffering have distinct UI states. A track change replaces the decoder with the selected container-global stream configured before FFmpeg starts, while retaining the displayed texture and playing/paused intent. A first-frame/open/seek stall lasting 30 seconds reports a timeout and closes the decoder instead of showing indefinite buffering. Seeking, pause/resume, resync, and loop restart stop the old Minecraft sound channel and clear stale audio. FFmpeg closes at EOF, then reopens on a later seek using the same worker. Cancellation, errors, world changes, and screen eviction close decoders and audio queues; inactive textures are released.

`ClientPlayback` bulk-copies due RGBA frames into `NativeImage.getPixelBytes()` and uploads `DynamicTexture` during extraction on the render thread. The last image remains displayed while preparing or recovering. `ScreenRenderer` submits a black panel and an aspect-fitted unlit video quad through the current block entity render-state API. Audio-only and loading states use Minecraft text and progress geometry.

`MediaSound` supplies custom PCM through Minecraft's streaming `AudioStream` interface in the RECORDS category. Reads supply 100 ms chunks, providing about 400 ms across Minecraft’s four queued buffers. PCM carries sample timestamps; stream epochs prevent a retired sound stream from reading the next seek’s audio. A positional mono channel is located at the screen center; per-tick gain applies linear distance fading and local preferences, while Minecraft supplies its category/master gain. `PersonalAudio` hashes server identity plus screen UUID into the local preferences file.

## Packaging

The build resolves JavaCV, JavaCPP, and FFmpeg without transitive platform aggregates. It merges each Java library with its own platform resources into three automatic-module JARs, then embeds those with NeoForge Jar-in-Jar. Module descriptors and invalidated signature metadata are removed; bytecode and native binaries are otherwise unchanged. This avoids classifier identity collisions and keeps class-relative native resource lookup intact. No OpenCV, CUDA, capture-device drivers, or system player dependency is requested.

The Gradle build, macOS ARM64 packaged native smoke test, and development FML client/server startup have run successfully. Packaged gameplay, actual movie texture/audio playback, and multiplayer still need integration verification; see `verification.md`.

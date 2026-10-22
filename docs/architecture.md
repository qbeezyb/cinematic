# Architecture

## Server ownership

`Cinematic` registers the controller block and item, Remote, creative tab, block entity, media sound event, configuration, payloads, and GameTest functions. `CinematicClient` is a physical-client entry point; server initialization has no client renderer, OpenGL, or sound-engine references. Headless JavaCV probing does not instantiate AWT or an audio device.

`CinemaServer` owns sessions on the Minecraft server thread. A `ScreenKey` combines dimension and block coordinates; each rectangular panel has a UUID shared by its member blocks. A session's immutable `Timeline` records duration, position, monotonic anchor, playing, and looping. Every mutation advances its revision. Possession of a Remote in either hand, dimension, distance to the entire physical wall, current controller existence, revision, and action-specific bounds are checked before mutation. Requests are capped at twenty per player per second.

`ScreenLayout` partitions coplanar, equally facing, edge-connected blocks into filled rectangles, each at most 40×40. The deterministic partition starts at the lowest row and extends complete rows; holes never produce phantom pixels or blocks. A bounded flood fill inspects at most 6,400 blocks per connected component; larger components defer grouping until split. Discovery waits for adjacent chunks to be available rather than loading them or splitting a partly unloaded wall. Pending changes retry on server ticks. After one successful wall scan, member block-entity loads reuse that result.

The session map indexes every physical member. Placement/removal rebuilds the affected components, retains the selected/playing donor session on a merge, moves its origin without changing its UUID, and copies playback into new pieces on a split when necessary. Entirely removing a panel drops its identity. Clicking any member opens the shared controls. Network RESIZE requests are rejected: adding or removing blocks determines dimensions. The renderer draws one full-panel quad from the origin block entity, with full-panel bounds; member blocks do not draw duplicate video overlays. Older overlapping virtual sessions are accepted and resolved to physical walls as their chunks load. Sessions in unloaded chunks retain their timelines.

`SessionStore` writes a bounded, versioned binary capture with atomic replacement where supported. The server captures on its own thread and saves on a single background worker every five seconds. Final shutdown captures again. Restored timelines always pause at their saved position. Corrupt persistence is preserved and disables saving until repaired.

## Library and transport

`MediaLibrary` publishes immutable snapshots from one scanning worker. It walks at most eight levels without following symlinks, indexes up to 4,096 files, reuses unchanged probe results, and rejects invalid files individually. IDs derive from relative path, size, and modification time; filesystem names and paths are never request parameters. `NativeMediaProbe` restricts local FFmpeg input protocols and container formats.

`MediaHttpServer` uses JDK HTTP facilities and bounded workers. `MediaHttpHandler` checks bearer authentication before lookup, accepts only exact `/media/<32 hex digits>` paths, and serves only current indexed files after rechecking containment/metadata. It implements GET/HEAD and a single byte range per request. The `Credentials` store holds SHA-256 token hashes, limits overlapping leases, and expires/revokes them.

The JDK HTTP API belongs to `jdk.httpserver`, which is not automatically resolved in every modular Java launch. Development runs explicitly add it; packaged installations must include `--add-modules=jdk.httpserver` in JVM arguments. A missing module is reported without terminating Minecraft.

Singleplayer binds loopback on an ephemeral port. Dedicated servers use configured bind/advertised addresses. A media-service failure is reported through snapshots and access responses without aborting Minecraft.

## Connection protocol

NeoForge payload identifiers carry a `v1` suffix and the registrar requires protocol version `1`. Codecs bound strings, lists, and enum values. Client handlers are registered from the client entry point.

| Payload | Direction | Role |
| --- | --- | --- |
| Request | Client → server | Library search/page, media lease, clock sample |
| Control | Client → server | Screen UUID, expected revision, action and validated parameters |
| Snapshots | Server → client | Complete nearest set, timeline anchor, media metadata, dimensions and revision |
| Library | Server → client | Twenty bounded entries; echoes request identity so stale searches are ignored |
| Access | Server → client | Advertised base URL, renewable bearer token and expiry, or actionable service error |
| Clock | Server → client | Echoed client send time and monotonic server time |
| Open / Notice | Server → client | Open controller UI or report a rejected action |

Each second the server sends at most 64 nearest snapshots within 256 blocks in the same dimension. Updates also follow controls. The client rejects older per-screen revisions. Lowest-round-trip midpoint samples estimate the clock offset; local timelines advance between snapshots. Server corrections larger than 250 ms trigger a background seek. Buffering never pauses the shared timeline.

## Playback and lifetime

Each client activates at most four nearest selected screens. `MediaDecoder` owns FFmpeg on one background thread per decoder; native objects never cross into render/audio callbacks. Four workers and a four-job pending queue bound work. Video is converted to RGBA at no more than 1920×1080 and 30 fps, with at most five copied frames queued. Mono signed 16-bit 48 kHz PCM has a two-second maximum queue; normal decoding stays about 150 ms ahead. This throttle starts only after a playable image (or audio-only samples) exists, so audio-leading MKV tracks cannot strand a paused preview. Container start timestamps are subtracted from frame timestamps to match the relative server timeline. Paused previews are displayed at the requested position even if the first video packet is later.

`SeekMailbox` coalesces requests and changes a generation before clearing queues. A decode already in progress may publish only into its original generation. Opening, seeking, and first-frame buffering have distinct UI states. A first-frame/open/seek stall lasting 30 seconds reports a timeout and closes the decoder instead of showing indefinite buffering. Seeking, pause/resume, resync, and loop restart stop the old Minecraft sound channel and clear stale audio. FFmpeg closes at EOF, then reopens on a later seek using the same worker. Cancellation, errors, world changes, and screen eviction close decoders and audio queues; inactive textures are released.

`ClientPlayback` uploads due copied frames through `NativeImage`/`DynamicTexture` during extraction. `ScreenRenderer` submits a black panel and an aspect-fitted unlit video quad through the current block entity render-state API. Audio-only and loading states use Minecraft text and progress geometry.

`MediaSound` supplies custom PCM through Minecraft's streaming `AudioStream` interface in the RECORDS category. A positional mono channel is located at the screen center; per-tick gain applies linear distance fading and local preferences, while Minecraft supplies its category/master gain. `PersonalAudio` hashes server identity plus screen UUID into the local preferences file.

## Packaging

The build resolves JavaCV, JavaCPP, and FFmpeg without transitive platform aggregates. It merges each Java library with its own platform resources into three automatic-module JARs, then embeds those with NeoForge Jar-in-Jar. Module descriptors and invalidated signature metadata are removed; bytecode and native binaries are otherwise unchanged. This avoids classifier identity collisions and keeps class-relative native resource lookup intact. No OpenCV, CUDA, capture-device drivers, or system player dependency is requested.

This packaging strategy and the current 26.3 API integration still require a real Gradle build, native smoke tests, and dedicated/client startup. The source-level design is not evidence that FML loaded the packaged modules successfully.

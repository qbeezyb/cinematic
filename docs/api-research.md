# Cinematic API and dependency research

Checked 2026-10-22 against first-party release records, tagged dependency sources, NeoForge's `26.3.x` sources, the official MDK, and migration primers. API research is not a successful compilation or runtime validation. Shell DNS is unavailable in this workspace; sources below were read through the web tool.

## Versions

| Component | Confirmed value | Primary evidence |
| --- | --- | --- |
| Minecraft | `26.3` | [Official MDK properties](https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/main/gradle.properties) |
| NeoForge | `26.3.0.95` | [Official MDK properties](https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/main/gradle.properties) |
| Java target | `25` | [Official MDK build](https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/main/build.gradle) |
| ModDevGradle | `2.0.148` | [Official MDK build](https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/main/build.gradle) |
| Gradle wrapper distribution | `9.2.1-bin` | [Official wrapper properties](https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/main/gradle/wrapper/gradle-wrapper.properties) |
| JavaCV | `1.5.14` | [Tagged POM](https://raw.githubusercontent.com/bytedeco/javacv/1.5.14/pom.xml), [August 10, 2026 release](https://github.com/bytedeco/javacv/releases/tag/1.5.14) |
| JavaCPP | `1.5.14` | [JavaCV tagged POM](https://raw.githubusercontent.com/bytedeco/javacv/1.5.14/pom.xml) |
| FFmpeg bindings/natives | `8.1.2-1.5.14` | [Tagged FFmpeg POM](https://raw.githubusercontent.com/bytedeco/javacpp-presets/1.5.14/ffmpeg/pom.xml) |

None of the requested versions were found to be unavailable. Network dependency resolution and artifact downloads still require verification in a network-capable build environment. MDK `main` is mutable; preserve downloaded wrapper files and pin the values above. The official build defines client, dedicated server, and `gameTestServer` run types. [MDK build](https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/main/build.gradle)

## Rendering and UI

Minecraft 26.3 has significant changes from 1.21-era examples. GPU interfaces now live under `com.mojang.renderpearl.api`; `RenderSystem`, `NativeImage`, `PoseStack`, and `VertexConsumer` retain Blaze3d packages. `PoseStack` replaces `mulPose` with `rotate` and supplies `rotateDegrees` accepting an axis and angle. Input uses SDL scancodes, so use `InputConstants` instead of GLFW constants. [Official 26.3 migration primer](https://docs.neoforged.net/primer/docs/26.3/)

Block entity renderers use two type parameters: the block entity and an extracted render state extending `net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState`. Implement `createRenderState`, populate it in `extractRenderState`, and submit geometry in `submit`. Extraction receives the block entity, state, partial tick, camera position, and nullable crumbling overlay. Submission receives the render state, `PoseStack`, `SubmitNodeCollector`, and `CameraRenderState`. Invoke the interface default extraction with `BlockEntityRenderer.super.extractRenderState` or call the base-state extractor. [Official BER guide](https://docs.neoforged.net/docs/blockentities/ber/)

Register renderers with `EntityRenderersEvent.RegisterRenderers#registerBlockEntityRenderer`, on the mod event bus on the physical client. The 26.3 source confirms the renderer provider also has two generic parameters. [26.3 renderer event source](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/event/EntityRenderersEvent.java)

Override `getRenderBoundingBox` **on the renderer** to return the whole panel's world-space `AABB`. Its default covers only the controller. The render dispatcher explicitly performs frustum testing against this method, including the globally rendered block entity path. [26.3 renderer extension](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/extensions/IBlockEntityRendererExtension.java), [dispatcher patch](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/patches/net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher.java.patch), [level extractor patch](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/patches/net/minecraft/client/renderer/extract/LevelExtractor.java.patch)

`SubmitNodeCollector#submitCustomGeometry` is the supported arbitrary-vertex path. Treat submitted data as immutable, because submission and rendering happen separately. Use a full-bright textured quad render type for decoded frames and a black background quad for aspect-preserving bars. That last sentence is a Cinematic design recommendation, not a vanilla guarantee. [Official feature guide](https://docs.neoforged.net/docs/rendering/feature/)

Screens now override `extractRenderState` with `GuiGraphicsExtractor`, mouse X/Y, and frame delta. Background extraction is `extractBackground`. Open screens through `Minecraft#getInstance().gui.setScreen`; NeoForge closes them through `gui.popScreenLayer`. [Exact 26.3 Screen patch](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/patches/net/minecraft/client/gui/screens/Screen.java.patch)

`GuiGraphicsExtractor` supplies `fill`, `outline`, `text`, `centeredText`, tooltips, and blits. Its pose is `Matrix3x2fStack`, using `pushMatrix`/`popMatrix`. Use standard widgets, `addRenderableWidget`, and focus/narration behavior. Do not copy examples calling old `GuiGraphics#drawString` or `Screen#render`. [Official screen guide](https://docs.neoforged.net/docs/rendering/screens/)

The precise 26.3 `DynamicTexture` construction/upload and vanilla `AudioStream` format/read signatures were not established from a first-party declaration in this research pass. They require compilation against the downloaded patched Minecraft artifact; do not count texture/audio playback as verified from these notes.

## Sound engine integration

NeoForge directly adds `SoundInstance#getStream(SoundBufferLibrary, Sound, boolean)`, returning a `CompletableFuture<AudioStream>`. A custom sound instance can return a PCM-backed stream instead of opening a resource-pack Ogg file. [Exact 26.3 SoundInstance patch](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/patches/net/minecraft/client/resources/sounds/SoundInstance.java.patch)

The sound engine calls that method for streaming sounds, attaches the result through `Channel#attachBufferStream`, and starts playback on the channel executor. The same path applies the instance pitch, volume, attenuation, relative flag, and position. Use this path for positional media and the jukebox category; avoid a separate Java Sound output device, which would bypass Minecraft audio behavior. [Exact 26.3 SoundEngine patch](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/patches/net/minecraft/client/sounds/SoundEngine.java.patch)

## Networking and persistence

Register versioned payload types using `RegisterPayloadHandlersEvent#registrar`. Use `CustomPacketPayload` with `StreamCodec`, and identifiers from `Identifier.fromNamespaceAndPath`. Client handlers can be registered separately through `RegisterClientPayloadHandlersEvent`, preserving dedicated-server separation. Main-thread handling is the default. [Official payload guide](https://docs.neoforged.net/docs/networking/payload/)

The exact 26.3 registrar has `playToServer(type, codec, handler)` and `playToClient(type, codec)`, plus bidirectional overloads. Play codecs accept `RegistryFriendlyByteBuf` or a supertype; common/configuration codecs use `FriendlyByteBuf`. `executesOn` returns a configured copy. Match payload versions across both sides. [26.3 registrar source](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/main/java/net/neoforged/neoforge/network/registration/PayloadRegistrar.java)

Use `SavedDataType` with an identifier, a supplier, and a `Codec` for server-owned screen records. This three-argument constructor is present in 26.3; a level-sensitive factory overload also exists. [Exact 26.3 SavedDataType patch](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/patches/net/minecraft/world/level/saveddata/SavedDataType.java.patch)

Obtain saved data through `SavedDataStorage#computeIfAbsent(type)` and call `setDirty` after changes. `MinecraftServer#getDataStorage` is appropriate for records spanning dimensions; `ServerLevel#getDataStorage` is dimension-specific. Cinematic should keep its sessions here rather than exclusively in controller block entities, so chunk unloading does not own playback lifetime. [Official saved-data guide](https://docs.neoforged.net/docs/datastorage/saveddata/)

## Native packaging and media APIs

Use `org.bytedeco:javacv:1.5.14`, `org.bytedeco:javacpp:1.5.14`, and `org.bytedeco:ffmpeg:8.1.2-1.5.14`. Disable JavaCV transitive dependencies and explicitly supply only the required libraries: its POM also lists OpenCV, OpenBLAS, camera drivers, OCR libraries, and other unrelated components. [Tagged JavaCV POM](https://raw.githubusercontent.com/bytedeco/javacv/1.5.14/pom.xml)

The FFmpeg platform POM actively lists desktop classifiers `windows-x86_64`, `macosx-x86_64`, `macosx-arm64`, `linux-x86_64`, and `linux-arm64`. Windows ARM64 and 32-bit desktop classifiers are not active entries. Bundle the matching JavaCPP natives as required by its loader. Platform POMs also contain Android entries; avoid depending on the unrestricted `*-platform` artifacts. [Tagged FFmpeg platform POM](https://raw.githubusercontent.com/bytedeco/javacpp-presets/1.5.14/ffmpeg/platform/pom.xml)

ModDevGradle embeds libraries with `jarJar` and requires `additionalRuntimeClasspath` for ordinary Java libraries in development runs. It supports file inputs, identified by filename and content hash. [Official MDG guide](https://docs.neoforged.net/toolchain/docs/plugins/mdg/)

**Classifier collision:** ModDevGradle issue 306 documents Jar-in-Jar metadata using only group/artifact identity, causing base and native classifier artifacts to collide. Resolve native jars in a separate configuration and pass the resulting distinct files to Jar-in-Jar, then inspect generated metadata and actually load the packaged mod. This is a workaround derived from the documented file-input behavior; successful native loading still needs a smoke test. [Official issue 306](https://github.com/neoforged/ModDevGradle/issues/306)

`FFmpegFrameGrabber` accepts filenames/URLs, exposes `tryLoad`, and loads FFmpeg through JavaCPP. Prefer a URL string for HTTP byte-range seeking; its `InputStream` constructor can cache almost the entire stream for seekability. Use `grab` for decoded frames, `setTimestamp` for seeking, and release/close the grabber. Copy data retained beyond the next decode operation. [Tagged grabber source](https://raw.githubusercontent.com/bytedeco/javacv/1.5.14/src/main/java/org/bytedeco/javacv/FFmpegFrameGrabber.java)

The default FFmpeg build is distinct from `-gpl` classifiers: GPL codecs are conditionally enabled by the build script. The default also enables version-3 components and several third-party codec libraries. Preserve original notices and distribute the relevant license/source materials for the exact selected binaries; do not describe every FFmpeg artifact as LGPL 2.1. [Tagged FFmpeg native build](https://raw.githubusercontent.com/bytedeco/javacpp-presets/1.5.14/ffmpeg/cppbuild.sh)

## Remaining validation

Local binary verification after the first user build corrected several source-level assumptions: `BlockStateProperties.HORIZONTAL_FACING` is `EnumProperty<Direction>`, client payload events live in `net.neoforged.neoforge.client.network.event`, and serverbound client sends use `net.neoforged.neoforge.client.network.ClientPacketDistributor`. `EditBox` has no `setFilter` method in this runtime; the dimension widgets constrain `insertText` instead. Direct Java 25 compilation against the generated patched 26.3.0.95 JAR reproduced all eleven reported compiler errors before these corrections and passes afterward, including all JUnit sources. The GameTest `setBlock(BlockPos, BlockState)` overload exists; its reported error was a consequence of the invalid facing-property type.

The sources establish available versions and integration points. Headless native WAV/MP4 decoding/seeking now passes on macOS ARM64 using the three generated embedded library JARs on the classpath. Final packaged module loading, the 1080p/30 target, server startup, and in-game playback still require validation. Build and execute the packaged mod, test HTTP seeking with actual media, and run the platform and multiplayer matrix before marking those requirements complete.

## Confirmed implementation signatures

These short examples identify APIs confirmed in first-party sources; they are not a substitute for compiling against the pinned toolchain.

### Video quad submission

The 26.3 NeoForge helper provides an unlit entity texture render type with overlay/lightmap inputs and the current transparency pipeline:

```java
RenderType videoType = NeoForgeRenderTypes.getEntityUnlitTranslucent(textureId);
```

`RenderType` is in `net.minecraft.client.renderer.rendertype`. [Exact render helper source](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/NeoForgeRenderTypes.java)

The 26.3 vertex extension confirms the scalar, complete-vertex overload. Transform positions and normals through the submitted pose first:

```java
Vector3f position = pose.pose().transformPosition(x, y, z, new Vector3f());
Vector3f normal = pose.transformNormal(new Vector3f(0, 0, 1), new Vector3f());
vertices.addVertex(position.x(), position.y(), position.z(), argb,
        u, v, overlay, light, normal.x(), normal.y(), normal.z());
```

[Exact vertex extension source](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/extensions/IVertexConsumerExtension.java)

For global custom geometry, prefer `SubmitCustomGeometryEvent`; it exposes `getSubmitNodeCollector`, `getPoseStack`, and `getLevelRenderState`. `RenderLevelStageEvent` explicitly redirects custom geometry to that event and warns against uploads/custom passes during active render-pass stages. [Submission event](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/event/SubmitCustomGeometryEvent.java), [stage event](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java)

### Minecraft widgets

The 26.3 configuration screen uses these button/edit-box constructions:

```java
Button button = Button.builder(label, pressed -> onPress()).width(100)
        .tooltip(Tooltip.create(help)).build();
EditBox field = new EditBox(font, 100, 20, label);
```

Widgets can receive position through inherited setters. The same source uses `setEditable`, `setMaxLength`, `setValue`, and `setResponder` on edit boxes. [Exact configuration-screen source](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/gui/ConfigurationScreen.java)

NeoForge also exposes `ExtendedButton(int x, int y, int width, int height, Component label, Button.OnPress action)`. Its rendering override is `extractContents(GuiGraphicsExtractor, int, int, float)`. [Exact ExtendedButton source](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/gui/widget/ExtendedButton.java)

`ExtendedSlider` has a public constructor taking X, Y, width, height, prefix, suffix, minimum, maximum, current value, step, precision, and draw-string flag. Override protected `applyValue()` to send changes; `getValue`, `getValueInt`, `getValueLong`, and `setValue` are public. It already handles the modern `KeyEvent` and `MouseButtonEvent` inputs. [Exact ExtendedSlider source](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/client/java/net/neoforged/neoforge/client/gui/widget/ExtendedSlider.java)

### Registration and tests

The 26.3 block-entity type patch exposes a factory plus block-varargs constructor, avoiding old `BlockEntityType.Builder` examples:

```java
new BlockEntityType<>(ScreenBlockEntity::new, controllerBlock);
```

[Exact BlockEntityType patch](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/patches/net/minecraft/world/level/block/entity/BlockEntityType.java.patch)

Modern GameTests register `Consumer<GameTestHelper>` functions against `BuiltInRegistries.TEST_FUNCTION`, then reference them from `test_instance` JSON. They require actual structure NBT under `data/<namespace>/structure`. `minecraft:default` is the vanilla no-op environment. For example:

```json
{
  "type": "minecraft:function",
  "function": "cinematic:placement",
  "environment": "minecraft:default",
  "structure": "cinematic:empty",
  "max_ticks": 100
}
```

The guide is labeled 26.1, so confirm final linkage with the pinned 26.3 runtime. [Official GameTest guide](https://docs.neoforged.net/docs/misc/gametest/)

The exact 26.3 `RegisterGameTestsEvent` alternatively exposes `registerEnvironment(Identifier, TestEnvironmentDefinition<?>...)` and `registerTest(Identifier, GameTestInstance)`, plus a test-data factory overload. NeoForge enables test discovery in development, on the GameTest server, or with its explicit enable flag. [26.3 registration event](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/main/java/net/neoforged/neoforge/event/RegisterGameTestsEvent.java), [GameTest hooks](https://raw.githubusercontent.com/neoforged/NeoForge/26.3.x/src/main/java/net/neoforged/neoforge/gametest/GameTestHooks.java)

### Audio signature confidence

An older first-party Forge test implements `AudioStream` with `AudioFormat getFormat()`, `ByteBuffer read(int capacity)`, and `void close()`. It demonstrates custom PCM returned by `SoundInstance#getStream`. This is historical evidence from the 1.21.11 branch, not confirmation that every signature remains unchanged in 26.3. [Historical Forge audio-stream test](https://raw.githubusercontent.com/MinecraftForge/MinecraftForge/1.21.11/src/test_old/java/net/minecraftforge/debug/client/AudioStreamTest.java)

# Third-party notices and redistribution

Cinematic's own code and original textures/silent sound use the project [LICENSE](LICENSE). Third-party licenses apply to their respective components, including the permissions they grant for modification and replacement. Minecraft and NeoForge are not redistributed in the mod JAR.

## Media components

| Component | Pinned version | License and source |
| --- | --- | --- |
| JavaCV | 1.5.14 | Apache-2.0 selected from the upstream dual-license grant. [Source and license](https://github.com/bytedeco/javacv/tree/1.5.14). |
| JavaCPP | 1.5.14 | Apache-2.0 selected from the upstream dual-license grant. [Source and license](https://github.com/bytedeco/javacpp/tree/1.5.14). |
| JavaCPP FFmpeg preset/bindings | 8.1.2-1.5.14 | Apache-2.0 for the JavaCPP preset code; native components retain their licenses. [Preset source and build recipe](https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg). |
| FFmpeg | 8.1.2 | FFmpeg copyright its contributors. The requested default preset build enables version-3 components; review the actual binary configuration and retained notices for LGPL-3.0-or-later and dependent codec terms. [FFmpeg source](https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz), [legal information](https://ffmpeg.org/legal.html). |

JavaCV/JavaCPP are copyright Samuel Audet and their contributors; preserve all original headers and notices in the upstream archives and resolved JARs. The complete common upstream dual-license text, including Apache 2.0, GPL, and Classpath exception, is in `licenses/JavaCPP-Presets-LICENSE.txt`. Full FFmpeg LGPLv3 and GPLv3 texts are included under `licenses/` (LGPLv3 incorporates GPLv3 provisions). Including a license text does not imply that every alternative license or optional codec is selected.

The build requests only base Java libraries plus the `windows-x86_64`, `macosx-x86_64`, `macosx-arm64`, `linux-x86_64`, and `linux-arm64` native classifiers. It does not request the optional `-gpl` FFmpeg classifiers, platform aggregate dependencies, OpenCV, CUDA, or capture drivers. Native builds can contain additional codec libraries. Their exact inventory and licenses must be taken from the downloaded artifacts and matching `ffmpeg/cppbuild.sh`, not inferred from the container extensions accepted by Cinematic.

## Repackaging and replacement

`build.gradle` documents all changes to upstream artifacts: each Java library is merged with its matching native resources, module descriptors and signature/index metadata are removed, and an automatic module name plus FML library marker are added. Java classes and native library binaries are not edited. Upstream notices are retained in their existing locations and under `META-INF/cinematic/native-notices/`; project notice files are included in the outer mod JAR.

The three libraries remain separate embedded JARs under `META-INF/jarjar/`, rather than being relocated into Cinematic classes. To use a modified compatible library, rebuild the matching native classifier with the upstream toolchain, substitute the artifact used by the corresponding Gradle configuration, and rebuild Cinematic. The build recreates Jar-in-Jar metadata. Native resources must keep the JavaCPP platform paths. JavaCPP's cache is extracted runtime material, not the canonical replacement source. The project license expressly permits reverse engineering for debugging modifications to LGPL components to the extent required by the LGPL.

## Release materials

Run:

```sh
./gradlew exportMediaArtifacts
python3 scripts/collect-redistribution.py
```

This creates `build/redistribution/` with the original selected binary JARs, individual extracted notices for each binary/platform, pinned Java source JARs and POMs, JavaCV/JavaCPP/presets source archives, the FFmpeg 8.1.2 source archive, the repackaging recipe, and SHA-256 inventory. The collector downloads upstream sources; it has not run in the restricted implementation environment.

Before publishing a binary release, inspect the actual native build configuration on every target, retain all dependent-library notices, and supply complete corresponding source, patches, configuration, and build instructions for the enabled LGPL dependencies through an appropriate distribution mechanism. The collector is a starting set of upstream sources; it does **not** collect every codec dependency's corresponding source automatically and is not a written source offer. Distribute the completed materials alongside the exact tested mod JAR and record their hashes. No exact-binary redistribution inventory is claimed before dependencies have resolved and been inspected.

## Build-only components

Gradle's wrapper is Apache-2.0 and carries its own `META-INF/LICENSE`. The current launcher JAR came from the locally installed Gradle 9.4.1 and targets Gradle 9.2.1; `scripts/restore-wrapper.py` restores the official NeoForge MDK wrapper and records provenance. ModDevGradle, Checkstyle, Spotless, and JUnit are build/test tools, not embedded runtime dependencies. Consult their distributions for their respective license and attribution files.

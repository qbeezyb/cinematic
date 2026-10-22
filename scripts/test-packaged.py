#!/usr/bin/env python3
"""Verify the actual embedded jars and native resources from a built universal mod jar."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import wave
import zipfile

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("jar", type=Path)
args = parser.parse_args()
java = Path(os.environ["JAVA_HOME"]) / "bin"
with tempfile.TemporaryDirectory(prefix="cinematic-packaged-") as scratch:
    temporary = Path(scratch)
    with zipfile.ZipFile(args.jar) as mod:
        libraries = [name for name in mod.namelist() if name.startswith("META-INF/jarjar/") and name.endswith(".jar")]
        if len(libraries) != 3:
            raise SystemExit(f"Expected three embedded media jars, found {libraries}")
        metadata = json.loads(mod.read("META-INF/jarjar/metadata.json"))
        if {item["path"] for item in metadata["jars"]} != set(libraries):
            raise SystemExit("Jar-in-Jar metadata does not match embedded media libraries")
        for name in libraries:
            target = temporary / Path(name).name
            target.write_bytes(mod.read(name))
            with zipfile.ZipFile(target) as embedded:
                library = "ffmpeg" if "ffmpeg" in name else "javacpp" if "javacpp" in name else None
                if library is not None:
                    for platform in ["windows-x86_64", "macosx-x86_64", "macosx-arm64", "linux-x86_64", "linux-arm64"]:
                        if not any(p.startswith(f"org/bytedeco/{library}/{platform}/") for p in embedded.namelist()):
                            raise SystemExit(f"Missing {platform} {library} natives")
    sample = temporary / "sample.wav"
    with wave.open(str(sample), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(48000)
        wav.writeframes(bytes(96000))
    subprocess.run([str(java / "javac"), "-d", str(temporary), str(ROOT / "scripts/PackagedNativeSmoke.java")], check=True)
    subprocess.run([str(java / "java"), "-Djava.awt.headless=true", "--module-path", str(temporary),
                    "--add-modules", "org.bytedeco.javacv,org.bytedeco.javacpp,org.bytedeco.ffmpeg",
                    "--enable-native-access=org.bytedeco.javacpp,org.bytedeco.ffmpeg", "-cp", str(temporary),
                    "PackagedNativeSmoke", str(sample)], check=True)

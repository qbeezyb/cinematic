#!/usr/bin/env python3
"""Collect pinned upstream source archives, notices, and checksums for release review."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SOURCES = {
    "javacv-1.5.14-upstream.tar.gz": "https://github.com/bytedeco/javacv/archive/refs/tags/1.5.14.tar.gz",
    "javacpp-1.5.14-upstream.tar.gz": "https://github.com/bytedeco/javacpp/archive/refs/tags/1.5.14.tar.gz",
    "javacpp-presets-1.5.14-upstream.tar.gz": "https://github.com/bytedeco/javacpp-presets/archive/refs/tags/1.5.14.tar.gz",
    "ffmpeg-8.1.2.tar.xz": "https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz",
}
for artifact, version in [("javacv", "1.5.14"), ("javacpp", "1.5.14"), ("ffmpeg", "8.1.2-1.5.14")]:
    for suffix in ["-sources.jar", ".pom"]:
        filename = f"{artifact}-{version}{suffix}"
        SOURCES[filename] = f"https://repo.maven.apache.org/maven2/org/bytedeco/{artifact}/{version}/{filename}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/redistribution")
    args = parser.parse_args()
    destination = args.output.resolve()
    archives = destination / "sources"
    archives.mkdir(parents=True, exist_ok=True)
    records = []
    for name, url in SOURCES.items():
        target = archives / name
        partial = target.with_suffix(target.suffix + ".part")
        digest = hashlib.sha256()
        try:
            with urlopen(Request(url, headers={"User-Agent": "Cinematic-source-collector"}), timeout=45) as response, partial.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
                    digest.update(chunk)
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
        records.append({"path": str(target.relative_to(destination)), "url": url, "sha256": digest.hexdigest()})
        print(f"Collected {name}")
    shutil.copytree(ROOT / "licenses", destination / "licenses", dirs_exist_ok=True)
    for name in ["THIRD_PARTY_NOTICES.md", "build.gradle", "gradle.lockfile"]:
        shutil.copy2(ROOT / name, destination / name)
    # Preserve every notice separately rather than losing same-named platform files.
    for jar in sorted((destination / "binaries").glob("*.jar")):
        with zipfile.ZipFile(jar) as binary:
            for name in binary.namelist():
                basename = Path(name).name.upper()
                if not name.endswith("/") and any(word in basename for word in ["LICENSE", "COPYING", "NOTICE"]):
                    target = destination / "binary-notices" / jar.name / name
                    if ".." in Path(name).parts or Path(name).is_absolute():
                        raise SystemExit(f"Unsafe archive entry: {jar.name}: {name}")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(binary.read(name))
        with jar.open("rb") as binary_file:
            records.append({"path": str(jar.relative_to(destination)), "sha256": hashlib.file_digest(binary_file, "sha256").hexdigest()})
    (destination / "source-manifest.json").write_text(json.dumps(records, indent=2) + "\n")
    print("Collected upstream source and available original binary notices. This is not a complete corresponding-source bundle:")
    print("inspect ffmpeg/cppbuild.sh and collect source/patches for every dependency enabled in the exact native binaries.")


if __name__ == "__main__":
    main()

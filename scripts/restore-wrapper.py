#!/usr/bin/env python3
"""Restore the four wrapper files from an explicit official MDK Git ref."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parent.parent
FILES = ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
         "gradle/wrapper/gradle-wrapper.properties"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True, help="Official MDK commit SHA, tag, or branch; prefer an immutable SHA")
    args = parser.parse_args()
    if not all(c.isalnum() or c in "-_." for c in args.ref):
        parser.error("Use a commit, tag, or simple branch name")
    base = f"https://raw.githubusercontent.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/{args.ref}/"
    downloaded = {}
    for name in FILES:
        with urlopen(Request(base + name, headers={"User-Agent": "Cinematic-wrapper-restore"}), timeout=45) as response:
            downloaded[name] = response.read()
    properties = downloaded[FILES[-1]].decode("utf-8")
    if "gradle-9.2.1-bin.zip" not in properties:
        raise SystemExit("MDK ref does not target Gradle 9.2.1; choose the matching historical commit")
    with zipfile.ZipFile(io.BytesIO(downloaded[FILES[-2]])) as wrapper:
        if "org/gradle/wrapper/GradleWrapperMain.class" not in wrapper.namelist() or wrapper.testzip() is not None:
            raise SystemExit("Downloaded wrapper JAR failed validation")
    # Fetch and validate everything before replacing any working files.
    records = []
    for name, data in downloaded.items():
        target = ROOT / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        records.append({"path": name, "url": base + name, "sha256": hashlib.sha256(data).hexdigest()})
    (ROOT / "gradlew").chmod(0o755)
    provenance = {"mdk_ref": args.ref, "gradle_distribution": "9.2.1-bin", "files": records}
    (ROOT / "docs/wrapper-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print("Restored official MDK wrapper; hashes recorded in docs/wrapper-provenance.json")


if __name__ == "__main__":
    main()

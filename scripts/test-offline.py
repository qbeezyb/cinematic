#!/usr/bin/env python3
"""Compile and run pure-Java JUnit tests with cached dependencies; never downloads anything."""
import os
from pathlib import Path
import subprocess
import shutil

ROOT = Path(__file__).resolve().parent.parent
JAVA = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@25")) / "bin"
CACHE = Path(os.environ.get("GRADLE_DEPENDENCY_CACHE", str(Path.home() / ".gradle/caches/modules-2/files-2.1")))
ARTIFACTS = {
    "org.junit.jupiter": ["junit-jupiter-api", "junit-jupiter-engine"],
    "org.junit.platform": ["junit-platform-commons", "junit-platform-engine", "junit-platform-launcher"],
    "org.opentest4j": ["opentest4j"],
    "org.apiguardian": ["apiguardian-api"],
}
jars = []
for group, artifacts in ARTIFACTS.items():
    for artifact in artifacts:
        directory = CACHE / group / artifact
        version = "6.0.3" if group.startswith("org.junit") else ("1.3.0" if group == "org.opentest4j" else "1.1.2")
        matches = list((directory / version).glob(f"*/{artifact}-{version}.jar"))
        maven = Path.home() / '.m2/repository' / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.jar'
        if not matches and maven.exists():
            matches = [maven]
        if not matches:
            raise SystemExit(f"Missing cached {group}:{artifact}:{version}; run ./gradlew test with network access first")
        jars.append(str(matches[0]))
classes = ROOT / "build/offline-tests"
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir(parents=True, exist_ok=True)
sources = list((ROOT / "src/main/java/me/cinematic/core").glob("*.java"))
sources += [p for p in (ROOT / "src/main/java/me/cinematic/media").glob("*.java") if p.name != "NativeMediaProbe.java"]
sources += [p for p in (ROOT / "tests/java").rglob("*.java") if p.name != "NativeMediaTest.java"]
sources += [ROOT / "scripts/OfflineTestRunner.java"]
classpath = os.pathsep.join(jars)
subprocess.run([str(JAVA / "javac"), "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), *map(str, sources)], check=True)
subprocess.run([str(JAVA / "java"), "-cp", str(classes) + os.pathsep + classpath, "OfflineTestRunner"], check=True)

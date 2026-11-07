#!/usr/bin/env python3
"""Package source with safe, reproducible ZIP timestamps (Python 3.10+).

ZIP DOS timestamps have no timezone. A wall-clock timestamp written on a machine
ahead of the recipient can leave CMakeLists.txt newer than every build.ninja that
CMake generates. Use an old fixed timestamp, not the packager's local clock.
"""
from pathlib import Path
import os
import re
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE_TIME = (2000, 1, 1, 0, 0, 0)
EXCLUDED_DIRS = {
    ".git", ".gradle", ".kotlin", ".idea", ".cxx", ".externalNativeBuild",
    "build", "output", "__pycache__",
}
EXCLUDED_FILES = {"local.properties", "keystore.properties", ".DS_Store"}
EXCLUDED_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pyc"}


def package_source():
    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    match = re.search(r'versionName\s+[\'"]([0-9]+\.[0-9]+)[\'"]', gradle)
    if not match:
        raise ValueError("Cannot find the release versionName in app/build.gradle")
    name = f"LiquidGlass_{match.group(1)}_source"
    output = ROOT.parent / "output"
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"{name}.zip"
    fd, temporary = tempfile.mkstemp(prefix=f"{name}-", suffix=".zip", dir=output)
    os.close(fd)
    try:
        count = 0
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED,
                             compresslevel=9) as archive:
            for folder, directories, filenames in os.walk(ROOT, followlinks=False):
                directories[:] = sorted(
                    d for d in directories
                    if d not in EXCLUDED_DIRS and not (Path(folder) / d).is_symlink()
                )
                for filename in sorted(filenames):
                    path = Path(folder) / filename
                    relative = path.relative_to(ROOT).as_posix()
                    if (filename in EXCLUDED_FILES
                            or path.suffix.lower() in EXCLUDED_SUFFIXES
                            or path.is_symlink()
                            or (relative.startswith("gradle/wrapper/")
                                and re.fullmatch(r"gradle-.+-(?:bin|all)\.zip", filename))):
                        continue
                    info = zipfile.ZipInfo(f"{name}/{relative}", date_time=ARCHIVE_TIME)
                    info.create_system = 3
                    info.compress_type = zipfile.ZIP_DEFLATED
                    mode = 0o100755 if relative == "gradlew" else 0o100644
                    info.external_attr = mode << 16
                    archive.writestr(info, path.read_bytes(), compresslevel=9)
                    count += 1
        os.replace(temporary, target)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print(f"Packaged {count} source files: {target}")
    return target


if __name__ == "__main__":
    package_source()

from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
destination = Path.home() / "Downloads" / "VideoFlow-v0.1.0-source.zip"
ignored_dirs = {".gradle", ".kotlin", ".tools", ".git", "build", "smoke", "__pycache__"}
ignored_files = {".DS_Store", "local.properties"}
count = 0
with ZipFile(destination, "w", ZIP_DEFLATED, compresslevel=8) as zip_out:
    for file in sorted(root.rglob("*")):
        if not file.is_file():
            continue
        parts = file.relative_to(root).parts
        if any(part in ignored_dirs for part in parts) or file.name in ignored_files:
            continue
        zip_out.write(file, file.relative_to(root).as_posix())
        count += 1
with ZipFile(destination, "r") as archive:
    assert archive.testzip() is None
    required = [
        "app/src/main/AndroidManifest.xml",
        "app/build.gradle.kts",
        "build.gradle.kts",
        "app/src/main/java/com/asterion/videoflow/MainActivity.kt",
        "gradle/wrapper/gradle-wrapper.jar",
        "README.md",
    ]
    assert all(name in archive.namelist() for name in required), "Source archive is incomplete"
print(f"Archive verified: {count} files, {destination.stat().st_size} bytes at {destination}")

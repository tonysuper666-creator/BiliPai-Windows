#!/usr/bin/env python3
"""Prepare fixed Graal worker resources without modifying the application's Gradle runtime."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

VERSION = "24.2.2"
JDK_VERSION = "21.0.12.1"
JDK_SHA256 = "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e"
JDK_URL = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
MODULES = ("java.base", "java.logging", "java.management", "java.transaction.xa", "java.xml", "java.sql", "jdk.management", "jdk.unsupported")
MAIN = "com.bilipai.desktop.plugins.js.DesktopJsPluginWorker"
OWNER = "bilipai-js-worker-v1"
JDK_NOTICE_FILE = "temurin-21.0.12.1-NOTICE"
JDK_NOTICE_ENTRY = "jdk-21.0.12.1+1/NOTICE"

def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()

def safe_relative(root: Path, name: str) -> Path:
    relative = PurePosixPath(name)
    if not name or relative.is_absolute() or ".." in relative.parts or "\\" in name or ":" in name:
        raise ValueError("Unsafe worker resource path")
    result = root.joinpath(*relative.parts).absolute()
    if not result.resolve().is_relative_to(root.resolve()): raise ValueError("Worker resource escaped its root")
    for parent in [result, *result.parents]:
        if parent == root.parent: break
        if parent.is_symlink(): raise ValueError("Worker resource contains a symlink")
    return result

def verify_file(path: Path, record: dict) -> None:
    if not path.is_file() or path.stat().st_size != record["bytes"] or digest(path) != record["sha256"]:
        raise ValueError("Worker resource checksum mismatch: " + path.name)

def record(root: Path, path: Path, role: str) -> dict:
    return {"file": path.relative_to(root).as_posix(), "sha256": digest(path), "bytes": path.stat().st_size, "role": role}

def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

def verify_jdk(home: Path, archive: Path) -> None:
    if digest(archive) != JDK_SHA256: raise ValueError("The selected JDK archive is not the fixed project JDK")
    # Validate every extracted archive member, including tools, jmods, DLLs and licenses.
    with zipfile.ZipFile(archive) as source:
        roots = {PurePosixPath(member.filename).parts[0] for member in source.infolist()}
        if len(roots) != 1: raise ValueError("Unexpected fixed JDK archive root")
        for member in source.infolist():
            if member.is_dir(): continue
            relative = "/".join(PurePosixPath(member.filename).parts[1:])
            path = safe_relative(home, relative)
            if not path.is_file() or path.stat().st_size != member.file_size: raise ValueError("Fixed JDK extraction differs")
            with source.open(member) as original:
                expected = hashlib.file_digest(original, "sha256").hexdigest()
            if digest(path) != expected: raise ValueError("Fixed JDK extraction checksum mismatch")

def verify_jdk_notice(archive: Path, notices: dict, notice_root: Path) -> dict:
    matches = [row for row in notices["sources"] if row.get("file") == "licenses/" + JDK_NOTICE_FILE]
    if len(matches) != 1: raise ValueError("Fixed worker JDK root NOTICE is missing or repeated")
    row = matches[0]
    if row.get("sourceType") != "fixed-jdk-archive-entry" or row.get("url") != JDK_URL or row.get("archiveSha256") != JDK_SHA256 or row.get("archiveEntry") != JDK_NOTICE_ENTRY:
        raise ValueError("Worker JDK NOTICE origin differs from the fixed archive")
    if digest(archive) != JDK_SHA256: raise ValueError("Worker JDK NOTICE archive checksum differs")
    with zipfile.ZipFile(archive) as source:
        member = source.getinfo(JDK_NOTICE_ENTRY)
        if member.is_dir() or not 0 < member.file_size <= 65536: raise ValueError("Worker JDK NOTICE archive entry is invalid")
        payload = source.read(member)
    actual = {"file": "notices/" + JDK_NOTICE_FILE, "archiveEntry": JDK_NOTICE_ENTRY,
        "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}
    if (row.get("bytes"), row.get("sha256")) != (actual["bytes"], actual["sha256"]):
        raise ValueError("Worker JDK NOTICE catalog differs from the fixed archive bytes")
    verify_file(safe_relative(notice_root, JDK_NOTICE_FILE), row)
    return actual

def copy_notices(notice_root: Path, notice_catalog: Path, output: Path) -> None:
    notices = json.loads(notice_catalog.read_text(encoding="utf-8"))
    target_notices = output / "notices"; target_notices.mkdir()
    for row in notices["sources"]:
        name = PurePosixPath(row["file"]).name
        source = safe_relative(notice_root, name)
        verify_file(source, row)
        shutil.copyfile(source, safe_relative(target_notices, name))
    shutil.copyfile(notice_catalog, target_notices / "catalog.json")

def clean_env() -> dict:
    # Java options, PATH classpath and preload variables never enter build tools or guest processes.
    return {key: value for key, value in os.environ.items() if key.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP"}}

def run(command: list[str]) -> None:
    subprocess.run(command, check=True, env=clean_env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)

def worker_bytes(jdk: Path, source: Path, engines: list[Path]) -> bytes:
    with tempfile.TemporaryDirectory(prefix="bilipai-js-javac-") as temp:
        classes = Path(temp)
        run([str(jdk / "bin/javac.exe"), "--release", "21", "-proc:none", "-classpath",
            os.pathsep.join(map(str, engines)), "-d", str(classes), str(source)])
        compiled = sorted(classes.rglob("*.class"))
        expected = {"com/bilipai/desktop/plugins/js/DesktopJsPluginWorker.class", "com/bilipai/desktop/plugins/js/DesktopJsPluginWorker$1.class"}
        if {path.relative_to(classes).as_posix() for path in compiled} != expected: raise ValueError("Worker JAR includes unreviewed classes")
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as packed:
            for path in compiled:
                member = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (2026, 1, 1, 0, 0, 0))
                packed.writestr(member, path.read_bytes())
        return buffer.getvalue()

def generated_hash(generated: Path, output: Path) -> None:
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_text("// GENERATED by prepare-js-worker.py; do not edit.\npackage com.bilipai.desktop.plugins.js\n"
        + "internal const val DESKTOP_JS_WORKER_CATALOG_SHA256 = \"" + digest(output / "classpath.json") + "\"\n", encoding="utf-8")

def verify_existing(output: Path, inputs: dict, lock: list[dict], runtime_lock: dict) -> dict:
    if not output.is_dir() or output.is_symlink(): raise ValueError("Existing worker output is not a managed directory")
    provenance = json.loads((output / "provenance.json").read_text(encoding="utf-8"))
    if provenance.get("owner") != OWNER or provenance.get("inputs") != inputs:
        raise ValueError("Existing worker provenance differs from current source/lock/notices/JDK inputs")
    if provenance.get("workerJdkNotice") != inputs.get("workerJdkNotice"):
        raise ValueError("Existing worker JDK NOTICE provenance differs")
    catalog_path = output / "classpath.json"
    if catalog_path.stat().st_size > 262_144: raise ValueError("Existing worker catalog is oversized")
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    if catalog.get("owner") != OWNER or catalog.get("schemaVersion") != 1 or catalog.get("engineVersion") != VERSION or catalog.get("jdkVersion") != JDK_VERSION or catalog.get("mainClass") != MAIN or catalog.get("runtimeModules") != list(MODULES):
        raise ValueError("Existing worker catalog/version differs")
    classpath, resources = catalog["classpath"], catalog["resources"]
    if len(classpath) != 13 or len(resources) > 512 or classpath[0].get("file") != "desktop-js-worker.jar" or classpath[0].get("role") != "worker":
        raise ValueError("Existing worker classpath is incomplete")
    expected_engines = {row["file"]: (row["sha256"], row["bytes"]) for row in lock if row["file"].endswith(".jar")}
    actual_engines = {row["file"]: (row["sha256"], row["bytes"]) for row in classpath[1:] if row.get("role") == "engine"}
    if actual_engines != expected_engines: raise ValueError("Existing engine artifacts differ from the fixed Maven pins")
    runtime_records = {"runtime/" + row["file"]: (row["sha256"], row["bytes"]) for row in runtime_lock["files"]}
    actual_runtime = {row["file"]: (row["sha256"], row["bytes"]) for row in resources if row.get("role") == "runtime"}
    if actual_runtime != runtime_records: raise ValueError("Existing runtime differs from the fixed linked-image pins")
    rows = classpath + resources
    names = [row["file"] for row in rows]
    if len(set(names)) != len(names): raise ValueError("Existing catalog repeats a resource")
    for row in rows:
        if set(row) != {"file", "bytes", "sha256", "role"}: raise ValueError("Existing resource descriptor is invalid")
        verify_file(safe_relative(output, row["file"]), row)
    actual_names = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
    if actual_names != set(names) | {"classpath.json"}: raise ValueError("Existing resources contain extra or missing files")
    if json.loads((output / "engine-artifacts.json").read_text(encoding="utf-8")) != lock:
        raise ValueError("Existing dependency evidence differs")
    if digest(output / "notices/catalog.json") != inputs["noticeCatalogSha256"]:
        raise ValueError("Existing notice catalog differs")
    for name, expected in inputs["noticeFiles"].items():
        if digest(safe_relative(output / "notices", name)) != expected: raise ValueError("Existing notice text differs")
    return catalog

def prepare(repo: Path, output: Path, jdk: Path, archive: Path, cache: Path, lock_path: Path,
    notice_root: Path, notice_catalog: Path, generated: Path, offline: bool, reuse_verified: bool = False,
    runtime_lock_path: Path | None = None) -> dict:
    repo, output, jdk, cache = [path.resolve() for path in (repo, output, jdk, cache)]
    existing = output.exists()
    if existing and not reuse_verified: raise ValueError("Choose a new output directory or request verified reuse; resources are never recursively replaced")
    if output == repo or repo.is_relative_to(output) or output == jdk or jdk.is_relative_to(output):
        raise ValueError("Worker output overlaps source/toolchain")
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    jars = [row for row in lock if row["file"].endswith(".jar")]
    if len(lock) != 26 or len(jars) != 12 or len({row["file"] for row in lock}) != 26:
        raise ValueError("The fixed Graal graph is incomplete")
    for row in lock:
        if not row["url"].startswith("https://repo.maven.apache.org/maven2/org/graalvm/") or "/24.2.2/" not in row["url"]:
            raise ValueError("Unreviewed engine coordinate")
    notices = json.loads(notice_catalog.read_text(encoding="utf-8"))
    for row in notices["sources"]:
        verify_file(safe_relative(notice_root, PurePosixPath(row["file"]).name), row)
    verify_jdk(jdk, archive)
    worker_jdk_notice = verify_jdk_notice(archive, notices, notice_root)
    worker_source = repo / "desktop/js-worker/src/main/java/com/bilipai/desktop/plugins/js/DesktopJsPluginWorker.java"
    if not worker_source.is_file():
        worker_source = Path(__file__).resolve().parent / "js-worker/src/main/java/com/bilipai/desktop/plugins/js/DesktopJsPluginWorker.java"
    runtime_lock_path = runtime_lock_path or repo / "desktop/third-party/graaljs/runtime.lock.json"
    runtime_lock = json.loads(runtime_lock_path.read_text(encoding="utf-8"))
    if runtime_lock.get("jdkArchiveSha256") != JDK_SHA256 or runtime_lock.get("modules") != list(MODULES) or len(runtime_lock.get("files", [])) != 125:
        raise ValueError("Fixed worker linked-image pins differ")
    inputs = {"version": 2, "workerSourceSha256": digest(worker_source), "mavenLockSha256": digest(lock_path),
        "noticeCatalogSha256": digest(notice_catalog), "runtimeLockSha256": digest(runtime_lock_path),
        "noticeFiles": {PurePosixPath(row["file"]).name: row["sha256"] for row in notices["sources"]},
        "jdkArchiveSha256": JDK_SHA256, "prepareToolSha256": digest(Path(__file__)),
        "workerJdkNotice": worker_jdk_notice}
    if existing:
        catalog = verify_existing(output, inputs, lock, runtime_lock)
        # The worker has no publisher binary: recompile its two deterministic classes to bind a cache to the current source.
        expected_worker = worker_bytes(jdk, worker_source, [safe_relative(output, row["file"]) for row in jars])
        if hashlib.sha256(expected_worker).hexdigest() != catalog["classpath"][0]["sha256"]:
            raise ValueError("Existing worker JAR does not match the current fixed-JDK compilation")
        generated_hash(generated, output)
        return {"reused": True, "classpathCount": 13, "resourceCount": len(catalog["resources"]),
            "catalogSha256": digest(output / "classpath.json")}
    for row in lock:
        if not row["url"].startswith("https://repo.maven.apache.org/maven2/org/graalvm/") or "/24.2.2/" not in row["url"]:
            raise ValueError("Unreviewed engine coordinate")
        path = safe_relative(cache, row["file"])
        if not path.exists():
            if offline: raise ValueError("Fixed engine cache missing in offline mode")
            path.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(row["url"], timeout=30) as response:
                payload = response.read(row["bytes"] + 1)
            if len(payload) != row["bytes"] or hashlib.sha256(payload).hexdigest() != row["sha256"]:
                raise ValueError("Publisher artifact differs from fixed lock")
            path.write_bytes(payload)
        verify_file(path, row)
    output.mkdir(parents=True)
    engine_files = []
    for row in jars:
        target = safe_relative(output, row["file"]); target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(safe_relative(cache, row["file"]), target); engine_files.append(target)
    worker = output / "desktop-js-worker.jar"
    worker.write_bytes(worker_bytes(jdk, worker_source, engine_files))
    runtime = output / "runtime"
    run([str(jdk / "bin/jlink.exe"), "--module-path", str(jdk / "jmods"), "--add-modules",
        "java.base,java.sql,jdk.management,jdk.unsupported", "--strip-debug", "--no-header-files",
        "--no-man-pages", "--compress=zip-6", "--output", str(runtime)])
    release = (runtime / "release").read_text(encoding="utf-8")
    if f'JAVA_VERSION="{JDK_VERSION}"' not in release or f'MODULES="{" ".join(MODULES)}"' not in release:
        raise ValueError("Linked worker runtime modules/version differ")
    if not (runtime / "bin/javaw.exe").is_file(): raise ValueError("Linked image stripped native commands")
    for row in runtime_lock["files"]: verify_file(safe_relative(runtime, row["file"]), row)
    if {path.relative_to(runtime).as_posix() for path in runtime.rglob("*") if path.is_file()} != {row["file"] for row in runtime_lock["files"]}:
        raise ValueError("Fresh linked runtime has extra or missing files")
    copy_notices(notice_root, notice_catalog, output)
    write_json(output / "engine-artifacts.json", lock)
    provenance = {"owner": OWNER, "jdkVersion": JDK_VERSION, "jdkArchiveUrl": JDK_URL,
        "jdkArchiveSha256": JDK_SHA256, "workerSourceSha256": digest(worker_source),
        "engineVersion": VERSION, "runtimeModules": MODULES,
        "command": "jlink --add-modules java.base,java.sql,jdk.management,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress=zip-6",
        "licenseCatalogComplete": bool(notices.get("complete")), "workerJdkNotice": worker_jdk_notice, "inputs": inputs}
    write_json(output / "provenance.json", provenance)
    catalog = {"owner": OWNER, "schemaVersion": 1, "engineVersion": VERSION, "jdkVersion": JDK_VERSION,
        "mainClass": MAIN, "classpath": [record(output, worker, "worker"), *[record(output, path, "engine") for path in engine_files]],
        "runtimeModules": MODULES,
        "resources": [record(output, path, "runtime" if path.is_relative_to(runtime) else "notice")
            for path in sorted(output.rglob("*")) if path.is_file() and path not in [worker, *engine_files]]}
    write_json(output / "classpath.json", catalog)
    generated_hash(generated, output)
    return {"classpathCount": 13, "resourceCount": len(catalog["resources"]),
        "runtimeBytes": sum(path.stat().st_size for path in runtime.rglob("*") if path.is_file()),
        "catalogSha256": digest(output / "classpath.json"), "provenance": provenance}

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "output", "java-home", "jdk-archive", "cache", "lock", "notice-root", "notice-catalog", "generated-kotlin"):
        cli.add_argument("--" + name, required=True, type=Path)
    cli.add_argument("--offline", action="store_true")
    cli.add_argument("--reuse-verified", action="store_true")
    cli.add_argument("--runtime-lock", type=Path)
    args = cli.parse_args()
    print(json.dumps(prepare(args.repo, args.output, args.java_home, args.jdk_archive, args.cache, args.lock,
        args.notice_root, args.notice_catalog, args.generated_kotlin, args.offline, args.reuse_verified, args.runtime_lock), indent=2))

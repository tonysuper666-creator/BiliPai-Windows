#!/usr/bin/env python3
"""Plan/apply a reviewed video migration without copying upstream Kotlin files.

Default: dry-run. Recipes pin upstream Git provenance and LF source bytes,
describe counted edits, and retire exact producer identities. All conflicts are
checked before any writes. Only migration registrations and new patches change.
"""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import tempfile


class Conflict(ValueError):
    pass


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    if result.returncode:
        raise Conflict("Git provenance check failed: " + " ".join(args))
    return result.stdout


def checked_path(repo, relative):
    path = PurePosixPath(relative)
    if path.is_absolute() or ".." in path.parts or "\\" in relative or ":" in relative:
        raise Conflict("Unsafe path: " + relative)
    target = repo.joinpath(*path.parts)
    if not path.parts or any(p.is_symlink() for p in [target, *target.parents] if p != repo.parent):
        raise Conflict("Symbolic link in path: " + relative)
    if not target.resolve().is_relative_to(repo):
        raise Conflict("Path escapes repository: " + relative)
    return target


def register(text, name, entries):
    marker = "val " + name + " = setOf(\n"
    if text.count(marker) != 1:
        raise Conflict("Missing/ambiguous registration block: " + name)
    start = text.index(marker) + len(marker)
    end = text.find("\n)", start)
    if end == -1:
        raise Conflict("Unterminated registration block: " + name)
    block = text[start:end]
    existing = re.findall(r'^\s*"([^"\n]+)",\s*$', block, re.MULTILINE)
    additions = []
    for entry in entries:
        if existing.count(entry) > 1:
            raise Conflict("Duplicate registration: " + entry)
        if entry not in existing:
            additions.append('    "' + entry + '",\n')
    return text[:start] + "".join(additions) + text[start:]


def edits(body, operations, label):
    for edit in operations:
        before, after = edit["before"], edit["after"]
        if not before or body.count(before) != edit.get("count", 1):
            raise Conflict("Edit anchor changed: " + label + ": " + before[:80])
        body = body.replace(before, after)
    return body


def unified(relative, before, after):
    return "".join(difflib.unified_diff(
        before.decode().splitlines(keepends=True), after.decode().splitlines(keepends=True),
        fromfile="a/" + relative, tofile="b/" + relative)).encode()


def transition_changes(repo, recipe):
    # Render the existing production producers in scratch space; never trust stale
    # build outputs. These are offline source generators, not a new build path.
    producers = {
        "original-video-detail-holder-full": "extract-upstream-video-detail-holder.py",
        "original-video-player-section-full": "extract-upstream-video-player-section-full.py",
    }
    result = {}
    items = recipe.get("transitions", [])
    if not items:
        return result
    base = recipe["desktopBaseCommit"]
    if not re.fullmatch(r"[a-f0-9]{40}", base):
        raise Conflict("Desktop base must be a full SHA")
    git(repo, "merge-base", "--is-ancestor", base, "HEAD")
    with tempfile.TemporaryDirectory(prefix="video-migration-render-") as directory:
        root = Path(directory)
        # Match settings.gradle.kts without mutating its shared legacy read point.
        legacy_commit = checked_path(repo, "desktop/upstream-core/legacy-root.commit").read_text().splitlines()
        legacy_commit = [line.strip() for line in legacy_commit if line.strip() and not line.startswith("#")]
        if len(legacy_commit) != 1 or not re.fullmatch(r"[a-f0-9]{40}", legacy_commit[0]):
            raise Conflict("Invalid legacy read-point commit")
        legacy = root / ".legacy-root"
        legacy.mkdir()
        archive = root / "legacy.tar"
        git(repo, "archive", "--format=tar", "-o", str(archive), legacy_commit[0],
            "app/src/main", "desktop/tools", "desktop/upstream-slices", "desktop/upstream-sources.json")
        with tarfile.open(archive) as snapshot:
            for member in snapshot.getmembers():
                if not (member.isfile() or member.isdir()) or ".." in PurePosixPath(member.name).parts or member.name.startswith("/"):
                    raise Conflict("Unsafe legacy archive member: " + member.name)
            snapshot.extractall(legacy, filter="data")
        rendered = set()
        for item in items:
            producer, identity = item["producer"], item["identity"]
            if producer not in producers or not re.fullmatch(
                    r"com/android/purebilibili/feature/video/(screen|ui/section)/[A-Za-z0-9]+\.kt", identity):
                raise Conflict("Transition outside reviewed video UI producers: " + identity)
            relative = "desktop/upstream-core/transition/patches/" + producer + "/" + identity + ".patch"
            if relative in result:
                raise Conflict("Duplicate transition: " + relative)
            if producer not in rendered:
                command = [sys.executable, str(repo / "desktop/tools" / producers[producer]),
                           str(legacy), str(root / producer)]
                completed = subprocess.run(command, capture_output=True)
                if completed.returncode:
                    raise Conflict("Production source renderer failed: " + producer + ": " +
                                   completed.stderr.decode(errors="replace")[-2000:])
                rendered.add(producer)
            source = root / producer / identity
            raw = source.read_bytes()
            if sha(raw) != item["generatedSha256LF"]:
                raise Conflict("Generated source changed: " + identity)
            # The old patch is pinned in Git, including all earlier desktop behavior.
            previous = item["previousPatchSha256"]
            old_patch = git(repo, "show", base + ":" + relative) if previous is not None else None
            if old_patch is not None and sha(old_patch) != previous:
                raise Conflict("Pinned transition patch changed: " + relative)
            if old_patch:
                consumed = subprocess.run(["git", "apply", "-"],
                                          input=old_patch, capture_output=True, cwd=root / producer)
                if consumed.returncode:
                    raise Conflict("Pinned transition patch does not apply: " + relative + ": " +
                                   consumed.stderr.decode(errors="replace")[-1000:])
            adapted = edits(source.read_text(encoding="utf-8"), item["edits"], identity).encode()
            content = unified(identity, raw, adapted)
            target = checked_path(repo, relative)
            current = target.read_bytes() if target.exists() else None
            if current not in (old_patch, content):
                raise Conflict("Existing transition differs; review/compose it manually: " + relative)
            result[relative] = (current, content)
    return result


def export_patch(changes):
    return b"".join("".join(difflib.unified_diff(
        (old or b"").decode().splitlines(keepends=True), new.decode().splitlines(keepends=True),
        fromfile="a/" + path if old is not None else "/dev/null", tofile="b/" + path)).encode()
        for path, (old, new) in changes.items() if old != new)


def plan(repo, recipe):
    if recipe.get("schema") != "bilipai-video-migration-v1":
        raise Conflict("Unknown recipe schema")
    upstream = recipe["upstream"]
    commit, tag = upstream["commit"], upstream["tag"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise Conflict("Upstream commit must be a full SHA")
    git(repo, "merge-base", "--is-ancestor", "refs/tags/" + tag, commit)
    git(repo, "merge-base", "--is-ancestor", commit, "HEAD")
    build_path = checked_path(repo, "desktop/build.gradle.kts")
    original_build = build_path.read_bytes()
    build = original_build.decode("utf-8")
    if "\r" in build:
        raise Conflict("Registration file must use LF line endings")
    changes = {}
    sources = []
    retired = []
    seen = set()
    for item in recipe["files"]:
        source = item["source"]
        if source in seen:
            raise Conflict("Duplicate recipe source: " + source)
        seen.add(source)
        if not source.startswith(tuple("app/src/main/java/com/android/purebilibili/feature/" + area + "/"
                                       for area in ("video", "bangumi"))) or not source.endswith(".kt"):
            raise Conflict("Source outside video/bangumi scope: " + source)
        path = checked_path(repo, source)
        raw = path.read_bytes().replace(b"\r\n", b"\n")
        pinned = git(repo, "show", commit + ":" + source).replace(b"\r\n", b"\n")
        if sha(raw) != item["sha256LF"] or raw != pinned:
            raise Conflict("Upstream version/source changed: " + source)
        body = raw.decode("utf-8")
        adapted = edits(body, item.get("edits", []), source)
        patch = "desktop/upstream-core/patches/" + source + ".patch"
        target = checked_path(repo, patch)
        old = target.read_bytes() if target.exists() else None
        if adapted != body:
            content = unified(source, raw, adapted.encode())
            if old is not None and old != content and sha(old) != item.get("previousPatchSha256"):
                raise Conflict("Existing patch differs; review/compose it manually: " + patch)
            changes[patch] = (old, content)
        elif old is not None:
            raise Conflict("Unaccounted existing patch for an unedited source: " + patch)
        identity = source.removeprefix("app/src/main/java/")
        sources.append(identity)
        for output in item.get("retire", []):
            # Producer name plus package path, never a glob or directory deletion.
            if not re.fullmatch(r"[a-z0-9-]+/" + re.escape(identity), output):
                raise Conflict("Retired output must have the same source identity: " + output)
            retired.append(output)
    if not sources:
        raise Conflict("Empty migration batch")
    if len(retired) != len(set(retired)):
        raise Conflict("Duplicate retired output")
    build = register(build, "migratedVideoFeaturePaths", sources)
    build = register(build, "upstreamOwnedGeneratedCopies", retired)
    changes.update(transition_changes(repo, recipe))
    registrations = recipe.get("transitionProducerRegistrations", {})
    for producer, task in registrations.items():
        if (producer, task) != ("original-video-player-section-full", "extractOriginalVideoPlayerSectionFull"):
            raise Conflict("Unreviewed transition producer registration: " + producer)
        entry = '    "' + producer + '" to "' + task + '",\n'
        if entry not in build:
            marker = "val transitionPatchProducers = mapOf(\n"
            if build.count(marker) != 1 or '"' + producer + '" to ' in build:
                raise Conflict("Missing/conflicting transition producer registration: " + producer)
            build = build.replace(marker, marker + entry, 1)
    changes["desktop/build.gradle.kts"] = (original_build, build.encode())
    return changes, sources


def write_atomic(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode & 0o777 if path.exists() else 0o644
    handle, temporary = tempfile.mkstemp(prefix=".migration-", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(content)
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def apply(repo, changes):
    # Recheck every target before the first write (concurrent edits fail closed).
    for relative, (old, _) in changes.items():
        path = checked_path(repo, relative)
        current = path.read_bytes() if path.exists() else None
        if current != old:
            raise Conflict("Target changed since preflight: " + relative)
    written = []
    try:
        for relative, (old, new) in changes.items():
            if old != new:
                write_atomic(checked_path(repo, relative), new)
                written.append(relative)
    except Exception:
        for relative in reversed(written):
            old = changes[relative][0]
            path = checked_path(repo, relative)
            if old is None:
                path.unlink()
            else:
                write_atomic(path, old)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recipe", type=Path)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="write only after the entire batch passes preflight")
    mode.add_argument("--dry-run", action="store_true", help="report planned changes without writing (default)")
    mode.add_argument("--export-patch", type=Path,
                      help="export the complete batch for the build-file owner; do not apply any changes")
    args = parser.parse_args()
    try:
        recipe = json.loads(args.recipe.read_text(encoding="utf-8"))
        repo = args.repo.resolve()
        changes, sources = plan(repo, recipe)
        if args.apply:
            apply(repo, changes)
        if args.export_patch:
            destination = args.export_patch.resolve()
            if destination == args.recipe.resolve() or any(destination == repo / path for path in changes):
                raise Conflict("Export destination would overwrite migration input/target")
            write_atomic(args.export_patch, export_patch(changes))
        report = dict(batch=recipe["id"], mode="apply" if args.apply else "dry-run",
                      upstream=recipe["upstream"], sources=sources,
                      changes=[dict(path=p, action="unchanged" if old == new else "create" if old is None else "update",
                                    sha256=sha(new)) for p, (old, new) in changes.items()],
                      pending=recipe.get("pending", []))
        report["changedFiles"] = sum(old != new for old, new in changes.values())
        if args.export_patch:
            report["exportedPatch"] = dict(path=str(args.export_patch), sha256=sha(export_patch(changes)))
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (Conflict, OSError, KeyError, TypeError, UnicodeError, json.JSONDecodeError) as error:
        print(json.dumps(dict(error=str(error), status="failed"), ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

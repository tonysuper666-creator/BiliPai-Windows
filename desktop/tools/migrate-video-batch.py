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
        adapted = body
        for edit in item.get("edits", []):
            before, after = edit["before"], edit["after"]
            if not before or adapted.count(before) != edit.get("count", 1):
                raise Conflict("Edit anchor changed: " + source + ": " + before[:80])
            adapted = adapted.replace(before, after)
        patch = "desktop/upstream-core/patches/" + source + ".patch"
        target = checked_path(repo, patch)
        old = target.read_bytes() if target.exists() else None
        if adapted != body:
            content = "".join(difflib.unified_diff(body.splitlines(keepends=True),
                                                 adapted.splitlines(keepends=True),
                                                 fromfile="a/" + source, tofile="b/" + source)).encode()
            if old is not None and old != content:
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
    args = parser.parse_args()
    try:
        recipe = json.loads(args.recipe.read_text(encoding="utf-8"))
        repo = args.repo.resolve()
        changes, sources = plan(repo, recipe)
        if args.apply:
            apply(repo, changes)
        report = dict(batch=recipe["id"], mode="apply" if args.apply else "dry-run",
                      upstream=recipe["upstream"], sources=sources,
                      changes=[dict(path=p, action="unchanged" if old == new else "create" if old is None else "update",
                                    sha256=sha(new)) for p, (old, new) in changes.items()],
                      pending=recipe.get("pending", []))
        report["changedFiles"] = sum(old != new for old, new in changes.values())
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (Conflict, OSError, KeyError, TypeError, UnicodeError, json.JSONDecodeError) as error:
        print(json.dumps(dict(error=str(error), status="failed"), ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

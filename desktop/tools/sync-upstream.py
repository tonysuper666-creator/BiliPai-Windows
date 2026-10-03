#!/usr/bin/env python3
"""Check releases or prepare a tested Windows update in an isolated Git worktree."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM = "jay3-yy/BiliPai"
SAFE_WORKFLOWS = {"windows-desktop.yml", "windows-upstream-sync.yml"}


class UpdateError(RuntimeError):
    pass


def normalized_source(source: bytes) -> bytes:
    """Git checkouts may use CRLF while raw GitHub always returns the blob's LF."""
    return source.replace(b"\r\n", b"\n")


def source_digest(source: bytes) -> str:
    return hashlib.sha256(normalized_source(source)).hexdigest()


def run(command: list[str], cwd: Path, *, check: bool = True, stream: bool = False) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=cwd, text=True, encoding="utf-8", errors="replace",
                            stdout=sys.stderr if stream else subprocess.PIPE,
                            stderr=sys.stderr if stream else subprocess.PIPE)
    if check and result.returncode:
        raise UpdateError(f"Command failed ({result.returncode}): {command[0]} {command[1]}\n"
                          f"{((result.stdout or '') + chr(10) + (result.stderr or '')).strip()[-16000:]}")
    return result


def git(repo: Path, *args: str, check: bool = True) -> str:
    return run(["git", *args], repo, check=check).stdout.strip()


def request_json(endpoint: str) -> object:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "BiliPai-Windows-updater"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"https://api.github.com/repos/{UPSTREAM}/{endpoint}", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return json.load(response)
    except (urllib.error.URLError, ValueError) as error:
        raise UpdateError(f"GitHub API request failed for {endpoint}: {error}") from error


def source_bytes(commit: str, path: str) -> bytes:
    url = f"https://raw.githubusercontent.com/{UPSTREAM}/{commit}/{urllib.parse.quote(path, safe='/')}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "BiliPai-Windows-updater"}),
                                    timeout=45) as response:
            return response.read()
    except urllib.error.URLError as error:
        raise UpdateError(f"Cannot verify upstream source {path}: {error}") from error


def source_path(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise UpdateError("Source paths must be nonempty repository-relative POSIX paths.")
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or "." in path.parts or str(path) != value:
        raise UpdateError(f"Unsafe source path: {value}")
    if path.parts[0] in {"desktop", ".git", ".github"}:
        raise UpdateError(f"Source inventory cannot include desktop or Git control files: {value}")
    return value


def read_manifest(repo: Path) -> dict:
    try:
        manifest = json.loads((repo / "desktop/upstream-sources.json").read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as error:
        raise UpdateError(f"Cannot read desktop/upstream-sources.json: {error}") from error
    if manifest.get("schemaVersion") != 1 or manifest.get("upstreamRepository") != UPSTREAM:
        raise UpdateError("Unsupported manifest schema or upstream repository.")
    if manifest.get("hashNormalization") != "lf":
        raise UpdateError("Source inventory must declare hashNormalization=lf.")
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("upstreamCommit", "")):
        raise UpdateError("upstreamCommit must pin a full Git commit SHA.")
    if not isinstance(manifest.get("upstreamTag"), str) or not manifest["upstreamTag"]:
        raise UpdateError("upstreamTag must be nonempty.")
    sources = manifest.get("sources")
    if not isinstance(sources, list) or not sources or len(sources) > 200:
        raise UpdateError("Manifest requires 1 to 200 explicit reused source files.")
    seen = set()
    for item in sources:
        path = source_path(item.get("path"))
        if path in seen or not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")):
            raise UpdateError(f"Duplicate source or invalid SHA-256: {path}")
        seen.add(path)
        local = repo.joinpath(*PurePosixPath(path).parts)
        if local.is_symlink() or not local.resolve().is_relative_to(repo.resolve()):
            raise UpdateError(f"Reused source escapes the repository: {path}")
        if not local.is_file() or source_digest(local.read_bytes()) != item["sha256"]:
            raise UpdateError(f"Local reused source differs from its pinned inventory: {path}")
        if not isinstance(item.get("features", []), list):
            raise UpdateError(f"features must be a list: {path}")
    if not isinstance(manifest.get("featureCoverage", {}), dict):
        raise UpdateError("featureCoverage must be an object.")
    return manifest


def latest_release() -> dict:
    # /releases/latest excludes prereleases. Read /releases so alpha tags are included.
    releases = request_json("releases?per_page=100")
    if not isinstance(releases, list):
        raise UpdateError("GitHub returned an invalid release list.")
    published = [item for item in releases if isinstance(item, dict) and not item.get("draft")
                 and item.get("published_at") and item.get("tag_name")]
    if not published:
        raise UpdateError("No published GitHub releases found; no tag or APK is guessed.")
    return max(published, key=lambda item: (item["published_at"], item.get("id", 0)))


def resolve_tag(tag: str) -> str:
    obj = request_json(f"git/ref/tags/{urllib.parse.quote(tag, safe='')}")
    if not isinstance(obj, dict):
        raise UpdateError("GitHub returned an invalid tag reference.")
    obj = obj.get("object", {})
    for _ in range(8):
        kind, sha = obj.get("type"), obj.get("sha", "")
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise UpdateError("Tag resolved to an invalid SHA.")
        if kind == "commit":
            return sha
        if kind != "tag":
            break
        tagged = request_json(f"git/tags/{sha}")
        obj = tagged.get("object", {}) if isinstance(tagged, dict) else {}
    raise UpdateError("Release tag did not resolve to a commit.")


def branch_name(tag: str, commit: str) -> str:
    name = re.sub(r"[^A-Za-z0-9._-]+", "-", tag).strip(".-")[:70] or "release"
    while ".." in name:
        name = name.replace("..", "-")
    return f"windows/upstream-{name}-{commit[:12]}"


def kotlin_tokens(text: str) -> list[tuple[str, int, int]]:
    """Keep literal contents, but do not mistake comments/templates for code delimiters."""
    def comment_end(start: int) -> int:
        depth, position = 1, start + 2
        while position < len(text):
            if text.startswith("/*", position):
                depth, position = depth + 1, position + 2
            elif text.startswith("*/", position):
                depth, position = depth - 1, position + 2
                if not depth:
                    return position
            else:
                position += 1
        raise UpdateError("Unterminated Kotlin comment; sensitive network review required.")

    def quoted_end(start: int) -> int:
        raw = text.startswith('"""', start)
        quote = '"""' if raw else text[start]
        position = start + len(quote)
        while position < len(text):
            if text.startswith(quote, position):
                return position + len(quote)
            if not raw and text[position] == "\\":
                position += 2
            elif quote != "'" and text.startswith("${", position):
                position = template_end(position + 2)
            else:
                position += 1
        raise UpdateError("Unterminated Kotlin literal; sensitive network review required.")

    def template_end(position: int) -> int:
        depth = 1
        while position < len(text):
            if text.startswith("//", position):
                position = text.find("\n", position)
                if position < 0:
                    break
            elif text.startswith("/*", position):
                position = comment_end(position)
            elif text[position] in "\"'":
                position = quoted_end(position)
            elif text[position] == "{":
                depth, position = depth + 1, position + 1
            elif text[position] == "}":
                depth, position = depth - 1, position + 1
                if not depth:
                    return position
            else:
                position += 1
        raise UpdateError("Unterminated Kotlin template; sensitive network review required.")

    identifier = re.compile(r"[^\W\d]\w*|\d+")
    result, position = [], 0
    while position < len(text):
        start = position
        if text[position].isspace():
            position += 1
            continue
        if text.startswith("//", position):
            position = text.find("\n", position)
            if position < 0:
                break
            continue
        if text.startswith("/*", position):
            position = comment_end(position)
            continue
        if text[position] in "\"'":
            position = quoted_end(position)
        elif text[position] == "`":
            position = text.find("`", position + 1)
            if position < 0:
                raise UpdateError("Unterminated Kotlin identifier; sensitive network review required.")
            position += 1
        else:
            name = identifier.match(text, position)
            position += len(name.group(0)) if name else 1
        result.append((text[start:position], start, position))
    return result


def kotlin_structure(tokens: list[tuple[str, int, int]], kind: str, name: str,
                     *, constructor_only: bool = False) -> tuple[int, int]:
    """Select one top-level declaration; malformed or missing reviewed blocks fail closed."""
    depth, matches = 0, []
    for index, (value, _, _) in enumerate(tokens):
        if depth == 0 and value == kind and index + 1 < len(tokens) and tokens[index + 1][0] == name:
            matches.append(index)
        depth += (value == "{") - (value == "}")
        if depth < 0:
            raise UpdateError("Unbalanced Kotlin structure; sensitive network review required.")
    if depth:
        raise UpdateError("Unbalanced Kotlin structure; sensitive network review required.")
    if len(matches) != 1:
        raise UpdateError(f"Expected one reviewed {kind} {name}; sensitive network review required.")
    start = matches[0]
    position, parentheses = start + 2, 0
    while position < len(tokens):
        value = tokens[position][0]
        parentheses += (value == "(") - (value == ")")
        if constructor_only and value == ")" and parentheses == 0:
            return start, position
        if value == "{" and parentheses == 0:
            opening, braces = position, 1
            while position + 1 < len(tokens):
                position += 1
                value = tokens[position][0]
                braces += (value == "{") - (value == "}")
                if not braces:
                    return (opening if kind == "interface" else start), position
            break
        if parentheses == 0 and value in {"class", "object", "interface", "fun", "val", "var", "="}:
            break
        position += 1
    raise UpdateError(f"Cannot delimit reviewed {kind} {name}; sensitive network review required.")


def selected_api_contract(repo: Path, source: bytes, *, login_only: bool = False) -> str:
    """Only declarations adopted by Windows enter the interface risk comparison."""
    extractor_path = repo / "desktop/tools/extract-upstream-api.py"
    spec = importlib.util.spec_from_file_location("bilipai_api_extractor", extractor_path)
    extractor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(extractor)
    text = normalized_source(source).decode("utf-8")
    tokens = kotlin_tokens(text)
    constant = re.search(r'(?m)^(?:internal )?const val FORCE_COOKIE_HEADER\s*=\s*"[^"\n]+"', text)
    if constant is None:
        raise UpdateError("Login cookie header constant changed; session adapter needs manual review.")
    declarations = [constant.group(0)]
    selected = ({"PassportApi": extractor.METHODS["PassportApi"], "BilibiliApi": ["getNavInfo"]}
                if login_only else extractor.METHODS)
    for interface, methods in selected.items():
        opening, closing = kotlin_structure(tokens, "interface", interface)
        body = text[tokens[opening][2]:tokens[closing][1]]
        declarations.extend(extractor.extract_method(body, method) for method in methods)
    return "\n".join(declarations)


def selected_login_api(repo: Path, source: bytes) -> str:
    return selected_api_contract(repo, source, login_only=True)


def selected_network_behavior(source: bytes) -> tuple[str, ...]:
    """Review account/visitor implementations, not unrelated Android API interfaces or objects."""
    text = normalized_source(source).decode("utf-8")
    tokens = kotlin_tokens(text)
    sections = []
    for kind, name, constructor_only in (
        ("fun", "applyForcedCookieHeader", False),
        ("class", "AppSessionCookieJar", False),
        ("class", "PlaybackAccountCookieJar", False),
        ("object", "NetworkModule", False),
        ("class", "BuvidSpiData", True),
        ("class", "BuvidSpiResponse", True),
    ):
        start, end = kotlin_structure(tokens, kind, name, constructor_only=constructor_only)
        # Preserve statement boundaries/operators; formatting inside sensitive code is conservatively reviewed.
        sections.append(text[tokens[start][1]:tokens[end][2]])
    # Include identifiers inside string templates too; extra names in comments/literals are conservative.
    identifiers = set(re.findall(r"[^\W\d]\w*", "\n".join(sections)))
    # Header constants and the imported policies/classes they reference are part of the behavior.
    for name in ("MERGED_APP_FEED_FP", "MERGED_APP_FEED_SESSION_ID"):
        positions = [index for index in range(len(tokens) - 1)
                     if tokens[index][0] == "val" and tokens[index + 1][0] == name]
        if len(positions) != 1:
            raise UpdateError(f"Missing or duplicate network constant {name}; manual review required.")
        start = positions[0]
        if (start + 4 >= len(tokens) or tokens[start + 2][0] != "=" or not tokens[start + 3][0].startswith('"') or
                tokens[start + 4][0] not in {"private", "internal", "public", "const", "val", "fun", "class", "data", "object", "interface", "@"}):
            raise UpdateError(f"Network constant {name} changed structure; manual review required.")
        sections.append(text[tokens[start][1]:tokens[start + 3][2]])
    for index, (value, _, _) in enumerate(tokens):
        if value != "import":
            continue
        end = index + 2
        while end + 1 < len(tokens) and tokens[end][0] == ".":
            end += 2
        alias = tokens[end + 1][0] if end + 1 < len(tokens) and tokens[end][0] == "as" else tokens[end - 1][0]
        if alias in identifiers or alias == "*":
            if end + 1 < len(tokens) and tokens[end][0] == "as":
                end += 2
            sections.append(text[tokens[index][1]:tokens[end - 1][2]])
    return tuple(sections)


def check_update(repo: Path, manifest: dict) -> tuple[dict, dict[str, str]]:
    release = latest_release()
    tag = release["tag_name"]
    commit = resolve_tag(tag)
    if tag == manifest["upstreamTag"] and commit != manifest["upstreamCommit"]:
        raise UpdateError("The pinned upstream tag moved to another commit; manual review is required.")
    report = {
        "upstreamRepository": UPSTREAM, "currentTag": manifest["upstreamTag"],
        "currentCommit": manifest["upstreamCommit"], "candidateTag": tag,
        "candidateCommit": commit, "prerelease": bool(release.get("prerelease")),
        "releaseUrl": release.get("html_url"), "candidateBranch": branch_name(tag, commit),
        "status": "upToDate" if commit == manifest["upstreamCommit"] else "updateAvailable",
        "changedReusedSources": [], "featuresNeedingReview": [],
        "featureCoverage": manifest.get("featureCoverage", {}), "loginApiChanged": False,
        "windowsApiContractChanged": False, "networkBehaviorChanged": False,
        "autoPublishEligible": True, "manualReviewReasons": [],
        "coverageNote": "Only explicitly ported Windows features are built. A new APK release does not imply new Windows feature support.",
    }
    if report["status"] == "upToDate":
        return report, {}
    tree = request_json(f"git/trees/{commit}?recursive=1")
    if not isinstance(tree, dict) or tree.get("truncated"):
        raise UpdateError("Cannot verify the complete upstream source inventory.")
    paths = {item["path"] for item in tree.get("tree", []) if item.get("type") == "blob"
             and item.get("mode") in {"100644", "100755"}}
    hashes, features, review_reasons = {}, set(), []
    for item in manifest["sources"]:
        path = item["path"]
        if path not in paths:
            raise UpdateError(f"Reused source removed or moved upstream: {path}. Manual adaptation required.")
        contents = source_bytes(commit, path)
        hashes[path] = source_digest(contents)
        if hashes[path] != item["sha256"]:
            report["changedReusedSources"].append(path)
            source_features = set(item.get("features", []))
            if path.endswith("core/network/ApiClient.kt"):
                try:
                    previous = repo.joinpath(*PurePosixPath(path).parts).read_bytes()
                    report["loginApiChanged"] = selected_login_api(repo, contents) != selected_login_api(
                        repo, previous)
                    report["windowsApiContractChanged"] = selected_api_contract(repo, contents) != selected_api_contract(repo, previous)
                    report["networkBehaviorChanged"] = selected_network_behavior(contents) != selected_network_behavior(previous)
                except (ValueError, OSError, ImportError) as error:
                    raise UpdateError(f"Sensitive Windows network extraction failed; manual review required: {error}") from error
                if not report["loginApiChanged"] and not report["networkBehaviorChanged"]:
                    source_features.discard("login")
                if report["windowsApiContractChanged"] or report["networkBehaviorChanged"]:
                    review_reasons.append(f"Windows API/account/visitor network behavior changed: {path}")
            features.update(source_features)
            if source_features.intersection({"login", "auth", "updates", "cookie", "qr-login"}) or (
                    not path.endswith("core/network/ApiClient.kt") and re.search(r"Login|Passport|Cookie|Session|NavModels", path, re.I)):
                review_reasons.append(f"Authentication/update contract changed: {path}")
    report["featuresNeedingReview"] = sorted(features)
    report["manualReviewReasons"] = review_reasons
    report["autoPublishEligible"] = not review_reasons
    return report, hashes


def publication_check(repo: Path) -> str:
    folder = repo / ".github/workflows"
    unsafe = sorted(path.name for path in folder.iterdir()
                    if path.is_file() and path.suffix.lower() in {".yml", ".yaml"}
                    and path.name not in SAFE_WORKFLOWS) if folder.is_dir() else []
    if unsafe:
        raise UpdateError("Publication disabled: inherited workflows are still active: " + ", ".join(unsafe)
                          + ". Export a Windows fork with these workflows disabled before enabling automatic PRs.")
    origin = git(repo, "remote", "get-url", "origin")
    normalized = origin.lower().removesuffix(".git").rstrip("/")
    if normalized.startswith("git@github.com:"):
        remote_path = normalized.removeprefix("git@github.com:")
    else:
        parsed = urllib.parse.urlparse(normalized)
        if parsed.hostname != "github.com" or parsed.scheme not in {"https", "ssh"}:
            raise UpdateError("Publication requires a configured GitHub fork remote.")
        remote_path = parsed.path.strip("/")
    if remote_path == "jay3-yy/bilipai":
        raise UpdateError("Publication must target your Windows fork, never the original upstream repository.")
    if len(remote_path.split("/")) != 2:
        raise UpdateError("Publication requires a configured GitHub fork remote.")
    return remote_path


def android_version_code(repo: Path) -> int:
    build_file = repo / "app/build.gradle.kts"
    match = re.search(r"\bversionCode\s*=\s*(\d+)", build_file.read_text(encoding="utf-8"))
    if match is None:
        raise UpdateError("Cannot read upstream Android versionCode for monotonic Windows versioning.")
    return int(match.group(1))


def sync_update(repo: Path, manifest: dict, report: dict, hashes: dict[str, str],
                worktree_root: Path, java_home: str | None, release_gate: bool = False) -> dict:
    if report["status"] == "upToDate":
        return report
    if git(repo, "status", "--porcelain"):
        raise UpdateError("Commit the Windows changes first. The original dirty checkout is left untouched.")
    commit, branch = report["candidateCommit"], report["candidateBranch"]
    git(repo, "fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", commit)
    ancestry = run(["git", "merge-base", "--is-ancestor", manifest["upstreamCommit"], commit], repo, check=False)
    if ancestry.returncode:
        raise UpdateError("Release is not a descendant of the pinned version; manual review required.")
    if git(repo, "diff", "--name-only", manifest["upstreamCommit"], commit, "--", "desktop"):
        raise UpdateError("Upstream added a desktop directory. Reconcile ownership manually before building it.")
    upstream_paths = git(repo, "diff", "--name-only", manifest["upstreamCommit"], commit).splitlines()
    reused_paths = {entry["path"] for entry in manifest["sources"]}
    report["upstreamChangedPaths"] = upstream_paths
    report["unportedChangedPaths"] = [path for path in upstream_paths if path not in reused_paths]
    base = git(repo, "rev-parse", "HEAD")
    worktree_root = worktree_root.resolve()
    candidate = worktree_root / branch.replace("/", "-")
    if candidate.exists() or git(repo, "branch", "--list", branch):
        raise UpdateError(f"Candidate already exists: {branch}. Review it before making another candidate.")
    worktree_root.mkdir(parents=True, exist_ok=True)
    git(repo, "worktree", "add", "-b", branch, str(candidate), base)
    report.update({"candidatePath": str(candidate), "baseCommit": base, "buildStatus": "notStarted"})
    try:
        merged = run(["git", "merge", "--no-commit", "--no-ff", commit], candidate, check=False)
        # Keep the reviewed build automation. Importing upstream CI can publish Android artifacts.
        git(candidate, "restore", "--source", base, "--staged", "--worktree", "--", ".github/workflows")
        if git(repo, "ls-tree", base, ".github/upstream-workflows"):
            git(candidate, "restore", "--source", base, "--staged", "--worktree", "--", ".github/upstream-workflows")
        conflicts = git(candidate, "diff", "--name-only", "--diff-filter=U")
        if conflicts:
            raise UpdateError("Merge conflicts in candidate worktree:\n" + conflicts)
        if merged.returncode and "CONFLICT" not in (merged.stdout + merged.stderr):
            raise UpdateError("Upstream merge failed:\n" + merged.stderr)
        candidate_manifest = json.loads(json.dumps(manifest))
        candidate_manifest.update({"upstreamTag": report["candidateTag"], "upstreamCommit": commit,
                                   "hashNormalization": "lf"})
        old_code, new_code = android_version_code(repo), android_version_code(candidate)
        if new_code < old_code:
            raise UpdateError("Upstream versionCode regressed; Windows versioning needs manual review.")
        candidate_manifest["windowsRevision"] = (
            int(manifest.get("windowsRevision", 1)) + 1 if new_code == old_code else 1)
        report["windowsBuildVersion"] = f"0.2.{new_code}.{candidate_manifest['windowsRevision']}"
        for item in candidate_manifest["sources"]:
            path = item["path"]
            local_hash = source_digest(candidate.joinpath(*PurePosixPath(path).parts).read_bytes())
            if local_hash != hashes[path]:
                raise UpdateError(f"Merged source is not the verified upstream source: {path}")
            item["sha256"] = hashes[path]
        candidate_manifest["lastSourceReview"] = {
            "changedReusedSources": report["changedReusedSources"],
            "featuresNeedingReview": report["featuresNeedingReview"],
            "status": "releaseGatePending" if release_gate else "requiresHumanFunctionalReview",
        }
        (candidate / "desktop/upstream-sources.json").write_text(
            json.dumps(candidate_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        run([sys.executable, "-m", "unittest", "discover", "-s", "desktop/tools/tests", "-p", "test_*.py"], candidate)
        build_command = ["pwsh", "-NoProfile", "-File", "desktop/tools/build.ps1"]
        if java_home:
            build_command.extend(["-JavaHome", java_home])
        if release_gate:
            build_command.append("-ReleaseGate")
        report["buildStatus"] = "building"
        run(build_command, candidate, stream=True)
        report["buildStatus"] = "passed"
        report["releaseGatePassed"] = release_gate
        candidate_manifest["lastSourceReview"]["status"] = (
            "releaseGatePassed" if release_gate else "requiresHumanFunctionalReview")
        (candidate / "desktop/upstream-sources.json").write_text(
            json.dumps(candidate_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        git(candidate, "add", "desktop/upstream-sources.json")
        git(candidate, "commit", "-m", f"Update Windows sources to {report['candidateTag']}")
        report["candidateHead"] = git(candidate, "rev-parse", "HEAD")
        report["status"] = "candidateReady"
        return report
    except (UpdateError, OSError) as error:
        report.update({"status": "candidateFailed", "error": str(error)})
        raise UpdateError(json.dumps(report, ensure_ascii=False, indent=2)
                          + "\nOriginal checkout preserved. Failed candidate retained for inspection; nothing pushed.") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="Read-only release and source inventory check")
    mode.add_argument("--sync", action="store_true", help="Prepare and build an isolated candidate; never pushes")
    mode.add_argument("--publication-check", action="store_true", help="Reject upstream remotes and inherited workflows")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--worktree-root", type=Path)
    parser.add_argument("--java-home")
    parser.add_argument("--release-gate", action="store_true", help="Require native offline and guest network smoke tests")
    args = parser.parse_args()
    repo = args.repo.resolve()
    try:
        if args.publication_check:
            publication_check(repo)
            print(json.dumps({"publicationSafe": True}))
            return 0
        manifest = read_manifest(repo)
        report, hashes = check_update(repo, manifest)
        if args.sync:
            report = sync_update(repo, manifest, report, hashes,
                                 args.worktree_root or repo.parent / "bilipai-windows-updates", args.java_home,
                                 args.release_gate)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (UpdateError, OSError, KeyError, TypeError) as error:
        print(f"Update stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

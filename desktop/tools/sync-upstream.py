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
# Bound configuration input before JSON decoding, independently of how many
# explicitly pinned files Windows reuses. The current complete inventory is
# under 0.5 MiB; this budget leaves room to grow without a stale file-count cap.
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
CANONICAL_CATALOG_SHA256 = "2fa53aa78cc27c76a3923cc44750128b3657c340dbcfe44ec13810cbc48becbc"
CANONICAL_BASELINE_COMMIT = "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
CANONICAL_CATALOG_PATH = "desktop/tools/v025-canonical-sources.json"


class UpdateError(RuntimeError):
    pass


def normalized_source(source: bytes) -> bytes:
    """Git checkouts may use CRLF while raw GitHub always returns the blob's LF."""
    return source.replace(b"\r\n", b"\n")


def normalized_input(source: bytes, normalization: str = "lf") -> bytes:
    if normalization == "lf":
        return normalized_source(source)
    if normalization == "raw":
        return source
    raise UpdateError(f"Unknown source hash normalization: {normalization}")


def source_digest(source: bytes, normalization: str = "lf") -> str:
    return hashlib.sha256(normalized_input(source, normalization)).hexdigest()


def blob_oid(contents: bytes) -> str:
    # Git's blob object ID identifies the exact bytes in the fixed release tree.
    return hashlib.sha1(b"blob " + str(len(contents)).encode("ascii") + b"\0" + contents).hexdigest()


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


def local_input(repo: Path, item: dict) -> bytes:
    path = source_path(item.get("path"))
    local = repo.joinpath(*PurePosixPath(path).parts)
    if local.is_symlink() or not local.resolve().is_relative_to(repo.resolve()):
        raise UpdateError(f"Reused source/resource escapes the repository: {path}")
    try:
        contents = local.read_bytes()
    except OSError as error:
        raise UpdateError(f"Cannot read reused source/resource {path}: {error}") from error
    if source_digest(contents, item.get("hashNormalization", "lf")) != item["sha256"]:
        raise UpdateError(f"Local reused source/resource differs from its pinned inventory: {path}")
    return contents


def read_manifest(repo: Path) -> dict:
    try:
        with (repo / "desktop/upstream-sources.json").open("rb") as source:
            contents = source.read(MAX_MANIFEST_BYTES + 1)
        if len(contents) > MAX_MANIFEST_BYTES:
            raise UpdateError("Source inventory exceeds the 4 MiB configuration input budget.")
        manifest = json.loads(contents.decode("utf-8-sig"))
    except (OSError, ValueError) as error:
        raise UpdateError(f"Cannot read desktop/upstream-sources.json: {error}") from error
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1 or manifest.get("upstreamRepository") != UPSTREAM:
        raise UpdateError("Unsupported manifest schema or upstream repository.")
    if manifest.get("hashNormalization") != "lf":
        raise UpdateError("Source inventory must declare hashNormalization=lf.")
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("upstreamCommit", "")):
        raise UpdateError("upstreamCommit must pin a full Git commit SHA.")
    if not isinstance(manifest.get("upstreamTag"), str) or not manifest["upstreamTag"]:
        raise UpdateError("upstreamTag must be nonempty.")
    sources = manifest.get("sources")
    if not isinstance(sources, list) or not sources:
        raise UpdateError("Manifest requires a nonempty list of explicit reused source files.")
    resources = manifest.get("resources", [])
    if not isinstance(resources, list):
        raise UpdateError("Manifest resources must be a list of explicitly pinned files.")
    seen = set()
    for item in sources + resources:
        if not isinstance(item, dict):
            raise UpdateError("Source/resource inventory entries must be objects.")
        path = source_path(item.get("path"))
        if path in seen or not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")):
            raise UpdateError(f"Duplicate source/resource or invalid SHA-256: {path}")
        seen.add(path)
        local_input(repo, item)
        if not isinstance(item.get("features", []), list) or not all(isinstance(value, str) for value in item.get("features", [])):
            raise UpdateError(f"features must be a list: {path}")
    if not isinstance(manifest.get("featureCoverage", {}), dict):
        raise UpdateError("featureCoverage must be an object.")
    return manifest


def canonical_baseline(repo: Path, manifest: dict) -> dict | None:
    path = repo / CANONICAL_CATALOG_PATH
    if not path.exists() and not path.is_symlink():
        # Older source-only fixtures have no proof of the complete build baseline.
        return None
    if path.is_symlink() or not path.resolve().is_relative_to(repo.resolve()) or not path.is_file():
        raise UpdateError("Canonical source catalog escapes the repository or is not a regular file.")
    try:
        contents = path.read_bytes()
        if hashlib.sha256(contents).hexdigest() != CANONICAL_CATALOG_SHA256:
            raise UpdateError("Unknown canonical source catalog; manual baseline review is required.")
        catalog = json.loads(contents.decode("utf-8-sig"))
    except (OSError, ValueError) as error:
        raise UpdateError(f"Cannot read canonical source catalog: {error}") from error
    if (not isinstance(catalog, dict) or catalog.get("schemaVersion") != 1 or
            catalog.get("upstreamCommit") != CANONICAL_BASELINE_COMMIT or
            not isinstance(catalog.get("paths"), dict) or not catalog["paths"] or
            not isinstance(catalog.get("sourceRoots"), list) or not catalog["sourceRoots"] or
            not all(isinstance(value, str) and value.endswith("/") for value in catalog["sourceRoots"])):
        raise UpdateError("Unknown canonical baseline structure or commit; manual review is required.")
    for name, pin in catalog["paths"].items():
        source_path(name)
        if (not isinstance(pin, dict) or not re.fullmatch(r"[0-9a-f]{40}", pin.get("blob", "")) or
                not re.fullmatch(r"[0-9a-f]{64}", pin.get("sha256", "")) or
                pin.get("hashNormalization") not in {"lf", "raw"}):
            raise UpdateError(f"Unknown canonical source pin structure: {name}")
    return catalog


def compatibility_inputs(repo: Path, manifest: dict, catalog: dict | None) -> list[dict]:
    entries = [{**item, "kind": kind} for kind, key in (("source", "sources"), ("resource", "resources"))
               for item in manifest.get(key, [])]
    if catalog is not None:
        build_path = "app/build.gradle.kts"
        if build_path not in catalog["paths"]:
            raise UpdateError("Canonical baseline does not pin the upstream version/build configuration.")
        if not any(item["path"] == build_path for item in entries):
            entries.append({"path": build_path, **catalog["paths"][build_path], "kind": "buildConfiguration"})
        for item in entries:
            if item["path"] == build_path:
                item["kind"] = "buildConfiguration"
        for item in entries:
            pin = catalog["paths"].get(item["path"])
            if (pin is None or pin["sha256"] != item["sha256"] or
                    pin["hashNormalization"] != item.get("hashNormalization", "lf")):
                raise UpdateError(f"Inventory differs from the reviewed canonical baseline: {item['path']}")
    # Verify local bytes even for callers that supplied an in-memory test inventory.
    for item in entries:
        local_input(repo, item)
    return entries


def release_tree(tree: object) -> dict[str, dict]:
    if not isinstance(tree, dict) or tree.get("truncated") or not isinstance(tree.get("tree"), list):
        raise UpdateError("Cannot verify the complete upstream source inventory.")
    result = {}
    for item in tree["tree"]:
        if not isinstance(item, dict):
            raise UpdateError("Invalid upstream tree entry structure.")
        name = item.get("path")
        if (not isinstance(name, str) or not name or "\\" in name or ":" in name or
                PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts or
                str(PurePosixPath(name)) != name or name in result or
                item.get("type") not in {"blob", "tree", "commit"} or
                not re.fullmatch(r"[0-9a-f]{40}", item.get("sha", ""))):
            raise UpdateError("Invalid or duplicate upstream tree entry: " + str(name))
        allowed_modes = {"blob": {"100644", "100755", "120000"}, "tree": {"040000"}, "commit": {"160000"}}
        if item.get("mode") not in allowed_modes[item["type"]]:
            raise UpdateError("Invalid upstream tree entry mode: " + name)
        result[name] = item
    return result


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
    catalog = canonical_baseline(repo, manifest)
    entries = compatibility_inputs(repo, manifest, catalog)
    reasons = [] if catalog is not None else [{
        "code": "canonicalBaselineUnavailable", "path": CANONICAL_CATALOG_PATH,
        "message": "No reviewed complete canonical build baseline; automatic construction is disabled.",
    }]
    compatibility = {
        "status": "ready" if catalog is not None else "manualAdaptationRequired",
        "canonicalCatalogSha256": CANONICAL_CATALOG_SHA256 if catalog is not None else None,
        "canonicalBaselineCommit": catalog["upstreamCommit"] if catalog is not None else None,
        "checkedInputCount": len(entries), "reasons": reasons,
    }
    report = {
        "upstreamRepository": UPSTREAM, "currentTag": manifest["upstreamTag"],
        "currentCommit": manifest["upstreamCommit"], "candidateTag": tag,
        "candidateCommit": commit, "prerelease": bool(release.get("prerelease")),
        "releaseUrl": release.get("html_url"), "candidateBranch": branch_name(tag, commit),
        "status": "upToDate" if commit == manifest["upstreamCommit"] else "updateAvailable",
        "changedReusedSources": [], "changedResources": [], "featuresNeedingReview": [],
        "featureCoverage": manifest.get("featureCoverage", {}), "loginApiChanged": False,
        "windowsApiContractChanged": False, "networkBehaviorChanged": False,
        "autoBuildEligible": not reasons, "buildCompatibility": compatibility,
        "autoPublishEligible": not reasons, "manualReviewReasons": [], "sensitiveReviewFailed": [],
        "unchangedBlobInputs": 0, "sourceDownloads": 0,
        "coverageNote": "Only explicitly ported Windows features are built. A new APK release does not imply new Windows feature support.",
    }
    if report["status"] == "upToDate":
        return report, {}
    tree = release_tree(request_json(f"git/trees/{commit}?recursive=1"))
    hashes, features, review_reasons = {}, set(), []
    for item in entries:
        path = item["path"]
        previous = local_input(repo, item)
        normalization = item.get("hashNormalization", "lf")
        incoming = tree.get(path)
        contents = None
        new_hash = None
        if incoming is None or incoming["type"] != "blob" or incoming["mode"] not in {"100644", "100755"}:
            reasons.append({
                "code": "inputRemovedOrMoved" if incoming is None else "inputNotRegularFile",
                "path": path, "kind": item["kind"], "oldSha256": item["sha256"],
                "newSha256": None, "hashNormalization": normalization,
                "message": "Pinned Windows input is missing or no longer a regular upstream file.",
            })
        else:
            baseline_oid = catalog["paths"][path]["blob"] if catalog is not None else blob_oid(normalized_input(previous, normalization))
            if incoming["sha"] == baseline_oid:
                new_hash = item["sha256"]
                report["unchangedBlobInputs"] += 1
            else:
                contents = source_bytes(commit, path)
                report["sourceDownloads"] += 1
                if blob_oid(contents) != incoming["sha"]:
                    raise UpdateError(f"Fixed-commit source does not match its complete-tree blob identity: {path}")
                new_hash = source_digest(contents, normalization)
            hashes[path] = new_hash
            if new_hash != item["sha256"]:
                reasons.append({
                    "code": "canonicalInputChanged" if catalog is not None else "pinnedInputChanged",
                    "path": path, "kind": item["kind"], "oldSha256": item["sha256"],
                    "newSha256": new_hash, "hashNormalization": normalization,
                    "message": "Review the canonical original and affected Windows adaptation; fixed pins were not rewritten.",
                })
        if new_hash != item["sha256"]:
            if item["kind"] == "source":
                report["changedReusedSources"].append(path)
            elif item["kind"] == "resource":
                report["changedResources"].append(path)
            source_features = set(item.get("features", []))
            if item["kind"] == "source" and path.endswith("core/network/ApiClient.kt") and contents is not None:
                try:
                    report["loginApiChanged"] = selected_login_api(repo, contents) != selected_login_api(
                        repo, previous)
                    report["windowsApiContractChanged"] = selected_api_contract(repo, contents) != selected_api_contract(repo, previous)
                    report["networkBehaviorChanged"] = selected_network_behavior(contents) != selected_network_behavior(previous)
                except (UpdateError, ValueError, OSError, ImportError) as error:
                    report["sensitiveReviewFailed"].append(path)
                    review_reasons.append(f"Sensitive Windows network extraction failed; manual review required: {path}")
                    reasons.append({"code": "sensitiveReviewFailed", "path": path, "kind": "source",
                                    "message": str(error)[:2000]})
                if path not in report["sensitiveReviewFailed"] and not report["loginApiChanged"] and not report["networkBehaviorChanged"]:
                    source_features.discard("login")
                if report["windowsApiContractChanged"] or report["networkBehaviorChanged"]:
                    review_reasons.append(f"Windows API/account/visitor network behavior changed: {path}")
            features.update(source_features)
            if source_features.intersection({"login", "auth", "updates", "cookie", "qr-login"}) or (
                    not path.endswith("core/network/ApiClient.kt") and re.search(r"Login|Passport|Cookie|Session|NavModels", path, re.I)):
                review_reasons.append(f"Authentication/update contract changed: {path}")
    report["featuresNeedingReview"] = sorted(features)
    report["manualReviewReasons"] = review_reasons
    compatibility["status"] = "manualAdaptationRequired" if reasons else "ready"
    report["autoBuildEligible"] = not reasons
    report["autoPublishEligible"] = not reasons and not review_reasons
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
    if report.get("autoBuildEligible") is not True or report.get("buildCompatibility", {}).get("status") != "ready":
        raise UpdateError("Automatic candidate build is disabled by the compatibility preflight: "
                          + json.dumps(report.get("buildCompatibility", {}), ensure_ascii=False))
    worktree_root = worktree_root.resolve()
    if worktree_root.is_relative_to(repo.resolve()):
        raise UpdateError("Candidate worktrees must be outside the original checkout.")
    commit, branch = report["candidateCommit"], report["candidateBranch"]
    git(repo, "fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", commit)
    ancestry = run(["git", "merge-base", "--is-ancestor", manifest["upstreamCommit"], commit], repo, check=False)
    if ancestry.returncode:
        raise UpdateError("Release is not a descendant of the pinned version; manual review required.")
    if git(repo, "diff", "--name-only", manifest["upstreamCommit"], commit, "--", "desktop"):
        raise UpdateError("Upstream added a desktop directory. Reconcile ownership manually before building it.")
    upstream_paths = git(repo, "diff", "--name-only", manifest["upstreamCommit"], commit).splitlines()
    reused_paths = {entry["path"] for entry in manifest["sources"] + manifest.get("resources", [])}
    report["upstreamChangedPaths"] = upstream_paths
    report["unportedChangedPaths"] = [path for path in upstream_paths if path not in reused_paths]
    base = git(repo, "rev-parse", "HEAD")
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
        for item in candidate_manifest["sources"] + candidate_manifest.get("resources", []):
            path = item["path"]
            local_hash = source_digest(candidate.joinpath(*PurePosixPath(path).parts).read_bytes(),
                                       item.get("hashNormalization", "lf"))
            if local_hash != hashes[path]:
                raise UpdateError(f"Merged source is not the verified upstream source: {path}")
            item["sha256"] = hashes[path]
        # The version/build configuration is a canonical input even when a
        # legacy manifest does not list it as a reused source.
        if "app/build.gradle.kts" in hashes and source_digest(
                (candidate / "app/build.gradle.kts").read_bytes()) != hashes["app/build.gradle.kts"]:
            raise UpdateError("Merged build configuration is not the verified upstream input.")
        candidate_manifest["lastSourceReview"] = {
            "changedReusedSources": report["changedReusedSources"],
            "changedResources": report.get("changedResources", []),
            "featuresNeedingReview": report["featuresNeedingReview"],
            "status": "requiresBuildVerification",
        }
        (candidate / "desktop/upstream-sources.json").write_text(
            json.dumps(candidate_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # Only the isolated candidate is committed. Test/build evidence must bind
        # this immutable source; no post-build manifest edit can acquire its ACK.
        git(candidate, "add", "desktop/upstream-sources.json")
        git(candidate, "commit", "-m", f"Update Windows sources to {report['candidateTag']}")
        report["candidateHead"] = git(candidate, "rev-parse", "HEAD")
        if git(candidate, "status", "--porcelain"):
            raise UpdateError("Committed candidate is dirty before verification.")
        run([sys.executable, "desktop/tools/run-tool-tests.py", "--stage", "policies"], candidate)
        if git(candidate, "rev-parse", "HEAD") != report["candidateHead"] or git(candidate, "status", "--porcelain"):
            raise UpdateError("Candidate source changed during policy verification.")
        build_command = ["pwsh", "-NoProfile", "-File", "desktop/tools/build.ps1"]
        if java_home:
            build_command.extend(["-JavaHome", java_home])
        if release_gate:
            build_command.append("-ReleaseGate")
        report["buildStatus"] = "building"
        run(build_command, candidate, stream=True)
        if git(candidate, "rev-parse", "HEAD") != report["candidateHead"] or git(candidate, "status", "--porcelain"):
            raise UpdateError("Candidate source changed during its successful build; no publication is allowed.")
        report["buildStatus"] = "passed"
        report["releaseGatePassed"] = release_gate
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

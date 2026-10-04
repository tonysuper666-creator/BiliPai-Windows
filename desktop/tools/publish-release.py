#!/usr/bin/env python3
"""Check or finish an immutable Windows release, preserving existing valid assets."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location("windows_sync", Path(__file__).with_name("sync-upstream.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)
ReleaseError = sync.UpdateError
GATES = ("kotlinUnitTests", "pythonSourceContractTests", "guestNetworkBackendSmoke", "packagedNativePlayerSmoke", "packagedUpdaterSmoke")


def valid_repository(repository: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ReleaseError("A GitHub owner/repository is required.")
    if repository.lower() == sync.UPSTREAM.lower():
        raise ReleaseError("Windows publication must never target the original upstream repository.")
    return repository


def validate_sha(source_sha: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ReleaseError("An exact Windows source commit SHA is required.")
    return source_sha


def version_for(repo: Path, manifest: dict) -> str:
    revision = manifest.get("windowsRevision", 1)
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        raise ReleaseError("windowsRevision must be a positive integer.")
    return f"0.2.{sync.android_version_code(repo)}.{revision}"


def asset_names(version: str) -> tuple[str, str, str]:
    if not re.fullmatch(r"0\.2\.\d+\.\d+", version):
        raise ReleaseError("Invalid Windows version.")
    archive = f"BiliPai-Windows-{version}-x64.zip"
    return archive, archive + ".sha256", f"BiliPai-Windows-{version}-source.json"


def validate_gates(gate: dict) -> None:
    if gate.get("passed") is not True or any(gate.get(name) != "passed" for name in GATES):
        raise ReleaseError("Unit tests, Python source contracts, guest backend smoke, packaged native smoke, and updater smoke must all pass.")


def checksum(contents: bytes, archive_name: str) -> str:
    match = re.fullmatch(rb"([0-9a-f]{64})  ([^\r\n]+)\r?\n?", contents)
    if not match or match.group(2).decode("utf-8") != archive_name:
        raise ReleaseError("The checksum must name the exact Windows ZIP asset.")
    return match.group(1).decode("ascii")


def evidence(manifest: dict, version: str, source_sha: str, digest: str, gate: dict) -> dict:
    validate_gates(gate)
    archive, sidecar, _ = asset_names(version)
    return {"schemaVersion": 1, "windowsVersion": version, "windowsSourceCommit": source_sha,
            "upstreamRepository": manifest["upstreamRepository"], "upstreamTag": manifest["upstreamTag"],
            "upstreamCommit": manifest["upstreamCommit"], "hashNormalization": manifest["hashNormalization"],
            "zipAsset": archive, "checksumAsset": sidecar, "zipSha256": digest,
            "featureCoverage": manifest.get("featureCoverage", {}),
            "releaseGate": {"passed": True, **{name: "passed" for name in GATES}}}


def validate_evidence(value: dict, manifest: dict, version: str, source_sha: str,
                      digest: str | None = None) -> str:
    declared = value.get("zipSha256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", declared):
        raise ReleaseError("Release source evidence has no valid ZIP SHA-256.")
    expected = evidence(manifest, version, source_sha, digest or declared, value.get("releaseGate", {}))
    if value != expected:
        raise ReleaseError("Existing release source evidence conflicts with this Windows source/version.")
    return declared


def inventory(release: dict | None) -> dict[str, dict]:
    result = {}
    for asset in (release or {}).get("assets", []):
        name = asset.get("name")
        if not isinstance(name, str) or name in result:
            raise ReleaseError("Release asset names must be unique.")
        result[name] = asset
    return result


class GitHub:
    def __init__(self, repository: str, repo: Path):
        self.repository, self.repo = valid_repository(repository), repo

    def gh(self, *args: str, missing: bool = False) -> str | None:
        result = subprocess.run(["gh", *args], cwd=self.repo, text=True, encoding="utf-8", errors="replace",
                                capture_output=True)
        if result.returncode:
            if missing and "HTTP 404" in result.stderr:
                return None
            raise ReleaseError(f"GitHub command failed: {result.stderr.strip()[-6000:]}")
        return result.stdout

    def api(self, endpoint: str, *, missing: bool = False) -> dict | None:
        output = self.gh("api", f"repos/{self.repository}/{endpoint}", missing=missing)
        return json.loads(output) if output is not None else None

    def tag_head(self, tag: str) -> str | None:
        ref = self.api(f"git/ref/tags/{tag}", missing=True)
        if ref is None:
            return None
        obj = ref.get("object", {})
        for _ in range(8):
            sha = validate_sha(obj.get("sha", ""))
            if obj.get("type") == "commit":
                return sha
            if obj.get("type") != "tag":
                break
            obj = self.api(f"git/tags/{sha}").get("object", {})
        raise ReleaseError("Windows release tag does not resolve to a commit.")

    def release(self, tag: str) -> dict | None:
        release = self.api(f"releases/tags/{tag}", missing=True)
        if release is not None:
            return release
        # The by-tag endpoint documents published releases. Drafts must also be
        # found through the authenticated, paginated inventory before creating one.
        output = self.gh("api", f"repos/{self.repository}/releases?per_page=100", "--paginate", "--slurp")
        matches = [item for page in json.loads(output) for item in page if item.get("tag_name") == tag]
        if len(matches) > 1:
            raise ReleaseError("More than one release has the same Windows tag.")
        return matches[0] if matches else None

    def download(self, tag: str, name: str, destination: Path) -> Path:
        self.gh("release", "download", tag, "--repo", self.repository, "--pattern", name,
                "--dir", str(destination))
        return destination / name

    def create_tag(self, tag: str, source_sha: str) -> None:
        try:
            self.gh("api", f"repos/{self.repository}/git/refs", "--method", "POST",
                    "-f", f"ref=refs/tags/{tag}", "-f", f"sha={source_sha}")
        except ReleaseError:
            # Another authorized run may have created the same immutable tag.
            if self.tag_head(tag) != source_sha:
                raise

    def create_draft(self, tag: str, version: str, notes: Path) -> None:
        self.gh("release", "create", tag, "--repo", self.repository, "--verify-tag", "--draft",
                "--prerelease", "--title", f"BiliPai Windows {version}", "--notes-file", str(notes))

    def draft(self, tag: str, value: bool) -> None:
        self.gh("release", "edit", tag, "--repo", self.repository, f"--draft={str(value).lower()}")

    def upload(self, tag: str, paths: list[Path]) -> None:
        if paths:
            # Never use --clobber: retrying cannot replace any existing asset.
            self.gh("release", "upload", tag, *(str(path) for path in paths), "--repo", self.repository)


def verify_tag(github: GitHub, tag: str, source_sha: str) -> bool:
    actual = github.tag_head(tag)
    if actual is not None and actual != source_sha:
        raise ReleaseError("Windows tag already points to another source commit. Increment windowsRevision; existing assets are retained.")
    return actual is not None


def remote_digest(github: GitHub, tag: str, asset: dict, directory: Path) -> str:
    digest = asset.get("digest")
    if isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        return digest.removeprefix("sha256:")
    # Old GitHub assets without server hashes still require content verification.
    path = github.download(tag, asset["name"], directory)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def publication_status(github: GitHub, manifest: dict, version: str, source_sha: str) -> dict:
    validate_sha(source_sha)
    archive, sidecar, source = asset_names(version)
    tag = "Windows-v" + version
    tag_exists = verify_tag(github, tag, source_sha)
    release = github.release(tag)
    assets = inventory(release)
    missing = [name for name in (archive, sidecar, source) if name not in assets]
    if release is not None and not tag_exists:
        raise ReleaseError("A release exists without its immutable source tag.")
    with tempfile.TemporaryDirectory(prefix="bilipai-release-check-") as temporary:
        directory = Path(temporary)
        declared = None
        if source in assets:
            declared = validate_evidence(json.loads(github.download(tag, source, directory).read_text(encoding="utf-8-sig")),
                                         manifest, version, source_sha)
        if sidecar in assets:
            sidecar_digest = checksum(github.download(tag, sidecar, directory).read_bytes(), archive)
            if declared is not None and declared != sidecar_digest:
                raise ReleaseError("Published checksum and source evidence disagree.")
            declared = sidecar_digest
        if archive in assets and declared is not None:
            if remote_digest(github, tag, assets[archive], directory) != declared:
                raise ReleaseError("Published ZIP differs from its checksum/source evidence.")
    complete = release is not None and not release.get("draft") and not missing
    return {"status": "published" if complete else "publicationRequired", "publicationNeeded": not complete,
            "repository": github.repository, "version": version, "tag": tag, "sourceSha": source_sha,
            "missingAssets": missing, "draft": bool(release and release.get("draft"))}


def publish(github: GitHub, manifest: dict, version: str, source_sha: str,
            assets_root: Path, gate: dict) -> dict:
    validate_gates(gate)
    if gate.get("windowsSourceCommit") != validate_sha(source_sha):
        raise ReleaseError("Release gate does not match the exact Windows source commit.")
    archive_name, checksum_name, source_name = asset_names(version)
    archive = assets_root / archive_name
    if not archive.is_file() or not zipfile.is_zipfile(archive):
        raise ReleaseError("The exact versioned Windows ZIP is missing or invalid.")
    local_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if checksum((assets_root / checksum_name).read_bytes(), archive_name) != local_digest:
        raise ReleaseError("Local Windows ZIP checksum mismatch.")
    if gate.get("windowsVersion") != version or gate.get("portableZipSha256") != local_digest:
        raise ReleaseError("Release gate does not match the checked Windows package version and SHA-256.")
    status = publication_status(github, manifest, version, source_sha)
    if not status["publicationNeeded"]:
        return status
    tag = status["tag"]
    release = github.release(tag)
    assets = inventory(release)
    with tempfile.TemporaryDirectory(prefix="bilipai-release-publish-") as temporary:
        directory = Path(temporary)
        selected_archive, digest = archive, local_digest
        if archive_name in assets:
            # Builds can differ due to ZIP timestamps. Preserve the reviewed package.
            selected_archive = github.download(tag, archive_name, directory)
            digest = hashlib.sha256(selected_archive.read_bytes()).hexdigest()
            if not zipfile.is_zipfile(selected_archive):
                raise ReleaseError("Existing Windows ZIP is invalid; it is retained for manual review.")
            server_digest = assets[archive_name].get("digest")
            if server_digest is not None and server_digest != "sha256:" + digest:
                raise ReleaseError("Existing Windows ZIP does not match the GitHub asset digest.")
            if digest != local_digest:
                if source_name not in assets:
                    raise ReleaseError("Existing Windows ZIP lacks release evidence for its exact bytes; verify that ZIP or increment windowsRevision.")
                # A fresh build's tests cannot attest another archive, even if its source tag is the same.
                remote_evidence = json.loads(github.download(tag, source_name, directory).read_text(encoding="utf-8-sig"))
                validate_evidence(remote_evidence, manifest, version, source_sha, digest)
        desired_evidence = evidence(manifest, version, source_sha, digest, gate)
        if checksum_name in assets:
            existing_checksum = github.download(tag, checksum_name, directory)
            if checksum(existing_checksum.read_bytes(), archive_name) != digest:
                raise ReleaseError("Existing checksum prevents replacing this package with different bytes; increment windowsRevision.")
        else:
            (directory / checksum_name).write_bytes(f"{digest}  {archive_name}\n".encode("ascii"))
        if source_name in assets:
            value = json.loads(github.download(tag, source_name, directory).read_text(encoding="utf-8-sig"))
            validate_evidence(value, manifest, version, source_sha, digest)
        else:
            (directory / source_name).write_text(json.dumps(desired_evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        notes = directory / "release-notes.md"
        notes.write_text(f"Native Windows x64 portable build {version}.\n\n"
                         f"Upstream: {manifest['upstreamTag']} at {manifest['upstreamCommit']}.\n"
                         f"Windows source commit: {source_sha}.\n\n"
                         "Updater safety tests, Windows unit tests, guest feed/search/DASH backend smoke, and packaged native player checks passed.\n\n"
                         "Extract the complete ZIP and run BiliPai Windows.exe. Verify the adjacent SHA-256 file before updating.\n\n"
                         "Windows feature coverage:\n" + json.dumps(manifest.get("featureCoverage", {}), ensure_ascii=False, indent=2) +
                         "\n\nNew Android features require a corresponding Windows implementation. Previous successful releases remain available.\n", encoding="utf-8")
        # Resolve/create the tag independently of Release existence, before publishing assets.
        if not verify_tag(github, tag, source_sha):
            github.create_tag(tag, source_sha)
        if not verify_tag(github, tag, source_sha):
            raise ReleaseError("Could not establish the exact Windows source tag.")
        if release is None:
            github.create_draft(tag, version, notes)
        elif not release.get("draft"):
            github.draft(tag, True)
        paths = {archive_name: selected_archive, checksum_name: directory / checksum_name,
                 source_name: directory / source_name}
        github.upload(tag, [paths[name] for name in (archive_name, checksum_name, source_name) if name not in assets])
        verified = publication_status(github, manifest, version, source_sha)
        if verified["missingAssets"]:
            raise ReleaseError("Release remains draft because an uploaded asset is missing.")
        github.draft(tag, False)
        result = publication_status(github, manifest, version, source_sha)
        if result["publicationNeeded"]:
            raise ReleaseError("Release did not become public after attachment verification.")
        return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--publish", action="store_true")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-sha")
    parser.add_argument("--assets", type=Path, default=Path("release-assets"))
    parser.add_argument("--gate", type=Path, default=Path("release-evidence/release-gate.json"))
    args = parser.parse_args()
    try:
        repo = args.repo.resolve()
        remote_repository = sync.publication_check(repo)
        if remote_repository.lower() != valid_repository(args.repository).lower():
            raise ReleaseError("Publication repository differs from the checkout's personal Windows remote.")
        if sync.git(repo, "status", "--porcelain", "--untracked-files=all"):
            raise ReleaseError("Commit reviewed Windows sources first; a dirty checkout cannot attest an exact source SHA.")
        manifest = sync.read_manifest(repo)
        head = sync.git(repo, "rev-parse", "HEAD")
        source_sha = validate_sha(args.source_sha or head)
        if source_sha != head:
            raise ReleaseError("The checkout differs from the required Windows source commit.")
        github = GitHub(args.repository, repo)
        version = version_for(repo, manifest)
        result = (publication_status(github, manifest, version, source_sha) if args.check else
                  publish(github, manifest, version, source_sha, args.assets.resolve(),
                          json.loads(args.gate.read_text(encoding="utf-8-sig"))))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ReleaseError, OSError, ValueError, KeyError, TypeError) as error:
        print(f"Windows publication stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

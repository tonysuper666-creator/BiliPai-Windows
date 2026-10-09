#!/usr/bin/env python3
"""Retain one reviewed builder image in own GHCR; CI-only, no container execution."""
from pathlib import Path
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

SPEC_SHA256 = "08710993da3ed27cf7288446d7a3f0f58cb655bb70b9e261a41f56d1827af4cd"
OWN_REPOSITORY = "tonysuper666-creator/BiliPai-Windows"
SOURCE_REPOSITORY = "shinchiro/archlinux"
TARGET_REPOSITORY = "tonysuper666-creator/bilipai-windows-builder"
EXPECTED_DIGEST = "sha256:c7dffe77b57d98b10e327dde12d3977faf4cb90aa7cb4f5eeac4e9d68d724239"
ALLOWED_REDIRECT_HOSTS = {"ghcr.io", "pkg-containers.githubusercontent.com"}

class RetainError(Exception):
    pass

def require(value, message):
    if not value:
        raise RetainError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result

def parsed(data):
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique)

def ci_guard():
    require(len(sys.argv) == 1, "free-form CLI inputs are forbidden")
    require(os.environ.get("GITHUB_ACTIONS") == "true", "GitHub Actions execution required")
    require(os.environ.get("GITHUB_SERVER_URL") == "https://github.com" and
            os.environ.get("GITHUB_API_URL") == "https://api.github.com", "public GitHub host required")
    require(os.environ.get("GITHUB_REPOSITORY") == OWN_REPOSITORY and
            os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
            os.environ.get("GITHUB_REF_TYPE") == "tag", "own manual tag context required")
    tag = os.environ.get("GITHUB_REF_NAME", "")
    require(re.fullmatch(r"rtx-source-[A-Za-z0-9][A-Za-z0-9._-]{0,100}", tag) and
            os.environ.get("GITHUB_REF") == "refs/tags/" + tag, "reviewed source tag required")
    commit = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "complete source commit required")
    event_path = Path(os.environ.get("GITHUB_EVENT_PATH", ""))
    require(event_path.is_file() and not event_path.is_symlink() and event_path.stat().st_size <= 1024 * 1024,
            "bounded runner event required")
    event_bytes = event_path.read_bytes()
    require(len(event_bytes) <= 1024 * 1024, "event exceeds bound")
    event = parsed(event_bytes)
    repository = event.get("repository", {})
    require(repository.get("full_name") == OWN_REPOSITORY and
            type(repository.get("private")) is bool and repository["private"] is False,
            "own public repository required")
    inputs = event.get("inputs", {})
    acknowledgement = inputs.get("acknowledge_source_build")
    require(inputs.get("producer_variant") == "retain-builder-only" and
            ((type(acknowledgement) is str and acknowledgement == "true") or
             (type(acknowledgement) is bool and acknowledgement is True)),
            "explicit retention-only acknowledgement required")
    actor = os.environ.get("GITHUB_ACTOR", "")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}", actor), "valid GitHub actor required")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    require(re.fullmatch(r"[1-9][0-9]{0,19}", run_id), "real workflow run identifier required")
    workspace = Path(os.environ.get("GITHUB_WORKSPACE", ""))
    require(workspace.is_dir() and not workspace.is_symlink(), "actual checked-out workspace required")
    # This read-only command has no credential argument, stdin or inherited token/GIT_* environment.
    git_env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"),
               "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
               "GIT_TERMINAL_PROMPT": "0"}
    try:
        observed = subprocess.run(["git", "--no-optional-locks", "-C", str(workspace),
                                   "rev-parse", "--verify", "HEAD^{commit}"],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  timeout=15, check=False, env=git_env)
    except (OSError, subprocess.TimeoutExpired):
        raise RetainError("actual checkout identity query failed") from None
    require(observed.returncode == 0 and len(observed.stdout) <= 128 and
            observed.stdout.strip() == commit.encode("ascii"), "actual checkout HEAD differs from GITHUB_SHA")
    return tag, commit, actor, int(run_id)

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        url = urllib.parse.urlsplit(new_url)
        require(url.scheme == "https" and url.hostname in ALLOWED_REDIRECT_HOSTS,
                "unexpected registry redirect")
        safe_headers = {key: value for key, value in request.headers.items()
                        if key.lower() != "authorization"}
        return urllib.request.Request(new_url, headers=safe_headers, method=request.get_method())

class Registry:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), SafeRedirect())

    def request(self, url, headers=None, method="GET", limit=65536):
        require(urllib.parse.urlsplit(url).scheme == "https" and
                urllib.parse.urlsplit(url).hostname == "ghcr.io", "fixed registry host required")
        request_headers = {"User-Agent": "BiliPai-Windows-reviewed-builder-retention"}
        request_headers.update(headers or {})
        try:
            with self.opener.open(urllib.request.Request(url, headers=request_headers, method=method), timeout=30) as response:
                data = b"" if method == "HEAD" else response.read(limit + 1)
                require(len(data) <= limit, "registry metadata exceeds bound")
                return response.status, dict(response.headers), data
        except urllib.error.HTTPError as error:
            data = error.read(limit + 1)
            require(len(data) <= limit, "registry error exceeds bound")
            return error.code, dict(error.headers), data
        except (urllib.error.URLError, TimeoutError):
            raise RetainError("registry request failed; no absence inferred") from None

    def token(self, repository, actor=None, github_token=None):
        require(repository in (SOURCE_REPOSITORY, TARGET_REPOSITORY), "fixed repository scope required")
        headers = {}
        if github_token is not None:
            credential = base64.b64encode((actor + ":" + github_token).encode()).decode()
            headers["Authorization"] = "Basic " + credential
        url = "https://ghcr.io/token?" + urllib.parse.urlencode({"service": "ghcr.io", "scope": "repository:" + repository + ":pull"})
        status, _, data = self.request(url, headers)
        require(status == 200, "registry pull authorization failed (HTTP " + str(status) + ")")
        value = parsed(data).get("token")
        require(type(value) is str and value, "registry token response invalid")
        return value

    def manifest(self, repository, reference, token, missing_ok=False):
        headers = {"Authorization": "Bearer " + token,
                   "Accept": "application/vnd.oci.image.manifest.v1+json"}
        status, response_headers, data = self.request("https://ghcr.io/v2/" + repository + "/manifests/" + reference, headers)
        if status == 404 and missing_ok:
            errors = parsed(data).get("errors", [])
            require(errors and all(row.get("code") in ("MANIFEST_UNKNOWN", "NAME_UNKNOWN") for row in errors),
                    "unqualified missing-image response")
            return None
        require(status == 200, "manifest unavailable (HTTP " + str(status) + ")")
        return response_headers, data

    def verify(self, repository, reference, token, spec):
        result = self.manifest(repository, reference, token)
        headers, data = result
        require(len(data) == spec["manifest"]["bytes"] and "sha256:" + sha(data) == spec["manifest"]["digest"],
                "exact manifest bytes differ")
        header_digest = next((value for key, value in headers.items() if key.lower() == "docker-content-digest"), None)
        require(header_digest == spec["manifest"]["digest"], "manifest response digest differs")
        manifest = parsed(data)
        require(type(manifest.get("schemaVersion")) is int and manifest["schemaVersion"] == 2 and
                manifest.get("mediaType") == spec["manifest"]["mediaType"] and
                manifest.get("config") == {"digest": spec["config"]["digest"], "size": spec["config"]["bytes"],
                                          "mediaType": spec["config"]["mediaType"]} and
                manifest.get("layers") == spec["layers"], "exact config/layer descriptors differ")
        cfg = spec["config"]
        status, _, config_data = self.request("https://ghcr.io/v2/" + repository + "/blobs/" + cfg["digest"],
                                              {"Authorization": "Bearer " + token}, limit=65536)
        require(status == 200 and len(config_data) == cfg["bytes"] and "sha256:" + sha(config_data) == cfg["digest"],
                "exact configuration bytes differ")
        config = parsed(config_data)
        require(config.get("os") == "linux" and config.get("architecture") == "amd64", "linux/amd64 config required")
        for layer in spec["layers"]:
            status, layer_headers, _ = self.request("https://ghcr.io/v2/" + repository + "/blobs/" + layer["digest"],
                                                     {"Authorization": "Bearer " + token}, method="HEAD")
            length = next((value for key, value in layer_headers.items() if key.lower() == "content-length"), "")
            require(status == 200 and length.isdigit() and int(length) == layer["size"], "retained layer unavailable or wrong size")
        return True

def command(args, timeout, input_bytes=None):
    # Every credential is stdin/authfile only. No child diagnostic containing credentials is emitted.
    env = dict(os.environ)
    env.pop("GITHUB_TOKEN", None)
    env.pop("REGISTRY_AUTH_FILE", None)
    try:
        result = subprocess.run(args, input=input_bytes, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=timeout, check=False, env=env)
    except (OSError, subprocess.TimeoutExpired):
        raise RetainError("registry transport unavailable or timed out") from None
    require(result.returncode == 0, "registry transport failed (exit " + str(result.returncode) + ")")

def main():
    tag, commit, actor, run_id = ci_guard()  # no token read/network occurs before the complete CI guard
    spec_path = Path(__file__).with_name("retain-builder-image-source.json")
    require(spec_path.is_file() and not spec_path.is_symlink() and spec_path.stat().st_size <= 16384, "bounded fixed source inventory required")
    data = spec_path.read_bytes()
    require(len(data) <= 16384 and sha(data) == SPEC_SHA256, "complete static source inventory differs")
    spec = parsed(data)
    require(spec["schema"] == 1 and spec["sourceRegistry"] == "ghcr.io" and
            spec["sourceRepository"] == SOURCE_REPOSITORY and spec["targetRepository"] == TARGET_REPOSITORY and
            spec["manifest"]["digest"] == EXPECTED_DIGEST and spec["retentionTag"] == "source-" + EXPECTED_DIGEST[7:] and
            spec["platform"] == {"os": "linux", "architecture": "amd64"}, "fixed source/destination inventory required")
    github_token = os.environ.get("GITHUB_TOKEN", "")
    require(github_token and github_token == github_token.strip() and "\n" not in github_token and "\r" not in github_token,
            "ephemeral workflow token required")
    registry = Registry()
    # Retained own bytes must remain usable even when the old upstream image disappears.
    target_token = registry.token(TARGET_REPOSITORY, actor, github_token)
    existing = registry.manifest(TARGET_REPOSITORY, spec["retentionTag"], target_token, missing_ok=True)
    reused = existing is not None
    if reused:
        # A wrong tag is a hard conflict; never overwrite it or infer success from metadata alone.
        registry.verify(TARGET_REPOSITORY, spec["retentionTag"], target_token, spec)
    else:
        # Only a genuine missing own tag requires upstream availability and a new transfer.
        source_token = registry.token(SOURCE_REPOSITORY)
        registry.verify(SOURCE_REPOSITORY, EXPECTED_DIGEST, source_token, spec)
        require(os.environ.get("RUNNER_TEMP"), "owned runner temp required")
        with tempfile.TemporaryDirectory(prefix="bilipai-builder-retain-", dir=os.environ["RUNNER_TEMP"]) as directory:
            owned = Path(directory)
            source_auth = owned / "anonymous-source-auth.json"
            source_auth.write_text('{"auths":{}}', encoding="utf-8")
            target_auth = owned / "workflow-target-auth.json"
            command(["skopeo", "login", "--authfile", str(target_auth), "--username", actor,
                     "--password-stdin", "ghcr.io"], 60, (github_token + "\n").encode())
            command(["skopeo", "copy", "--all", "--preserve-digests", "--src-authfile", str(source_auth),
                     "--dest-authfile", str(target_auth), "docker://ghcr.io/" + SOURCE_REPOSITORY + "@" + EXPECTED_DIGEST,
                     "docker://ghcr.io/" + TARGET_REPOSITORY + ":" + spec["retentionTag"]], 1400)
    # Renew from the SAME workflow credential after transfer; public/anonymously readable metadata is insufficient.
    target_token = registry.token(TARGET_REPOSITORY, actor, github_token)
    registry.verify(TARGET_REPOSITORY, spec["retentionTag"], target_token, spec)
    registry.verify(TARGET_REPOSITORY, EXPECTED_DIGEST, target_token, spec)
    print(json.dumps({"schema": 1, "status": "retained-builder-verified", "workflowRunId": run_id,
                      "sourceTag": tag, "sourceCommit": commit, "sourceInventorySha256": SPEC_SHA256,
                      "sourceImage": "ghcr.io/" + SOURCE_REPOSITORY + "@" + EXPECTED_DIGEST,
                      "retainedImage": "ghcr.io/" + TARGET_REPOSITORY + "@" + EXPECTED_DIGEST,
                      "retentionTag": spec["retentionTag"], "configDigest": spec["config"]["digest"],
                      "layers": len(spec["layers"]), "compressedLayerBytes": sum(row["size"] for row in spec["layers"]),
                      "reusedExactOwnTag": reused, "ownAuthenticatedReadback": True,
                      "nativeBuildExecuted": False, "imageExecuted": False}, separators=(",", ":")))

if __name__ == "__main__":
    try:
        main()
    except RetainError as error:
        # RetainError text is constructed from fixed messages and numeric HTTP/exit codes only.
        print("Builder retention failed closed: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (ValueError, KeyError, TypeError, OSError):
        # Never echo arbitrary response/token/argv/environment or filesystem exception details.
        print("Builder retention failed closed; invalid metadata or transport state.", file=sys.stderr)
        sys.exit(1)

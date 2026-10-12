"""Pinned original PGC metadata/preparation overlay; no runtime authority."""
from pathlib import Path
import hashlib
import json
import os
COMMIT = "a69d4181a073aa3207def3fca43318dcf7ab2c70"
MANIFEST_SHA256 = "bd4aff1432a9c6cf20e9bd1d55b958f9fc746c258001ef1d203f00d32f98b575"
OUTPUTS = {'com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerViewModel.kt', 'com/android/purebilibili/core/player/DesktopOriginalBangumiBasePlayerViewModel.kt'}
def wide(path):
    path = Path(path).resolve()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" and not str(path).startswith("\\\\?\\") else path
def sha(raw):
    return hashlib.sha256(raw).hexdigest()
def require(value, message):
    if not value:
        raise ValueError(message)
def fixed():
    root = Path(__file__).resolve().parents[1] / "upstream-slices" / "v034-pgc-initial-detail"
    raw = wide(root / "manifest.json").read_bytes()
    require(sha(raw) == MANIFEST_SHA256, "PGC initial detail manifest changed")
    manifest = json.loads(raw)
    require(manifest["schemaVersion"] == 1 and manifest["upstreamCommit"] == COMMIT,
            "PGC initial detail provenance changed")
    require(set(manifest["outputs"]) == OUTPUTS, "PGC initial detail output set changed")
    require(len(manifest["sources"]) == 2 and
            {row["file"] for row in manifest["sources"]} ==
            {"BasePlayerViewModel.kt", "BangumiPlayerViewModel.kt"}, "PGC whole source set changed")
    for row in manifest["sources"]:
        value = wide(root / row["file"]).read_bytes()
        require(len(value) == row["bytes"] and sha(value) == row["sha256"] and
                hashlib.sha1(b"blob " + str(len(value)).encode() + b"\0" + value).hexdigest() == row["gitBlob"],
                "PGC whole original source changed: " + row["file"])
    return manifest
def apply_and_record(path, body, output):
    if path not in OUTPUTS:
        return body
    manifest = fixed()
    recipe = manifest["outputs"][path]
    original = body.encode("utf-8")
    require(sha(original) == recipe["beforeSha256LF"], "PGC initial detail producer input changed: " + path)
    value = original
    for edit in recipe["edits"]:
        offset = edit["offsetBytes"]
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        require(value[offset:offset + len(before)] == before, "PGC initial detail exact seam changed: " + edit["name"])
        value = value[:offset] + after + value[offset + len(before):]
    require(sha(value) == recipe["afterSha256LF"], "PGC initial detail producer output changed: " + path)
    inverse = value
    for edit in reversed(recipe["edits"]):
        offset = edit["offsetBytes"]
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        require(inverse[offset:offset + len(after)] == after, "PGC initial detail inverse seam changed")
        inverse = inverse[:offset] + before + inverse[offset + len(after):]
    require(inverse == original, "PGC initial detail whole producer inverse changed")
    proof = {"schemaVersion": 1, "upstreamCommit": COMMIT, "manifestSha256": MANIFEST_SHA256,
             "path": path, "sources": manifest["sources"], "recipe": recipe}
    destination = wide(Path(output) / (Path(path).stem + ".pgc-initial-detail-source.json"))
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return value.decode("utf-8")

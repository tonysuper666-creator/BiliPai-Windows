"""Pinned partial reuse of the full original a69 PGC audio-download method."""
from pathlib import Path
import hashlib
import json
import os
COMMIT = "a69d4181a073aa3207def3fca43318dcf7ab2c70"
MANIFEST_SHA256 = "443ed266166886cc0481b59e725daeda06caad9df28d3fcce0da5114f81161f4"
OUTPUT = "com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerViewModel.kt"
def wide(path):
    path = Path(path).resolve()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" and not str(path).startswith("\\\\?\\") else path

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def require(value, message):
    if not value:
        raise ValueError(message)

def apply_edits(value, edits):
    original = value
    for edit in edits:
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        offset = edit["offsetBytes"]
        require(sha(value) == edit["beforeWholeSha256"] and value[offset:offset + len(before)] == before,
                "PGC audio forward seam changed: " + edit["name"])
        value = value[:offset] + after + value[offset + len(before):]
        require(sha(value) == edit["afterWholeSha256"], "PGC audio forward output changed")
    inverse = value
    for edit in reversed(edits):
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        offset = edit["offsetBytes"]
        require(sha(inverse) == edit["afterWholeSha256"] and inverse[offset:offset + len(after)] == after,
                "PGC audio inverse seam changed")
        inverse = inverse[:offset] + before + inverse[offset + len(after):]
        require(sha(inverse) == edit["beforeWholeSha256"], "PGC audio inverse output changed")
    require(inverse == original, "PGC audio whole inverse changed")
    return value

def apply_and_record(path, body, output):
    if path != OUTPUT:
        return body
    desktop = Path(__file__).resolve().parents[1]
    raw_manifest = wide(desktop / "upstream-slices/v034-pgc-initial-detail/audio-download-manifest.json").read_bytes()
    require(sha(raw_manifest) == MANIFEST_SHA256, "PGC audio manifest changed")
    manifest = json.loads(raw_manifest)
    require(manifest["schemaVersion"] == 1 and manifest["upstreamCommit"] == COMMIT and
            manifest["partialReuse"] is True and manifest["output"] == OUTPUT, "PGC audio partial provenance changed")
    source = manifest["source"]
    raw_source = wide(desktop.parent / source["localPath"]).read_bytes()
    require(len(raw_source) == source["bytesRaw"] and sha(raw_source) == source["sha256Raw"] and
            hashlib.sha1(b"blob " + str(len(raw_source)).encode() + b"\0" + raw_source).hexdigest() == source["gitBlob"],
            "PGC audio whole original source changed")
    normalized = raw_source.replace(b"\r\n", b"\n")
    pin = manifest["originalMethod"]
    original_method = normalized[pin["offsetBytesLF"]:pin["offsetBytesLF"] + pin["bytesLF"]]
    require(pin["completeOriginalMethod"] is True and sha(original_method) == pin["sha256LF"],
            "PGC audio complete original method changed")
    adapted = apply_edits(original_method, manifest["methodAdaptations"])
    require(sha(adapted) == manifest["adaptedMethodSha256LF"], "PGC audio adapted method changed")
    original = body.encode("utf-8")
    require(sha(original) == manifest["beforeSha256LF"], "PGC audio producer input changed")
    require(len(manifest["edits"]) == 1 and manifest["edits"][0]["after"] ==
            manifest["compatibilityWrapper"] + adapted.decode("utf-8") + "\n" + manifest["edits"][0]["before"],
            "PGC audio producer must insert its whole reused original method")
    value = apply_edits(original, manifest["edits"])
    require(sha(value) == manifest["afterSha256LF"], "PGC audio producer output changed")
    target = wide(Path(output) / "DesktopOriginalBangumiPlayerViewModel.pgc-audio-download-source.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"schemaVersion": 1, "partialReuse": True, "manifestSha256": MANIFEST_SHA256,
        "source": source, "originalMethod": pin, "adaptedMethodSha256LF": sha(adapted),
        "beforeSha256LF": sha(original), "afterSha256LF": sha(value), "wholeInverseVerified": True},
        ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return value.decode("utf-8")

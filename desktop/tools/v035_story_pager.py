"""Pinned v035 external recommendation consumers; the existing sole Pager emits them."""
from pathlib import Path
import hashlib
import json
import os

COMMIT = "46eaf277e83512061785be9759d1f07aba3c4839"
MANIFEST_SHA256 = "fe4fa6babb5a08d13e7d35e062e492dfd4b8d718bc7d2ce6580667ee84fffdb0"
PAGER = "com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt"


def wide(path):
    path = Path(path).resolve()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" and not str(path).startswith("\\\\?\\") else path


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def need(value, message):
    if not value:
        raise ValueError(message)


def fixed():
    root = Path(__file__).resolve().parents[1] / "upstream-slices/v035-story-pager"
    raw = wide(root / "manifest.json").read_bytes()
    need(sha(raw) == MANIFEST_SHA256, "Story Pager manifest changed")
    manifest = json.loads(raw)
    need(manifest["schemaVersion"] == 1 and manifest["upstreamCommit"] == COMMIT,
         "Story Pager provenance changed")
    pin = manifest["source"]
    need(pin["file"] == "PortraitVideoPager.kt", "Story Pager original path changed")
    raw = wide(root / pin["file"]).read_bytes()
    need(len(raw) == pin["bytesRaw"] and sha(raw) == pin["sha256Raw"] and
         hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == pin["gitBlob"],
         "Story Pager whole original changed")
    original = raw.replace(b"\r\n", b"\n")
    for consumer in manifest["reusedConsumers"]:
        block = original[consumer["offsetBytesLF"]:consumer["offsetBytesLF"] + consumer["bytesLF"]]
        need(block == consumer["original"].encode("utf-8") and sha(block) == consumer["sha256LF"],
             "Story Pager complete original consumer changed: " + consumer["name"])
        adapted = block.decode("utf-8")
        if consumer["name"] == "complete-original-parent-list-effect-preserves-external-order":
            before = "            enabled = onlyVerticalRecommendations,\n        )"
            after = "            enabled = onlyVerticalRecommendations,\n        isVerticalVideo = platform.requests::isVerticalVideo,\n)"
            need(adapted.count(before) == 1, "Story Pager owned classifier anchor changed")
            adapted = adapted.replace(before, after, 1)
            need(adapted.count(after) == 1 and adapted.replace(after, before, 1) == block.decode("utf-8"),
                 "Story Pager original classifier whole inverse changed")
        need(adapted == consumer["adapted"] and sha(adapted.encode("utf-8")) == consumer["adaptedSha256LF"],
             "Story Pager original consumer adaptation changed")
    return manifest


def apply_edits(original, edits):
    value = original
    for edit in edits:
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        offset = edit["offsetBytes"]
        need(before and value.count(before) == 1 and sha(value) == edit["beforeWholeSha256"] and
             value[offset:offset + len(before)] == before,
             "Story Pager exact producer seam changed: " + edit["name"])
        value = value[:offset] + after + value[offset + len(before):]
        need(sha(value) == edit["afterWholeSha256"], "Story Pager exact producer output changed")
    inverse = value
    for edit in reversed(edits):
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        offset = edit["offsetBytes"]
        need(sha(inverse) == edit["afterWholeSha256"] and inverse[offset:offset + len(after)] == after,
             "Story Pager exact inverse seam changed")
        inverse = inverse[:offset] + before + inverse[offset + len(after):]
        need(sha(inverse) == edit["beforeWholeSha256"], "Story Pager whole inverse changed")
    need(inverse == original, "Story Pager complete producer inverse changed")
    return value


def apply_and_record(path, body, output):
    if path != PAGER:
        return body, None
    manifest = fixed()
    recipe = manifest["output"]
    need(recipe["path"] == PAGER, "Story Pager output owner changed")
    original = body.encode("utf-8")
    need(sha(original) == recipe["beforeSha256LF"], "Story Pager retained final producer input changed")
    value = apply_edits(original, recipe["edits"])
    need(sha(value) == recipe["afterSha256LF"], "Story Pager retained final output changed")
    for consumer in manifest["reusedConsumers"]:
        need(value.count(consumer["adapted"].encode("utf-8")) == 1,
             "Story Pager must consume the complete original block: " + consumer["name"])
    proof = dict(upstreamCommit=COMMIT, manifestSha256=MANIFEST_SHA256,
                 partialReuse=True, wholeOriginal=manifest["source"],
                 completeConsumers=manifest["reusedConsumers"], recipe=recipe,
                 wholeInverseVerified=True)
    destination = wide(Path(output) / "PortraitVideoPager.kt.v035-story-source.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return value.decode("utf-8"), proof

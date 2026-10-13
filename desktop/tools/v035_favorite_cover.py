"""Pinned v035 favorite cover leaves/methods; only the existing Favorites producer emits them."""
from pathlib import Path
import hashlib
import json
import os
COMMIT = "46eaf277e83512061785be9759d1f07aba3c4839"
MANIFEST_SHA256 = "fb6ffae1aded48644cf1e36f9a309cb0717f9a67c588ac08100a5a9cbbe5b14c"

def wide(path):
    path = Path(path).resolve()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" and not str(path).startswith("\\\\?\\") else path

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def need(value, message):
    if not value:
        raise ValueError(message)

def fixed():
    root = Path(__file__).resolve().parents[1] / "upstream-slices/v035-favorite-cover"
    raw = wide(root / "manifest.json").read_bytes()
    need(sha(raw) == MANIFEST_SHA256, "Favorite cover manifest changed")
    manifest = json.loads(raw)
    need(manifest["schemaVersion"] == 1 and manifest["upstreamCommit"] == COMMIT,
         "Favorite cover provenance changed")
    sources = {}
    for row in manifest["sources"]:
        raw = wide(root / row["file"]).read_bytes()
        need(len(raw) == row["bytesRaw"] and sha(raw) == row["sha256Raw"] and
             hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == row["gitBlob"],
             "Favorite cover whole original changed: " + row["file"])
        sources[row["file"]] = raw.replace(b"\r\n", b"\n")
    return manifest, sources

def apply_edits(original, edits):
    value = original
    for edit in edits:
        offset = edit["offsetBytes"]
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        need(sha(value) == edit["beforeWholeSha256"] and value[offset:offset + len(before)] == before,
             "Favorite cover exact forward seam changed: " + edit["name"])
        value = value[:offset] + after + value[offset + len(before):]
        need(sha(value) == edit["afterWholeSha256"], "Favorite cover forward output changed")
    inverse = value
    for edit in reversed(edits):
        offset = edit["offsetBytes"]
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        need(sha(inverse) == edit["afterWholeSha256"] and inverse[offset:offset + len(after)] == after,
             "Favorite cover inverse seam changed")
        inverse = inverse[:offset] + before + inverse[offset + len(after):]
        need(sha(inverse) == edit["beforeWholeSha256"], "Favorite cover inverse output changed")
    need(inverse == original, "Favorite cover whole inverse changed")
    return value

def selected_source(rel):
    manifest, sources = fixed()
    pin = next(row for row in manifest["sources"] if row["file"] == Path(rel).name + ".kt")
    recipe = manifest["leafs"][rel]
    original = sources[pin["file"]]
    need(sha(original) == recipe["beforeSha256LF"], "Favorite cover leaf input changed")
    value = apply_edits(original, recipe["edits"])
    need(sha(value) == recipe["afterSha256LF"], "Favorite cover leaf output changed")
    row = dict(path=pin["originalPath"], upstreamCommit=COMMIT, sha256LfUtf8=sha(original),
        gitBlob=pin["gitBlob"], favoriteCoverGeneratedSource=True, wholeInverseVerified=True,
        favoriteCoverManifestSha256=MANIFEST_SHA256)
    return value.decode("utf-8"), row

def additional_leaf_sources():
    for rel in ("core/store/FavoriteFolderCoverCache", "feature/list/FavoriteFolderCoverPrefetchPolicy"):
        body, row = selected_source(rel)
        yield rel, body, row

def apply_and_record(path, body, output):
    key = "com/android/purebilibili/" + path + ".kt"
    if path not in {"feature/list/DesktopOriginalListViewModels", "feature/list/CommonListScreen"}:
        return body, None
    manifest, sources = fixed()
    recipe = manifest["outputs"][key]
    original = body.encode("utf-8")
    need(sha(original) == recipe["beforeSha256LF"], "Favorite cover retained producer input changed")
    method = manifest["partialVmReuse"]
    raw_vm = sources["ListViewModel.kt"]
    raw_block = raw_vm[method["offsetBytesLF"]:method["offsetBytesLF"] + method["bytesLF"]]
    need(sha(raw_block) == method["sha256LF"], "Favorite cover five whole original methods changed")
    adapted = apply_edits(raw_block, method["edits"])
    need(sha(adapted) == method["adaptedSha256LF"], "Favorite cover adapted whole methods changed")
    if key.endswith("DesktopOriginalListViewModels.kt"):
        need(any(edit["after"].encode("utf-8") == adapted for edit in recipe["edits"]),
             "Favorite cover must insert the complete original method block")
    callback = manifest["partialUiReuse"]
    raw_common = sources["CommonListScreen.kt"]
    raw_callback = raw_common[callback["offsetBytesLF"]:callback["offsetBytesLF"] + callback["bytesLF"]]
    need(sha(raw_callback) == callback["sha256LF"], "Favorite cover original UI callback changed")
    adapted_callback = apply_edits(raw_callback, callback["edits"])
    need(sha(adapted_callback) == callback["adaptedSha256LF"], "Favorite cover adapted UI callback changed")
    if key.endswith("CommonListScreen.kt"):
        need(any(adapted_callback.decode("utf-8") in edit["after"] for edit in recipe["edits"]),
             "Favorite cover UI must consume the exact original callback")
    value = apply_edits(original, recipe["edits"])
    need(sha(value) == recipe["afterSha256LF"], "Favorite cover retained producer output changed")
    proof = dict(upstreamCommit=COMMIT, manifestSha256=MANIFEST_SHA256, partialReuse=True,
        sources=manifest["sources"], partialVmReuse=method, recipe=recipe)
    destination = wide(Path(output) / (Path(path).name + ".v035-cover-source.json"))
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return value.decode("utf-8"), proof

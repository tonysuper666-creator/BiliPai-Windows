"""Exact v035 dynamic DTO/link-card closure on the existing sole Windows producers."""
from pathlib import Path
import hashlib
import json
import os

COMMIT = "46eaf277e83512061785be9759d1f07aba3c4839"
MANIFEST_SHA256 = "746066d49ad0d28ea46aa81706c98e7faa3e49261092843695413630337f9503"

def wide(path):
    path = Path(path).resolve()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" and not str(path).startswith("\\\\?\\") else path

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def need(value, message):
    if not value:
        raise ValueError(message)

def fixed():
    root = Path(__file__).resolve().parents[1] / "upstream-slices/v035-dynamic-link"
    raw = wide(root / "manifest.json").read_bytes()
    need(sha(raw) == MANIFEST_SHA256, "Dynamic link manifest changed")
    manifest = json.loads(raw)
    need(manifest["schemaVersion"] == 1 and manifest["upstreamCommit"] == COMMIT,
         "Dynamic link provenance changed")
    sources = {}
    need({p.name for p in root.iterdir()} == {"manifest.json", *[row["file"] for row in manifest["sources"]]},
         "Dynamic link archive leaves changed")
    for row in manifest["sources"]:
        raw = wide(root / row["file"]).read_bytes()
        need(len(raw) == row["bytesRaw"] and sha(raw) == row["sha256Raw"] and
             hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == row["gitBlob"],
             "Dynamic link whole original changed: " + row["file"])
        sources[row["file"]] = raw
    card = sources["DynamicCard.kt"].replace(b"\r\n", b"\n")
    for consumer in manifest["card"]["consumers"]:
        block = card[consumer["offsetBytesLF"]:consumer["offsetBytesLF"] + consumer["bytesLF"]]
        need(block == consumer["original"].encode("utf-8") and sha(block) == consumer["sha256LF"],
             "Dynamic link complete original consumer changed: " + consumer["name"])
        adapted = block.decode("utf-8")
        if consumer["name"] == "complete-original-reserve-override-and-link-card-slot-closure":
            before = "    val context = LocalContext.current"
            after = "    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current"
            need(adapted.count(before) == 1, "Dynamic slot original feedback owner changed")
            adapted = adapted.replace(before, after, 1)
            toast = ('                        android.widget.Toast.makeText(\n'
                     '                            context,\n'
                     '                            error.message ?: "预约操作失败",\n'
                     '                            android.widget.Toast.LENGTH_SHORT,\n'
                     '                        ).show()')
            feedback = '                        platform.showFeedback(error.message ?: "预约操作失败")'
            need(adapted.count(toast) == 1, "Dynamic slot original failure feedback changed")
            adapted = adapted.replace(toast, feedback, 1)
            need(adapted.replace(feedback, toast, 1).replace(after, before, 1) == block.decode("utf-8"),
                 "Dynamic slot complete original inverse changed")
        need(adapted == consumer["adapted"] and sha(adapted.encode("utf-8")) == consumer["adaptedSha256LF"],
             "Dynamic link complete consumer adaptation changed")
    return manifest, sources

def apply_edits(original, edits):
    value = original
    for edit in edits:
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        offset = edit["offsetBytes"]
        need(before and value.count(before) == 1 and sha(value) == edit["beforeWholeSha256"] and
             value[offset:offset + len(before)] == before, "Dynamic link exact forward seam changed")
        value = value[:offset] + after + value[offset + len(before):]
        need(sha(value) == edit["afterWholeSha256"], "Dynamic link exact output changed")
    inverse = value
    for edit in reversed(edits):
        before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
        offset = edit["offsetBytes"]
        need(sha(inverse) == edit["afterWholeSha256"] and inverse[offset:offset + len(after)] == after,
             "Dynamic link exact inverse seam changed")
        inverse = inverse[:offset] + before + inverse[offset + len(after):]
        need(sha(inverse) == edit["beforeWholeSha256"], "Dynamic link whole inverse changed")
    need(inverse == original, "Dynamic link original input inverse changed")
    return value

def adapt_card_body(body, name, output):
    if name != "DesktopOriginalDynamicCard.kt":
        return body
    manifest, _ = fixed()
    original = (body.strip() + "\n").encode("utf-8")
    recipe = manifest["card"]["body"]
    need(sha(original) == recipe["beforeSha256LF"], "Dynamic link retained card input changed")
    result = apply_edits(original, recipe["edits"])
    for consumer in manifest["card"]["consumers"]:
        need(result.count(consumer["adapted"].encode("utf-8")) == 1,
             "Dynamic card must consume the complete original block")
    target = wide(Path(output) / "DesktopOriginalDynamicCard.kt.v035-link-source.json")
    target.write_text(json.dumps(dict(upstreamCommit=COMMIT, manifestSha256=MANIFEST_SHA256,
        partialReuse=True, completeConsumers=manifest["card"]["consumers"], recipe=recipe,
        wholeInverseVerified=True), ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return result.decode("utf-8")

def emit_link_sources(output):
    manifest, sources = fixed()
    generated = []
    for name in ("DynamicLinkCardPolicy.kt", "DynamicLinkCard.kt", "PageAwareAsyncImage.kt"):
        original = sources[name].replace(b"\r\n", b"\n")
        raw = apply_edits(original, manifest["pageAware"]["edits"]) if name == "PageAwareAsyncImage.kt" else original
        package = "core/ui/components" if name == "PageAwareAsyncImage.kt" else "feature/dynamic/components"
        target = wide(Path(output) / "com/android/purebilibili" / package / name)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        generated.append(target)
    proof = wide(Path(output) / "v035-dynamic-link-leaves-source.json")
    proof.write_text(json.dumps(dict(upstreamCommit=COMMIT, manifestSha256=MANIFEST_SHA256,
        originalSources=manifest["sources"], pageAware=manifest["pageAware"],
        retiredDirectSources=manifest["retiredDirectSources"], notificationConsumerImplemented=False),
        ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return generated

def upgraded_dynamic_model(previous_raw, previous_row):
    manifest, sources = fixed()
    recipe = manifest["dynamicModel"]
    need(sha(previous_raw) == recipe["previousSource"]["sha256"],
         "Dynamic DTO previous original validation source changed")
    result = apply_edits(previous_raw, recipe["edits"])
    need(result == sources["DynamicModels.kt"], "Dynamic DTO must emit the full pinned v035 original")
    pin = next(row for row in manifest["sources"] if row["file"] == "DynamicModels.kt")
    row = dict(file=pin["file"], originalPath=pin["originalPath"], outputPath=recipe["output"],
               bytes=pin["bytesRaw"], sha256=pin["sha256Raw"], gitBlob=pin["gitBlob"],
               upstreamCommit=COMMIT, completeOriginalFile=True, referenceOnly=False,
               source66ManifestSha256=MANIFEST_SHA256, previousOriginalValidation=previous_row,
               fullOriginalReplacementInverse=recipe["edits"])
    return result, row

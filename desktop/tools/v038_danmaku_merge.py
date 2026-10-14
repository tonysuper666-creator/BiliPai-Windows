"""Fixed whole v038 merger and neutral item schema for the existing Windows renderer.

The schema must be emitted by the existing list/menu producer, not a second owner.
This module prepares exact original bodies; preparing them does not mount them.
"""
from pathlib import Path
import hashlib
import json

COMMIT = "b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14"
ARCHIVE = Path("desktop/upstream-slices/v038-danmaku-merge")
MANIFEST_SHA256 = "f60d288300586e608925ddafd4510211b5abf07b8b4f009a7b012dee043caa7d"
PINS = {
    "DanmakuMerger.kt": ("f72b887e79acfbd268ba14e49ed28ab362260a67e6ff38f0f735175418985514", 4790, "08193bae66d713359c226c43d328262a7dabffad"),
    "DanmakuModels.kt": ("7529b545cd41e8784c755d50ace9218971817263f7d8c40f8a6e439d2f98ea0f", 3639, "807aa3f53fb51a2388a87d718a13ae50c629d4b0"),
    "DanmakuMergerPolicyTest.kt": ("663cc71fa565e7ef818fdec1dd7c653a3345fe63a86d9aaae8e00b8753eae563", 4154, "e9fc8cd86064f3176fe9e59d0a71bc2924e88ba9"),
    "SettingsManager.kt": ("da70e7eb22ec483e99115882230ba409f401654f93b4a25d49e21edc350e3beb", 409271, "b9cfb558a29e1eed1b9ef715f08171ff94ba4c42"),
    "DanmakuSettingsPanel.kt": ("13701f428b5632c0ef06f5d3e7ed572ae9c71eb00c82ec9332be52f0a2b73d08", 102962, "2a7db2d79b8cbed9c59c383dadf74cb6e4546cc6"),
    "DanmakuConfig.kt": ("e98abd3942f5db84d74d1006955ad85b88b847beae46250b25d2556fc1a0efd7", 11125, "f70d0d4b427493bc105f5521bd962af5415bb2d7"),
    "AppContentColors.kt": ("755543c78bd3ba7c2685362e1c98e77daefefb28a7e7ed6e338c4644b1d4e90f", 5012, "07c6dd210e0f98f2d1a0821690b4dc4154b06af9"),
}


def sha(raw):
    return hashlib.sha256(raw.encode() if isinstance(raw, str) else raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / "manifest.json").read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError("Fixed v038 merge manifest changed")
    manifest = json.loads(raw)
    if manifest["fixedUpstreamCommit"] != COMMIT or manifest["schemaVersion"] != 1:
        raise ValueError("Unknown merge source identity")
    if {p.name for p in root.iterdir()} != set(PINS) | {"manifest.json"}:
        raise ValueError("Unexpected merge archive file set")
    rows = {row["filename"]: row for row in manifest["files"]}
    if len(rows) != len(manifest["files"]) or set(rows) != set(PINS):
        raise ValueError("Unexpected merge source set")
    result = {}
    for name, (digest, size, blob) in PINS.items():
        path = root / name
        if path.is_symlink():
            raise ValueError("Linked merge source rejected")
        raw = path.read_bytes()
        row = rows[name]
        if (row["sha256Bytes"], row["bytes"], row["gitBlob"]) != (digest, size, blob):
            raise ValueError("Fixed merge input identity changed")
        if len(raw) != size or sha(raw) != digest or hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest() != blob:
            raise ValueError("Fixed merge source bytes changed")
        result[name] = raw.replace(b"\r\n", b"\n").decode("utf-8")
    return result, manifest


def adapt(original, mappings):
    text = original
    edits = []
    for before, after in mappings:
        if text.count(before) != 1:
            raise ValueError("Original merge platform anchor changed")
        text = text.replace(before, after, 1)
        edits.append(dict(before=before, after=after))
    recovered = text
    for edit in reversed(edits):
        if recovered.count(edit["after"]) != 1:
            raise ValueError("Merge inverse anchor changed")
        recovered = recovered.replace(edit["after"], edit["before"], 1)
    if recovered != original:
        raise ValueError("Complete merge source inverse differs")
    return text, dict(upstreamCommit=COMMIT, originalSha256LF=sha(original), generatedSha256LF=sha(text),
                      edits=edits, exactFullSelectionInverse=True, sourceQualified=False, mounted=False)


def merger_source(repo):
    sources, _ = fixed_sources(repo)
    return adapt(sources["DanmakuMerger.kt"], [
        ("import android.util.Log", "import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionLog as Log"),
    ])


def neutral_item_source(repo):
    sources, _ = fixed_sources(repo)
    whole = sources["DanmakuModels.kt"]
    item = whole[:whole.index("\ndata class DanmakuWindow(")]
    body, proof = adapt(item, [
        ("import android.graphics.Bitmap", "import java.awt.image.BufferedImage as Bitmap"),
        ("import android.graphics.Typeface", "import java.awt.Font as Typeface"),
        ("import android.graphics.Path", "// Android Path belongs only to unselected mask declarations."),
    ])
    proof.update(wholeOriginalSourceSha256LF=sha(whole), selection="complete original standard-item schema",
                 emissionOwner="extract-upstream-danmaku-list-menu.py")
    return body, proof


def original_policy_test(repo):
    sources, _ = fixed_sources(repo)
    return adapt(sources["DanmakuMergerPolicyTest.kt"], [
        ("class DanmakuMergerPolicyTest", "class DesktopV038DanmakuMergerPolicyTest"),
    ])

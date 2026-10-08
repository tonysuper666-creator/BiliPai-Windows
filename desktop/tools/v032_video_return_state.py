"""One fixed v032 state subscription applied to the existing v025 Holder.

No new player, lifecycle, or coroutine owner. Preserve the original return policy.
The complete newer source is a reference, never a second generated Holder.
"""
import hashlib
import json
import os
from pathlib import Path

COMMIT = '1db8665cb9706dca44fcae0f540f2a3440721089'
MANIFEST_SHA256 = '39cd1e9d2ef642f872d64789ba7b9faf8c8fe50a0590411abc8c4f9a925b4acc'
OUTPUT = "com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt"
BEFORE = '    val playerDebugInfo by playerState.debugInfo.collectAsStateWithLifecycle()\n    val hasRenderedFirstFrameForReturn = remember(playerDebugInfo.firstFrame) {\n        playerDebugInfo.firstFrame.equals("rendered", ignoreCase = true)\n    }\n'


def wide(p):
    value = str(Path(p).absolute())
    return Path(value if os.name != "nt" or value.startswith("\\\\?\\") else "\\\\?\\" + value)


def sha(raw):
    return hashlib.sha256(raw.encode("utf8") if isinstance(raw, str) else raw).hexdigest()


def fixed(repo):
    root = Path(repo) / "desktop/upstream-slices/v032-video-return-state"
    manifest_raw = wide(root / "manifest.json").read_bytes()
    assert sha(manifest_raw) == MANIFEST_SHA256, "Fixed v032 return-state manifest changed"
    manifest = json.loads(manifest_raw)
    assert manifest["upstreamCommit"] == COMMIT and len(manifest["sources"]) == 1
    row = manifest["sources"][0]
    raw = wide(root / row["path"]).read_bytes().replace(b"\r\n", b"\n")
    assert len(raw) == row["bytesLF"] and sha(raw) == row["sha256LF"]
    assert hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == row["gitBlob"]
    source = raw.decode("utf8")
    start = source.index("    // 返回动效只需要首帧信号；")
    end = source.index("    // 全量 Success 但无首帧", start)
    return row, source[start:end]


def apply_and_record(repo, path, text, output):
    if path != OUTPUT:
        return text
    row, after = fixed(repo)
    before_all = text
    edits = []
    def replace(before, after, label):
        nonlocal text
        assert text.count(before) == 1, (path, label, text.count(before))
        text = text.replace(before, after, 1)
        edits.append(dict(label=label, before=before, after=after, count=1))
    replace("import kotlinx.coroutines.flow.first\n",
            "import kotlinx.coroutines.flow.first\nimport kotlinx.coroutines.flow.distinctUntilChanged\nimport kotlinx.coroutines.flow.map\n",
            "Original v032 imports for the return first-frame boolean flow")
    replace(BEFORE, after, "Original lifecycle-bound first-frame map and distinct subscription")
    proof = dict(path=path, upstreamCommit=COMMIT, originalSource=row,
                 beforeSha256LF=sha(before_all), afterSha256LF=sha(text), edits=edits)
    report = wide(Path(output) / "v032-return-first-frame-adaptation.json")
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf8", newline="\n")
    return text


def undo(text, proof):
    assert sha(text) == proof["afterSha256LF"]
    for edit in reversed(proof["edits"]):
        assert text.count(edit["after"]) == edit["count"]
        text = text.replace(edit["after"], edit["before"], edit["count"])
    assert sha(text) == proof["beforeSha256LF"]
    return text

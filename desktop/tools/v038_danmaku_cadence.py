"""The complete v038 deadline policy and original View frame-request members.

The existing list/menu producer is the single emission owner. Windows supplies
the event-loop ports; the original request/cancel/coalescing methods stay intact.
"""
from pathlib import Path
import hashlib
import json

COMMIT = "b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14"
ARCHIVE = Path("desktop/upstream-slices/v038-danmaku-cadence")
MANIFEST_SHA256 = "7df7676c7168326195d8d4bc6a59feb11443bbaa00e3f660742cb26c70e37086"
PINS = {
    "RenderFrameCadence.kt": ("970f728c46e908b59ba1a4d13052bbac78052ae209b45e331775d870b0b66e07", 839, "89b042d9021d1b99e5dfe6004ad5a7bcb63820a1"),
    "RenderFrameCadenceTest.kt": ("321fb8987ee6d7832ca223999557b1eab17de0d23eeb800a525669608ee16739", 1123, "cc44c939ae45a7eb9043f9b01108d41f5e0d4c6d"),
    "DanmakuView.kt": ("e084e570bb835731c8503d782580582236a222d5375e76a9482ddce3898fd013", 5040, "55abc0be863e8c8379394bf1cb75da9a6ece0810"),
}


def sha(raw):
    return hashlib.sha256(raw.encode("utf-8") if isinstance(raw, str) else raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    if root.is_symlink() or (root / "manifest.json").is_symlink():
        raise ValueError("Linked cadence source rejected")
    raw = (root / "manifest.json").read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError("Fixed cadence manifest changed")
    manifest = json.loads(raw)
    if manifest["fixedUpstreamCommit"] != COMMIT or manifest["schemaVersion"] != 1:
        raise ValueError("Unknown cadence source identity")
    if {p.name for p in root.iterdir()} != set(PINS) | {"manifest.json"}:
        raise ValueError("Unexpected cadence archive file set")
    rows = {row["filename"]: row for row in manifest["files"]}
    if len(rows) != len(manifest["files"]) or set(rows) != set(PINS):
        raise ValueError("Unexpected cadence source set")
    result = {}
    for name, (digest, size, blob) in PINS.items():
        path = root / name
        if path.is_symlink():
            raise ValueError("Linked cadence input rejected")
        raw = path.read_bytes()
        row = rows[name]
        if (row["sha256Bytes"], row["bytes"], row["gitBlob"]) != (digest, size, blob):
            raise ValueError("Fixed cadence input identity changed")
        actual_blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        if len(raw) != size or sha(raw) != digest or actual_blob != blob:
            raise ValueError("Fixed cadence source bytes changed")
        result[name] = raw.replace(b"\r\n", b"\n").decode("utf-8")
    return result, manifest


def adapted(original, mappings):
    text = original
    edits = []
    for before, after in mappings:
        count = text.count(before)
        if count < 1:
            raise ValueError("Cadence platform anchor changed: " + before)
        text = text.replace(before, after)
        edits.append(dict(before=before, after=after, occurrences=count))
    reverse = text
    for edit in reversed(edits):
        if reverse.count(edit["after"]) != edit["occurrences"]:
            raise ValueError("Cadence inverse anchor changed")
        reverse = reverse.replace(edit["after"], edit["before"])
    if reverse != original:
        raise ValueError("Complete cadence selection inverse differs")
    return text, dict(upstreamCommit=COMMIT, originalSha256LF=sha(original),
                      generatedSha256LF=sha(text), edits=edits,
                      exactFullSelectionInverse=True, mainCompiled=False, runtimeExecuted=False)


def cadence_source(repo):
    sources, _ = fixed_sources(repo)
    # A read-only deadline port lets Swing arrange the next callback. All actual
    # deadline advancement, frame admission and reset remain the original class.
    return adapted(sources["RenderFrameCadence.kt"], [
        ("    private var nextFrameNs = 0L", "    internal var nextFrameNs = 0L\n        private set"),
    ])


def frame_request_source(repo, function):
    sources, _ = fixed_sources(repo)
    view = sources["DanmakuView.kt"]
    start = view.index("    private val cadence = RenderFrameCadence()")
    end = view.index("\n\n    init {", start)
    state, state_proof = adapted(view[start:end], [
        ("    private val renderFrame = Choreographer.FrameCallback { timeNs ->",
         "    private val renderFrame: (Long) -> Unit = { timeNs ->"),
        ("                invalidate()", "                renderAndContinue()"),
    ])
    common = [
        ("Looper.myLooper() != Looper.getMainLooper()", "!platform.isDispatchThread()"),
        ("removeCallbacks(wakeFrame)", "platform.removeWake(wakeFrame)"),
    ]
    rows = [dict(selection="complete original frame fields and callbacks", **state_proof)]
    pieces = [state]
    definitions = [
        ("requestRenderFrame", common + [
            ("post { requestRenderFrame(delayMs) }", "platform.dispatch { requestRenderFrame(delayMs) }"),
            ("SystemClock.uptimeMillis()", "platform.uptimeMillis()"),
            ("postDelayed(wakeFrame, delayMs)", "platform.scheduleWake(wakeFrame, delayMs)"),
        ]),
        ("cancelRenderFrames", common + [
            ("post { cancelRenderFrames() }", "platform.dispatch { cancelRenderFrames() }"),
            ("Choreographer.getInstance().removeFrameCallback(renderFrame)", "platform.cancelFrameCallback(renderFrame)"),
        ]),
        ("enqueueFrame", [
            ("Choreographer.getInstance().postFrameCallback(renderFrame)",
             "platform.requestFrameCallback(renderFrame, cadence.nextFrameNs)"),
        ]),
        ("updateVisibilityScheduling", [
            ("private fun updateVisibilityScheduling()", "internal fun updateVisibilityScheduling()"),
        ]),
    ]
    for name, mappings in definitions:
        original = function(view, name)[0]
        body, proof = adapted(original, mappings)
        pieces.append(body)
        rows.append(dict(selection="complete original " + name, **proof))
    original = "    private fun canRender() = isAttachedToWindow && isShown && windowVisibility == VISIBLE"
    if view.count(original) != 1:
        raise ValueError("Original View visibility source changed")
    body, proof = adapted(original, [(original, "    private fun canRender() = visible()")])
    pieces.append(body)
    rows.append(dict(selection="complete original visibility platform port", **proof))
    header = """package com.bilipai.desktop.danmaku

import com.bytedance.danmaku.render.engine.RenderFrameCadence

/** Original v038 View scheduling, with Windows event-loop and renderer ports. */
internal class DesktopOriginalDanmakuFrameRequests(
    private val platform: DesktopDanmakuFramePlatform,
    private val visible: () -> Boolean,
    private val render: () -> Boolean,
) {
"""
    tail = """

    private fun renderAndContinue() {
        if (render()) requestRenderFrame()
    }
}
"""
    text = header + "\n\n".join(pieces) + tail
    return text, dict(upstreamCommit=COMMIT, wholeViewSourceSha256LF=sha(view),
                      generatedSha256LF=sha(text), selections=rows,
                      completeSelectionInverse=True, emissionOwner="extract-upstream-danmaku-list-menu.py",
                      mainCompiled=False, runtimeExecuted=False)


def original_policy_test(repo):
    sources, _ = fixed_sources(repo)
    return adapted(sources["RenderFrameCadenceTest.kt"], [])

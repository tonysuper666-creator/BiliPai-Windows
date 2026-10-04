"""Fixed v029 whole BAS painter and retained frame/input bodies; Windows primitives only."""
import argparse
import hashlib
import json
from pathlib import Path

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
ARCHIVE = Path("desktop/upstream-slices/v029-bas-renderer")
MANIFEST_SHA256 = "ca46e04a26797709d862affc4954aa0f8f966268916e5dd54b26b6bd7e7f88ec"
PINS = {
    "BasScenePainter.kt": "768bd16faedf4d4696546fcfb7595a0b31ad8e0e6422ea23b0b725e5e8eec94b",
    "BasDanmakuOverlay.kt": "3593305c47470a87f5d89e3b1d358c0be146ce1183bb09fb1753f49388b000b6",
}
PACKAGE = Path("com/bilipai/desktop/danmaku")


def checked(repo):
    root = Path(repo).resolve() / ARCHIVE
    manifest_path = root / "manifest.json"
    if root.is_symlink() or manifest_path.is_symlink():
        raise ValueError("BAS painter archive must be regular")
    manifest_raw = manifest_path.read_bytes()
    if hashlib.sha256(manifest_raw).hexdigest() != MANIFEST_SHA256:
        raise ValueError("Fixed BAS painter manifest bytes changed")
    manifest = json.loads(manifest_raw)
    rows = manifest["files"]
    if manifest["schemaVersion"] != 1 or manifest["fixedUpstreamCommit"] != COMMIT or len(rows) != 2 or {r["archiveFile"] for r in rows} != set(PINS):
        raise ValueError("Unknown fixed BAS painter inputs")
    originals = {}
    for row in rows:
        name = row["archiveFile"]
        expected_path = "app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/" + name
        file = root / name
        if file.is_symlink() or not file.is_file() or file.resolve().parent != root.resolve():
            raise ValueError("BAS painter input must be a regular archive child")
        raw = file.read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        if row["originalPath"] != expected_path or row["sha256Bytes"] != PINS[name] or len(raw) != row["bytes"] or \
                hashlib.sha256(raw).hexdigest() != PINS[name] or blob != row["gitBlob"]:
            raise ValueError("Fixed BAS painter bytes changed: " + name)
        originals[name] = raw.decode("utf-8").replace("\r\n", "\n")
    return originals


def replace(text, before, after, edits, count=1):
    if text.count(before) != count:
        raise ValueError("BAS painter adaptation anchor changed: " + before)
    edits.append(dict(before=before, after=after, count=count))
    return text.replace(before, after, count)


def inverse(text, edits):
    for edit in reversed(edits):
        if text.count(edit["after"]) != edit["count"]:
            raise ValueError("BAS painter inverse anchor changed")
        text = text.replace(edit["after"], edit["before"], edit["count"])
    return text


def painter(original):
    edits = []
    text = original
    def change(before, after, count=1):
        nonlocal text
        text = replace(text, before, after, edits, count)
    change("package com.android.purebilibili.feature.video.ui.overlay", "package com.bilipai.desktop.danmaku")
    for owner, simple, windows in [
        ("graphics", "Canvas", "Canvas"), ("graphics", "Color", "Color"), ("graphics", "Matrix", "Matrix"),
        ("graphics", "Paint", "Paint"), ("graphics", "Path", "Path"), ("graphics", "RectF", "Rect"),
        ("graphics", "Typeface", "Typeface"), ("text", "Layout", "Layout"),
        ("text", "StaticLayout", "StaticLayout"), ("text", "TextPaint", "TextPaint"),
    ]:
        change("import android." + owner + "." + simple,
               "import com.bilipai.desktop.danmaku.DesktopBas" + windows + " as " + simple)
    change("import androidx.core.graphics.PathParser", "import com.bilipai.desktop.danmaku.DesktopBasPathParser as PathParser")
    change("internal class BasScenePainter(val item: BasDanmaku) : BasMeasurer {",
           "internal class DesktopBasScenePainter(val item: BasDanmaku, private val budget: DesktopBasRenderBudget) : BasMeasurer, AutoCloseable {\n    private var closed = false")
    change("private val timeline = BasTimeline(item.program)", "private val timeline = BasTimeline(budget.validateScene(item))\n    private val reservation = budget.reserveScene(item)")
    change("private val nodes = timeline.states.map { Node(it) }", "private val nodes = buildNodes()\n    private fun buildNodes(): List<Node> {\n        val result = ArrayList<Node>()\n        try { for (state in timeline.states) result.add(Node(state)); return result }\n        catch (error: Exception) { for (node in result) node.close(); budget.releaseScene(item); throw error }\n    }")
    change("        init { path?.computeBounds(bounds, true) }", "        init { path?.computeBounds(bounds, true) }\n        fun close() { layout?.close(); layout = null; textPaint.close(); path?.close() }")
    old = """            paint.typeface = if (android.os.Build.VERSION.SDK_INT >= 28)
                Typeface.create(base, (userWeight * 100).coerceIn(if (bold) 700 else 100, 900), false)
            else Typeface.create(base, if (bold || userWeight >= 6) Typeface.BOLD else Typeface.NORMAL)"""
    change(old, "            paint.typeface = Typeface.create(base, (userWeight * 100).coerceIn(if (bold) 700 else 100, 900), false)")
    change("        val size = max(0.01f, state.fontSize * userScale * item.fontScale)",
           "        val size = max(0.01f, state.fontSize * userScale * item.fontScale)\n        budget.text(state, size)")
    change("            node.layout = StaticLayout.Builder.obtain", "            node.layout?.close(); node.layout = null\n            node.layout = StaticLayout.Builder.obtain")
    change("android.opengl.Matrix.", "DesktopBasMatrix4.", 8)
    change("        node.screen.setValues(projection)", "        node.screen.setValues(projection)\n        budget.projection(state, node.screen)")
    end = "    private fun color(rgb: Int, alpha: Float): Int =\n        (rgb and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)\n}"
    change(end, end[:-1] + "\n    override fun close() {\n        if (!closed) { closed = true; for (node in nodes) node.close(); budget.releaseScene(item) }\n    }\n}")
    if inverse(text, edits) != original:
        raise ValueError("BAS whole painter inverse failed")
    return text, edits


def frames(original):
    # This full block includes original configure/frame/draw/hit/down/move/up/cancel.
    start = "    fun configure(items: List<BasDanmaku>, viewport: DanmakuViewport, opacity: Float, fontScale: Float, fontWeight: Int) {"
    end = "    override fun performClick(): Boolean {"
    if original.count(start) != 1 or original.count(end) != 1:
        raise ValueError("BAS retained frame/input boundary changed")
    body = original[original.index(start):original.index(end)]
    edits = []
    text = body
    def change(before, after, count=1):
        nonlocal text
        text = replace(text, before, after, edits, count)
    change("DanmakuViewport", "DesktopBasViewport")
    change("BasScenePainter(item)", "DesktopBasScenePainter(item, budget)")
    change("if (items.none { it === cached }) iterator.remove()", "if (items.none { it === cached }) { painters[cached]?.close(); iterator.remove() }")
    change("                if (iterator.next() !in active) iterator.remove()", "                val cached = iterator.next()\n                if (cached !in active) { cached.close(); iterator.remove() }")
    change("    override fun onDraw(canvas: Canvas) {\n        super.onDraw(canvas)", "    fun draw(canvas: DesktopBasCanvas) {")
    change("        invalidate()", "        // Existing overlay owns repaint scheduling; this retained leaf opens no window.")
    change("    private fun hit(x: Float, y: Float): BasTarget?", "    fun hit(x: Float, y: Float): BasTarget?")
    change("    override fun onTouchEvent(event: MotionEvent): Boolean", "    fun onTouchEvent(event: DesktopBasPointerEvent): Boolean")
    change("MotionEvent.", "DesktopBasPointerEvent.", 5)
    change("                    performClick()\n                    activate(target)", "                    activated = target")
    if inverse(text, edits) != body:
        raise ValueError("BAS configure/frame/input full inverse failed")
    header = """package com.bilipai.desktop.danmaku
import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasTarget
import kotlin.math.abs
internal data class DesktopBasViewport(val widthPx: Int, val heightPx: Int)
internal data class DesktopBasPointerEvent(val actionMasked: Int, val x: Float, val y: Float) {
    companion object { const val ACTION_DOWN=0; const val ACTION_UP=1; const val ACTION_MOVE=2; const val ACTION_CANCEL=3; const val ACTION_POINTER_DOWN=5 }
}
internal class DesktopBasRetainedScenes(private val budget: DesktopBasRenderBudget, private val touchSlop: Float) : AutoCloseable {
    private var items: List<BasDanmaku> = emptyList()
    private val painters = java.util.IdentityHashMap<BasDanmaku, DesktopBasScenePainter>()
    private val active = ArrayList<DesktopBasScenePainter>()
    private var viewport: DesktopBasViewport? = null
    private var opacity=1f; private var fontScale=1f; private var fontWeight=5
    private var pressed: BasTarget?=null; private var downX=0f; private var downY=0f
    var activated: BasTarget?=null
    val hasActiveScene get()=active.isNotEmpty()
    val activeCount get()=active.size
    fun evictInactive(positionMs: Long) {
        val iterator=painters.values.iterator()
        while(iterator.hasNext()) {
            val painter=iterator.next(); val relative=positionMs-painter.item.startTimeMs
            if(relative<0L || relative>=painter.item.durationMs) { painter.close(); iterator.remove() }
        }
    }
"""
    tail = """    fun clear() {
        pressed=null; activated=null; active.clear()
        for(painter in painters.values) painter.close()
        painters.clear(); items=emptyList(); viewport=null
    }
    override fun close()=clear()
}
"""
    return header + text + tail, edits, body


def generate(repo, output):
    originals = checked(repo)
    paint, paint_edits = painter(originals["BasScenePainter.kt"])
    frame, frame_edits, frame_original = frames(originals["BasDanmakuOverlay.kt"])
    root = Path(output).absolute()
    archive = (Path(repo).resolve() / ARCHIVE).resolve()
    if root.resolve().is_relative_to(archive) or archive.is_relative_to(root.resolve()) or root.is_symlink():
        raise ValueError("BAS generated output overlaps fixed originals")
    outputs = {"DesktopOriginalBasScenePainter.kt": paint, "DesktopOriginalBasRetainedScenes.kt": frame}
    expected = {(PACKAGE / name).as_posix(): body.encode() for name, body in outputs.items()}
    for file in root.rglob("*.kt") if root.exists() else ():
        relative = file.relative_to(root).as_posix()
        if file.is_symlink() or relative not in expected or file.read_bytes() != expected[relative]:
            raise ValueError("Unknown or edited BAS generated output is protected")
    for relative, raw in expected.items():
        path = root / relative
        for ancestor in (path, *path.parents):
            if ancestor.is_symlink():
                raise ValueError("BAS output path traverses a symlink")
            if ancestor == root: break
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
    proof = dict(fixedUpstreamCommit=COMMIT, canonicalBaselineAdvanced=False,
        wholePainterInverse=inverse(paint, paint_edits)==originals["BasScenePainter.kt"],
        retainedConfigureFrameHitInputInverse=inverse(frame[frame.index("    fun configure("):frame.index("    fun clear()")], frame_edits)==frame_original,
        painterEdits=paint_edits, frameEdits=frame_edits,
        originalPins=PINS, outputSha256={path:hashlib.sha256(raw).hexdigest() for path, raw in expected.items()},
        compiled=False, rasterTestsExecuted=False, actualMainBasRendered=False)
    if not proof["wholePainterInverse"] or not proof["retainedConfigureFrameHitInputInverse"]:
        raise ValueError("BAS source inverse failed")
    (root / "source-proof.json").write_text(json.dumps(proof,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    return [root / path for path in expected]


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo",type=Path,required=True);parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    for path in generate(args.repo,args.output): print(path)

from pathlib import Path
import hashlib, json

COMMIT = "0e2206a85e288ba08f361cc636fab0710c2b9ab8"
EXPECTED = [{'file': 'DanmakuPoolSheet.kt', 'upstreamPath': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/DanmakuPoolSheet.kt', 'commit': '0e2206a85e288ba08f361cc636fab0710c2b9ab8', 'gitBlob': '5d525a6edfaf9b66c9ee04028b56650e003fc69f', 'sha256': 'b8660bf7027977122055ccc6a76ea83c6cd9be3492e54f2f928f10b36817e939', 'bytes': 27700}, {'file': 'DanmakuModels.kt', 'upstreamPath': 'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/DanmakuModels.kt', 'commit': '0e2206a85e288ba08f361cc636fab0710c2b9ab8', 'gitBlob': 'fd5d86e9b6d4db5e987c0504205e93b2c798f04c', 'sha256': 'a212fae3e6793584a84253e165bccc510907ab7149dd481faa37dd4d6635a355', 'bytes': 3468}, {'file': 'TextDrawItem.kt', 'upstreamPath': 'danmaku-engine/src/main/java/com/bytedance/danmaku/render/engine/render/draw/text/TextDrawItem.kt', 'commit': '0e2206a85e288ba08f361cc636fab0710c2b9ab8', 'gitBlob': '824d528247e89a1464c873b0b8a4b49d9e59ab84', 'sha256': 'fe3918b8196cb6db480ae48afbe1176ba6697c5f6b75a6b8bfe2976d3ad1da43', 'bytes': 10575}, {'file': 'DanmakuManager.kt', 'upstreamPath': 'app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuManager.kt', 'commit': '0e2206a85e288ba08f361cc636fab0710c2b9ab8', 'gitBlob': 'f65fbabf1aaba961eed4487485fea15c63dc6dbb', 'sha256': '10a6924b41bfc1d05ddb96b58958a56c8827e80315b724f0eeadbe0201c8c331', 'bytes': 134982}, {'file': 'ByteDanceDanmakuEngine.kt', 'upstreamPath': 'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt', 'commit': '0e2206a85e288ba08f361cc636fab0710c2b9ab8', 'gitBlob': '2537c3bc280d27177e9e544012bf95c73f0308e0', 'sha256': '9ffed7c42982a1af404896fbc381d54041547a2bf77c0f4c0849e31986ebf650', 'bytes': 13476}, {'file': 'DanmakuSameSendConfirmation.kt', 'upstreamPath': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/DanmakuSameSendConfirmation.kt', 'commit': '0e2206a85e288ba08f361cc636fab0710c2b9ab8', 'gitBlob': '1ffea13a43555cb1ec5fa7af602bb9cb4a9ee7ed', 'sha256': '23b033faa6b20e884e8fd319204b221d28aa3ca680874d108ee5ca9406f5a665', 'bytes': 929}]

MANIFEST_SHA256 = "ba41f5940d9b71152592121679e63e36b763cc3bc3a9fe175c684d6acda0416f"

def fixed_v030_up_files(root=None):
    root = Path(root) if root is not None else Path(__file__).resolve().parent.parent / "upstream-slices/v030-up-danmaku"
    manifest_raw = (root / "manifest.json").read_bytes()
    if hashlib.sha256(manifest_raw).hexdigest() != MANIFEST_SHA256:
        raise ValueError("Changed fixed-v030 author/pool manifest bytes")
    manifest = json.loads(manifest_raw)
    if manifest.get("schemaVersion") != 1 or manifest.get("commit") != COMMIT or manifest.get("sources") != EXPECTED:
        raise ValueError("Changed fixed-v030 author/pool source manifest")
    result = {}
    for row in EXPECTED:
        raw = (root / row["file"]).read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()
        if len(raw) != row["bytes"] or hashlib.sha256(raw).hexdigest() != row["sha256"] or blob != row["gitBlob"]:
            raise ValueError("Changed fixed-v030 author/pool source: " + row["file"])
        result[row["file"]] = raw.decode("utf8").replace("\r\n", "\n")
    return result, EXPECTED


def original_up_badge_adapter(original, function, adapt):
    """Whole original UP measurement/draw functions and constants; explicit AWT ports."""
    raw_advance = function(original, "resolveUpBadgeAdvance")[0]
    raw_draw = function(original, "drawUpBadge")[0]
    start = original.index("    private companion object {")
    end = original.index("\n    private fun getFontHeight", start)
    raw_constants = original[start:end].rstrip()
    functions = []
    proof = []
    for name, raw in [("resolveUpBadgeAdvance", raw_advance), ("drawUpBadge", raw_draw)]:
        s = raw; rows = []
        s = adapt(s, "        mUpBadgePaint.textSize = badgeTextSize\n        mUpBadgePaint.typeface = Typeface.DEFAULT_BOLD",
            '        val badgeFont = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(badgeTextSize)', rows)
        s = adapt(s, "mUpBadgePaint.measureText(UP_BADGE_TEXT)",
            "badgeFont.getStringBounds(UP_BADGE_TEXT, graphics.fontRenderContext).width.toFloat()", rows)
        if name == "drawUpBadge":
            s = adapt(s, "canvas: Canvas", "canvas: Graphics2D", rows)
            s = adapt(s, "        mUpBadgePaint.color = UP_BADGE_COLOR\n        mUpBadgeRect.set(x, badgeTop, x + badgeWidth, badgeTop + badgeHeight)\n        canvas.drawRoundRect(mUpBadgeRect, badgeHeight * 0.22f, badgeHeight * 0.22f, mUpBadgePaint)",
                "        canvas.color = Color(UP_BADGE_COLOR, true)\n        canvas.fill(RoundRectangle2D.Float(x, badgeTop, badgeWidth, badgeHeight, badgeHeight * 0.44f, badgeHeight * 0.44f))", rows)
            s = adapt(s, "        mUpBadgePaint.color = UP_BADGE_TEXT_COLOR\n        val metrics = mUpBadgePaint.fontMetrics",
                "        canvas.color = Color(UP_BADGE_TEXT_COLOR, true)\n        canvas.font = badgeFont\n        val metrics = canvas.getFontMetrics(badgeFont)", rows)
            s = adapt(s, "metrics.bottom - metrics.top", "metrics.maxDescent - (-metrics.maxAscent)", rows)
            call = "        canvas.drawText(\n            UP_BADGE_TEXT,\n            x + (badgeWidth - badgeTextWidth) / 2f,\n            badgeBaseline,\n            mUpBadgePaint,\n        )"
            s = adapt(s, call, call.replace("canvas.drawText", "canvas.drawString").replace("            mUpBadgePaint,\n", ""), rows)
        reverse = s
        for row in reversed(rows):
            if reverse.count(row["after"]) != 1: raise ValueError("UP badge inverse occurrence changed")
            reverse = reverse.replace(row["after"], row["before"])
        if reverse != raw: raise ValueError("Complete UP badge function inverse failed")
        functions.append(s)
        proof.append({"member": name, "original": raw, "adapted": s, "adaptations": rows,
            "originalSha256LF": hashlib.sha256(raw.encode()).hexdigest(),
            "adaptedSha256LF": hashlib.sha256(s.encode()).hexdigest(), "completeInverse": True})
    prefix = """package com.bilipai.desktop.danmaku

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.ceil

/** Physical-pixel AWT geometry over complete fixed original UP badge functions.
 * No raster allocation/cache, media timeline, plugin or account authority. */
internal data class DesktopDanmakuAuthorTextMetrics(val pureWidth: Int, val width: Int,
    val ascent: Double, val height: Float, val badgeAdvance: Float)

internal class DesktopOriginalUpDanmakuBadge(private val graphics: Graphics2D) {
    private var x = 0f
    private var y = 0f
    private var height = 0f
    fun measure(text: String, font: Font, author: Boolean): DesktopDanmakuAuthorTextMetrics {
        val metrics = graphics.getFontMetrics(font)
        val textHeight = (metrics.maxAscent + metrics.maxDescent).toFloat()
        val advance = if (author && text.isNotEmpty()) resolveUpBadgeAdvance(textHeight) else 0f
        val pure = metrics.stringWidth(text)
        return DesktopDanmakuAuthorTextMetrics(pure, ceil(pure + advance.toDouble()).toInt(),
            metrics.ascent.toDouble(), textHeight, advance)
    }
    fun paint(left: Double, baseline: Double, metrics: DesktopDanmakuAuthorTextMetrics) {
        if (metrics.badgeAdvance <= 0f) return
        x = left.toFloat(); y = (baseline - metrics.ascent).toFloat(); height = metrics.height
        val owned = graphics.create() as Graphics2D
        try { drawUpBadge(owned) } finally { owned.dispose() }
    }

"""
    body = original[:original.index("package ")] + prefix + "\n\n".join(functions) + "\n\n" + raw_constants + "\n}\n"
    return body, proof


def same_send_expected_source_delta(path, body, edits):
    """A final counted platform seam over the complete original VM send body."""
    if path != "com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt":
        return body
    original = body
    def change(before, after):
        nonlocal body
        if body.count(before) != 1: raise ValueError("Changed original same-send guard anchor")
        offset = body.index(before)
        edits.append(dict(offset=offset, before=before, after=after))
        body = body[:offset] + after + body[offset+len(before):]
    change("        attentionCommand: Boolean = false\n    ) {\n", """        attentionCommand: Boolean = false,
        desktopExpectedNativeSource: com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot? = null,
        desktopExpectedCurrent: (() -> Boolean)? = null
    ) {
        val desktopExpectedSubmission = com.bilipai.desktop.ui.DesktopOriginalDanmakuExpectedSubmission
            .capture(desktopExpectedNativeSource, desktopExpectedCurrent)
        if (desktopExpectedSubmission?.isCurrentSource(exoPlayer?.nativePlayer?.currentSourceSnapshot()) == false) return
""")
    change("        val clickedNativeSource = exoPlayer?.nativePlayer?.currentSourceSnapshot() ?: return\n", """        val clickedNativeSource = desktopExpectedSubmission?.source ?: exoPlayer?.nativePlayer?.currentSourceSnapshot() ?: return
        if (desktopExpectedSubmission?.isCurrentSource(exoPlayer?.nativePlayer?.currentSourceSnapshot()) == false) return
""")
    change("""        if (!environment.commit {
            submissionId = ++desktopDanmakuSubmissionSerial
            _isSendingDanmaku.value = true
        }) return
""", """        if (!environment.commit {
            if (desktopExpectedSubmission?.isCurrentSource(exoPlayer?.nativePlayer?.currentSourceSnapshot()) != false) {
                submissionId = ++desktopDanmakuSubmissionSerial
                _isSendingDanmaku.value = true
            }
        } || submissionId == 0L) return
""")
    change("""        fun ensureSubmissionSubject() {
            environment.assertCurrent()
""", """        fun ensureSubmissionSubject() {
            environment.assertCurrent()
            desktopExpectedSubmission?.assertCurrent(exoPlayer?.nativePlayer?.currentSourceSnapshot())
""")
    change("        val submission = try { environment.invocations.launch {\n", """        val submission = try { environment.invocations.launch(
            context = desktopExpectedSubmission ?: kotlin.coroutines.EmptyCoroutineContext
        ) {
""")
    restored = body
    for edit in reversed(edits):
        i=edit["offset"]
        if restored[i:i+len(edit["after"])] != edit["after"]: raise ValueError("Changed same-send inverse")
        restored=restored[:i]+edit["before"]+restored[i+len(edit["after"]):]
    if restored != original: raise ValueError("Complete same-send VM platform-stage inverse failed")
    return body

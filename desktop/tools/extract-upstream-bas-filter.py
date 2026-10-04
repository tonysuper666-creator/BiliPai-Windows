"""Fixed v029 BAS filter + whole Mode9 pipeline, with explicit Windows budget ports.

Outputs are checked-in leaves; this tool validates provenance and reproduces them.
No Android manager, ordinary danmaku adapter, source authority or new dependency.
"""
from pathlib import Path
import hashlib
import json
import sys

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
BASE = "app/src/main/java/com/android/purebilibili/feature/video/danmaku/"
PINS = {
    "BasDanmakuFilterPolicy.kt": (BASE + "BasDanmakuFilterPolicy.kt", 2677, "363cb7000c2de1037effb1b7a638d96cf7e69f11928b5f7840de08350edaf5da", "c68f44691c763ccf0795192675f1fb64a616e9ba"),
    "DanmakuManager.kt": (BASE + "DanmakuManager.kt", 132378, "5951d425ad3aa159468a6c811a534552d927d77fefed8de18580fae73ce4758f", "cbc074ababb0a0ca866aefba731892a243b2de2d"),
    "BasDanmakuFilterPolicyTest.kt": ("app/src/test/java/com/android/purebilibili/feature/video/danmaku/BasDanmakuFilterPolicyTest.kt", 2897, "ddbff928a5145a8ad433d1efa87782ca8cd711139dfbc9b2fa53444406ebb5e1", "6f481ae7055047bbf97d1943f74bdf9ab69ffce7"),
}


def read(repo, name):
    root = Path(repo) / "desktop/upstream-slices/v029-bas-filter"
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["schemaVersion"] == 1 and manifest["commit"] == COMMIT
    assert manifest["hashNormalization"] == "raw" and manifest["canonicalBaselineAdvanced"] is False
    assert len(manifest["files"]) == len(PINS)
    assert {r["archiveFile"] for r in manifest["files"]} == set(PINS)
    for row in manifest["files"]:
        path, size, sha, blob = PINS[row["archiveFile"]]
        assert (row["path"], row["bytes"], row["sha256Raw"], row["gitBlob"]) == (path, size, sha, blob)
    target = root / name
    assert not target.is_symlink() and target.resolve().is_relative_to(root.resolve())
    raw = target.read_bytes()
    path, size, sha, blob = PINS[name]
    assert len(raw) == size and hashlib.sha256(raw).hexdigest() == sha, path + " raw source changed"
    assert hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest() == blob
    return raw.decode("utf-8").replace("\r\n", "\n")


def replace(text, before, after, edits, count=1):
    assert text.count(before) == count, before
    edits.append((before, after, count))
    return text.replace(before, after, count)


def inverse(text, edits):
    for before, after, count in reversed(edits):
        assert text.count(after) == count, after
        text = text.replace(after, before, count)
    return text


SUPPORT = '''package com.bilipai.desktop.danmaku

import androidx.compose.ui.graphics.toArgb
import com.android.purebilibili.core.plugin.DanmakuItem as PluginDanmakuItem
import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.danmaku.parser.bas.*
import com.android.purebilibili.feature.video.danmaku.*

/** Capture both ports from the same original enabled-plugin/JSON batch.
 * Style is called only after the filtered DSL has passed parsing admission.
 * Calls are synchronous stage work, outside source/account/Overlay gates. */
internal class DesktopBasPluginProcessor(
    val filter: (PluginDanmakuItem) -> PluginDanmakuItem?,
    val style: (PluginDanmakuItem) -> DanmakuStyle?,
)

internal enum class DesktopBasRejection {
    SOURCE_PREFLIGHT, DOCUMENT_BUDGET, INVALID_SCRIPT, PROGRAM_LIMIT, INVALID_STYLE,
}

/** Exactly the existing parse/document limits. No truncation, mode conversion or merge. */
private fun parseDesktopBasPluginProgram(
    item: BasDanmaku, source: String, budget: DesktopBasDocumentBudget,
    onRejected: (Long, DesktopBasRejection) -> Unit,
): BasProgram? {
    val estimate = DesktopBasParseBudget.estimate(source)
    if (estimate == null) { onRejected(item.id, DesktopBasRejection.SOURCE_PREFLIGHT); return null }
    if (!budget.reserve(estimate)) { onRejected(item.id, DesktopBasRejection.DOCUMENT_BUDGET); return null }
    return BasScriptParser.parse(source)
}

private fun retainDesktopBasPluginItem(
    original: BasDanmaku, item: BasDanmaku, budget: DesktopBasDocumentBudget,
    onRejected: (Long, DesktopBasRejection) -> Unit,
): Boolean {
    if (!item.fontScale.isFinite() || item.fontScale <= 0f) {
        onRejected(item.id, DesktopBasRejection.INVALID_STYLE); return false
    }
    if (item.program.elements.isEmpty() || item.program.elements.size > 256 || item.program.transitions.size > 1_024) {
        onRejected(item.id, DesktopBasRejection.PROGRAM_LIMIT); return false
    }
    // Changed DSL was already charged before the original parser allocated its AST.
    if (item.source == original.source) {
        val estimate = DesktopBasParseBudget.estimate(item.source)
        if (estimate == null) { onRejected(item.id, DesktopBasRejection.SOURCE_PREFLIGHT); return false }
        if (!budget.reserve(estimate)) { onRejected(item.id, DesktopBasRejection.DOCUMENT_BUDGET); return false }
    }
    if (!budget.retain(item)) { onRejected(item.id, DesktopBasRejection.DOCUMENT_BUDGET); return false }
    return true
}

private fun retainUnchangedDesktopBasBatch(
    items: List<BasDanmaku>, budget: DesktopBasDocumentBudget,
    onRejected: (Long, DesktopBasRejection) -> Unit,
): List<BasDanmaku> {
    val admitted = items.filter { retainDesktopBasPluginItem(it, it, budget, onRejected) }
    return if (admitted.size == items.size) items else admitted
}

/** Call once from the raw BAS document with one shared output budget and a captured
 * plugin batch. Refilter settings from raw input; do not reprocess last plugin output.
 * No source publication happens here. Rejections expose only item ID and a safe reason.
 * Caller still owns current-source checks, cancellation checkpoints and final install. */
'''


def adapt_pipeline(original):
    start = "    private fun filterBasDanmakuForRender(items: List<BasDanmaku>): List<BasDanmaku> {"
    end = "    private fun applyDanmakuPluginPipeline("
    assert original.count(start) == original.count(end) == 1
    body = original[original.index(start):original.index(end)]
    edits = []
    text = replace(body, start,
        "internal fun filterDesktopBasDanmaku(\n"
        "    items: List<BasDanmaku>, config: DanmakuSettings, processor: DesktopBasPluginProcessor?,\n"
        "    budget: DesktopBasDocumentBudget, onRejected: (Long, DesktopBasRejection) -> Unit,\n"
        "): List<BasDanmaku> {", edits)
    text = replace(text, "        val settings = currentTypeFilterSettings()\n",
        "        val settings = DanmakuTypeFilterSettings(config.allowScroll, config.allowTop, config.allowBottom, config.allowColorful, config.allowSpecial)\n"
        "        val blockedRuleMatchers = compileDanmakuBlockRules((config.blockedKeywords + config.blockedRules).distinct())\n", edits)
    text = replace(text, "        if (!settings.allowSpecial) return emptyList()",
        "        if (!config.enabled || !settings.allowSpecial) return emptyList()", edits)
    text = replace(text, "        val nativePlugins = PluginManager.getEnabledDanmakuPlugins()\n"
        "        val useJsonRules = JsonPluginManager.plugins.value.any { it.enabled && it.plugin.type == \"danmaku\" }\n"
        "        val hasPlugins = nativePlugins.isNotEmpty() || useJsonRules",
        "        val hasPlugins = processor != null", edits)
    text = replace(text, "            return items\n",
        "            return retainUnchangedDesktopBasBatch(items, budget, onRejected)\n", edits)
    text = replace(text, "runDanmakuFilters(sourceItem, nativePlugins, useJsonRules)",
        "checkNotNull(processor).filter(sourceItem)", edits)
    text = replace(text, "BasScriptParser.parse(filtered.content)",
        "parseDesktopBasPluginProgram(item, filtered.content, budget, onRejected) ?: continue", edits)
    text = replace(text, '                    Log.w(TAG, "Plugin produced invalid BAS ${item.id}: ${error.message}")',
        "                    onRejected(item.id, DesktopBasRejection.INVALID_SCRIPT)", edits)
    text = replace(text, "collectDanmakuStyle(filtered, nativePlugins, useJsonRules)",
        "checkNotNull(processor).style(filtered)", edits)
    text = replace(text, "                result.add(visible)",
        "                if (retainDesktopBasPluginItem(item, visible, budget, onRejected)) result.add(visible)", edits)
    assert inverse(text, edits) == body
    return body, text, edits


def generate(repo, output):
    output = Path(output)
    outputs = []
    def emit(relative, source_name, body):
        path, size, sha, blob = PINS[source_name]
        header = "// Fixed original v0.2.9 " + COMMIT + "; " + path + "\n// Raw SHA-256 " + sha + "; Git blob " + blob + ".\n"
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(header + body, encoding="utf-8", newline="\n")
        outputs.append(relative)
    emit("main/com/android/purebilibili/feature/video/danmaku/DesktopOriginalBasDanmakuFilterPolicy.kt", "BasDanmakuFilterPolicy.kt", read(repo, "BasDanmakuFilterPolicy.kt"))
    original, adapted, edits = adapt_pipeline(read(repo, "DanmakuManager.kt"))
    emit("main/com/bilipai/desktop/danmaku/DesktopBasPluginFilter.kt", "DanmakuManager.kt", SUPPORT + adapted)
    emit("test/com/android/purebilibili/feature/video/danmaku/BasDanmakuFilterPolicyTest.kt", "BasDanmakuFilterPolicyTest.kt", read(repo, "BasDanmakuFilterPolicyTest.kt"))
    proof = {"commit": COMMIT, "pipelineWholeInverse": inverse(adapted, edits) == original,
        "pipelineSelectedSha256LF": hashlib.sha256(original.encode()).hexdigest(), "edits": edits,
        "outputs": outputs, "pins": PINS, "rawFilterWhole": True, "rawTestsWhole": True,
        "safety": "Windows existing parse/document budget; no ordinary truncation/clamping/merge; finite positive style only",
        "runtimeVisible": False}
    (output / "bas-filter-proof.json").write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return proof


if __name__ == "__main__":
    generate(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())

"""Fixed v030 LINK presentation source; existing vote/state/account owners remain unchanged."""
from pathlib import Path
import hashlib, json, os
import v029_command_vote as vote

COMMIT = "0e2206a85e288ba08f361cc636fab0710c2b9ab8"
BASE = "app/src/main/java/com/android/purebilibili/"
OVERLAY = BASE + "feature/video/ui/overlay/CommandDanmakuOverlay.kt"
SECTION = BASE + "feature/video/ui/section/VideoPlayerSection.kt"
PINS = {'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlay.kt': (42133, '116a9f1450ccdaec9d1a8c2ea923558aec2faacb2fa8ffbce43edcc74d08023d', '048e91ba7711a9d92370de27f679466f14d506e7'), 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt': (316137, '880feb3fcd54278b3563c9325402b5f1e081159c9730e9e029f91878a7e40237', '0f0b3fd6d0843c2c8582fd5ead2caa3f7e54f052')}

def wide(path):
    value = os.path.abspath(str(path))
    return Path("\\\\?\\" + value) if os.name == "nt" and not value.startswith("\\\\?\\") else Path(value)

def read(repo, path):
    root = Path(repo) / "desktop/upstream-slices/v030-command-link"
    manifest = json.loads(wide(root / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["schemaVersion"] == 1 and manifest["upstreamCommit"] == COMMIT
    assert manifest["hashNormalization"] == "raw"
    rows = manifest["files"]
    assert len(rows) == len(PINS) and {r["path"] for r in rows} == set(PINS)
    for row in rows:
        size, sha, blob = PINS[row["path"]]
        assert (row["bytes"], row["sha256"], row["gitBlob"], row["commit"]) == (size, sha, blob, COMMIT)
    target = wide(root / path)
    assert not target.is_symlink() and target.resolve().is_relative_to(wide(root).resolve())
    raw = target.read_bytes()
    size, sha, blob = PINS[path]
    assert len(raw) == size and hashlib.sha256(raw).hexdigest() == sha, path + " raw source changed"
    assert hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest() == blob
    return raw.decode("utf-8").replace("\r\n", "\n")

def select(text, start, end):
    assert text.count(start) == text.count(end) == 1
    return text.split(start, 1)[1].split(end, 1)[0]

def link_bodies(original):
    click = select(original, "                    onLinkClick = { item ->\n",
                   "                    },\n                    onVoteSubmit = { item, option, optionIndex ->\n")
    click_edits = []
    toast = 'android.widget.Toast.makeText(\n                                context,\n                                "关联视频信息缺失，无法跳转",\n                                android.widget.Toast.LENGTH_SHORT\n                            ).show()'
    clicked = vote.replace(click, toast, 'onFeedback("关联视频信息缺失，无法跳转")', click_edits)
    clicked = vote.replace(clicked, "pendingCommandLinkItem = item", "onPending(item, targetBvid)", click_edits)

    dialog_start = "                // 关联视频命令弹幕：跳转前确认\n"
    dialog_end = "                // 3.1 高赞弹幕悬浮条：当前时间窗内点赞 Top-N，支持一键跟发\n"
    dialog = select(original, dialog_start, dialog_end)
    confirm = select(dialog, "                            AppTextButton(onClick = {\n",
                     "                            }) {\n                                AppText(\"跳转\")\n")
    confirm_edits = []
    confirmed = vote.replace(confirm, "pendingCommandLinkItem = null", "onDismiss()", confirm_edits)
    confirmed = vote.replace(confirmed, "onRelatedVideoClick(dialogTargetBvid, null)", "onNavigate(dialogTargetBvid)", confirm_edits)
    dialog_edits = []
    displayed = vote.replace(dialog, confirm, "                                onConfirm(dialogTargetBvid)\n", dialog_edits)
    displayed = vote.replace(displayed, "pendingCommandLinkItem = null", "onDismiss()", dialog_edits, 2)
    displayed = vote.replace(displayed, '                        text = {\n',
                             '                        text = {\n                            onMounted()\n', dialog_edits)
    for raw, result, edits in [(click, clicked, click_edits), (dialog, displayed, dialog_edits),
                               (confirm, confirmed, confirm_edits)]:
        assert vote.inverse(result, edits) == raw
    return [("click", click, clicked, click_edits), ("dialog", dialog, displayed, dialog_edits),
            ("confirm", confirm, confirmed, confirm_edits)]

def emit(output, path, source_path, body):
    size, sha, blob = PINS[source_path]
    target = wide(Path(output) / path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("// GENERATED from " + source_path + "; do not edit.\n// Fixed upstream " + COMMIT +
                      "; raw SHA-256: " + sha + "; Git blob: " + blob + "\n" + body, encoding="utf-8", newline="\n")

def emit_link_callbacks(repo, output):
    original = read(repo, SECTION)
    selections = link_bodies(original)
    bodies = {name: body for name, _, body, _ in selections}
    header = """package com.android.purebilibili.feature.video.ui.overlay
import androidx.compose.runtime.Composable
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton

internal fun showOriginalDesktopCommandLink(item: CommandDanmakuItem,
    onFeedback: (String) -> Unit, onPending: (CommandDanmakuItem, String) -> Unit) {
"""
    body = header + bodies["click"] + "\n}\n\n" + """@Composable
internal fun DesktopOriginalCommandLinkConfirmation(pendingCommandLinkItem: CommandDanmakuItem?,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit, onMounted: @Composable () -> Unit) {
""" + bodies["dialog"] + "\n}\n\n" + """internal fun completeOriginalDesktopCommandLinkConfirmation(pendingCommandLinkItem: CommandDanmakuItem?,
    dialogTargetBvid: String, commandState: CommandDanmakuOverlayState,
    onDismiss: () -> Unit, onNavigate: (String) -> Unit) {
""" + bodies["confirm"] + "\n}\n"
    emit(output, "com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandLinkConfirmation.kt", SECTION, body)
    proof = {"upstreamCommit": COMMIT, "rawSectionSha256": PINS[SECTION][1], "sourcePins": PINS,
             "wholeSectionReferenced": True, "selections": [
                 {"name": name, "rawBody": raw, "adaptedBody": adapted, "edits": edits,
                  "rawBodySha256": hashlib.sha256(raw.encode()).hexdigest(),
                  "adaptedBodySha256": hashlib.sha256(adapted.encode()).hexdigest(),
                  "fullInverse": vote.inverse(adapted, edits) == raw}
                 for name, raw, adapted, edits in selections]}
    wide(Path(output) / "v030-command-link-proof.json").write_text(
        json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

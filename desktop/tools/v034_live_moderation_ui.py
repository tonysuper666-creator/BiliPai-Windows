"""Complete fixed live moderation composables; only Windows admission/platform seams."""
from __future__ import annotations
import hashlib, json
from pathlib import Path
from v030_live_stream import safe
COMMIT = 'b2091b1de636e499df80af7e700796b4f52b7f92'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/live/components/LiveInteractionSheets.kt', 'archive': 'desktop/upstream-slices/v034-live-moderation-ui/app/src/main/java/com/android/purebilibili/feature/live/components/LiveInteractionSheets.kt', 'rawBytes': 20629, 'rawSha256': 'f3e4db4ebabd16ad9b79d7d6d2cd38e7c77384bc783c78dbe4fb6c25a6c853b4', 'gitBlob': '894fad12402fa866f355c9a049214e936b946bb9', 'url': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/b2091b1de636e499df80af7e700796b4f52b7f92/app/src/main/java/com/android/purebilibili/feature/live/components/LiveInteractionSheets.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/live/components/LiveChatSection.kt', 'archive': 'desktop/upstream-slices/v034-live-moderation-ui/app/src/main/java/com/android/purebilibili/feature/live/components/LiveChatSection.kt', 'rawBytes': 33653, 'rawSha256': 'b7d6803bc68725a00a487535dad0fd699d32c4ffcb3012f3110fadc62c6a7b82', 'gitBlob': '639af3b4469f5d9ed79e19a8199ca7c9696571ca', 'url': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/b2091b1de636e499df80af7e700796b4f52b7f92/app/src/main/java/com/android/purebilibili/feature/live/components/LiveChatSection.kt'}]
SELECTIONS = [{'source': 0, 'name': 'LiveReportDialog', 'start': 3974, 'end': 5254, 'originalSha256LF': '9feea4717f0ac5e9dc3fd5161aec89eb002b016fdf68e0126eac35c21c1834aa', 'adaptedSha256LF': '9feea4717f0ac5e9dc3fd5161aec89eb002b016fdf68e0126eac35c21c1834aa', 'mappings': []}, {'source': 0, 'name': 'LiveDmBlockSheet', 'start': 11112, 'end': 13816, 'originalSha256LF': '2bf54cf0c8a98f50bcde29e3b0a85277e668a61f3b1e3436e02868dbf6bb9fa9', 'adaptedSha256LF': 'fd2f97d4bfd0772c44aa0944d68be0bf610ad73dbd503c6c83a6937d2b7eb315', 'mappings': [('    onDismiss: () -> Unit\n) {', '    onDismiss: () -> Unit,\n    canEdit: Boolean = true\n) {'), ('enabled = isLoggedIn && keyword.trim().isNotBlank(),', 'enabled = isLoggedIn && canEdit && keyword.trim().isNotBlank(),'), ('onValueChange = { keyword = it.take(20) },\n                    enabled = isLoggedIn,', 'onValueChange = { keyword = it.take(20) },\n                    enabled = isLoggedIn && canEdit,'), ('shieldInfo = shieldInfo,\n                enabled = isLoggedIn,\n                onSetRule = onSetRule', 'shieldInfo = shieldInfo,\n                enabled = isLoggedIn && canEdit,\n                onSetRule = onSetRule'), ('shieldInfo = shieldInfo,\n                enabled = isLoggedIn,\n                onDeleteKeyword = onDeleteKeyword', 'shieldInfo = shieldInfo,\n                enabled = isLoggedIn && canEdit,\n                onDeleteKeyword = onDeleteKeyword'), ('shieldInfo = shieldInfo,\n                enabled = isLoggedIn,\n                onUnblockUser = onUnblockUser', 'shieldInfo = shieldInfo,\n                enabled = isLoggedIn && canEdit,\n                onUnblockUser = onUnblockUser')]}, {'source': 0, 'name': 'LiveRuleSection', 'start': 13818, 'end': 15668, 'originalSha256LF': '9624c8d27d64647f594c48f916fc36604059549976c1198254fd97fea5645661', 'adaptedSha256LF': '9624c8d27d64647f594c48f916fc36604059549976c1198254fd97fea5645661', 'mappings': []}, {'source': 0, 'name': 'LiveKeywordSection', 'start': 15670, 'end': 17932, 'originalSha256LF': '0e4b9d2106d72590c697b9c30df95f78a538a10db9d532afe83b9b2d76f48d0d', 'adaptedSha256LF': '0e4b9d2106d72590c697b9c30df95f78a538a10db9d532afe83b9b2d76f48d0d', 'mappings': []}, {'source': 0, 'name': 'LiveShieldUserSection', 'start': 17934, 'end': 20362, 'originalSha256LF': '849a245b03c4402ca7a7d4c4ae2210a7263833f78b3ec0f7ea122ae3bb6bae68', 'adaptedSha256LF': '849a245b03c4402ca7a7d4c4ae2210a7263833f78b3ec0f7ea122ae3bb6bae68', 'mappings': []}, {'source': 1, 'name': 'LiveDanmakuUserMenu', 'start': 17829, 'end': 19718, 'originalSha256LF': '52732c55aa060de2c7d6e4e641814f852d61e439774479731da0fbf6f859dc42', 'adaptedSha256LF': '8dd4b657ebe86d70278e133f1fd834820b1e4374dfe1ebe03c55b094d2fe8ede', 'mappings': [('private fun LiveDanmakuUserMenu(', 'internal fun DesktopOriginalLiveDanmakuUserMenu('), ('    onReportDanmaku: (LiveDanmakuItem) -> Unit\n) {', '    onReportDanmaku: (LiveDanmakuItem) -> Unit,\n    canReply: Boolean,\n    canModerate: Boolean\n) {'), ('enabled = item.uname.isNotBlank(),', 'enabled = canReply && item.uname.isNotBlank(),'), ('enabled = item.uname.isNotBlank() || item.uid > 0L,', 'enabled = canModerate && (item.uname.isNotBlank() || item.uid > 0L),'), ('enabled = item.text.isNotBlank(),', 'enabled = canModerate && item.text.isNotBlank(),')]}]
IMPORTS = 'package com.android.purebilibili.feature.live.components\n\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.outlined.Block\nimport androidx.compose.material.icons.outlined.Report\nimport androidx.compose.material3.ExperimentalMaterial3Api\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.style.TextOverflow\nimport androidx.compose.ui.unit.dp\nimport com.android.purebilibili.core.ui.AppAlertDialog\nimport com.android.purebilibili.core.ui.AppModalBottomSheet\nimport com.android.purebilibili.core.ui.AppShapes\nimport com.android.purebilibili.core.ui.AppSpacingTokens\nimport com.android.purebilibili.core.ui.AppSurfaceTokens\nimport com.android.purebilibili.core.ui.ContainerLevel\nimport com.android.purebilibili.core.ui.rememberAppClearIcon\nimport com.android.purebilibili.core.ui.components.*\nimport com.android.purebilibili.data.repository.DefaultLiveReportReasons\nimport com.android.purebilibili.data.repository.LiveReportReason\nimport com.android.purebilibili.data.repository.LiveShieldInfo\nimport com.android.purebilibili.data.repository.LiveShieldUser\nimport com.android.purebilibili.feature.live.LiveDanmakuItem\n\n'

def emit(repo: Path, output: Path, write) -> list[Path]:
    sources = []
    for pin in PINS:
        raw = safe(repo / pin["archive"]).read_bytes()
        if len(raw) != pin["rawBytes"] or hashlib.sha256(raw).hexdigest() != pin["rawSha256"] or \
                hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() != pin["gitBlob"]:
            raise ValueError("Fixed complete moderation UI source changed: " + pin["path"])
        sources.append(raw.decode("utf8").replace("\r\n", "\n"))
    parts = []
    projections = list(sources)
    proof = []
    for row in SELECTIONS:
        original = sources[row["source"]][row["start"]:row["end"]]
        if hashlib.sha256(original.encode()).hexdigest() != row["originalSha256LF"]:
            raise ValueError("Complete original moderation UI function changed")
        adapted = original
        for before, after in row["mappings"]:
            if adapted.count(before) != 1: raise ValueError("Moderation UI seam is ambiguous")
            adapted = adapted.replace(before, after, 1)
        inverse = adapted
        for before, after in reversed(row["mappings"]):
            if inverse.count(after) != 1: raise ValueError("Moderation UI inverse is ambiguous")
            inverse = inverse.replace(after, before, 1)
        if inverse != original or hashlib.sha256(adapted.encode()).hexdigest() != row["adaptedSha256LF"]:
            raise ValueError("Complete moderation UI function inverse failed")
        index = row["source"]
        if projections[index].count(original) != 1: raise ValueError("Whole original moderation UI range changed")
        projections[index] = projections[index].replace(original, adapted, 1)
        parts.append(adapted)
        proof.append((index, original, adapted))
    for index, original, adapted in reversed(proof):
        if projections[index].count(adapted) != 1: raise ValueError("Whole original moderation UI inverse ambiguous")
        projections[index] = projections[index].replace(adapted, original, 1)
    if projections != sources: raise ValueError("Whole original moderation UI inverse failed")
    body = IMPORTS + "\n\n".join(parts) + "\n"
    result = write(output, "com/android/purebilibili/feature/live/components/DesktopOriginalLiveModerationUi.kt",
        PINS[0]["archive"], sources[0], body)
    safe(output / "v034-live-moderation-ui-source-proof.json").write_text(json.dumps(dict(
        fixedCommit=COMMIT, completeOriginalSources=PINS, selections=SELECTIONS,
        completeFunctionsAndWholeSourcesInverseExact=True,
        copiedAndroidClipboardReplacedByCaller=True, originalFiveMenuActionsRetained=True,
        sameExistingRoomSession=True), ensure_ascii=True, indent=2) + "\n", encoding="utf8", newline="\n")
    return [result]

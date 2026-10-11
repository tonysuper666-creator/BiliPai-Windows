"""Extract renderer-neutral PGC/offline policies and original download danmaku repository.

Original read/LRU algorithms and Retrofit declarations are retained. Explicit inverse-proved
Windows task/receipt admission adapts the API and cache boundaries in the same existing owner.
"""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import textwrap

BASE = "app/src/main/java/com/android/purebilibili/"
SOURCES = {
    BASE + "data/repository/BangumiRepository.kt": "policy-extract",
    BASE + "data/repository/DanmakuRepository.kt": "extracted",
    "core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt": "extracted",
    BASE + "feature/video/danmaku/DanmakuParser.kt": "extracted",
    BASE + "feature/download/DownloadDanmakuAssetService.kt": "direct",
    BASE + "feature/download/OfflineEpisodeQueuePolicy.kt": "direct",
    BASE + "feature/download/OfflineVideoPlaybackPolicy.kt": "policy-extract",
    BASE + "core/network/socket/DanmakuProtocol.kt": "policy-extract",
    BASE + "core/network/socket/LiveDanmakuConnectionHealthPolicy.kt": "policy-extract",
    BASE + "core/network/socket/LiveDanmakuClient.kt": "extracted",
    BASE + "data/repository/LiveInteractionModels.kt": "direct",
    BASE + "data/repository/LiveRepository.kt": "policy-extract",
    BASE + "feature/live/LiveRealtimeMessagePolicy.kt": "direct",
    BASE + "feature/live/LivePlayerViewModel.kt": "policy-extract",
    BASE + "feature/live/LiveSuperChatExpiryPolicy.kt": "direct",
    BASE + "feature/bangumi/policy/BangumiSeasonActionPolicy.kt": "direct",
    BASE + "feature/bangumi/policy/BangumiFollowStatusPolicy.kt": "policy-extract",
    BASE + "feature/bangumi/policy/BangumiPlaybackUrlPolicy.kt": "direct",
    BASE + "feature/bangumi/policy/CourseNavigationPolicy.kt": "direct",
}


def read(repo: Path, path: str) -> str:
    from v025_source_paths import canonical_source
    return canonical_source(repo,path).read_text(encoding="utf-8").replace("\r\n", "\n")


def parser_for(repo: Path):
    spec = importlib.util.spec_from_file_location("bilipai_media_kotlin", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    return parser


def function(source: str, name: str, parser) -> str:
    matches = list(re.finditer(r"(?m)^[ \t]*(?:(?:internal|private|suspend)\s+)*fun\s+(?:[\w.]+\.)?" + re.escape(name) + r"\s*\(", source))
    if len(matches) != 1:
        raise ValueError(f"Required original function {name} is missing or ambiguous")
    match = matches[0]
    tokens = parser.kotlin_tokens(source)
    index = next(i for i, (_, begin, _) in enumerate(tokens) if begin >= match.start())
    while tokens[index][0] != "(":
        index += 1
    parentheses = 1
    while parentheses:
        index += 1
        parentheses += (tokens[index][0] == "(") - (tokens[index][0] == ")")
    # Every reviewed selection has a block or a `when {}` expression body.
    while tokens[index][0] != "{":
        if tokens[index][1] > match.end() and tokens[index][0] in {"fun", "class", "object"}:
            raise ValueError(f"Original {name} no longer has a supported body")
        index += 1
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == "{") - (tokens[index][0] == "}")
    return textwrap.dedent(source[match.start():tokens[index][2]])


def data_class(source: str, name: str, parser) -> str:
    tokens = parser.kotlin_tokens(source)
    start, end = parser.kotlin_structure(tokens, "class", name, constructor_only=True)
    beginning = source.rfind("\n", 0, tokens[start][1]) + 1
    return source[beginning:tokens[end][2]]


def write(output: Path, relative: str, original_path: str, original: str, body: str) -> Path:
    result = output / relative
    result.parent.mkdir(parents=True, exist_ok=True)
    header = f"// GENERATED from {original_path}; do not edit.\n"
    header += "// LF-normalized SHA-256: " + hashlib.sha256(original.encode()).hexdigest() + "\n"
    result.write_text(header + body.strip() + "\n", encoding="utf-8")
    return result


def emit_live_vote(repo: Path, output: Path, parser) -> list[Path]:
    """Complete fixed snapshot method and real state-merge body, one existing owner."""
    from v030_live_stream import safe, whole_proof
    fixed_commit = "b2091b1de636e499df80af7e700796b4f52b7f92"
    archive = repo / "desktop/upstream-slices/v034-live-vote"
    identities = {
        "app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt": ("0c69a011fa66f0c9829b066aa4fa149a865c996c", "554ca3c32522c88148144c4221879701657aab296519ea0da4162661039d4857", 56832),
        "app/src/main/java/com/android/purebilibili/feature/live/LivePlayerViewModel.kt": ("605d846a97f2faaf5af468d5757d180caf1ba098", "e86a019aa43d9d5aca595ff6073bb0cf50d00436142bb5a0bda50655b2507e74", 63055),
    }
    fixed = {}
    for path, (blob, digest, size) in identities.items():
        raw = safe(archive / path).read_bytes()
        if len(raw) != size or hashlib.sha256(raw).hexdigest() != digest or \
                hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest() != blob:
            raise ValueError("Fixed original live-vote source changed: " + path)
        fixed[path] = raw.decode("utf8").replace("\r\n", "\n")
    repository_path = "app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt"
    viewmodel_path = "app/src/main/java/com/android/purebilibili/feature/live/LivePlayerViewModel.kt"
    original_repository = fixed[repository_path]
    original_viewmodel = fixed[viewmodel_path]
    snapshot_before = function(original_repository, "getLiveVoteSnapshot", parser)
    if hashlib.sha256(snapshot_before.encode()).hexdigest() != "6ab5a616906704f9d83524897ed1fbf4b320008501a99194e581d00e2fd84b00":
        raise ValueError("Complete original live-vote snapshot method changed")
    header_before = "suspend fun getLiveVoteSnapshot(roomId: Long): Result<LiveVoteSnapshot>"
    header_after = "internal suspend fun requestOriginalDesktopLiveVoteSnapshot(\n    canonicalRoomId: Long,\n    api: BilibiliApi,\n): Result<LiveVoteSnapshot>"
    normalization_before = "val realRoomId = resolveRealRoomId(roomId)"
    normalization_after = "val realRoomId = canonicalRoomId"
    if snapshot_before.count(header_before) != 1 or snapshot_before.count(normalization_before) != 1:
        raise ValueError("Original live-vote platform seams changed")
    snapshot_after = snapshot_before.replace(header_before, header_after, 1).replace(normalization_before, normalization_after, 1)
    snapshot_inverse = snapshot_after.replace(header_after, header_before, 1).replace(normalization_after, normalization_before, 1)
    snapshot_original_range = textwrap.indent(snapshot_before, "    ")
    snapshot_adapted_range = textwrap.indent(snapshot_after, "    ")
    if snapshot_inverse != snapshot_before or original_repository.count(snapshot_original_range) != 1:
        raise ValueError("Complete original live-vote snapshot inverse failed")
    repository_projection = original_repository.replace(snapshot_original_range, snapshot_adapted_range, 1)
    if repository_projection.replace(snapshot_adapted_range, textwrap.indent(snapshot_inverse, "    "), 1) != original_repository:
        raise ValueError("Whole original live-vote repository inverse failed")
    merge_range = "            is LiveRealtimeAction.UpdateVote -> {\n                val previous = _voteSnapshot.value\n                _voteSnapshot.value = previous.copy(\n                    current = action.vote,\n                    history = if (action.vote.isActive) previous.history else {\n                        listOf(action.vote) + previous.history.filterNot {\n                            it.interactionId > 0L && it.interactionId == action.vote.interactionId\n                        }\n                    }.take(10)\n                )\n                emitLiveChatItem(action.announcement)\n            }"
    if original_viewmodel.count(merge_range) != 1:
        raise ValueError("Complete original live-vote merge range changed")
    # Only unwrap the original when-branch. Its entire state merge and announcement
    # body stays byte-equal; the existing session supplies the same flow and sink.
    merge_lines = merge_range.splitlines()
    if merge_lines[0] != "            is LiveRealtimeAction.UpdateVote -> {" or merge_lines[-1] != "            }":
        raise ValueError("Original live-vote merge branch changed")
    merge_body = textwrap.dedent("\n".join(merge_lines[1:-1]))
    if hashlib.sha256(merge_body.encode()).hexdigest() != "d7a914a449ee4c85373ce766df5097e3ee2b1c23e5ef2cd2d2f162811096baec":
        raise ValueError("Complete original live-vote merge body changed")
    merge_prefix = "internal fun applyOriginalDesktopLiveVoteUpdate(\n    _voteSnapshot: MutableStateFlow<LiveVoteSnapshot>,\n    action: LiveRealtimeAction.UpdateVote,\n    emitLiveChatItem: (LiveDanmakuItem) -> Unit,\n) {\n"
    merge_after = merge_prefix + textwrap.indent(merge_body, "    ") + "\n}"
    recovered_body = textwrap.dedent(merge_after[len(merge_prefix):-2])
    recovered_range = merge_lines[0] + "\n" + textwrap.indent(recovered_body, "                ") + "\n" + merge_lines[-1]
    if recovered_body != merge_body or recovered_range != merge_range or original_viewmodel.replace(merge_range, recovered_range, 1) != original_viewmodel:
        raise ValueError("Whole original live-vote ViewModel extraction inverse failed")
    # Existing canonical types/parsers and actual API must expose the exact reused
    # source contracts. Their whole files are not relabelled as this new source epoch.
    contracts = [
        ("app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt", "internal fun parseLiveVotePanel(rawJson: String): LiveVoteInfo? {\n    val root = JSONObject(rawJson)\n    if (root.optInt(\"code\") != 0) return null\n    return root.optJSONObject(\"data\")?.optJSONObject(\"vote_info\")?.let(::parseLiveVoteInfo)\n}\n\ninternal fun parseLiveVoteHistory(rawJson: String): List<LiveVoteInfo> {\n    val root = JSONObject(rawJson)\n    if (root.optInt(\"code\") != 0) return emptyList()\n    val history = root.optJSONObject(\"data\")?.optJSONArray(\"history\") ?: return emptyList()\n    return buildList {\n        for (index in 0 until history.length()) {\n            history.optJSONObject(index)?.let(::parseLiveVoteInfo)?.let(::add)\n        }\n    }\n}\n\nprivate fun parseLiveVoteInfo(json: JSONObject): LiveVoteInfo? {\n    val question = json.optString(\"question\").trim()\n    if (question.isBlank()) return null\n    val optionsJson = json.optJSONArray(\"options\")\n    val options = buildList {\n        if (optionsJson != null) for (index in 0 until optionsJson.length()) {\n            val option = optionsJson.optJSONObject(index) ?: continue\n            val description = option.optString(\"desc\").trim()\n            if (description.isNotBlank()) {\n                add(\n                    LiveVoteOption(\n                        id = option.optInt(\"idx\"),\n                        description = description,\n                        percent = option.optDouble(\"percent\", 0.0).toFloat().coerceIn(0f, 1f)\n                    )\n                )\n            }\n        }\n    }\n    return LiveVoteInfo(\n        status = json.optInt(\"status\"),\n        question = question,\n        options = options,\n        durationMillis = json.optLong(\"duration\"),\n        remainingMillis = json.optLong(\"left_duration\"),\n        resultText = json.optString(\"result_text\"),\n        endTimeText = json.optString(\"etime_str\"),\n        interactionId = json.optLong(\"interaction_id\")\n    )\n}\n"),
        ("app/src/main/java/com/android/purebilibili/data/repository/LiveInteractionModels.kt", "data class LiveVoteOption(val id: Int = 0, val description: String = \"\", val percent: Float = 0f)\n\ndata class LiveVoteInfo(\n    val status: Int = 0,\n    val question: String = \"\",\n    val options: List<LiveVoteOption> = emptyList(),\n    val durationMillis: Long = 0L,\n    val remainingMillis: Long = 0L,\n    val resultText: String = \"\",\n    val endTimeText: String = \"\",\n    val interactionId: Long = 0L\n) {\n    val isActive: Boolean get() = status == 4\n}\n\ndata class LiveVoteSnapshot(\n    val current: LiveVoteInfo? = null,\n    val history: List<LiveVoteInfo> = emptyList()\n)\n"),
        ("app/src/main/java/com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt", "private fun parseVoteInteraction(data: JsonObject): LiveRealtimeAction {\n    val question = data.string(\"question\").ifBlank { return LiveRealtimeAction.Ignore }\n    val voteOptions = data.array(\"options\").orEmpty()\n        .mapNotNull { option ->\n            val obj = option.asObjectOrNull() ?: return@mapNotNull null\n            val desc = obj.string(\"desc\").ifBlank { return@mapNotNull null }\n            LiveVoteOption(\n                id = obj.int(\"idx\"),\n                description = desc,\n                percent = obj.float(\"percent\").coerceIn(0f, 1f)\n            )\n        }\n        .take(2)\n    if (voteOptions.isEmpty()) return LiveRealtimeAction.Ignore\n    val remainingSeconds = (data.long(\"left_duration\") / 1000L).coerceAtLeast(0L)\n    val suffix = if (remainingSeconds > 0L) \"｜剩余 ${remainingSeconds}s\" else \"\"\n    val optionSummary = voteOptions.joinToString(\" / \") {\n        \"${it.description} ${(it.percent * 100f).roundToInt()}%\"\n    }\n    val announcement = systemMessage(\"投票\", \"投票：$question｜$optionSummary$suffix\")\n    val chat = (announcement as? LiveRealtimeAction.EmitChat)?.item ?: return LiveRealtimeAction.Ignore\n    return LiveRealtimeAction.UpdateVote(\n        vote = LiveVoteInfo(\n            status = data.int(\"status\", 4),\n            question = question,\n            options = voteOptions,\n            durationMillis = data.long(\"duration\"),\n            remainingMillis = data.long(\"left_duration\"),\n            resultText = data.string(\"result_text\"),\n            endTimeText = data.string(\"etime_str\"),\n            interactionId = data.long(\"interaction_id\")\n        ),\n        announcement = chat\n    )\n}\n"),
        ("core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt", "    @GET(\"https://api.live.bilibili.com/xlive/app-room/v1/dm/interaction/votePanel\")\n    suspend fun getLiveVotePanel(@Query(\"room_id\") roomId: Long): ResponseBody\n\n    @GET(\"https://api.live.bilibili.com/xlive/app-room/v1/dm/interaction/voteHistory\")\n    suspend fun getLiveVoteHistory(@Query(\"room_id\") roomId: Long): ResponseBody\n"),
    ]
    for path, contract in contracts:
        if read(repo, path).count(contract) != 1:
            raise ValueError("Existing live-vote source contract changed: " + path)
    body = "package com.android.purebilibili.data.repository\n\n"
    body += "import com.android.purebilibili.core.network.BilibiliApi\n"
    body += "import com.android.purebilibili.feature.live.LiveDanmakuItem\n"
    body += "import com.android.purebilibili.feature.live.LiveRealtimeAction\n"
    body += "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\n"
    body += "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.flow.MutableStateFlow\n\n"
    body += "// This argument is the canonical room from the existing Windows liveRoom result.\n"
    body += "// Room alias resolution remains with that existing owned initial room request.\n"
    body += snapshot_after + "\n\n" + merge_after + "\n"
    generated = safe(output / "com/android/purebilibili/data/repository/DesktopLiveVoteSnapshot.kt")
    generated.parent.mkdir(parents=True, exist_ok=True)
    header = "// GENERATED from fixed b209 whole LiveRepository.kt and LivePlayerViewModel.kt; do not edit.\n"
    header += "// Repository raw SHA-256: " + identities[repository_path][1] + "\n"
    header += "// ViewModel raw SHA-256: " + identities[viewmodel_path][1] + "\n"
    if hashlib.sha256((header + body).encode()).hexdigest() != "062465cc3e445b911c095c8cf82ec2554d1105ecff39b1e40f84f90903e43520":
        raise ValueError("Complete original live-vote generated output changed")
    generated.write_text(header + body, encoding="utf8", newline="\n")
    proof = dict(fixedUpstreamCommit=fixed_commit, completeRawInputs=identities,
                 snapshotMethod=whole_proof(snapshot_before, snapshot_after),
                 wholeRepository=whole_proof(original_repository, repository_projection),
                 originalMergeRange=merge_range, generatedMergeMethod=merge_after,
                 exactWholeViewModelExtractionInverse=True,
                 outputSha256LF=hashlib.sha256((header + body).encode()).hexdigest())
    safe(output / "v034-live-vote-source-proof.json").write_text(json.dumps(proof, ensure_ascii=True, indent=2) + "\n", encoding="utf8", newline="\n")
    return [generated]


def emit_live_chat_image(repo: Path, output: Path) -> list[Path]:
    """Exact fixed source RANGE, not a whole upstream composable; existing Coil owner only."""
    from v030_live_stream import safe
    identity = dict(path="app/src/main/java/com/android/purebilibili/feature/live/components/LiveChatSection.kt",
        upstreamCommit="0e2206a85e288ba08f361cc636fab0710c2b9ab8",
        gitBlob="c76fca866b728bec8fba64eb753cb7c5823810ea",
        sha256="a947af01db7208d3a04f0902aa74a08716552614388bb445a4663cddd61dc914", bytes=33625)
    root = repo / "desktop/upstream-slices/v030-live-chat-image"
    if json.loads(safe(root / "manifest.json").read_bytes()) != dict(schema=1, source=identity):
        raise ValueError("Live chat image source identity changed")
    raw = safe(root / identity["path"]).read_bytes()
    if len(raw) != identity["bytes"] or hashlib.sha256(raw).hexdigest() != identity["sha256"] or \
            hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() != identity["gitBlob"]:
        raise ValueError("Live chat image raw source changed")
    selected = b"""                    AsyncImage(
                        model = item.emoticonUrl,
                        contentDescription = item.text,
                        modifier = Modifier.size(AppSpacingTokens.DoubleExtraLarge)
                    )"""
    if raw.count(selected) != 1:
        raise ValueError("Original live chat image call is missing or ambiguous")
    offset = raw.index(selected)
    original = "\n".join(line[20:] for line in selected.decode("utf8").splitlines())
    before = "    modifier = Modifier.size(AppSpacingTokens.DoubleExtraLarge)\n"
    after = "    modifier = Modifier.size(AppSpacingTokens.DoubleExtraLarge),\n    onError = { onError() }\n"
    if original.count(before) != 1:
        raise ValueError("Original live image modifier changed")
    adapted = original.replace(before, after, 1)
    inverse = adapted.replace(after, before, 1)
    if inverse != original or textwrap.indent(inverse, " " * 20).encode("utf8") != selected:
        raise ValueError("Original live chat image range inverse failed")
    header = """// GENERATED from a fixed LiveChatSection.kt byte range; not a whole upstream composable.
package com.android.purebilibili.feature.live.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.feature.live.LiveDanmakuItem

@Composable
internal fun DesktopOriginalLiveChatImage(item: LiveDanmakuItem, onError: () -> Unit) {
"""
    body = header + textwrap.indent(adapted, "    ") + "\n}\n"
    target = safe(output / "com/android/purebilibili/feature/live/components/DesktopOriginalLiveChatImage.kt")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(body.encode("utf8"))
    proof = dict(source=identity, wholeOriginalComposable=False,
        selectedByteStart=offset, selectedByteEnd=offset + len(selected),
        selectedSha256=hashlib.sha256(selected).hexdigest(), selectedUtf8=selected.decode("utf8"),
        rangeInverseVerified=True, indentationRemoved=20,
        adaptations=[dict(before=before, after=after, count=1,
            reason="Existing Windows text fallback after the same Coil image request fails")],
        generatedSha256=hashlib.sha256(target.read_bytes()).hexdigest(),
        sharedExistingCoilSingleton=True, newNetworkBusiness=False, animationParityClaimed=False)
    safe(output / "v030-live-chat-image-source-proof.json").write_bytes((json.dumps(proof, indent=2) + "\n").encode())
    return [target]



def bind_offline_task_repository(body):
    # Adapt only the existing original owner; no second cache, client or account state.
    changes = []
    mappings = [('private data class DanmakuSegmentCacheKey(\n    val cid: Long,\n    val segmentIndex: Int\n)', 'private data class DanmakuRawCacheKey(val cid: Long, val authorization: com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt)\n\nprivate data class DanmakuSegmentCacheKey(\n    val cid: Long,\n    val segmentIndex: Int,\n    val authorization: com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt\n)', 1, 'Complete nonsecret receipt extends both existing cache keys'), ('LinkedHashMap<Long, ByteArray>(5, 0.75f, true)', 'LinkedHashMap<DanmakuRawCacheKey, ByteArray>(5, 0.75f, true)', 1, 'Retain the same original raw LRU/count/bytes owner'), ('        // 先检查缓存\n        synchronized(danmakuCache) {\n            danmakuCache[cid]?.let { return@withContext it }\n        }', '        val binding = com.bilipai.desktop.download.DownloadDanmakuTransport.currentBinding()\n        val cacheKey = DanmakuRawCacheKey(cid, binding.cacheReceipt)\n        val cached = binding.admit { synchronized(danmakuCache) { danmakuCache[cacheKey] } }\n        if (cached != null) return@withContext cached', 1, 'Raw access-order hit is admitted'), ('                    synchronized(danmakuCache) {\n                        danmakuCache.remove(cid)?.let { danmakuCacheBytes -= it.size.toLong() }\n\n                        val iterator = danmakuCache.entries.iterator()\n                        while (iterator.hasNext() &&\n                            (danmakuCache.size >= MAX_DANMAKU_CACHE_COUNT ||\n                                danmakuCacheBytes + entrySize > MAX_DANMAKU_CACHE_BYTES)\n                        ) {\n                            val eldest = iterator.next()\n                            danmakuCacheBytes -= eldest.value.size.toLong()\n                            iterator.remove()\n                        }\n                        danmakuCache[cid] = result\n                        danmakuCacheBytes += entrySize\n                    }', '                    binding.admit {\n                        synchronized(danmakuCache) {\n                            danmakuCache.remove(cacheKey)?.let { danmakuCacheBytes -= it.size.toLong() }\n\n                            val iterator = danmakuCache.entries.iterator()\n                            while (iterator.hasNext() &&\n                                (danmakuCache.size >= MAX_DANMAKU_CACHE_COUNT ||\n                                    danmakuCacheBytes + entrySize > MAX_DANMAKU_CACHE_BYTES)\n                            ) {\n                                val eldest = iterator.next()\n                                danmakuCacheBytes -= eldest.value.size.toLong()\n                                iterator.remove()\n                            }\n                            danmakuCache[cacheKey] = result\n                            danmakuCacheBytes += entrySize\n                        }\n                    }', 1, 'Raw remove/evict/put/byte accounting Store -> queue -> same cache'), ('            result\n        } catch (e: CancellationException)', '            binding.assertCurrent()\n            result\n        } catch (e: CancellationException)', 1, 'Retired raw result rejected after IO/decompression'), ('        val cacheKey = DanmakuSegmentCacheKey(cid, segmentIndex)\n        synchronized(danmakuSegmentCache) {\n            danmakuSegmentCache[cacheKey]?.let { return@withContext it }\n        }', '        val binding = com.bilipai.desktop.download.DownloadDanmakuTransport.currentBinding()\n        val cacheKey = DanmakuSegmentCacheKey(cid, segmentIndex, binding.cacheReceipt)\n        val cached = binding.admit { synchronized(danmakuSegmentCache) { danmakuSegmentCache[cacheKey] } }\n        if (cached != null) return@withContext cached', 1, 'Parallel child hit uses inherited original root guard and receipt'), ('            synchronized(danmakuSegmentCache) {\n                danmakuSegmentCache.remove(cacheKey)?.let { removed ->\n                    danmakuSegmentCacheBytes -= removed.size.toLong()\n                }\n                val iterator = danmakuSegmentCache.entries.iterator()\n                while (\n                    iterator.hasNext() &&\n                    (danmakuSegmentCache.size >= MAX_SEGMENT_CACHE_COUNT ||\n                        danmakuSegmentCacheBytes + entrySize > MAX_SEGMENT_CACHE_BYTES)\n                ) {\n                    val eldest = iterator.next()\n                    danmakuSegmentCacheBytes -= eldest.value.size.toLong()\n                    iterator.remove()\n                }\n                danmakuSegmentCache[cacheKey] = bytes\n                danmakuSegmentCacheBytes += entrySize\n            }', '            binding.admit {\n                synchronized(danmakuSegmentCache) {\n                    danmakuSegmentCache.remove(cacheKey)?.let { removed ->\n                        danmakuSegmentCacheBytes -= removed.size.toLong()\n                    }\n                    val iterator = danmakuSegmentCache.entries.iterator()\n                    while (\n                        iterator.hasNext() &&\n                        (danmakuSegmentCache.size >= MAX_SEGMENT_CACHE_COUNT ||\n                            danmakuSegmentCacheBytes + entrySize > MAX_SEGMENT_CACHE_BYTES)\n                    ) {\n                        val eldest = iterator.next()\n                        danmakuSegmentCacheBytes -= eldest.value.size.toLong()\n                        iterator.remove()\n                    }\n                    danmakuSegmentCache[cacheKey] = bytes\n                    danmakuSegmentCacheBytes += entrySize\n                }\n            }', 1, 'Segment remove/evict/put/byte accounting under same owner'), ('        bytes\n    }\n\n    /**\n     * 并发拉取', '        binding.assertCurrent()\n        bytes\n    }\n\n    /**\n     * 并发拉取', 1, 'Segment result still belongs to original task')]
    mappings.extend([
        ("    private val api get() = com.bilipai.desktop.download.DownloadDanmakuTransport.api",
         "    private suspend fun api() = com.bilipai.desktop.download.DownloadDanmakuTransport.currentBinding().api()", 1,
         "Actual coroutine request facade captures child body-cancellation Job"),
        ("api.", "api().", 5, "All core/canonical metadata/legacy-special/v029 streaming API calls bound"),
        ("                            output.write(buffer, 0, count)",
         "                            com.bilipai.desktop.download.DownloadDanmakuTransport.currentBinding().assertCurrent()\n                            output.write(buffer, 0, count)", 1,
         "Owned special file check outside short gates"),
    ])
    for before, after, count, reason in mappings:
        if body.count(before) != count:
            raise ValueError("Pinned offline binding boundary changed: " + reason)
        changes.append(dict(before=before, after=after, count=count, reason=reason))
        body = body.replace(before, after, count)
    inverse = body
    for row in reversed(changes):
        if inverse.count(row["after"]) != row["count"]:
            raise ValueError("Offline binding inverse ambiguous")
        inverse = inverse.replace(row["after"], row["before"], row["count"])
    return body, changes, inverse


def generate(repo: Path, output: Path, test_output: Path | None = None) -> list[Path]:
    parser = parser_for(repo)
    generated = []
    path = BASE + "data/repository/BangumiRepository.kt"
    source = read(repo, path)
    pieces = ["package com.android.purebilibili.data.repository",
        "import com.android.purebilibili.data.model.response.*",
        "import kotlinx.serialization.json.*", "import kotlinx.serialization.decodeFromString",
        data_class(source, "BangumiPlayUrlPayload", parser)]
    for name in ["shouldFallbackToLegacyBangumiPlayUrl", "validateBangumiPlayableVideoInfo", "hasPlayableWebStream",
                 "decodeBangumiPlayUrlPayload", "shouldLoadBangumiSections", "mergeBangumiDetailSections",
                 "mergeBangumiEpisodes", "mergeBangumiSections"]:
        pieces.append(function(source, name, parser))
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopMediaPgcPolicies.kt", path, source, "\n\n".join(pieces)))

    account_path = BASE + "data/repository/DanmakuRepository.kt"
    account_source = read(repo, account_path)
    content_path = "core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt"
    content_source = read(repo, content_path)
    # Entire canonical read/cache schema and object body in the existing owner.
    # The account methods use the same already configured transport getter.
    body = content_source
    for original,replacement in {
        "import com.android.purebilibili.core.network.NetworkModule\n": "",
        "object DanmakuContentRepository {": "object DanmakuRepository {",
        "    private val api = NetworkModule.api": "    private val api get() = com.bilipai.desktop.download.DownloadDanmakuTransport.api",
    }.items():
        if body.count(original) != 1:
            raise ValueError("Original shared danmaku read/cache boundary changed: " + original)
        body=body.replace(original,replacement,1)
    closing=body.rfind("}")
    if body[closing:].strip() != "}":
        raise ValueError("Canonical danmaku content object closing changed")
    pieces=[]
    for name in ["getDanmakuView", "getSpecialDanmakuSegments"]:
        pieces.append(textwrap.indent(function(account_source,name,parser),"    "))
    special_spec=importlib.util.spec_from_file_location("desktop_special_download",repo / "desktop/tools/extract-upstream-special-danmaku.py")
    special=importlib.util.module_from_spec(special_spec);special_spec.loader.exec_module(special)
    special_source=special.checked_inputs(repo)["DanmakuRepository.kt"][1]
    original_download=function(special_source,"downloadSpecialDanmaku",parser)
    download=original_download
    download_changes=[
        ("api.getDanmakuSpecialDm(resolvedUrl).use { body ->", "com.bilipai.desktop.danmaku.DesktopSpecialBodyRead.use(api.getDanmakuSpecialDm(resolvedUrl)) { body ->"),
        ('android.util.Log.w("DanmakuRepo", "Special danmaku export failed: ${e.message}")', 'android.util.Log.w("DanmakuRepo", "Special danmaku export failed")'),
    ]
    for before,after in download_changes:
        if download.count(before)!=1:raise ValueError("Special offline streaming port changed")
        download=download.replace(before,after,1)
    inverse=download
    for before,after in reversed(download_changes):inverse=inverse.replace(after,before,1)
    if inverse!=original_download:raise ValueError("Original special export inverse changed")
    pieces.append(textwrap.indent(download,"    "))
    body=body.replace("package com.android.purebilibili.data.repository", "package com.android.purebilibili.data.repository\n\nimport java.io.File\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive",1)
    output.mkdir(parents=True,exist_ok=True)
    (output/"special-download-source-proof.json").write_text(json.dumps(dict(
        originalCommit=special.COMMIT, originalRawSha256=special.PINS["DanmakuRepository.kt"],
        originalSelectedBodyInverseExact=True, selectedSha256LF=hashlib.sha256(original_download.encode()).hexdigest(),
        mappings=[dict(before=b,after=a) for b,a in download_changes],
        existingRepositoryAndTransport=True,originalStandardAlgorithmRetained=True,standardCacheBindingAdapted=True),indent=2)+"\n",encoding="utf8")
    # Legacy public clear entry is retained on the same existing object only.
    pieces.append("    fun clearDanmakuCache() = clearCache()")
    closing=body.rfind("}")
    body=body[:closing]+"\n"+"\n\n".join(pieces)+"\n}"+body[closing+1:]
    unbound_body = body
    body, binding_changes, inverse = bind_offline_task_repository(body)
    if inverse != unbound_body:
        raise ValueError("Original offline composite binding inverse failed")
    (output/"offline-task-binding-source-proof.json").write_text(json.dumps(dict(
        canonicalContentSha256LF=hashlib.sha256(content_source.encode()).hexdigest(),
        canonicalAccountSha256LF=hashlib.sha256(account_source.encode()).hexdigest(),
        sameRepositoryCacheOwner=True, originalLruLimitsAndParallelism=True, noGlobalApiOrMetadata=True,
        wholeCompositeInverseExact=True, mappings=binding_changes),indent=2)+"\n",encoding="utf8")
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt", content_path, content_source, body))

    path = BASE + "feature/video/danmaku/DanmakuParser.kt"
    source = read(repo, path)
    from v025_source_paths import canonical_source
    canonical_path=canonical_source(repo,path).relative_to(repo.resolve()).as_posix()
    body = "package com.android.purebilibili.danmaku.parser\n\nobject DanmakuParser {\n" + textwrap.indent(function(source, "parseWebViewReply", parser), "    ") + "\n}"
    generated.append(write(output, "com/android/purebilibili/danmaku/parser/DesktopDanmakuMetadataParser.kt", canonical_path, source, body))

    path = BASE + "feature/download/OfflineVideoPlaybackPolicy.kt"
    source = read(repo, path)
    body = "package com.android.purebilibili.feature.download\n\n" + function(source, "resolveOfflinePersistedPlaybackPosition", parser)
    generated.append(write(output, "com/android/purebilibili/feature/download/DesktopOfflinePositionPolicy.kt", path, source, body))

    path = BASE + "data/repository/DanmakuRepository.kt"
    source = read(repo, path)
    # This was an inline algorithm in the original startLiveDanmaku method.
    # Preserve it exactly while supplying hosts from the same upstream response.
    start = source.index("            val orderedHosts = hosts.sortedWith(")
    end = source.index("\n\n            com.android.purebilibili.core.util.Logger.d(", start)
    host_body = textwrap.dedent(source[start:end])
    if "val webSocketUrls =" not in host_body or host_body.count(".distinct()") != 1:
        raise ValueError("Original live host ordering algorithm changed")
    body = "package com.android.purebilibili.data.repository\n\ninternal fun resolveDesktopLiveDanmakuHosts(hosts: List<com.android.purebilibili.data.model.response.LiveDanmuHost>): List<String> {\n"
    body += textwrap.indent(host_body, "    ") + "\n    return webSocketUrls\n}"
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopLiveHosts.kt", path, source, body))

    from v030_live_danmaku import emit_socket
    generated.extend(emit_socket(repo, output, test_output))

    path = BASE + "data/repository/LiveRepository.kt"
    source = read(repo, path)
    # The entire original renderer-neutral model/parser prelude is reused. All
    # repository methods following the singleton keep their Retrofit declarations
    # in the shared API extractor and are called by the Windows thin adapter.
    if source.count("object LiveRepository {") != 1:
        raise ValueError("Original live repository singleton declaration changed")
    body = source.split("object LiveRepository {", 1)[0]
    for binding in ["import com.android.purebilibili.core.network.AppSignUtils\n",
                    "import com.android.purebilibili.core.network.NetworkModule\n",
                    "import com.android.purebilibili.core.store.TokenManager\n"]:
        if body.count(binding) != 1:
            raise ValueError(f"Original live policy import changed: {binding!r}")
        body = body.replace(binding, "")
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopLivePolicies.kt", path, source, body))

    path = BASE + "feature/live/LivePlayerViewModel.kt"
    source = read(repo, path)
    body = "package com.android.purebilibili.feature.live\n\n" + data_class(source, "LiveDanmakuItem", parser)
    generated.append(write(output, "com/android/purebilibili/feature/live/DesktopLiveDanmakuItem.kt", path, source, body))

    path = BASE + "feature/bangumi/policy/BangumiFollowStatusPolicy.kt"
    source = read(repo, path)
    start = source.index("internal const val BANGUMI_FOLLOW_STATUS_UNFOLLOW")
    end = source.index("internal fun isBangumiFollowed", start)
    body = "package com.android.purebilibili.feature.bangumi\n\nimport com.android.purebilibili.data.model.response.UserStatus\n\n" + source[start:end]
    for name in ["isBangumiFollowed", "resolveBangumiFollowStatusLabel", "resolveBangumiMergedFollowStatus"]:
        body += function(source, name, parser) + "\n\n"
    generated.append(write(output, "com/android/purebilibili/feature/bangumi/DesktopFollowPolicies.kt", path, source, body))
    from v030_live_stream import emit_media
    generated.extend(emit_media(repo, output, test_output))
    from v030_live_recovery import emit_recovery
    generated.extend(emit_recovery(repo, output))
    generated.extend(emit_live_chat_image(repo, output))
    generated.extend(emit_live_vote(repo, output, parser))
    return generated


if __name__ == "__main__":
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--repo", type=Path, required=True)
    command.add_argument("--output", type=Path)
    command.add_argument("--test-output", type=Path)
    command.add_argument("--inventory", action="store_true")
    arguments = command.parse_args()
    repo = arguments.repo.resolve()
    if arguments.inventory:
        print(json.dumps([{"path": path, "mode": mode, "features": ["media", "downloads"],
            "sha256": hashlib.sha256(read(repo, path).encode()).hexdigest()} for path, mode in SOURCES.items()], indent=2))
    elif arguments.output:
        for path in generate(repo, arguments.output.resolve(), arguments.test_output.resolve() if arguments.test_output else None):
            print(path)
    else:
        command.error("Pass --output or --inventory")

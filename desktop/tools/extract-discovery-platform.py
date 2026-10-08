#!/usr/bin/env python3
"""Expose selected pure discovery declarations, preserving upstream declaration bodies."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import textwrap

BASE = "app/src/main/java/com/android/purebilibili/"
SOURCES = {
    BASE + "feature/home/HomeUiState.kt": "policy-extract",
    BASE + "feature/home/HomeViewModel.kt": "policy-extract",
    BASE + "feature/partition/PartitionScreen.kt": "policy-extract",
    BASE + "data/repository/VideoRepository.kt": "policy-extract",
    BASE + "feature/plugin/BiliPaiFeedFilterPlugin.kt": "policy-extract",
    BASE + "feature/home/PopularFeedPolicy.kt": "direct",
    BASE + "feature/home/HomeRefreshUiPolicy.kt": "direct",
    BASE + "feature/video/ui/components/CollectionEpisodePolicy.kt": "direct",
    # The existing dynamic-editor producer owns the complete fixed public v032 FormatUtils.
    BASE + "core/util/FormatUtils.kt": "policy-extract",
    BASE + "feature/home/HomeFeedMergePolicy.kt": "direct",
    BASE + "feature/home/HomeNotInterestedPolicy.kt": "direct",
    "network-core/src/main/java/com/android/purebilibili/core/network/policy/MergedAppFeedCookiePolicy.kt": "direct",
    BASE + "core/store/SettingsManager.kt": "policy-extract",
    BASE + "core/store/TodayWatchFeedbackStore.kt": "platform-rewrite",
    BASE + "data/repository/ActionRepository.kt": "policy-extract",
    BASE + "data/repository/BlockedUpRepository.kt": "policy-extract",
    BASE + "core/network/ApiClient.kt": "policy-extract",
}


def inventory(repo: Path) -> list[dict]:
    return [{"path": path, "mode": mode, "features": ["discovery"],
             "sha256": hashlib.sha256((_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").encode("utf-8")).hexdigest()}
            for path, mode in SOURCES.items()]


def generate(repo: Path, output: Path) -> None:
    spec = importlib.util.spec_from_file_location("discovery_kotlin_parser", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    selections = [
        ("app/src/main/java/com/android/purebilibili/feature/home/HomeUiState.kt", "com.android.purebilibili.feature.home",
         [("class", "HomeCategory", False), ("class", "PopularSubCategory", False)], [], ""),
        ("app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt", "com.android.purebilibili.feature.partition",
         [], [],
         "// Complete original Partition producer now owns class, list and resolver once."),
        ("app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt", "com.android.purebilibili.data.repository",
         [("fun", "shouldFallbackRegionLatestToRanking", False)], ["UGC_MAIN_REGION_TIDS", "REGION_TID_TO_RANKING_RID"], ""),
        ("app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt", "com.android.purebilibili.feature.home",
         [("fun", "resolveRecommendFeedRequestIndex", False)], [], ""),
        ("app/src/main/java/com/android/purebilibili/feature/plugin/BiliPaiFeedFilterPlugin.kt", "com.android.purebilibili.feature.plugin",
         [("class", "BiliPaiFeedFilterConfig", True), ("fun", "shouldShowFeedItem", False)], [],
         "import kotlinx.serialization.Serializable\nimport com.android.purebilibili.core.plugin.FeedKind\nimport com.android.purebilibili.data.model.response.VideoItem"),
    ]
    for relative, package, declarations, values, imports in selections:
        source = (_desktop_canonical_source(repo, relative)).read_text(encoding="utf-8")
        tokens = parser.kotlin_tokens(source)
        pieces = ["// GENERATED verbatim from " + relative, "package " + package, imports]
        for name in values:
            starts = [i for i, token in enumerate(tokens[:-1]) if token[0] == "val" and tokens[i + 1][0] == name]
            if len(starts) != 1:
                raise ValueError("Missing or duplicate upstream discovery value: " + name)
            start = starts[0]
            begin = source.rfind("\n", 0, tokens[start][1]) + 1
            end = start
            while tokens[end][0] != "(":
                end += 1
            depth = 1
            while depth:
                end += 1
                depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
            pieces.append(source[begin:tokens[end][2]])
        for kind, name, constructor_only in declarations:
            if kind == "fun":
                matches = [i for i, token in enumerate(tokens[:-1]) if token[0] == "fun" and tokens[i + 1][0] == name]
                if len(matches) != 1:
                    raise ValueError("Missing or duplicate upstream discovery function: " + name)
                start = matches[0]
                end = start
                while tokens[end][0] != "(":
                    end += 1
                depth = 1
                while depth:
                    end += 1
                    depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
                while tokens[end][0] != "{":
                    end += 1
                depth = 1
                while depth:
                    end += 1
                    depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
            else:
                start, end = parser.kotlin_structure(tokens, kind, name, constructor_only=constructor_only)
            begin = source.rfind("\n", 0, tokens[start][1]) + 1
            declaration = source[begin:tokens[end][2]]
            if name == "BiliPaiFeedFilterConfig":
                declaration = "@Serializable\n" + declaration
            pieces.append(declaration)
        if package.endswith("repository"):
            matches = list(re.finditer(r"(?m)^internal fun resolveRegionRankingRid\(tid: Int\): Int\? = REGION_TID_TO_RANKING_RID\[tid\]$", source))
            if len(matches) != 1:
                raise ValueError("Upstream region ranking mapping changed")
            pieces.append(matches[0].group(0))
            functions = [i for i, token in enumerate(tokens[:-1]) if token[0] == "fun" and tokens[i + 1][0] == "fetchWebFeed"]
            if len(functions) != 1:
                raise ValueError("Missing or duplicate upstream web recommendation implementation")
            cursor = functions[0]
            while tokens[cursor][0] != "{":
                cursor += 1
            end_function = cursor
            depth = 1
            while depth:
                end_function += 1
                depth += (tokens[end_function][0] == "{") - (tokens[end_function][0] == "}")
            params = [i for i in range(cursor, end_function - 3) if [t[0] for t in tokens[i:i + 4]] == ["val", "params", "=", "mapOf"]]
            if len(params) != 1:
                raise ValueError("Upstream web recommendation request parameters changed")
            start = params[0]
            end = start + 4
            if tokens[end][0] != "(":
                raise ValueError("Upstream web recommendation parameter builder changed")
            depth = 1
            while depth:
                end += 1
                depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
            begin = source.rfind("\n", 0, tokens[start][1]) + 1
            block = textwrap.indent(textwrap.dedent(source[begin:tokens[end][2]]), "    ")
            pieces.append("internal fun buildDesktopWebRecommendParams(idx: Int, refreshCount: Int): Map<String, String> {\n" + block + "\n    return params\n}")
        target = output / (package.replace(".", "/") + "/DesktopDiscovery" + Path(relative).stem + ".kt")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n\n".join(pieces) + "\n", encoding="utf-8")
    source = (_desktop_canonical_source(repo, "app/src/main/java/com/android/purebilibili/feature/plugin/BiliPaiFeedFilterPlugin.kt")).read_text(encoding="utf-8")
    tokens = parser.kotlin_tokens(source)
    methods = []
    for name in ["parseBanWordToRegex", "parseUidMap", "extractMid"]:
        positions = [i for i, token in enumerate(tokens[:-1]) if token[0] == "fun" and tokens[i + 1][0] == name]
        if len(positions) != 1:
            raise ValueError("Missing or duplicate upstream filter editor policy: " + name)
        start = positions[0]
        end = start
        while tokens[end][0] != "(":
            end += 1
        depth = 1
        while depth:
            end += 1
            depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
        while tokens[end][0] != "{":
            end += 1
        depth = 1
        while depth:
            end += 1
            depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
        begin = source.rfind("\n", 0, tokens[start][1]) + 1
        methods.append(textwrap.indent(textwrap.dedent(source[begin:tokens[end][2]]), "    "))
    target = output / "com/android/purebilibili/feature/plugin/DesktopFeedFilterEditor.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("// GENERATED verbatim methods from original BiliPaiFeedFilterPlugin companion.\npackage com.android.purebilibili.feature.plugin\n\ninternal object DesktopFeedFilterEditor {\n" + "\n\n".join(methods) + "\n}\n", encoding="utf-8")
    generate_recommendation_platform(repo, output, parser)


def selected(source: str, kind: str, name: str, parser, constructor_only: bool = False) -> str:
    tokens = parser.kotlin_tokens(source)
    positions = [i for i, token in enumerate(tokens[:-1]) if token[0] == kind and tokens[i + 1][0] == name]
    if len(positions) != 1:
        raise ValueError("Missing or duplicate original discovery declaration: " + name)
    start = positions[0]
    end = start
    while tokens[end][0] != "(":
        end += 1
    depth = 1
    while depth:
        end += 1
        depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
    if not constructor_only:
        while tokens[end][0] != "{":
            end += 1
        depth = 1
        while depth:
            end += 1
            depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
    begin = source.rfind("\n", 0, tokens[start][1]) + 1
    return textwrap.dedent(source[begin:tokens[end][2]])


def selected_enum(source: str, name: str, parser) -> str:
    tokens = parser.kotlin_tokens(source)
    positions = [i for i, token in enumerate(tokens[:-1]) if token[0] == "class" and tokens[i + 1][0] == name]
    if len(positions) != 1:
        raise ValueError("Missing or duplicate original discovery enum: " + name)
    start = positions[0]
    end = start
    while tokens[end][0] != "{":
        end += 1
    depth = 1
    while depth:
        end += 1
        depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
    begin = source.rfind("\n", 0, tokens[start][1]) + 1
    return textwrap.dedent(source[begin:tokens[end][2]])


def constant(source: str, name: str) -> str:
    matches = list(re.finditer(r"(?m)^\s*(?:private |internal )?const val " + re.escape(name) + r"\s*=\s*(?:\"[^\"\n]*\"|\d+)", source))
    if len(matches) != 1:
        raise ValueError("Original discovery constant changed: " + name)
    return textwrap.dedent(matches[0].group(0)).strip()



def adapt_owned_today_watch_feedback(text):
    before = '    fun saveSnapshot(context: Context, snapshot: TodayWatchFeedbackSnapshot) {\n        synchronized(lock) {\n            val payload = TodayWatchFeedbackPayload(\n                dislikedBvids = snapshot.dislikedBvids\n                    .filter { it.isNotBlank() }\n                    .takeLast(MAX_DISLIKED_BVIDS),\n                dislikedCreatorMids = snapshot.dislikedCreatorMids\n                    .filter { it > 0L }\n                    .takeLast(MAX_DISLIKED_CREATORS),\n                dislikedKeywords = snapshot.dislikedKeywords\n                    .map { it.trim().lowercase() }\n                    .filter { it.isNotBlank() }\n                    .takeLast(MAX_DISLIKED_KEYWORDS),\n                recentDislikedVideos = snapshot.recentDislikedVideos\n                    .filter { it.bvid.isNotBlank() }\n                    .takeLast(MAX_RECENT_DISLIKED_VIDEOS)\n                    .map { item ->\n                        TodayWatchDislikedVideoPayload(\n                            bvid = item.bvid.trim(),\n                            title = item.title.trim(),\n                            creatorName = item.creatorName.trim(),\n                            creatorMid = item.creatorMid,\n                            dislikedAtMillis = item.dislikedAtMillis\n                        )\n                    }\n            )\n            val raw = json.encodeToString(payload)\n            context\n                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)\n                .edit()\n                .putString(KEY_PAYLOAD, raw)\n                .apply()\n        }\n    }\n'
    after = '    private fun encodeSnapshot(snapshot: TodayWatchFeedbackSnapshot): String {\n    val payload = TodayWatchFeedbackPayload(\n        dislikedBvids = snapshot.dislikedBvids\n            .filter { it.isNotBlank() }\n            .takeLast(MAX_DISLIKED_BVIDS),\n        dislikedCreatorMids = snapshot.dislikedCreatorMids\n            .filter { it > 0L }\n            .takeLast(MAX_DISLIKED_CREATORS),\n        dislikedKeywords = snapshot.dislikedKeywords\n            .map { it.trim().lowercase() }\n            .filter { it.isNotBlank() }\n            .takeLast(MAX_DISLIKED_KEYWORDS),\n        recentDislikedVideos = snapshot.recentDislikedVideos\n            .filter { it.bvid.isNotBlank() }\n            .takeLast(MAX_RECENT_DISLIKED_VIDEOS)\n            .map { item ->\n                TodayWatchDislikedVideoPayload(\n                    bvid = item.bvid.trim(),\n                    title = item.title.trim(),\n                    creatorName = item.creatorName.trim(),\n                    creatorMid = item.creatorMid,\n                    dislikedAtMillis = item.dislikedAtMillis\n                )\n            }\n    )\n    val raw = json.encodeToString(payload)\n        return raw\n    }\n\n    fun saveSnapshot(context: Context, snapshot: TodayWatchFeedbackSnapshot) {\n        synchronized(lock) {\n            val raw = encodeSnapshot(snapshot)\n            context\n                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)\n                .edit()\n                .putString(KEY_PAYLOAD, raw)\n                .apply()\n        }\n    }\n\n    internal fun saveSnapshot(context: Context, snapshot: TodayWatchFeedbackSnapshot,\n        checkRequest: () -> Unit,\n        acquirePermit: () -> com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit,\n    ) {\n        checkRequest()\n        val raw = encodeSnapshot(snapshot)\n        context.store.updateOriginalFromSnapshot(PREFS_NAME, checkRequest, acquirePermit) {\n            Unit to mapOf(KEY_PAYLOAD to kotlinx.serialization.json.JsonPrimitive(raw))\n        }\n    }\n\n    internal fun clear(context: Context, checkRequest: () -> Unit,\n        acquirePermit: () -> com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit,\n    ) {\n        context.store.updateOriginalFromSnapshot(PREFS_NAME, checkRequest, acquirePermit) {\n            Unit to mapOf(KEY_PAYLOAD to null)\n        }\n    }\n'
    if text.count(before) != 1:
        raise ValueError("Original TodayWatch feedback save body changed")
    return text.replace(before, after, 1)

def generate_recommendation_platform(repo: Path, output: Path, parser) -> None:
    def write(package: str, filename: str, body: str) -> None:
        target = output / package.replace(".", "/") / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("// GENERATED original declarations and Windows platform bindings.\npackage " + package + "\n\n" + body + "\n", encoding="utf-8")

    settings = (_desktop_canonical_source(repo, BASE + "core/store/SettingsManager.kt")).read_text(encoding="utf-8")
    write("com.android.purebilibili.core.store", "DesktopDiscoverySettings.kt",
        "\n\n".join(constant(settings, name) for name in ("DEFAULT_HOME_REFRESH_COUNT", "MIN_HOME_REFRESH_COUNT", "MAX_HOME_REFRESH_COUNT")) +
        "\n\n" + selected(settings, "fun", "normalizeHomeRefreshCount", parser) +
        "\n\nobject DesktopFeedSettings {\n" + textwrap.indent(selected_enum(settings, "FeedApiType", parser), "    ") + "\n}")

    feedback_path = BASE + "core/store/TodayWatchFeedbackStore.kt"
    feedback = (_desktop_canonical_source(repo, feedback_path)).read_text(encoding="utf-8")
    before = "import android.content.Context"
    if feedback.count(before) != 1:
        raise ValueError("Original negative feedback Context binding changed")
    feedback = feedback.replace(before, "import com.bilipai.desktop.plugins.DesktopPluginContext as Context")
    feedback = adapt_owned_today_watch_feedback(feedback)
    target = output / "com/android/purebilibili/core/store/TodayWatchFeedbackStore.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("// GENERATED original " + feedback_path + "; Context only is platform-bound.\n" + feedback, encoding="utf-8")

    actions = (_desktop_canonical_source(repo, BASE + "data/repository/ActionRepository.kt")).read_text(encoding="utf-8")
    write("com.android.purebilibili.data.repository", "DesktopRecommendationFeedbackRequests.kt",
        "import com.android.purebilibili.core.network.AppSignUtils\nimport com.android.purebilibili.data.model.response.*\n\n" +
        "\n\n".join([selected(actions, "class", "RecommendationFeedbackRequest", parser, True),
            selected(actions, "fun", "buildRecommendationFeedbackRequest", parser), selected(actions, "fun", "buildRecommendationFeedbackParams", parser)]))

    blocked = (_desktop_canonical_source(repo, BASE + "data/repository/BlockedUpRepository.kt")).read_text(encoding="utf-8")
    declarations = [constant(blocked, name) for name in ("BILIBILI_RELATION_ACT_BLOCK", "BILIBILI_RELATION_ACT_UNBLOCK", "BILIBILI_RELATION_PROFILE_BLOCK_RE_SRC", "BILIBILI_RELATION_COMMENT_BLOCK_RE_SRC")]
    declarations += [selected_enum(blocked, name, parser) for name in ("BilibiliBlockedListRemoteStatus", "BlockedUpRelationSource")]
    declarations += [selected(blocked, "class", "BlockedUpWriteResult", parser, True), selected(blocked, "fun", "resolveBlockedUpRelationReSrc", parser), selected(blocked, "fun", "buildBlockedUpWriteMessage", parser)]
    declarations.append("internal fun desktopBlockedRelationArguments(blocked: Boolean, source: BlockedUpRelationSource): Pair<Int, Int> =\n    (if (blocked) BILIBILI_RELATION_ACT_BLOCK else BILIBILI_RELATION_ACT_UNBLOCK) to resolveBlockedUpRelationReSrc(source)")
    write("com.android.purebilibili.data.repository", "DesktopBlockedCreatorPolicy.kt", "\n\n".join(declarations))

    video = (_desktop_canonical_source(repo, BASE + "data/repository/VideoRepository.kt")).read_text(encoding="utf-8")
    builders = []
    for original, adapter, arguments in [
        ("fetchMobileFeed", "buildDesktopMobileRecommendParams", "idx: Int, refreshCount: Int, accessToken: String"),
        ("fetchMergedWebFeed", "buildDesktopMergedWebRecommendParams", "idx: Int, refreshCount: Int"),
        ("fetchMergedMobileFeed", "buildDesktopMergedMobileRecommendParams", "idx: Int")]:
        body = selected(video, "fun", original, parser)
        tokens = parser.kotlin_tokens(body)
        starts = [i for i in range(len(tokens) - 4) if [t[0] for t in tokens[i:i + 3]] == ["val", "params", "="] and tokens[i + 3][0] in ("mapOf", "mutableMapOf")]
        if len(starts) != 1:
            raise ValueError("Original recommendation request parameters changed: " + original)
        start = starts[0]; end = start + 4
        if tokens[end][0] != "(":
            raise ValueError("Original recommendation map construction changed: " + original)
        depth = 1
        while depth:
            end += 1
            depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
        begin = body.rfind("\n", 0, tokens[start][1]) + 1
        declaration = textwrap.indent(textwrap.dedent(body[begin:tokens[end][2]]), "    ")
        builders.append("internal fun " + adapter + "(" + arguments + "): " + ("MutableMap" if original == "fetchMergedMobileFeed" else "Map") + "<String, String> {\n" + declaration + "\n    return params\n}")
    write("com.android.purebilibili.data.repository", "DesktopRecommendationAppParams.kt", "import com.android.purebilibili.core.network.AppSignUtils\n\n" + "\n\n".join(builders))

    network = (_desktop_canonical_source(repo, BASE + "core/network/ApiClient.kt")).read_text(encoding="utf-8")
    header_bodies = []
    for prefix in ("if (androidHdLoginAppKeyHeader != null || isHdFeedRequest) {", "if (isHdFeedRequest) {"):
        if network.count(prefix) != 1:
            raise ValueError("Original HD feed request headers changed")
        begin = network.index(prefix) + len(prefix)
        tokens = parser.kotlin_tokens(network[begin:]); depth = 1; end = None
        for token, left, right in tokens:
            depth += (token == "{") - (token == "}")
            if depth == 0:
                end = begin + left; break
        if end is None:
            raise ValueError("Unbalanced original HD feed header block")
        header_bodies.append(textwrap.indent(textwrap.dedent(network[begin:end]).strip(), "    "))
    write("com.android.purebilibili.core.network", "DesktopMergedFeedHeaders.kt",
        constant(network, "MERGED_APP_FEED_FP") + "\n" + constant(network, "MERGED_APP_FEED_SESSION_ID") +
        "\n\ninternal fun applyDesktopMergedFeedHeaders(builder: okhttp3.Request.Builder, buvid: String): okhttp3.Request.Builder {\n" +
        "    val androidHdLoginAppKeyHeader: String? = null\n    val loginBuvid: String? = buvid\n    val TokenManager = object { val buvid3Cache = buvid }\n" +
        "\n\n".join(header_bodies) + "\n    return builder\n}")


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--inventory", action="store_true")
    args = cli.parse_args()
    if args.inventory:
        print(json.dumps(inventory(args.repo.resolve()), ensure_ascii=False, indent=2))
    elif args.output:
        generate(args.repo.resolve(), args.output.resolve())
    else:
        cli.error("--output or --inventory is required")

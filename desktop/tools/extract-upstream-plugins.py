#!/usr/bin/env python3
"""Reuse original plugin SDK, policies and algorithms with reviewed platform bindings.

This is separate from the API extractor. Direct sources must remain free of Android
dependencies. Platform substitutions are exact and counted; source drift fails closed.
The original plugin settings UI is supplied by Windows instead of copied Android UI.
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
SDK = "plugin-sdk/src/main/java/com/android/purebilibili/plugin/sdk/"
DIRECT = [SDK + name + ".kt" for name in (
    "PluginCapabilityManifest", "PlayerPluginApi", "DanmakuPluginApi", "RecommendationPluginApi")]
DIRECT += [BASE + name + ".kt" for name in (
    "core/plugin/Plugin", "core/plugin/PlayerPlugin", "core/plugin/DanmakuPlugin",
    "core/plugin/TypedPluginApis", "core/plugin/FeedPlugin", "core/plugin/RecommendationPluginApi",
    "core/plugin/PluginCapabilityManifest", "core/plugin/BuiltinPluginDefaultPolicy",
    "core/plugin/ExternalPluginInstallPolicy", "core/plugin/PluginEffectHintPolicy",
    "core/plugin/PluginEffectHintBus", "core/plugin/json/JsonRulePlugin",
    "core/plugin/feed/FeedModels", "core/plugin/feed/FeedArticleExtractor",
    "core/plugin/feed/FeedHtmlParser", "core/plugin/feed/FeedDocumentParser",
    "core/plugin/feed/FeedImportParser", "core/plugin/feed/FeedLayoutAllocation",
    "feature/anime4k/Anime4KConfig", "feature/plugin/EyeProtectionPolicy",
    "feature/plugin/CdnRegionPolicy", "feature/plugin/CdnOptimizationPolicy",
    "feature/home/TodayWatchCandidatePoolPolicy", "feature/plugin/SponsorBlockCommunityPolicy")]

EXTRACTED = [BASE + name + ".kt" for name in (
    "core/plugin/PluginStore", "core/plugin/PluginManager", "core/plugin/CastPluginApi",
    "core/plugin/json/RuleEngine", "core/plugin/json/JsonPluginManager",
    "core/plugin/json/JsonPluginStatsNotificationPolicy", "core/plugin/feed/SubscriptionFeedStore",
    "core/plugin/feed/FeedFetcher", "core/plugin/feed/FeedTitleResolver", "core/plugin/feed/FeedConditionalStore",
    "feature/plugin/EyeProtectionPlugin", "feature/plugin/DanmakuEnhancePlugin",
    "feature/plugin/SponsorBlockPlugin", "feature/plugin/SponsorBlockInsightPolicy",
    "feature/video/danmaku/DanmakuManager",
    "data/repository/SponsorBlockRepository")]

DIRECT += ['network-core/src/main/java/com/android/purebilibili/core/network/policy/HomeFeedAnonymizerPolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/anime4k/VideoEnhancementConfigLoadGuard.kt', 'app/src/main/java/com/android/purebilibili/feature/home/TodayWatchPolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/home/TodayWatchQueuePolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/anime4k/VideoEnhancementSessionPolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/anime4k/gl/MpvAnime4KShader.kt', 'app/src/main/java/com/android/purebilibili/feature/anime4k/gl/Anime4KDisplayPolicy.kt']
EXTRACTED += ['app/src/main/java/com/android/purebilibili/feature/plugin/HomeFeedAnonymizerPlugin.kt', 'app/src/main/java/com/android/purebilibili/feature/plugin/SubscriptionFeedPlugin.kt', 'app/src/main/java/com/android/purebilibili/feature/plugin/Anime4KPlugin.kt', 'app/src/main/java/com/android/purebilibili/feature/plugin/AdFilterInsightPolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/plugin/AdFilterPlugin.kt', 'app/src/main/java/com/android/purebilibili/feature/home/HomeUiState.kt', 'app/src/main/java/com/android/purebilibili/core/store/TodayWatchProfileStore.kt', 'app/src/main/java/com/android/purebilibili/feature/plugin/TodayWatchPlugin.kt', 'app/src/main/java/com/android/purebilibili/feature/anime4k/gl/Anime4KShaderRepository.kt', 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedReadingStore.kt', 'app/src/main/java/com/android/purebilibili/feature/plugin/CdnRegionPlugin.kt']
SOURCES = {**{path: "direct" for path in DIRECT}, **{path: "extracted" for path in EXTRACTED}}
SOURCES[BASE + "feature/home/HomeUiState.kt"] = "policy-extract"
EXTRACTED.append(BASE + "feature/home/HomeViewModel.kt")
SOURCES[BASE + "feature/home/HomeViewModel.kt"] = "policy-extract"
DIRECT += [BASE + 'feature/anime4k/gl/Fsr1Shaders.kt', BASE + 'feature/anime4k/Anime4KFirstFrameFallbackPolicy.kt']
SOURCES.update({path: 'direct' for path in DIRECT[-2:]})
SOURCES[BASE + 'feature/anime4k/Anime4KOutputPolicy.kt'] = 'policy-extract'
SOURCES[BASE + 'feature/anime4k/gl/Anime4KPipelineRenderer.kt'] = 'reference-only'
SOURCES[BASE + 'feature/anime4k/gl/FboManager.kt'] = 'reference-only'
SOURCES[BASE + 'feature/video/ui/components/Anime4KSettingsUi.kt'] = 'policy-extract'
PLUGIN_ASSETS = ["app/src/main/assets/anime4k/" + name for name in (
    "Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Clamp_Highlights.glsl",
    "Anime4K_Restore_CNN_M.glsl", "Anime4K_Restore_CNN_S.glsl", "Anime4K_Restore_CNN_VL.glsl",
    "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_Upscale_CNN_x2_S.glsl", "Anime4K_Upscale_CNN_x2_VL.glsl", "LICENSE")]
PLUGIN_ASSETS += ["app/src/main/res/raw/cdn_region_catalog.json"]


def read(repo: Path, path: str) -> str:
    return (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").replace("\r\n", "\n")


def parser_for(repo: Path):
    spec = importlib.util.spec_from_file_location("plugins_kotlin_parser", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    return parser


def media_extractor(repo: Path):
    spec = importlib.util.spec_from_file_location("plugins_block_selector", repo / "desktop/tools/extract-upstream-media.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def substitute(source: str, before: str, after: str, count: int = 1) -> str:
    if source.count(before) != count:
        raise ValueError(f"Original plugin platform binding changed: {before!r}")
    return source.replace(before, after)


def platform_context(source: str) -> str:
    source = substitute(source, "import android.content.Context", "import com.bilipai.desktop.plugins.DesktopPluginContext as Context")
    return source


# Only the RSS identity fix is borrowed; the canonical v0.2.5 Store/schema stay pinned.
# The complete helper is identical in v0.2.6 ad85f33c5d3713d0bf95e13aecaef8572e9e7623
# and v0.2.7 e5a6b59a69ed2de9ea4e69dc7675ea05de495ebf, SubscriptionFeedStore.kt:132-142.
SUBSCRIPTION_ID_FIX_SOURCE_SHA256 = "dc594289c735aa8f9cb72a1d67d72e411d0784d15038399199cd39b04a3cae6a"
SUBSCRIPTION_ID_FIX_HELPER_SHA256 = "bc8ef4c4c3babe62671e4a41d6722c1aaabcb8d40ffd567449e1d530510e5ead"
SUBSCRIPTION_ID_FIX_HELPER = '''    /** url 的 32 位哈希；与现有条目冲突时追加序号，避免两个订阅共用 id 造成连删/筛选串台。 */
    private fun uniqueFeedId(url: String, usedIds: Set<String>): String {
        val base = url.hashCode().toUInt().toString(16)
        if (base !in usedIds) return base
        var suffix = 1
        while (true) {
            val candidate = "$base-$suffix"
            if (candidate !in usedIds) return candidate
            suffix += 1
        }
    }
'''


def subscription_feed_identity_fix(source: str) -> str:
    """Apply the fixed upstream ID allocator without renumbering persisted feeds."""
    if hashlib.sha256(source.encode()).hexdigest() != "204b5891d005c0cac525ca6fdd328b4534c4f3a6a3966917610eee528d3b2785":
        raise ValueError("Canonical subscription Store changed before the bounded ID fix")
    if hashlib.sha256(SUBSCRIPTION_ID_FIX_HELPER.encode()).hexdigest() != SUBSCRIPTION_ID_FIX_HELPER_SHA256:
        raise ValueError("Fixed upstream subscription ID helper changed")
    source = substitute(source,
        "        val seen = current.map { it.url }.toMutableSet()\n",
        "        val seen = current.map { it.url }.toMutableSet()\n"
        "        val usedIds = current.map { it.id }.toMutableSet()\n")
    source = substitute(source,
        "            current += SavedSubscriptionFeed(\n"
        "                id = url.hashCode().toUInt().toString(16),",
        "            val feed = SavedSubscriptionFeed(\n"
        "                id = uniqueFeedId(url, usedIds),")
    source = substitute(source,
        "            )\n            added += 1\n",
        "            )\n            usedIds += feed.id\n            current += feed\n            added += 1\n")
    source = substitute(source,
        "        val feed = SavedSubscriptionFeed(\n"
        "            id = trimmedUrl.hashCode().toUInt().toString(16),",
        "        val current = list(context)\n"
        "        // An existing URL keeps its persisted ID and associated reading keys.\n"
        "        val existing = current.firstOrNull { it.url == trimmedUrl }\n"
        "        val feed = SavedSubscriptionFeed(\n"
        "            id = existing?.id ?: uniqueFeedId(trimmedUrl, current.map { it.id }.toSet()),")
    source = substitute(source,
        "        val current = list(context).filterNot { it.url == feed.url }\n"
        "        write(context, current + feed)",
        "        write(context, current.filterNot { it.url == feed.url } + feed)")
    return substitute(source,
        "    private fun write(context: Context, feeds: List<SavedSubscriptionFeed>) {",
        "    // ID allocator: upstream v0.2.7 e5a6b59a69ed2de9ea4e69dc7675ea05de495ebf:132-142.\n"
        + SUBSCRIPTION_ID_FIX_HELPER + "\n"
        "    private fun write(context: Context, feeds: List<SavedSubscriptionFeed>) {")


def platform_logger(source: str) -> str:
    return substitute(source, "import com.android.purebilibili.core.util.Logger", "import com.bilipai.desktop.plugins.DesktopPluginLog as Logger")


def checked_output(output: Path, target: Path) -> Path:
    """Resolve symlinks and parent segments before touching any generated target."""
    root = output.resolve()
    canonical = target.resolve()
    try:
        canonical.relative_to(root)
    except ValueError as error:
        raise ValueError(f"Plugin generated target escapes specified output directory: {target}") from error
    if canonical == root:
        raise ValueError("Plugin generated target cannot replace the output directory")
    return target


def output_target(output: Path, original_path: str, body: str, name: str | None = None) -> Path:
    package = re.search(r"(?m)^package ([\w.]+)", body)
    if package is None or not all(re.fullmatch(r"[A-Za-z_]\w*", segment) for segment in package[1].split(".")):
        raise ValueError(f"Source has no valid package: {original_path}")
    return checked_output(output, output / package[1].replace(".", "/") / (name or Path(original_path).name))


def prune_old_direct(output: Path, path: str, original: str) -> None:
    target = output_target(output, path, original)
    if not target.is_file():
        return
    expected = f"// GENERATED from {path}; do not edit.".encode("utf-8")
    # Limit reads and delete only the generator's own former verbatim copy.
    with target.open("rb") as stream:
        first_line = stream.readline(4096).rstrip(b"\r\n")
    if first_line == expected:
        target.unlink()


def write(output: Path, path: str, original: str, body: str, name: str | None = None) -> Path:
    target = output_target(output, path, body, name)
    target.parent.mkdir(parents=True, exist_ok=True)
    header = f"// GENERATED from {path}; do not edit.\n// LF-normalized SHA-256: {hashlib.sha256(original.encode()).hexdigest()}\n"
    target.write_text(header + body.strip() + "\n", encoding="utf-8")
    return target


def drop_settings(source: str, parser) -> str:
    """Remove only complete original Android SettingsContent methods, not algorithms."""
    tokens = parser.kotlin_tokens(source)
    spans = []
    for match in re.finditer(r"(?m)^    @Composable\n    override fun SettingsContent\(", source):
        position = next(i for i, token in enumerate(tokens) if token[1] >= match.start())
        while tokens[position][0] != "{":
            position += 1
        depth = 1
        while depth:
            position += 1
            depth += (tokens[position][0] == "{") - (tokens[position][0] == "}")
        spans.append((match.start(), tokens[position][2]))
    if not spans:
        raise ValueError("Original built-in settings declarations changed")
    for begin, end in reversed(spans):
        source = source[:begin] + source[end:]
    return source


def keep_imports(source: str, allowed: tuple[str, ...]) -> str:
    return re.sub(r"(?m)^import ([^\n]+)\n", lambda match: match[0] if match[1].startswith(allowed) else "", source)


def add_json_text_import(source: str, original: str, repo: Path, parser) -> str:
    """Expose local text installation through the original validation/install bodies."""
    selector = media_extractor(repo)
    method = selector.function(original, "importFromUrl", parser)
    method = substitute(method, "importFromUrl(url: String)", "importFromText(content: String)")
    method = substitute(method, """val normalizedUrl = url.trim()
            val plugin = fetchPluginFromUrl(normalizedUrl).getOrElse { error ->
                return@withContext Result.failure(error)
            }""", """val plugin = parsePluginText(content).getOrElse { error ->
                return@withContext Result.failure(error)
            }""")
    method = substitute(method, "sourceUrl = normalizedUrl", "sourceUrl = null")
    fetch = selector.function(original, "fetchPluginFromUrl", parser)
    marker = "    val plugin = try {"
    if fetch.count(marker) != 1:
        raise ValueError("Original JSON parse/validation boundary changed")
    parse = fetch[fetch.index(marker):fetch.rfind("\n}")]
    parse_method = "private fun parsePluginText(content: String): Result<JsonRulePlugin> {\n" + parse + "\n}"
    insert = textwrap.indent(method + "\n\n" + parse_method, "    ") + "\n\n"
    return substitute(source, "    suspend fun importFromUrl(url: String)", insert + "    suspend fun importFromUrl(url: String)")


def bind_owned_scopes(source: str, owner: str) -> str:
    """Keep original scheduling, while making its actual jobs awaitable on desktop shutdown."""
    pattern = r"CoroutineScope\(SupervisorJob\(\) \+ (Dispatchers\.(?:Main|IO|Default))\)"
    index = 0
    def replace(match):
        nonlocal index
        index += 1
        return ('com.bilipai.desktop.plugins.DesktopPluginScopeRegistry.create('
                + json.dumps(owner + ":" + str(index)) + ', ' + match.group(1) + ')')
    return re.sub(pattern, replace, source)


def generate(repo: Path, output: Path) -> list[Path]:
    parser = parser_for(repo)
    generated = []
    for path in DIRECT:
        source = read(repo, path)
        if re.search(r"(?m)^import android\.", source):
            raise ValueError(f"Direct plugin source gained Android dependency: {path}")
        # prepareUpstreamSources owns the sole verbatim copy of every direct source.
        prune_old_direct(output, path, source)

    path = BASE + "core/plugin/json/RuleEngine.kt"
    source = read(repo, path)
    body = substitute(source, "android.graphics.Color.parseColor(it)", "com.bilipai.desktop.plugins.DesktopPluginColor.parseColor(it)")
    generated.append(write(output, path, source, body))

    for name in ("PluginManager", "CastPluginApi"):
        path = BASE + "core/plugin/" + name + ".kt"
        source = read(repo, path)
        body = platform_context(source)
        if name == "PluginManager":
            body = platform_logger(body)
            # Retired desktop media requests must be checked after the original manager lock.
            body = substitute(body, 'suspend fun setEnabled(pluginId: String, enabled: Boolean) {',
                'suspend fun setEnabled(pluginId: String, enabled: Boolean, stillOwned: () -> Boolean = { true }) {')
            body = substitute(body, '        pluginStateMutex.withLock {\n            val info = _pluginsFlow.value.firstOrNull',
                '        pluginStateMutex.withLock {\n            if (!stillOwned()) return@withLock\n            val info = _pluginsFlow.value.firstOrNull')
            body = substitute(body, '                    plugin.onEnable()\n                    Logger.d(TAG, " Plugin enabled: ${plugin.name}")',
                '                    plugin.onEnable()\n                    if (!stillOwned()) {\n                        plugin.onDisable()\n                        return@withLock\n                    }\n                    Logger.d(TAG, " Plugin enabled: ${plugin.name}")')
        generated.append(write(output, path, source, body))

    path = BASE + "core/plugin/PluginStore.kt"
    source = read(repo, path)
    body = platform_context(source)
    for name in ("booleanPreferencesKey", "stringPreferencesKey"):
        body = substitute(body, f"import androidx.datastore.preferences.core.{name}", f"import com.bilipai.desktop.plugins.{name}")
    body = substitute(body, "import androidx.datastore.preferences.core.edit\n", "")
    body = substitute(body, "import androidx.datastore.preferences.preferencesDataStore\n", "")
    body = substitute(body, 'private val Context.pluginDataStore by preferencesDataStore(name = "plugin_prefs")\n', "")
    generated.append(write(output, path, source, body))

    path = BASE + "core/plugin/json/JsonPluginManager.kt"
    source = read(repo, path)
    body = platform_logger(platform_context(source))
    body = substitute(body, "import android.net.Uri\n", "")
    body = substitute(body, "Uri.parse(url)", "com.bilipai.desktop.plugins.DesktopPluginUrl.parse(url)")
    body = substitute(body, "import com.android.purebilibili.core.network.NetworkModule\n", "")
    body = substitute(body, "NetworkModule.resolveSharedNetworkProtocols()", "com.bilipai.desktop.plugins.DesktopPluginNetwork.protocols")
    body = add_json_text_import(body, source, repo, parser)
    generated.append(write(output, path, source, body))

    path = BASE + "core/plugin/json/JsonPluginStatsNotificationPolicy.kt"
    source = read(repo, path)
    generated.append(write(output, path, source, platform_context(source)))

    path = BASE + "core/plugin/feed/SubscriptionFeedStore.kt"
    source = read(repo, path)
    body = substitute(source, "import android.content.Context\n", "")
    if body.count("object SubscriptionFeedStore {") != 1:
        raise ValueError("Original subscription model/store boundary changed")
    body = body.split("object SubscriptionFeedStore {", 1)[0]
    body = keep_imports(body, ("kotlinx.serialization.Serializable",))
    generated.append(write(output, path, source, body, "DesktopSavedSubscriptionFeed.kt"))

    path = BASE + "core/plugin/feed/FeedConditionalStore.kt"
    source = read(repo, path)
    tokens = parser.kotlin_tokens(source)
    start, end = parser.kotlin_structure(tokens, "class", "FeedConditionalValidators", constructor_only=True)
    begin = source.rfind("\n", 0, tokens[start][1]) + 1
    body = "package com.android.purebilibili.core.plugin.feed\n\nimport kotlinx.serialization.Serializable\n\n@Serializable\n"
    body += source[begin:tokens[end][2]]
    generated.append(write(output, path, source, body, "DesktopFeedConditionalValidators.kt"))

    for name in ("FeedFetcher", "FeedTitleResolver"):
        path = BASE + "core/plugin/feed/" + name + ".kt"
        source = read(repo, path)
        body = source
        if name == "FeedFetcher":
            body = substitute(body, "import com.android.purebilibili.core.network.NetworkModule\n", "")
            body = substitute(body, "NetworkModule.okHttpClient.newBuilder()", "com.bilipai.desktop.plugins.DesktopPluginNetwork.publicClient.newBuilder()")
        if re.search(r"(?m)^import android\.", body):
            raise ValueError("Unexpected platform dependency in original feed implementation")
        generated.append(write(output, path, source, body))

    for name in ("EyeProtectionPlugin", "DanmakuEnhancePlugin"):
        path = BASE + "feature/plugin/" + name + ".kt"
        source = read(repo, path)
        body = drop_settings(source, parser)
        body = keep_imports(body, ("com.android.purebilibili.core.plugin.", "com.android.purebilibili.core.util.Logger",
            "kotlinx.", "java.", "android.content.Context", "androidx.compose.ui.graphics.Color"))
        # Built-in icon selection belongs to Windows UI, not Android material resources.
        body = re.sub(r"(?m)^    override val icon: ImageVector = Icons\.[^\n]+\n", "", body)
        body = platform_logger(body)
        if name == "EyeProtectionPlugin":
            body = substitute(body, "androidx.lifecycle.ProcessLifecycleOwner.get()\n            .lifecycle.currentState\n            .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)",
                "com.bilipai.desktop.plugins.DesktopPluginLifecycle.isAppVisible()")
        if "import android.content.Context" in body:
            body = platform_context(body)
        generated.append(write(output, path, source, body))

    path = BASE + "data/repository/SponsorBlockRepository.kt"
    source = read(repo, path)
    body = substitute(source, "import com.android.purebilibili.core.network.NetworkModule\n", "")
    body = substitute(body, "NetworkModule.okHttpClient", "com.bilipai.desktop.plugins.DesktopPluginNetwork.publicClient")
    body = substitute(body, "android.os.SystemClock.elapsedRealtime()", "com.bilipai.desktop.appearance.DesktopMonotonicClock.elapsedRealtime()", count=2)
    body = substitute(body, "    private suspend fun postJson(url: String, body: String): Result<String> = withContext(Dispatchers.IO) {\n        runCatching {\n            val request = Request.Builder()\n                .url(url)\n                .post(body.toRequestBody(\"application/json\".toMediaType()))\n                .build()\n            client.newCall(request).execute().use { response ->", "    private suspend fun postJson(url: String, body: String): Result<String> = withContext(Dispatchers.IO) {\n        runCatching {\n            val request = Request.Builder()\n                .url(url)\n                .post(body.toRequestBody(\"application/json\".toMediaType()))\n                .build()\n            com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(client.newCall(request)) { response ->", 1)
    generated.append(write(output, path, source, body))

    path = BASE + "feature/plugin/SponsorBlockPlugin.kt"
    source = read(repo, path)
    marker = "    @Composable\n    override fun SettingsContent()"
    if source.count(marker) != 1:
        raise ValueError("Original SponsorBlock playback/settings boundary changed")
    # All original playback callbacks and community request methods precede Android UI.
    body = source[:source.index(marker)] + "}\n"
    body = keep_imports(body, ("com.android.purebilibili.core.plugin.", "com.android.purebilibili.core.util.Logger",
        "com.android.purebilibili.data.", "kotlinx.", "java.time."))
    body = re.sub(r"(?m)^    override val icon: ImageVector = Icons\.[^\n]+\n", "", body)
    tokens = parser.kotlin_tokens(source)
    begin, end = parser.kotlin_structure(tokens, "class", "SponsorBlockConfig")
    start = source.rfind("\n", 0, tokens[begin][1]) + 1
    body += "\n@Serializable\n" + source[start:tokens[end][2]] + "\n"
    body = substitute(body, "com.android.purebilibili.core.util.AnalyticsHelper.logSponsorBlockSkip", "com.bilipai.desktop.plugins.DesktopPluginAnalytics.logSponsorBlockSkip")
    generated.append(write(output, path, source, platform_logger(body)))

    path = BASE + "feature/plugin/SponsorBlockInsightPolicy.kt"
    source = read(repo, path)
    original = source
    source = substitute(source, "        writeMutex.withLock {\n            val nextRecords =", "        writeMutex.withLock {\n            com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.checkCurrentOrOriginal()\n            val nextRecords =", 1)
    generated.append(write(output, path, original, platform_context(source)))

    path = BASE + "feature/video/danmaku/DanmakuManager.kt"
    source = read(repo, path)
    selector = media_extractor(repo)
    pieces = ["package com.android.purebilibili.feature.video.danmaku",
        "import com.android.purebilibili.core.plugin.DanmakuItem as PluginDanmakuItem",
        "import com.android.purebilibili.core.plugin.DanmakuPlugin",
        "import com.android.purebilibili.core.plugin.DanmakuStyle",
        "import com.android.purebilibili.core.plugin.json.JsonPluginManager",
        "import com.bilipai.desktop.plugins.DesktopPluginLog as Log", "import kotlin.math.abs",
        'internal object DesktopPluginDanmakuPolicy {\n    private const val TAG = "DanmakuManager"']
    for name in ("runDanmakuFilters", "collectDanmakuStyle", "mergeDanmakuStyle"):
        method = selector.function(source, name, parser)
        if name != "mergeDanmakuStyle":
            method = substitute(method, "private fun " + name, "internal fun " + name)
        pieces.append(textwrap.indent(method, "    "))
    pieces.append("}")
    generated.append(write(output, path, source, "\n\n".join(pieces), "DesktopPluginDanmakuPolicy.kt"))
    generated.extend(generate_additional(repo, output))
    path = BASE + "feature/home/HomeViewModel.kt"
    source = read(repo, path)
    pieces = ["package com.android.purebilibili.feature.home", "import com.android.purebilibili.core.plugin.*", "import kotlinx.collections.immutable.*"]
    for name in ("toTodayWatchMode", "toTodayUpRanks"): # Full original HomeVM now owns toTodayWatchPlan once.
        if name == "toTodayUpRanks":
            method = selector.function(substitute(source, "RecommendationGroup?.toTodayUpRanks", "RecommendationGroup.toTodayUpRanks"), name, parser)
            method = substitute(method, "RecommendationGroup.toTodayUpRanks", "RecommendationGroup?.toTodayUpRanks")
        else:
            method = selector.function(source, name, parser)
        pieces.append(method)
    generated.append(write(output, path, source, "\n\n".join(pieces), "DesktopTodayWatchPlanConversion.kt"))
    rows = asset_inventory(repo)
    entries = ",\n".join("        " + json.dumps(row["path"]) + " to " + json.dumps(row["sha256"]) for row in rows)
    body = "package com.bilipai.desktop.plugins\n\ninternal object DesktopPluginAssetHashes {\n    val hashes = mapOf(\n" + entries + "\n    )\n}\n"
    generated.append(write(output, PLUGIN_ASSETS[0], read(repo, PLUGIN_ASSETS[0]), body, "DesktopPluginAssetHashes.kt"))
    unique = list(dict.fromkeys(generated))
    for target in unique:
        source = target.read_text(encoding="utf-8")
        bound = bind_owned_scopes(source, str(target.relative_to(output)).replace("\\", "/"))
        if source != bound:
            target.write_text(bound, encoding="utf-8", newline="\n")
    return unique


def generate_additional(repo: Path, output: Path) -> list[Path]:
    parser = parser_for(repo)
    selector = media_extractor(repo)
    generated = []
    # Existing source identities keep their canonical output names, so no duplicate DTO is created.
    for name, target in [('SubscriptionFeedStore', 'DesktopSavedSubscriptionFeed.kt'),
                         ('FeedConditionalStore', 'DesktopFeedConditionalValidators.kt'),
                         ('FeedReadingStore', 'FeedReadingStore.kt')]:
        path = BASE + 'core/plugin/feed/' + name + '.kt'
        original = read(repo, path)
        body = platform_context(subscription_feed_identity_fix(original) if name == 'SubscriptionFeedStore' else original)
        if name in ('FeedConditionalStore', 'FeedReadingStore'):
            body = substitute(body, 'import android.util.AtomicFile',
                'import com.bilipai.desktop.plugins.DesktopPluginAtomicFile as AtomicFile')
        generated.append(write(output, path, original, body, target))

    for name in ('HomeFeedAnonymizerPlugin', 'SubscriptionFeedPlugin', 'Anime4KPlugin'):
        path = BASE + 'feature/plugin/' + name + '.kt'
        original = read(repo, path)
        body = drop_settings(original, parser)
        if name == 'HomeFeedAnonymizerPlugin':
            body = body.split('data class HomeFeedAnonymizerInfoRow(', 1)[0]
        elif name == 'SubscriptionFeedPlugin':
            body = body.split('@Composable\nprivate fun SubscriptionFeedSettings', 1)[0]
        body = keep_imports(body, ('com.android.purebilibili.core.plugin.',
            'com.android.purebilibili.plugin.sdk.', 'com.android.purebilibili.core.network.policy.',
            'com.android.purebilibili.core.util.Logger', 'com.android.purebilibili.feature.anime4k.',
            'kotlinx.', 'java.time.'))
        body = re.sub(r'(?m)^    override val icon: ImageVector = Icons\.[^\n]+\n', '', body)
        if 'import com.android.purebilibili.core.util.Logger' in body:
            body = platform_logger(body)
        if name == 'SubscriptionFeedPlugin':
            method = selector.function(original, 'resolveImportPayload', parser)
            method = substitute(method, 'private suspend fun resolveImportPayload', 'internal suspend fun resolveImportPayload')
            body += '\n\n' + method
        elif name == 'Anime4KPlugin':
            # Platform visibility and actual IO drain only; original config decisions stay intact.
            body = substitute(body, 'private suspend fun loadConfig()', 'internal suspend fun loadConfig()')
            body = substitute(body, '    private var config = Anime4KConfig()',
                '    @Volatile private var desktopPersistenceError: Throwable? = null\n    private var config = Anime4KConfig()')
            body = substitute(body, '        configLoadGuard.markLocalChange()',
                '        desktopPersistenceError = null\n        configLoadGuard.markLocalChange()')
            body = substitute(body, '                Logger.e(TAG, "保存画质增强配置失败", error)',
                '                desktopPersistenceError = error\n                Logger.e(TAG, "保存画质增强配置失败", error)')
            method = '''    internal suspend fun awaitDesktopConfigurationWrites() {
        ioScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
        desktopPersistenceError?.let { throw IllegalStateException("画质增强配置保存失败", it) }
    }

'''
            body = substitute(body, '    companion object {', method + '    companion object {')
        generated.append(write(output, path, original, body))

    path = BASE + 'feature/plugin/AdFilterInsightPolicy.kt'
    original = read(repo, path)
    generated.append(write(output, path, original, platform_context(original)))

    path = BASE + 'feature/plugin/AdFilterPlugin.kt'
    original = read(repo, path)
    marker = '    @Composable\n    override fun SettingsContent()'
    if original.count(marker) != 1:
        raise ValueError('Original AdFilter core/settings boundary changed')
    body = original[:original.index(marker)] + '}\n'
    body = keep_imports(body, ('com.android.purebilibili.core.plugin.',
        'com.android.purebilibili.core.util.Logger', 'com.android.purebilibili.data.model.response.',
        'kotlinx.', 'java.time.', 'android.content.Context'))
    body = re.sub(r'(?m)^    override val icon: ImageVector = Icons\.[^\n]+\n', '', body)
    for name in ('fetchAdFilterUpProfileByMid', 'fetchAdFilterUpProfileByName',
                 'findAdFilterProfileForRefresh', 'refreshMissingAdFilterUpProfiles'):
        body += '\n' + selector.function(original, name, parser) + '\n'
    tokens = parser.kotlin_tokens(original)
    start, end = parser.kotlin_structure(tokens, 'class', 'AdFilterConfig', constructor_only=True)
    begin = original.rfind('\n', 0, tokens[start][1]) + 1
    body += '\n@Serializable\n' + original[begin:tokens[end][2]] + '\n'
    body = substitute(body, 'NetworkModule.api.getUserCard',
        'com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.api.getUserCard')
    body = substitute(body, 'SearchRepository.searchUp',
        'com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.searchUp')
    generated.append(write(output, path, original, platform_logger(platform_context(body))))

    path = BASE + 'core/store/TodayWatchProfileStore.kt'
    original = read(repo, path)
    generated.append(write(output, path, original, platform_context(original)))

    path = BASE + 'feature/home/HomeUiState.kt'
    original = read(repo, path)
    tokens = parser.kotlin_tokens(original)
    pieces = ['package com.android.purebilibili.feature.home',
        'import androidx.compose.runtime.Immutable',
        'import com.android.purebilibili.data.model.response.VideoItem',
        'import kotlinx.collections.immutable.*']
    for name in ('TodayWatchMode', 'TodayUpRank', 'TodayWatchPlan'):
        start, end = parser.kotlin_structure(tokens, 'class', name, constructor_only=name != 'TodayWatchMode')
        begin = original.rfind('\n', 0, tokens[start][1]) + 1
        pieces.append(('@Immutable\n' if name != 'TodayWatchMode' else '') + original[begin:tokens[end][2]])
    generated.append(write(output, path, original, '\n\n'.join(pieces), 'DesktopTodayWatchModels.kt'))

    path = BASE + 'feature/plugin/TodayWatchPlugin.kt'
    original = read(repo, path)
    tokens = parser.kotlin_tokens(original)
    start, end = parser.kotlin_structure(tokens, 'class', 'TodayWatchPlugin')
    body = original[:tokens[end][2]]
    body = substitute(body, '    @OptIn(ExperimentalLayoutApi::class)\n', '')
    body = drop_settings(body, parser)
    body = keep_imports(body, ('com.android.purebilibili.core.plugin.',
        'com.android.purebilibili.core.store.TodayWatchFeedbackStore',
        'com.android.purebilibili.core.store.TodayWatchProfileStore',
        'com.android.purebilibili.core.util.Logger', 'com.android.purebilibili.feature.home.', 'kotlinx.'))
    body = substitute(body, 'import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl\n', '')
    body = re.sub(r'(?m)^    override val icon: ImageVector = Icons\.[^\n]+\n', '', body)
    body = substitute(body, 'class TodayWatchPlugin : RecommendationPluginApi',
        'class TodayWatchPlugin(private val personalizationContext: () -> com.bilipai.desktop.plugins.DesktopPluginContext) : RecommendationPluginApi')
    method = selector.function(body, 'clearPersonalizationData', parser)
    replacement = substitute(method, 'val context = PluginManager.getContext()', 'val context = personalizationContext()')
    body = substitute(body, textwrap.indent(method, '    '), textwrap.indent(replacement, '    '))
    for name in ('toTodayWatchMode', 'toTodayWatchCreatorSignal'):
        body += '\n\n' + selector.function(original, name, parser)
    generated.append(write(output, path, original, platform_logger(body)))

    path = BASE + 'feature/anime4k/gl/Anime4KShaderRepository.kt'
    original = read(repo, path)
    body = 'package com.android.purebilibili.feature.anime4k.gl\n\nimport com.android.purebilibili.feature.anime4k.Anime4KShaderChain\n\n'
    body += selector.function(original, 'resolveAnime4KShaderFiles', parser)
    generated.append(write(output, path, original, body, 'DesktopAnime4KShaderList.kt'))

    path = BASE + 'feature/plugin/CdnRegionPlugin.kt'
    original = read(repo, path)
    body = drop_settings(original, parser)
    editor = selector.function(body, 'CdnCustomRuleEditor', parser)
    body = substitute(body, '    @Composable\n' + textwrap.indent(editor, '    '), '')
    body = keep_imports(body, ('android.content.Context', 'com.android.purebilibili.core.plugin.',
        'com.android.purebilibili.core.util.Logger', 'kotlinx.', 'okhttp3.', 'java.net.'))
    body = re.sub(r'(?m)^    override val icon: ImageVector = Icons\.[^\n]+\n', '', body)
    body = substitute(body, 'AppScope.ioScope', 'com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope', count=2)
    body = substitute(body, 'NetworkModule.playbackOkHttpClient', 'com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient')
    body = substitute(body, 'NetworkModule.api.getIpZone()', 'com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.api.getIpZone()')
    body = substitute(body, 'context.resources.openRawResource(R.raw.cdn_region_catalog)',
        'com.bilipai.desktop.plugins.DesktopPluginResource.open("plugin/cdn_region_catalog.json")')
    body = substitute(body, "        val context = PluginManager.getContext()\n        val now = System.currentTimeMillis()\n        val current = CdnRegionPluginStore.read(context).also { cache = it }", "        val context = com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.contextOrOriginal { PluginManager.getContext() }\n        val now = System.currentTimeMillis()\n        val current = CdnRegionPluginStore.read(context).also { value -> com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.mutateOrOriginal { cache = value } }", 1)
    body = substitute(body, "        cache = next\n        CdnRegionPluginStore.write(context, next)", "        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.mutateOrOriginal { cache = next }\n        CdnRegionPluginStore.write(context, next)", 1)
    body = substitute(body, "        cache = next\n        com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope.launch {\n            CdnRegionPluginStore.write(PluginManager.getContext(), next)\n        }", "        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.mutateOrOriginal { cache = next }\n        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.launchOrOriginal(com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope) {\n            CdnRegionPluginStore.write(com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.contextOrOriginal { PluginManager.getContext() }, next)\n        }", 1)
    body = substitute(body, '                com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient, url, CdnByteRange', '                com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.playbackCallsOrOriginal { com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient }, url, CdnByteRange', 1)
    body = substitute(body, '        val next = cache.copy(parallelDownloadEnabled = enabled)\n        CdnRegionPluginStore.write(PluginManager.getContext(), next)\n        cache = next\n        CdnTransferRuntime.configure(running, enabled && !next.experimentalRewriteEnabled)', '        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.checkCurrentOrOriginal()\n        val next = cache.copy(parallelDownloadEnabled = enabled)\n        CdnRegionPluginStore.write(com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.contextOrOriginal { PluginManager.getContext() }, next)\n        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.mutateOrOriginal {\n            cache = next\n            CdnTransferRuntime.configure(running, enabled && !next.experimentalRewriteEnabled)\n        }', 1)
    generated.append(write(output, path, original, platform_logger(platform_context(body))))

    spec = importlib.util.spec_from_file_location('video_enhancement_platform', repo / 'desktop/tools/extract-video-enhancement.py')
    enhancement = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(enhancement)
    generated.extend(enhancement.generate(repo, output, selector, parser, write, substitute))
    return generated

def inventory(repo: Path) -> list[dict]:
    return [{"path": path, "role": "plugins", "mode": mode,
             "sha256": hashlib.sha256(read(repo, path).encode()).hexdigest()} for path, mode in SOURCES.items()]


def asset_inventory(repo: Path) -> list[dict]:
    return [{"path": path, "role": "plugins", "mode": "asset",
             "sha256": hashlib.sha256(read(repo, path).encode()).hexdigest()} for path in PLUGIN_ASSETS]


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--inventory", action="store_true")
    cli.add_argument("--asset-inventory", action="store_true")
    args = cli.parse_args()
    if args.asset_inventory:
        print(json.dumps(asset_inventory(args.repo.resolve()), ensure_ascii=False, indent=2))
    elif args.inventory:
        print(json.dumps(inventory(args.repo.resolve()), ensure_ascii=False, indent=2))
    elif args.output:
        print(f"Generated {len(generate(args.repo.resolve(), args.output.resolve()))} original plugin source files")
    else:
        cli.error("--output or --inventory is required")

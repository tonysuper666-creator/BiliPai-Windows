#!/usr/bin/env python3
"""Reuse original plugin SDK, policies and algorithms with reviewed platform bindings.

This is separate from the API extractor. Direct sources must remain free of Android
dependencies. Platform substitutions are exact and counted; source drift fails closed.
The original plugin settings UI is supplied by Windows instead of copied Android UI.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

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
    "feature/home/TodayWatchCandidatePoolPolicy")]

EXTRACTED = [BASE + name + ".kt" for name in (
    "core/plugin/PluginStore", "core/plugin/PluginManager", "core/plugin/CastPluginApi",
    "core/plugin/json/RuleEngine", "core/plugin/json/JsonPluginManager",
    "core/plugin/json/JsonPluginStatsNotificationPolicy", "core/plugin/feed/SubscriptionFeedStore",
    "core/plugin/feed/FeedFetcher", "core/plugin/feed/FeedTitleResolver",
    "feature/plugin/EyeProtectionPlugin", "feature/plugin/DanmakuEnhancePlugin",
    "data/repository/SponsorBlockRepository")]
SOURCES = {**{path: "direct" for path in DIRECT}, **{path: "extracted" for path in EXTRACTED}}


def read(repo: Path, path: str) -> str:
    return (repo / path).read_text(encoding="utf-8").replace("\r\n", "\n")


def parser_for(repo: Path):
    spec = importlib.util.spec_from_file_location("plugins_kotlin_parser", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    return parser


def substitute(source: str, before: str, after: str, count: int = 1) -> str:
    if source.count(before) != count:
        raise ValueError(f"Original plugin platform binding changed: {before!r}")
    return source.replace(before, after)


def platform_context(source: str) -> str:
    source = substitute(source, "import android.content.Context", "import com.bilipai.desktop.plugins.DesktopPluginContext as Context")
    return source


def platform_logger(source: str) -> str:
    return substitute(source, "import com.android.purebilibili.core.util.Logger", "import com.bilipai.desktop.plugins.DesktopPluginLog as Logger")


def write(output: Path, path: str, original: str, body: str, name: str | None = None) -> Path:
    package = re.search(r"(?m)^package ([\w.]+)", body)
    if package is None:
        raise ValueError(f"Source has no package: {path}")
    target = output / package[1].replace(".", "/") / (name or Path(path).name)
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


def generate(repo: Path, output: Path) -> list[Path]:
    parser = parser_for(repo)
    generated = []
    for path in DIRECT:
        source = read(repo, path)
        if re.search(r"(?m)^import android\.", source):
            raise ValueError(f"Direct plugin source gained Android dependency: {path}")
        generated.append(write(output, path, source, source))

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
        if "import android.content.Context" in body:
            body = platform_context(body)
        generated.append(write(output, path, source, body))

    path = BASE + "data/repository/SponsorBlockRepository.kt"
    source = read(repo, path)
    body = substitute(source, "import com.android.purebilibili.core.network.NetworkModule\n", "")
    body = substitute(body, "NetworkModule.okHttpClient", "com.bilipai.desktop.plugins.DesktopPluginNetwork.publicClient")
    generated.append(write(output, path, source, body))
    return generated


def inventory(repo: Path) -> list[dict]:
    return [{"path": path, "role": "plugins", "mode": mode,
             "sha256": hashlib.sha256(read(repo, path).encode()).hexdigest()} for path, mode in SOURCES.items()]


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--inventory", action="store_true")
    args = cli.parse_args()
    if args.inventory:
        print(json.dumps(inventory(args.repo.resolve()), ensure_ascii=False, indent=2))
    elif args.output:
        print(f"Generated {len(generate(args.repo.resolve(), args.output.resolve()))} original plugin source files")
    else:
        cli.error("--output or --inventory is required")

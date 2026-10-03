#!/usr/bin/env python3
"""Original JS schemas and pure policies; this does not claim to provide a JS VM."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import re
import textwrap
from pathlib import Path

BASE = "app/src/main/java/com/android/purebilibili/"
DIRECT = [BASE + "core/plugin/js/" + name + ".kt" for name in
    ("BiliPaiJsPluginModels", "ExternalMediaLaunchStore")]
EXTRACTED = [BASE + name + ".kt" for name in
    ("core/plugin/js/BiliPaiJsPluginInstallStore", "core/plugin/feed/FeedSourceCatalog", "core/plugin/js/BiliPaiJsModuleResultCache")]
POLICIES = [BASE + name + ".kt" for name in
    ("core/plugin/js/BiliPaiJsRuntime", "feature/plugin/js/BiliPaiJsPluginContentScreen")]
SOURCES = {**{path: "direct" for path in DIRECT}, **{path: "extracted" for path in EXTRACTED},
    **{path: "policy-extract" for path in POLICIES}}
SCREEN_EXTRA = [BASE + "feature/plugin/js/BiliPaiJsLayoutPresetStore.kt", BASE + "feature/plugin/js/ExternalMediaDanmaku.kt"]
DIRECT.append(SCREEN_EXTRA[1])
SOURCES.update({SCREEN_EXTRA[0]: "extracted", SCREEN_EXTRA[1]: "direct"})
REMOTE_SOURCE = BASE + "feature/settings/screen/PluginsScreen.kt"
SOURCES[REMOTE_SOURCE] = "policy-extract"
EXAMPLES = ["examples/plugins/tv-live.bilipai.js", "examples/plugins/huya-live.bilipai.js"]
SCRIPT_METHODS = ("buildBiliPaiJsPreviewExpression", "buildBiliPaiJsModuleExpression", "buildBiliPaiJsExecutionScript")
CONTENT_METHODS = ('buildBiliPaiJsModuleParamsJson', 'resolveBiliPaiJsInitialParamValues', 'resolveBiliPaiJsInitialPage', 'resolveBiliPaiJsModuleId', 'buildBiliPaiJsParamPreferenceKey', 'readBiliPaiJsParamValues', 'persistBiliPaiJsParamValues', 'safePreferencePart', 'flattenMediaItems', 'resolveBiliPaiJsMediaItemLazyKey')


def original_storage_bridge(original: str, parser) -> str:
    tokens = parser.kotlin_tokens(original)
    starts = [i for i, token in enumerate(tokens[:-1]) if token[0] == "class" and tokens[i + 1][0] == "StorageBridge"]
    if len(starts) != 1: raise ValueError("Original JS storage bridge is missing or ambiguous")
    start = starts[0]
    opening = next(i for i in range(start, len(tokens)) if tokens[i][0] == "{")
    closing, depth = opening, 1
    while depth:
        closing += 1
        if closing >= len(tokens): raise ValueError("Original JS storage bridge is unbalanced")
        depth += (tokens[closing][0] == "{") - (tokens[closing][0] == "}")
    line = original.rfind("\n", 0, tokens[start][1]) + 1
    return textwrap.dedent(original[line:tokens[closing][2]])


def helper(repo: Path):
    spec = importlib.util.spec_from_file_location("js_source_helpers", repo / "desktop/tools/extract-upstream-plugins.py")
    host = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(host)
    return host


def generate_original_content_screen(repo, output, host):
    recipe = json.loads(Path(__file__).with_name("upstream-js-content-adaptations.json").read_text(encoding="utf8"))
    source = host.read(repo, recipe["source"])
    if hashlib.sha256(source.encode()).hexdigest() != recipe["sha256LF"]:
        raise ValueError("Original JS whole content screen changed")
    body = source
    for row in recipe["changes"]:
        body = host.substitute(body, row["before"], row["after"], row["count"])
    undone = body
    for row in reversed(recipe["changes"]):
        before, after = row["before"], row["after"]
        for index in range(len(row["beforePositions"]) - 1, -1, -1):
            position = row["beforePositions"][index] + index * (len(after) - len(before))
            if undone[position:position + len(after)] != after:
                raise ValueError("Original JS whole screen inverse binding changed")
            undone = undone[:position] + before + undone[position + len(after):]
    if undone != source:
        raise ValueError("Original JS whole screen inverse failed")
    results = [host.write(output, recipe["source"], source, body, "OriginalBiliPaiJsPluginContentScreen.kt")]
    preset = host.read(repo, SCREEN_EXTRA[0])
    results.append(host.write(output, SCREEN_EXTRA[0], preset, host.platform_context(preset)))
    mapping = host.read(repo, SCREEN_EXTRA[1])
    host.prune_old_direct(output, SCREEN_EXTRA[1], mapping)
    return results


def generate(repo: Path, output: Path) -> list[Path]:
    host = helper(repo)
    parser = host.parser_for(repo)
    selector = host.media_extractor(repo)
    result = []
    for path in DIRECT:
        host.prune_old_direct(output, path, host.read(repo, path))
    for path in EXTRACTED:
        original = host.read(repo, path)
        body = host.platform_context(original)
        if path.endswith("InstallStore.kt"):
            body = host.substitute(body, "scriptFile.writeText(script, Charsets.UTF_8)",
                "com.bilipai.desktop.plugins.writeDesktopPluginDocument(scriptFile, script)")
            body = host.substitute(body, 'metadataFile(installed.manifest.id)\n            .writeText(json.encodeToString(InstalledBiliPaiJsPlugin.serializer(), installed), Charsets.UTF_8)',
                'com.bilipai.desktop.plugins.writeDesktopPluginDocument(metadataFile(installed.manifest.id),\n            json.encodeToString(InstalledBiliPaiJsPlugin.serializer(), installed))')
        if path.endswith("BiliPaiJsModuleResultCache.kt"):
            # Original complete cache uses this existing account/plugin directory.
            body = host.substitute(body, 'File(context.cacheDir, "bilipai_js_plugin_module_cache")',
                'File(File(context.filesDir, "cache"), "bilipai_js_plugin_module_cache")')
        result.append(host.write(output, path, original, body))
    path = POLICIES[0]
    original = host.read(repo, path)
    body = "\n\n".join(["package com.android.purebilibili.core.plugin.js",
        "import kotlinx.serialization.encodeToString", "import kotlinx.serialization.json.Json"] +
        [selector.function(original, name, parser) for name in SCRIPT_METHODS])
    result.append(host.write(output, path, original, body, "DesktopBiliPaiJsScriptPolicy.kt"))
    bridge = original_storage_bridge(original, parser)
    bridge = host.substitute(bridge, "private class StorageBridge", "internal class DesktopBiliPaiJsStorageBridge")
    bridge = re.sub(r"(?m)^[ \t]*@JavascriptInterface\n", "", bridge)
    bridge = host.substitute(bridge, 'File(storageDir, key.safeStorageName()).writeText(value, Charsets.UTF_8)',
        'com.bilipai.desktop.plugins.writeDesktopPluginDocument(File(storageDir, key.safeStorageName()), value)')
    storage_name = selector.function(original, "safeStorageName", parser).replace("private fun", "internal fun", 1)
    body = "\n\n".join(["package com.android.purebilibili.core.plugin.js",
        "import java.io.File", "import java.util.concurrent.ConcurrentHashMap", bridge, storage_name])
    result.append(host.write(output, path, original, body, "DesktopBiliPaiJsStorageBridge.kt"))
    path = POLICIES[1]
    original = host.read(repo, path)
    methods = []
    for name in CONTENT_METHODS:
        method = selector.function(original, name, parser)
        method = method.replace("private fun", "internal fun", 1)
        method = method.replace("android.content.Context", "Context")
        methods.append(method)
    body = "\n\n".join(["package com.android.purebilibili.feature.plugin.js",
        "import com.bilipai.desktop.plugins.DesktopPluginContext as Context",
        "import com.android.purebilibili.core.plugin.js.*",
        "import kotlinx.serialization.json.JsonPrimitive",
        "import kotlinx.serialization.json.buildJsonObject"] + methods)
    result.append(host.write(output, path, original, body, "DesktopBiliPaiJsContentPolicy.kt"))
    original = host.read(repo, REMOTE_SOURCE)
    download = selector.function(original, "downloadJsRemotePlugin", parser)
    validate = selector.function(original, "validateImportUrlOrError", parser)
    validate = validate.replace("fun validateImportUrlOrError", "internal fun validateDesktopJsImportUrlOrError", 1)
    body = "\n\n".join(["package com.android.purebilibili.feature.settings.screen",
        "import okhttp3.Request",
        "import com.bilipai.desktop.plugins.js.DesktopJsRemoteNetwork as NetworkModule",
        "import com.bilipai.desktop.plugins.DesktopPluginUrl as Uri", download, validate])
    result.append(host.write(output, REMOTE_SOURCE, original, body, "DesktopJsRemoteImportPolicy.kt"))
    result.extend(generate_original_content_screen(repo, output, host))
    return result


def inventory(repo: Path) -> list[dict]:
    host = helper(repo)
    return [{"path": path, "mode": mode, "role": "js-plugins",
        "sha256": hashlib.sha256(host.read(repo, path).encode()).hexdigest()}
        for path, mode in SOURCES.items()]


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--inventory", action="store_true")
    cli.add_argument("--example-inventory", action="store_true")
    args = cli.parse_args()
    repo = args.repo.resolve()
    if args.inventory: print(json.dumps(inventory(repo), indent=2))
    elif args.example_inventory:
        host = helper(repo)
        print(json.dumps([{"path": path, "role": "js-plugins-example", "mode": "example",
            "sha256": hashlib.sha256(host.read(repo, path).encode()).hexdigest()} for path in EXAMPLES], indent=2))
    elif args.output: print(f"Generated {len(generate(repo, args.output.resolve()))} original JS policy/store files; JS VM not included")
    else: cli.error("--output, --inventory or --example-inventory is required")

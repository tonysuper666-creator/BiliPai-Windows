#!/usr/bin/env python3
"""Original external-package and skin protocols, with only desktop platform bindings."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

BASE = "app/src/main/java/com/android/purebilibili/"
DIRECT = [BASE + item + ".kt" for item in (
    "core/plugin/kotlinpkg/ExternalKotlinPluginPackageReader", "core/plugin/skin/UiSkinModels",
    "core/plugin/skin/UiSkinPackageReader", "core/plugin/skin/UiSkinImportPackageResolver",
    "feature/settings/PluginCapabilityUiPolicy", "feature/video/ui/overlay/VideoProgressBarLayoutPolicy")]
EXTRACTED = [BASE + item + ".kt" for item in (
    "core/plugin/kotlinpkg/ExternalKotlinPluginInstallStore", "core/plugin/skin/UiSkinInstallStore",
    "core/plugin/skin/UiSkinSettingsStore", "core/plugin/skin/SkinCatalog")]
POLICIES = [BASE + item + ".kt" for item in ("core/plugin/skin/UiSkinActivationPolicy", "core/plugin/skin/UiSkinComposition", "feature/profile/ProfileScreen")]
SOURCES = {**{item: "direct" for item in DIRECT}, **{item: "extracted" for item in EXTRACTED},
    **{item: "policy-extract" for item in POLICIES}}
ASSET = "app/src/main/assets/rovniced-skin-catalog.json"

def helper(repo: Path):
    spec = importlib.util.spec_from_file_location("package_platform_helpers", repo / "desktop/tools/extract-upstream-plugins.py")
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

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
            body = host.substitute(body, "file.writeText(json.encodeToString(value))",
                "com.bilipai.desktop.plugins.writeDesktopPluginDocument(file, json.encodeToString(value))")
        elif path.endswith("SkinCatalog.kt"):
            body = host.substitute(body, "context.assets.open(ASSET_NAME)",
                "com.bilipai.desktop.plugins.DesktopSkinCatalogResource.open(ASSET_NAME)")
        elif path.endswith("UiSkinSettingsStore.kt"):
            old = selector.function(body, "observe", parser)
            body = host.substitute(body, "    " + old.replace("\n", "\n    "), """    fun observe(context: Context): Flow<UiSkinState> =
        context.store.snapshot("ui_skin_settings").map { readState(context.applicationContext) }.distinctUntilChanged()""")
            start = body.index("\n@Composable\nfun rememberUiSkinState")
            body = body[:start]
            body = host.substitute(body, "import androidx.compose.runtime.Composable\n", "")
            body = host.substitute(body, "import androidx.compose.runtime.State\n", "")
            body = host.substitute(body, "import androidx.lifecycle.compose.collectAsStateWithLifecycle\n", "")
            body = host.substitute(body, "import kotlinx.coroutines.channels.awaitClose\n", "")
            body = host.substitute(body, "import kotlinx.coroutines.flow.callbackFlow\n", "import kotlinx.coroutines.flow.map\n")
        result.append(host.write(output, path, original, body))
    path = BASE + "core/plugin/skin/UiSkinActivationPolicy.kt"
    original = host.read(repo, path)
    body = "package com.android.purebilibili.core.plugin.skin\n\n" + selector.function(original, "resolveUiSkinState", parser)
    result.append(host.write(output, path, original, body))
    path = BASE + "core/plugin/skin/UiSkinComposition.kt"
    original = host.read(repo, path)
    pieces = ["package com.android.purebilibili.core.plugin.skin", "import androidx.compose.runtime.compositionLocalOf", "import androidx.compose.ui.graphics.Color",
        "val LocalUiSkinState = compositionLocalOf { UiSkinState() }"]
    for name in ("parseUiSkinColor", "assetPath"):
        method = selector.function(original, name, parser)
        if name == "parseUiSkinColor":
            method = host.substitute(method, "android.graphics.Color.parseColor", "com.bilipai.desktop.plugins.DesktopPluginColor.parseColor")
        pieces.append(method)
    result.append(host.write(output, path, original, "\n\n".join(pieces), "DesktopUiSkinCompositionPolicy.kt"))
    path = BASE + "feature/profile/ProfileScreen.kt"
    original = host.read(repo, path)
    body = "package com.android.purebilibili.feature.profile\n\nimport com.bilipai.desktop.plugins.DesktopSkinVideoRepeat as Player\n\n" + selector.function(original, "resolveProfileSkinVideoRepeatMode", parser)
    result.append(host.write(output, path, original, body, "DesktopProfileSkinVideoPolicy.kt"))
    digest = hashlib.sha256(host.read(repo, ASSET).encode()).hexdigest()
    body = 'package com.bilipai.desktop.plugins\n\ninternal const val DESKTOP_SKIN_CATALOG_SHA256 = ' + json.dumps(digest) + '\n'
    result.append(host.write(output, ASSET, host.read(repo, ASSET), body, "DesktopSkinAssetHash.kt"))
    return result

def inventory(repo: Path) -> list[dict]:
    host = helper(repo)
    return [{"path": path, "mode": mode, "role": "packages", "sha256": hashlib.sha256(host.read(repo, path).encode()).hexdigest()} for path, mode in SOURCES.items()]

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--inventory", action="store_true")
    cli.add_argument("--asset-inventory", action="store_true")
    args = cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo.resolve()), indent=2))
    elif args.asset_inventory:
        host = helper(args.repo.resolve())
        print(json.dumps([{"path": ASSET, "role": "packages", "mode": "asset", "sha256": hashlib.sha256(host.read(args.repo.resolve(), ASSET).encode()).hexdigest()}], indent=2))
    elif args.output: print(f"Generated {len(generate(args.repo.resolve(), args.output.resolve()))} original package source files")
    else: cli.error("--output, --inventory or --asset-inventory is required")

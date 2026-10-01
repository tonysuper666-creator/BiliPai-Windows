"""Original preference, navigation, list and window facades with measured Windows bounds."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re

BASE = "design-system/src/main/java/com/android/purebilibili/core/ui/"
DIRECT = [BASE + name + ".kt" for name in (
    "AppIcons", "AppSemanticVisualPolicy", "AppListItemPolicy", "AppDrawerVisualPolicy",
    "AppNavigationCapabilities", "AppPullRefreshIndicator",
    "AdaptiveLoadingIndicator", "AdaptiveLoadingIndicatorPolicy", "PresetPrimitiveRenderer",
    "AppBottomNavigationHost", "AppSquircleModifiers", "motion/AppMotionTokens")]
DIRECT += [BASE + "components/" + name + ".kt" for name in (
    "AppPreferenceComponents", "AppListItem", "AppSingleChoiceRow", "AppWindowActionMenu",
    "AppSplitLayout", "AppBackToTopButton", "AppContentStateComponents",
    "AdaptiveListItemPolicy", "LongPressActionModifier")]
DIRECT += [BASE + "renderer/" + name + ".kt" for name in (
    "material3/AppMaterial3ListItem", "miuix/AppMiuixListItem")]
ADAPTED = [BASE + "components/" + name + ".kt" for name in (
    "AdaptivePreferenceComponents", "AppSelectionPreferenceComponents", "AdaptiveContentCardComponents", "AppNavigationComponents")]
POLICIES = [BASE + "AdaptiveChrome.kt"]

def helper(repo):
    spec = importlib.util.spec_from_file_location("preference_helpers", repo / "desktop/tools/extract-upstream-plugins.py")
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result

def adapt(path, source, host):
    if path.endswith("/AdaptivePreferenceComponents.kt"):
        # Keep original rendering/signatures; bind the M3 collector to current hoisted input.
        duplicate = "import com.android.purebilibili.core.ui.LocalAppThemeConfig\n"
        if source.count(duplicate) != 2: raise ValueError("Original duplicate preference import changed")
        source = source.replace(duplicate, "", 1)
        source = host.substitute(source, "import androidx.compose.runtime.remember\n",
            "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberUpdatedState\n")
        source = host.substitute(source,
            "val textFieldState = rememberTextFieldState(initialText = query)\n        LaunchedEffect(textFieldState)",
            "val textFieldState = rememberTextFieldState(initialText = query)\n        val currentQuery by rememberUpdatedState(query)\n        val currentOnQueryChange by rememberUpdatedState(onQueryChange)\n        LaunchedEffect(textFieldState)")
        source = host.substitute(source, "if (updated != query) {\n                        onQueryChange(updated)",
            "if (updated != currentQuery) {\n                        currentOnQueryChange(updated)")
    elif path.endswith("/AppSelectionPreferenceComponents.kt"):
        source = host.substitute(source, "import androidx.compose.ui.platform.LocalConfiguration",
            "import com.bilipai.desktop.appearance.DesktopWindowConfiguration as LocalConfiguration")
    elif path.endswith("/AdaptiveContentCardComponents.kt"):
        # Android's legacy FontMetrics padding toggle does not exist in the Skia JVM renderer.
        source = host.substitute(source, "import androidx.compose.ui.text.PlatformTextStyle\n", "")
        source = host.substitute(source, "        platformStyle = PlatformTextStyle(includeFontPadding = false),\n", "")
    else: raise ValueError("No platform binding for " + path)
    return source

def generate(repo, output, standalone=False):
    host = helper(repo); output.mkdir(parents=True, exist_ok=True); result = []
    for path in DIRECT:
        original = host.read(repo, path)
        if re.search(r"(?m)^import (?:android\.|androidx\.activity\.)", original): raise ValueError("Pure facade acquired Android dependency: " + path)
        host.prune_old_direct(output, path, original)
        if standalone: result.append(host.write(output, path, original, original))
    for path in ADAPTED:
        original = host.read(repo, path)
        result.append(host.write(output, path, original, adapt(path, original, host)))
    for path in POLICIES:
        original = host.read(repo, path)
        declaration = re.findall(r"(?m)^val LocalGlobalWallpaperBackdropVisible = [^\n]+", original)
        if len(declaration) != 1: raise ValueError("Original wallpaper host local changed")
        body = "package com.android.purebilibili.core.ui\n\nimport androidx.compose.runtime.compositionLocalOf\n\n" + declaration[0] + "\n"
        result.append(host.write(output, path, original, body, "AppWallpaperBackdropLocal.kt"))
    return result

def inventory(repo):
    host = helper(repo)
    return [dict(path=p, mode="direct" if p in DIRECT else "policy-extract" if p in POLICIES else "platform-adapter-reference",
        features=["preference-parity"], sha256=hashlib.sha256(host.read(repo, p).encode()).hexdigest()) for p in DIRECT + ADAPTED + POLICIES]

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True); cli.add_argument("--output", type=Path)
    cli.add_argument("--standalone", action="store_true"); cli.add_argument("--inventory", action="store_true")
    args = cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo.resolve()), indent=2))
    if args.output: print("Generated", len(generate(args.repo.resolve(), args.output.resolve(), args.standalone)), "original preference/navigation files")

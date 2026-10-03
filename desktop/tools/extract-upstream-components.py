"""Exact original App* facades and renderer graph, with two real Windows platform bindings."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re

BASE = "design-system/src/main/java/com/android/purebilibili/core/ui/"
DIRECT = [BASE + name + ".kt" for name in (
    "AppThemeConfig", "AppShapes", "AppSurfaceTokens", "AppSpacingTokens", "AppChromeSizeTokens",
    "AppPopupSurface", "AppDialogComponents", "ButtonVisualPolicy",
    "AppPrimitiveThemeDefaults", "blur/BlurIntensity", "motion/MiuixPressFeedbackModifier")]
DIRECT += [BASE + "components/" + name + ".kt" for name in (
    "AppPrimitiveComponents", "AppPrimitiveRendererPolicy", "AppDesktopInteraction", "AppPrimaryButton",
    "AppCheckbox", "AppCard", "AppIcon", "AppSwitch", "AppSurface", "AppRadioButton",
    "AppIconButton", "AppProgressIndicator", "AppBadge")]
DIRECT += [BASE + "renderer/material3/AppMaterial3" + name + ".kt" for name in (
    "Checkbox", "Card", "Icon", "Switch", "Surface", "Slider", "RadioButton", "IconButton", "Text", "ProgressIndicator", "Badge")]
DIRECT += [BASE + "renderer/miuix/AppMiuix" + name + ".kt" for name in (
    "ActionPrimitives", "Checkbox", "Card", "Icon", "Switch", "Surface", "Slider", "RadioButton",
    "IconButton", "HapticFeedback", "Text", "ProgressIndicator", "Badge")]
ADAPTED = [BASE + "components/AppText.kt", BASE + "AppContentDialogLayoutPolicy.kt", BASE + "components/AppSlider.kt", BASE + "AdaptiveDialogComponents.kt"]
REUSED = [BASE + "renderer/miuix/AppMiuix" + name + ".kt" for name in ("Text", "ProgressIndicator")]
POLICIES = [BASE + "components/AppSegmentedControl.kt"]

def helper(repo):
    spec = importlib.util.spec_from_file_location("component_helpers", repo / "desktop/tools/extract-upstream-plugins.py")
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result

def adapt(path, source, host):
    if path.endswith("/AppText.kt"):
        source = re.sub(r"(?m)^import android\.[^\n]+\n", "", source)
        source = host.substitute(source, "import androidx.compose.ui.platform.LocalContext\n", "import com.bilipai.desktop.appearance.LocalDesktopTextClipboard\n")
        source = host.substitute(source, "val context = LocalContext.current", "val clipboard = LocalDesktopTextClipboard.current")
        source = host.substitute(source, "pointerInput(text, context)", "pointerInput(text, clipboard)")
        original = '''                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BiliPai 文本", text.trim()))
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }'''
        source = host.substitute(source, original, '''                if (clipboard.copyText(text.trim())) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }''')
    elif path.endswith("/AppContentDialogLayoutPolicy.kt"):
        # JVM DialogProperties does not expose Android secure-policy or decor-fit fields.
        source = host.substitute(source, "        securePolicy = base.securePolicy,\n", "")
        source = host.substitute(source, "        decorFitsSystemWindows = base.decorFitsSystemWindows,\n", "")
        # Desktop Dialog passes a tight host minimum. Release that minimum before
        # applying the original content policy's min/max widths and centering.
        source = host.substitute(source, "import androidx.compose.foundation.layout.wrapContentHeight\n",
            "import androidx.compose.foundation.layout.wrapContentHeight\nimport androidx.compose.foundation.layout.wrapContentWidth\n")
        source = host.substitute(source, "        .padding(horizontal = policy.horizontalPaddingDp.dp)\n        .widthIn(",
            "        .padding(horizontal = policy.horizontalPaddingDp.dp)\n        .wrapContentWidth()\n        .widthIn(")
    elif path.endswith("/AdaptiveDialogComponents.kt"):
        # Windows DialogProperties has no Android secure-policy / decor-fit fields.
        original=source;edits=[]
        assert hashlib.sha256(original.encode()).hexdigest()=='509450b773b1b9916580985dc1f0be17fceaeb971af8dc595b10bd03ce9e562b'
        for before,after in [('                securePolicy = properties.securePolicy,\n', ''), ('                decorFitsSystemWindows = false,\n', '')]:
            assert source.count(before)==1;at=source.index(before);edits.append((at,before,after))
            source=source[:at]+after+source[at+len(before):]
        inverse=source
        for at,before,after in reversed(edits):
            assert inverse[at:at+len(after)]==after;inverse=inverse[:at]+before+inverse[at+len(after):]
        assert inverse==original
        assert hashlib.sha256(source.encode()).hexdigest()=='7644e0cefa05a536ce37abd80fe862d2ad8d5c2114ede93f48c68d892f9afe13'
    elif path.endswith("/AppSlider.kt"):
        source = host.substitute(source, "import android.os.SystemClock", "import com.bilipai.desktop.appearance.DesktopMonotonicClock as SystemClock")
    else: raise ValueError("No platform binding for " + path)
    return source

def generate(repo, output, standalone=False):
    host = helper(repo)
    output.mkdir(parents=True, exist_ok=True)
    result = []
    for path in DIRECT:
        original = host.read(repo, path)
        if re.search(r"(?m)^import android\.", original): raise ValueError("Pure renderer acquired Android dependency: " + path)
        host.prune_old_direct(output, path, original)
        if standalone and path not in REUSED:
            result.append(host.write(output, path, original, original))
    for path in ADAPTED:
        original = host.read(repo, path)
        result.append(host.write(output, path, original, adapt(path, original, host)))
    for path in POLICIES:
        original = host.read(repo, path)
        declaration = re.findall(r"(?m)^internal fun resolvePiliPlusScrollableUnderlineMinWidth\(\): Dp = [^\n]+", original)
        if len(declaration) != 1: raise ValueError("Original tab-width policy changed")
        body = "package com.android.purebilibili.core.ui.components\n\nimport androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp\n\n" + declaration[0] + "\n"
        result.append(host.write(output, path, original, body, "AppScrollableUnderlinePolicy.kt"))
    return result

def inventory(repo):
    host = helper(repo)
    return [dict(path=p, mode="direct" if p in DIRECT else "policy-extract" if p in POLICIES or p.endswith("/AdaptiveDialogComponents.kt") else "platform-adapter-reference",
        features=["component-parity"], sha256=hashlib.sha256(host.read(repo, p).encode()).hexdigest()) for p in DIRECT + ADAPTED + POLICIES]

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--standalone", action="store_true")
    cli.add_argument("--inventory", action="store_true")
    args = cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo.resolve()), indent=2))
    if args.output: print("Generated", len(generate(args.repo.resolve(), args.output.resolve(), args.standalone)), "original component files")

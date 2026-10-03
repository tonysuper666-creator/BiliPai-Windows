"""Whole original IconSettingsScreen; Windows preference/frame/image resource leaves only."""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, os, re, struct, sys
from pathlib import Path

UPSTREAM = "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
CATALOG_SHA = "2fa53aa78cc27c76a3923cc44750128b3657c340dbcfe44ec13810cbc48becbc"
SCREEN = "app/src/main/java/com/android/purebilibili/feature/settings/screen/IconSettingsScreen.kt"
NORMALIZER = "app/src/main/java/com/android/purebilibili/core/store/AppIconKeyNormalizer.kt"
MANAGER = "app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt"
VIEW_MODEL = "app/src/main/java/com/android/purebilibili/feature/settings/SettingsViewModel.kt"
SYMBOLS = ["ms_info_24", "ms_check_circle_fill_24"]
FEATURE = "desktop-whole-icon-settings"
sys.dont_write_bytecode = True

def wide(path):
    value = str(Path(path).absolute())
    return Path("\\\\?\\" + value) if os.name == "nt" and not value.startswith("\\\\?\\") else Path(value)

def sha(raw): return hashlib.sha256(raw).hexdigest()
def emit(path, raw):
    wide(path.parent).mkdir(parents=True, exist_ok=True)
    wide(path).write_bytes(raw if isinstance(raw, bytes) else raw.encode("utf-8"))

class Original:
    def __init__(self, repo):
        self.repo = repo
        raw = wide(repo / "desktop/tools/v025-canonical-sources.json").read_bytes()
        if sha(raw) != CATALOG_SHA: raise ValueError("Canonical catalog digest changed")
        self.catalog = json.loads(raw)
        if self.catalog["upstreamCommit"] != UPSTREAM: raise ValueError("Canonical commit changed")
    def raw(self, path):
        if path not in self.catalog["paths"]: raise ValueError("Unknown canonical original: " + path)
        file = wide(self.repo / path)
        if file.is_symlink(): raise ValueError("Original is a link: " + path)
        raw = file.read_bytes()
        row = self.catalog["paths"][path]
        normalized = raw.replace(b"\r\n", b"\n") if row["hashNormalization"] == "lf" else raw
        if sha(normalized) != row["sha256"]: raise ValueError("Original digest mismatch: " + path)
        return raw
    def text(self, path): return self.raw(path).decode("utf-8").replace("\r\n", "\n")
    def row(self, path, mode=None):
        raw = self.raw(path)
        result = dict(path=path, sha256=sha(raw.replace(b"\r\n", b"\n") if path.endswith((".kt", ".xml")) else raw), features=[FEATURE])
        if not path.endswith((".kt", ".xml")): result["hashNormalization"] = "raw"
        if mode is not None: result["mode"] = mode
        return result

def adapt(original):
    source = original
    changes = []
    def replace(before, after, count=1):
        nonlocal source
        if source.count(before) != count: raise ValueError("Leaf occurrence changed: " + before)
        start = 0
        for _ in range(count):
            offset = source.index(before, start)
            changes.append(dict(offset=offset, before=before, after=after))
            source = source[:offset] + after + source[offset+len(before):]
            start = offset + len(after)
    for line in ["import android.widget.Toast\n", "import androidx.compose.ui.platform.LocalContext\n", "import androidx.compose.ui.res.stringResource\n", "import androidx.lifecycle.viewmodel.compose.viewModel\n", "import com.android.purebilibili.R\n", "import com.android.purebilibili.core.store.SettingsManager\n", "import androidx.lifecycle.compose.collectAsStateWithLifecycle\n"]:
        replace(line, "")
    replace("package com.android.purebilibili.feature.settings\n", "package com.android.purebilibili.feature.settings\nimport com.bilipai.desktop.settings.*\nimport com.bilipai.desktop.appearance.LocalDesktopStrings\nimport androidx.compose.foundation.isSystemInDarkTheme\n")
    replace("val iconRes: Int)", "val iconRes: String)")
    replace("): Int {\n    return when (iconKey to appearance)", "): String {\n    return when (iconKey to appearance)")
    for name in sorted(set(re.findall(r"R\.mipmap\.(\w+)", source)), key=len, reverse=True):
        replace("R.mipmap." + name, '"' + name + '"', source.count("R.mipmap." + name))
    for name in SYMBOLS:
        replace("com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable." + name + ")", 'DesktopIconSettingsVectors.vector("' + name + '")')
    replace("fun IconSettingsScreen(\n    viewModel: SettingsViewModel = viewModel(),", "internal fun DesktopOriginalIconSettingsScreen(\n    viewModel: DesktopOriginalIconSettingsBindings,")
    replace("val context = LocalContext.current", "val context = viewModel")
    replace("val state by viewModel.state.collectAsStateWithLifecycle()", "val state by viewModel.state.collectAsState(initial = viewModel.initialState)")
    replace("val iconAppearance by SettingsManager.getAppIconAppearance(context)\n        .collectAsStateWithLifecycle(initialValue = AppIconAppearance.FOLLOW_SYSTEM)", "val iconAppearance by viewModel.iconAppearance\n        .collectAsState(initial = viewModel.initialAppearance)")
    replace("val screenTitle = stringResource(R.string.icon_settings_title)", 'val screenTitle = LocalDesktopStrings.current["icon_settings_title"]')
    replace("val backLabel = stringResource(R.string.common_back)", 'val backLabel = LocalDesktopStrings.current["common_back"]')
    replace("fun IconSettingsContent(", "internal fun DesktopOriginalIconSettingsContent(")
    replace("IconSettingsContent(\n            state", "DesktopOriginalIconSettingsContent(\n            state")
    replace("state: SettingsUiState,", "state: DesktopOriginalIconSettingsState,")
    replace("viewModel: SettingsViewModel,", "viewModel: DesktopOriginalIconSettingsBindings,")
    replace("context: android.content.Context,", "context: DesktopOriginalIconSettingsBindings,")
    replace('Toast.makeText(context, "正在切换图标…", Toast.LENGTH_SHORT).show()', "context.showSwitchNotice()")
    replace("model = resolveIconOptionPreviewRes(option.key, iconAppearance),", "model = desktopOriginalIconPreviewUri(resolveIconOptionPreviewRes(option.key, iconAppearance), isSystemInDarkTheme()),")
    # Windows does not perform Android launcher alias reconfiguration or stall its launcher.
    replace("图标切换可能需要几秒钟生效，系统可能会短暂卡顿。", "所选图标用于 Windows 窗口和任务栏，重启后保留。EXE 文件图标由安装包提供。")
    reconstructed = source
    for change in reversed(changes):
        offset, before, after = change["offset"], change["before"], change["after"]
        if reconstructed[offset:offset+len(after)] != after: raise ValueError("Inverse hunk mismatch")
        reconstructed = reconstructed[:offset] + before + reconstructed[offset+len(after):]
    if reconstructed != original: raise ValueError("Whole-screen inverse failed")
    return source, dict(originalLfSha256=sha(original.encode()), adaptedLfSha256=sha(source.encode()), fullSourceInverseVerified=True, selectedProtocolMembers=[], changes=changes)

def assets(original):
    source = original.text(SCREEN)
    names = sorted(set(re.findall(r"R\.mipmap\.(\w+)", source)) | {"ic_launcher_3d_round"})
    rows = []
    for name in names:
        base = "app/src/main/res/mipmap-xxxhdpi/" + name + ".png"
        original.raw(base)
        rows.append((name, False, base))
        night = "app/src/main/res/mipmap-night-xxxhdpi/" + name + ".png"
        if night in original.catalog["paths"]:
            original.raw(night)
            rows.append((name, True, night))
    return rows

def generate(repo, output, resource_output, proof_output):
    sys.path.insert(0, str(repo / "desktop/tools"))
    original = Original(repo)
    screen, proof = adapt(original.text(SCREEN))
    emit(output / "com/android/purebilibili/feature/settings/DesktopOriginalIconSettingsScreen.kt", screen)
    selected = assets(original)
    mappings = []
    resource_proof = []
    for name, night, path in selected:
        relative = "original-app-icons/" + ("night/" if night else "day/") + name + ".png"
        raw = original.raw(path)
        if raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR": raise ValueError("Invalid original PNG")
        width, height = struct.unpack(">II", raw[16:24])
        if not (1 <= width <= 4096 and 1 <= height <= 4096): raise ValueError("Unsafe PNG dimensions")
        if resource_output: emit(resource_output / relative, raw)
        mappings.append((name, night, "/" + relative))
        resource_proof.append(dict(path=path, target=relative, bytes=len(raw), sha256=sha(raw), width=width, height=height, untouchedOriginalBytes=True))
    night_names = sorted(name for name, night, _ in selected if night)
    resource_source = '''package com.bilipai.desktop.settings
import com.android.purebilibili.feature.settings.IconOption
internal fun resolveDesktopOriginalIconResource(name: String, systemDark: Boolean): String {
    require(name in setOf(NAMES)) { "Unknown original app icon: $name" }
    val night = systemDark && name in setOf(NIGHT_NAMES)
    return "/original-app-icons/${if(night) "night" else "day"}/$name.png"
}
internal fun desktopOriginalIconPreviewUri(name: String, systemDark: Boolean): coil3.Uri =
    desktopOriginalCoilClasspathUri(requireNotNull(IconOption::class.java.getResource(resolveDesktopOriginalIconResource(name, systemDark))) { "Original app icon resource missing: $name" })
internal fun desktopOriginalCoilClasspathUri(location: java.net.URL): coil3.Uri = when (location.protocol) {
    // Supply native path components directly; Windows drive and nested jar colons must not be reparsed.
    "file" -> coil3.Uri(scheme = "file", path = java.nio.file.Path.of(location.toURI()).toString().replace(java.io.File.separatorChar, '/'))
    "jar" -> {
        val connection = location.openConnection() as java.net.JarURLConnection
        require(connection.jarFileURL.protocol == "file") { "App icons require a local classpath archive" }
        val path = java.nio.file.Path.of(connection.jarFileURL.toURI()).toString().replace(java.io.File.separatorChar, '/')
        coil3.Uri(scheme = "jar:file", path = path + "!/" + connection.entryName)
    }
    else -> error("Unsupported original icon classpath protocol: ${location.protocol}")
}
'''.replace("NIGHT_NAMES", ", ".join(json.dumps(n) for n in night_names)).replace("NAMES", ", ".join(json.dumps(n) for n in sorted(set(name for name, _, _ in selected))))
    emit(output / "com/bilipai/desktop/settings/DesktopOriginalAppIconResources.kt", resource_source)
    spec = importlib.util.spec_from_file_location("icon_vectors", repo / "desktop/tools/extract-upstream-settings-search.py")
    converter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(converter)
    converter.symbol_names = lambda _: SYMBOLS
    vectors = converter.vectors(repo).replace("DesktopSettingsSymbols", "DesktopIconSettingsSymbols").replace("DesktopSettingsVectors", "DesktopIconSettingsVectors")
    emit(output / "com/bilipai/desktop/settings/DesktopIconSettingsVectors.kt", vectors)
    proof.update(upstreamCommit=UPSTREAM, sourcePath=SCREEN, resources=resource_proof, adapterCompiled=False, actualRootRuntimeVerified=False, actualWindowIconVerified=False, candidateModified=False)
    if proof_output: emit(proof_output, json.dumps(proof, ensure_ascii=False, indent=2) + "\n")
    return proof

def source_inventory(original):
    return [original.row(path, "direct" if path == NORMALIZER else "extracted") for path in [SCREEN, NORMALIZER, MANAGER, VIEW_MODEL]]

def resource_inventory(original):
    return [original.row(path) for _, _, path in assets(original)] + [original.row("app/src/main/res/drawable/"+name+".xml") for name in SYMBOLS]

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--resource-output", type=Path)
    cli.add_argument("--proof", type=Path)
    cli.add_argument("--inventory", action="store_true")
    cli.add_argument("--resource-inventory", action="store_true")
    args = cli.parse_args()
    original = Original(args.repo.resolve())
    if args.inventory: print(json.dumps(source_inventory(original), ensure_ascii=False, indent=2))
    if args.resource_inventory: print(json.dumps(resource_inventory(original), ensure_ascii=False, indent=2))
    if args.output:
        proof = generate(args.repo.resolve(), args.output.resolve(), args.resource_output.resolve() if args.resource_output else None, args.proof)
        print(json.dumps(dict(fullSourceInverseVerified=proof["fullSourceInverseVerified"], pngResources=len(proof["resources"]), originalScreenLfSha256=proof["originalLfSha256"], adaptedScreenLfSha256=proof["adaptedLfSha256"])))

"""Prepare full original Tips/Licenses/Scaffold using the existing platform leaves.

Writes only the explicitly requested output directory. No Gradle, app execution,
registry mutation or repository changes. Review fixture output is separate from the production generated source root.
"""
from pathlib import Path
import argparse
import difflib
import hashlib
import importlib.util
import json
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
BASE = "app/src/main/java/com/android/purebilibili/feature/settings/"
SOURCES = [BASE + "screen/TipsSettingsScreen.kt",
           BASE + "screen/OpenSourceLicensesScreen.kt",
           BASE + "ui/SettingsPageScaffold.kt",
           BASE + "SettingsScreenPolicy.kt"]
UPSTREAM = "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
FEATURE = ["desktop-whole-static-settings-pages"]

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

def original(repo, relative):
    paths = module(repo / "desktop/tools/v025_source_paths.py", "static_page_paths")
    return paths.canonical_source(repo, relative).read_text(encoding="utf-8").replace("\r\n", "\n")

def edit(source, before, after, operations):
    if source.count(before) != 1:
        raise ValueError("Expected one exact original hunk: " + before)
    at = source.index(before)
    operations.append(dict(offset=at, before=before, after=after))
    return source[:at] + after + source[at+len(before):]

def adapt(repo, path):
    source = initial = original(repo, path)
    operations = []
    if path.endswith(("TipsSettingsScreen.kt", "OpenSourceLicensesScreen.kt")):
        source = edit(source, "import androidx.compose.ui.res.stringResource",
            "import com.bilipai.desktop.appearance.LocalDesktopStrings", operations)
        source = edit(source, "import com.android.purebilibili.R",
            "import com.bilipai.desktop.settings.DesktopStaticSettingsSymbols\n"
            "import com.bilipai.desktop.settings.desktopStaticSettingsVector", operations)
        for key in ("common_back", "tips_title" if path.endswith("TipsSettingsScreen.kt")
                    else "open_source_licenses_title"):
            source = edit(source, "stringResource(R.string."+key+")",
                'LocalDesktopStrings.current["'+key+'"]', operations)
        if path.endswith("TipsSettingsScreen.kt"):
            source = edit(source, "import androidx.annotation.DrawableRes\n", "", operations)
            source = edit(source, "@DrawableRes val iconResId: Int", "val iconResId: String", operations)
            source = edit(source, "@DrawableRes iconResId: Int", "iconResId: String", operations)
            for name in re.findall(r"R\.drawable\.(\w+)", initial):
                source = edit(source, "R.drawable."+name, "DesktopStaticSettingsSymbols."+name, operations)
            source = edit(source, "rememberMaterialSymbol(iconResId)",
                "desktopStaticSettingsVector(iconResId)", operations)
        else:
            # Only dependency attribution data changes; all screen/layout declarations remain.
            source = edit(source, '    OpenSourceLibrary(\n        name = "pinyin4j",\n        license = "GPL-2.0",\n        url = "https://github.com/belerweb/pinyin4j",\n        description = "中文拼音转换"\n    ),', '    OpenSourceLibrary(\n        name = "TinyPinyin",\n        license = "Apache-2.0",\n        url = "https://github.com/biezhi/TinyPinyin",\n        description = "中文拼音转换"\n    ),\n    OpenSourceLibrary(\n        name = "AhoCorasick",\n        license = "Apache-2.0",\n        url = "https://github.com/robert-bor/aho-corasick",\n        description = "TinyPinyin 的 Java 字符串匹配依赖"\n    ),', operations)
            source = edit(source,
                "com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_keyboard_arrow_right_24)",
                "desktopStaticSettingsVector(DesktopStaticSettingsSymbols.ms_keyboard_arrow_right_24)", operations)
    elif path.endswith("SettingsPageScaffold.kt"):
        source = edit(source,
            "shouldAllowRenderEffectBackedHazeEffect(android.os.Build.VERSION.SDK_INT)",
            "com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()", operations)
        source = edit(source,
            "import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n",
            "", operations)
    elif not path.endswith("SettingsScreenPolicy.kt"):
        raise ValueError(path)
    inverse = source
    for op in reversed(operations):
        at = op["offset"]
        if inverse[at:at+len(op["after"])] != op["after"]:
            raise ValueError("Inverse offset is inconsistent")
        inverse = inverse[:at] + op["before"] + inverse[at+len(op["after"]):]
    if inverse != initial:
        raise ValueError("Full original inverse mismatch")
    if re.search(r"(?m)^import (?:android\.|androidx\.annotation|androidx\.compose\.ui\.res)", source):
        raise ValueError("Unbound Android resource leaf remains")
    if re.search(r"\b(?:R\.(?:drawable|string)|android\.os\.Build)\b", source):
        raise ValueError("Unbound Android resource or capability use")
    return initial, source, operations

def source_inventory(repo):
    return [dict(path=p, sha256=sha(original(repo,p).encode()),
                 mode="direct" if p.endswith("SettingsScreenPolicy.kt") else "platform-adapter-reference",
                 features=FEATURE) for p in SOURCES]

def resource_inventory(repo):
    src = original(repo, SOURCES[0]) + original(repo, SOURCES[1])
    names = sorted(set(re.findall(r"R\.drawable\.(\w+)", src)))
    return [dict(path="app/src/main/res/drawable/"+n+".xml",
        sha256=sha(original(repo, "app/src/main/res/drawable/"+n+".xml").encode()),
        features=FEATURE) for n in names]

def generate(repo, output, standalone=False):
    sys.path.insert(0, str(repo / "desktop/tools"))
    converter = module(repo / "desktop/tools/extract-upstream-settings-search.py", "static_page_vectors")
    shared = set(converter.symbol_names(repo))
    category = {"ms_keyboard_arrow_right_24"}
    assets = resource_inventory(repo)
    names = [Path(row["path"]).stem for row in assets]
    extra = sorted(set(names)-shared-category)
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for path in SOURCES:
        before, after, operations = adapt(repo, path)
        package = re.search(r"(?m)^package\s+([\w.]+)", after).group(1)
        target = output / package.replace(".", "/") / Path(path).name
        target.parent.mkdir(parents=True, exist_ok=True)
        emitted = standalone or not path.endswith("SettingsScreenPolicy.kt")
        if emitted:
            target.write_text(after, encoding="utf-8", newline="\n")
        results.append(dict(originalPath=path, outputPath=str(target),
            emitted=emitted, directOriginalCopyOwnedByUpstreamTask=not emitted,
            inputSha256LF=sha(before.encode()), outputSha256LF=sha(after.encode()),
            wholeSourcePreserved=True, fullInverseVerified=True, operations=operations))
    helper = """package com.bilipai.desktop.settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/** Exact original asset identifiers; existing owners keep their vector caches. */
internal object DesktopStaticSettingsSymbols {
""" + "".join("    const val "+n+'="'+n+'"\n' for n in names) + """}
@Composable internal fun desktopStaticSettingsVector(name: String): ImageVector = when (name) {
"""
    for name in names:
        owner = ("DesktopSettingsVectors" if name in shared else
                 "DesktopSettingsCategoryVectors" if name in category else
                 "DesktopStaticSettingsAdditionalVectors")
        helper += '    "'+name+'" -> '+owner+".vector(name)\n"
    helper += '    else -> error("Unknown original static settings vector: $name")\n}\n'
    target = output / "com/bilipai/desktop/settings/DesktopStaticSettingsVectors.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(helper, encoding="utf-8", newline="\n")
    if extra:
        converter.symbol_names = lambda unused: extra
        extra_src = converter.vectors(repo).replace("DesktopSettingsSymbols", "DesktopStaticSettingsAdditionalSymbols").replace("DesktopSettingsVectors", "DesktopStaticSettingsAdditionalVectors")
        (target.parent / "DesktopStaticSettingsAdditionalVectors.kt").write_text(extra_src,encoding="utf-8",newline="\n")
    registry = json.loads((repo / "desktop/upstream-sources.json").read_text(encoding="utf-8"))
    registered_assets = {row["path"]: row for row in registry["resources"]}
    registered_sources = {row["path"]: row for row in registry["sources"]}
    strings = []
    for locale, path in (("zh-CN","app/src/main/res/values/strings.xml"),
                         ("en","app/src/main/res/values-en/strings.xml"),
                         ("zh-TW","app/src/main/res/values-zh-rTW/strings.xml")):
        data = original(repo,path)
        values = {node.attrib.get("name"): "".join(node.itertext()) for node in ET.fromstring(data) if node.tag=="string"}
        strings.append(dict(locale=locale,path=path,sha256LF=sha(data.encode()),
            existingAppearanceProducer="desktop/tools/extract-appearance-platform.py",
            values={key:values.get(key) for key in ("tips_title","open_source_licenses_title","common_back")}))
        if any(value is None for value in strings[-1]["values"].values()):
            raise ValueError("Original localized string missing")
    report = dict(schemaVersion=1, upstreamCommit=UPSTREAM, scope="prepared-full-source-platform-adaptation",
        compileAccepted=False, actualRootAccepted=False, actionsAccepted=False,
        sources=results, sourceInventory=source_inventory(repo),
        existingRegistrySources={p:registered_sources.get(p) for p in SOURCES},
        sourceRegistryDelta=[row for row in source_inventory(repo) if row["path"] not in registered_sources],
        resourceInventory=assets,
        resourceRegistryDelta=[row for row in assets if row["path"] not in registered_assets],
        assetOwners={name: "settings-search" if name in shared else "settings-categories" if name in category else "static-settings-additional" for name in names},
        additionalVectorNames=extra, localizedStrings=strings,
        requiredExistingDependencyDeclarations=[
            "SettingsVisualSpec.SettingsPageScrollHost", "AppScaffold", "AppTopBar",
            "ProgressiveTopChrome.BiliPaiImmersiveTopBar", "DesktopOriginalDetailAdaptiveChrome.rememberAppTopBarCollapseBehavior",
            "DesktopOriginalDetailAdaptiveChrome.appTopBarNestedScroll", "AppIcons.rememberAppBackIcon",
            "DesktopFavoriteBottomBarLocals.LocalBottomBarContentPadding", "DesktopFavoriteBottomBarLocals.LocalSetBottomBarVisible",
            "DesktopStrings.LocalDesktopStrings", "AppEntrance.EntranceGroup/entrance",
            "desktopDetailRenderEffectsSupported"],
        integrationRequirements=[
            "Run new producer into a new generated source root and register all four original source identities",
            "A direct SettingsScreenPolicy registry row must emit only once; do not also compile duplicate producer output",
            "Mounted physical TipsSettings/OpenSourceLicenses routes and SettingsSearchTarget.TIPS/OPEN_SOURCE_LICENSES must call these original screens",
            "Add both details to nestedPageOwnsScroll and let original LazyColumn own height/scroll; do not put a bounded LazyColumn inside current unconstrained verticalScroll",
            "Reuse existing captured Root bottom-bar/content-padding/environment providers; do not build an alternate settings store or navigation state",
            "No new artifact dependency or JavaScript worker classpath change",
            "Original library/reference list is preserved as upstream text; separate actual Windows bundled legal notices remain authoritative and should be accessible too"])
    (output/"static-settings-pages-source-report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    diffs=[]
    for row in results:
        if not row["emitted"]:
            continue
        diffs.extend(difflib.unified_diff(original(repo,row["originalPath"]).splitlines(True),
            Path(row["outputPath"]).read_text(encoding="utf-8").splitlines(True),
            fromfile=row["originalPath"],tofile=row["outputPath"]))
    (output/"static-settings-pages-source-inverse.patch").write_text("".join(diffs),encoding="utf-8")
    return report

if __name__ == "__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo",type=Path,required=True)
    cli.add_argument("--output",type=Path)
    cli.add_argument("--inventory",action="store_true")
    cli.add_argument("--resource-inventory",action="store_true")
    cli.add_argument("--standalone",action="store_true")
    args=cli.parse_args()
    if args.inventory:
        print(json.dumps(source_inventory(args.repo.resolve()),indent=2))
    if args.resource_inventory:
        print(json.dumps(resource_inventory(args.repo.resolve()),indent=2))
    if args.output:
        result=generate(args.repo.resolve(),args.output.resolve(),args.standalone)
        print(json.dumps(dict(prepared=True,sources=len(result["sources"]),additionalVectors=result["additionalVectorNames"],
            registrySourceDelta=len(result["sourceRegistryDelta"]),registryResourceDelta=len(result["resourceRegistryDelta"]),
            compileAccepted=False,contract=str(args.output.resolve()/"static-settings-pages-source-report.json")),ensure_ascii=False))

"""Exact upstream theme/renderer declarations and string resources for JVM. No Android stubs."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, shutil
SETTINGS="app/src/main/java/com/android/purebilibili/feature/settings/"
THEME="design-system/src/main/java/com/android/purebilibili/core/theme/"
DIRECT=[SETTINGS+x for x in ["AppThemeMode.kt","DarkThemeStyle.kt","ThemePreferencePolicy.kt","AppLanguage.kt","AppearanceUiPresetDescriptionPolicy.kt","AppearanceUiPresetSegmentPolicy.kt"]]+[THEME+x for x in ["UiPreset.kt","Color.kt","Shape.kt","Type.kt","AndroidNativeVariantThemePolicy.kt","AppDisplayPolicy.kt","ThemeContrastPolicy.kt","AdaptiveAccentColorPolicy.kt"]]+[
 "app/src/main/java/com/android/purebilibili/core/theme/ThemeRoleOverridePolicy.kt",
 "design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixText.kt",
 "design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixProgressIndicator.kt"]
EXTRACTED={
 "app/src/main/java/com/android/purebilibili/core/theme/Theme.kt":["LightSurfaceVariant","createDarkColorScheme","createAmoledDarkColorScheme","createIosColorScheme","alignIosColorSchemeWithDynamicAccent","resolveEffectiveDynamicColorEnabled","resolveMd3ThemeSeedColor","parseMd3CustomColorHex","formatMd3CustomColorHex","resolveMiuixColorSchemeMode","shouldUseNativeMiuixPalette","resolvePaletteStylePreference","resolveColorSpecPreference","MiuixMaterialBridge","createMiuixMaterialBridge","resolveMaterialColorSchemeFromMiuixBridge","resolveNativeMiuixColors","alignMaterialSurfacesWithMiuix","resolveMiuixColorsFromMaterialBridge","applyAmoledSurfaceOverrides","createLightColorScheme","createMiuixAlignedColorScheme","createBiliPaiStyleColorScheme"],
 "app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt":["ThemeModeRoleOverrides","ThemeRoleOverrides"],
 "app/src/main/java/com/android/purebilibili/core/store/theme/ThemeSelectionStore.kt":["parseThemeSelectionString"],
 SETTINGS+"AppearanceThemeSegmentPolicy.kt":["resolveThemeModeSegmentOptions","resolveDarkThemeStyleSegmentOptions","resolveAppLanguageSegmentOptions","resolveColorStyleOptions","resolveColorSpecOptions","shouldShowMd3CustomColorControls","resolveMd3ColorSourceOptions","resolveAdvancedPaletteSubtitle"],
 "design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSegmentedControl.kt":["AppSegmentOption"]
}
RESOURCES={"zh-CN":"app/src/main/res/values/strings.xml","en":"app/src/main/res/values-en/strings.xml","zh-TW":"app/src/main/res/values-zh-rTW/strings.xml"}
HEADERS={
 "Theme.kt":"""import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.feature.settings.Md3ColorSource
import com.android.purebilibili.feature.settings.normalizeMd3CustomColorHex
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme
""",
 "SettingsManager.kt":"",
 "ThemeSelectionStore.kt":"import com.android.purebilibili.core.theme.AppUiStyle\n",
 "AppearanceThemeSegmentPolicy.kt":"""import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
""",
 "AppSegmentedControl.kt":""
}
def declarations(parser,source,names):
    tokens=parser.kotlin_tokens(source);depth=0;parens=0;brackets=0;starts=[]
    for i,(text,start,end) in enumerate(tokens):
        if depth==0 and parens==0 and brackets==0 and text in ['fun','val','var','class','object','interface']:
            name=tokens[i+1][0]
            line=source.rfind('\n',0,start)+1
            # Include original modifiers on the declaration line, not unrelated previous comments.
            while line>0:
                previous=source.rfind('\n',0,line-1)+1
                if source[previous:line].strip().startswith('@'):line=previous
                else:break
            starts.append((name,line))
        depth+=(text=='{')-(text=='}')
        parens+=(text=='(')-(text==')');brackets+=(text=='[')-(text==']')
    output=[]
    for name in names:
        found=[i for i,(n,_) in enumerate(starts) if n==name];assert len(found)==1,name
        i=found[0];begin=starts[i][1];end=starts[i+1][1] if i+1<len(starts) else len(source)
        text=source[begin:end].rstrip()
        # Any documentation immediately before the next declaration is harmless and remains source-identical.
        output.append(text)
    return '\n\n'.join(output)+'\n'
def inventory(repo):
    return [dict(path=p,mode='direct' if p in DIRECT else 'policy-extract',features=['appearance-parity'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').encode()).hexdigest()) for p in DIRECT+list(EXTRACTED)]
def generate(repo,output,resource_output=None,policy_only=False):
    spec=importlib.util.spec_from_file_location('appearance_parser',repo/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    output.mkdir(parents=True,exist_ok=True)
    for path in ([] if policy_only else DIRECT)+list(EXTRACTED):
        source=(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8');package=re.search(r'(?m)^package (\S+)',source).group(1)
        text=source if path in DIRECT else 'package '+package+'\n\n'+HEADERS[Path(path).name]+'\n'+declarations(parser,source,EXTRACTED[path])
        target=output/package.replace('.','/')/Path(path).name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
    for language,path in RESOURCES.items():
        target=(resource_output or output.parent/'resources')/'bilipai-strings'/language/'strings.xml';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(repo/path,target)
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path);ap.add_argument('--resource-output',type=Path);ap.add_argument('--policy-only',action='store_true');ap.add_argument('--inventory',action='store_true');ap.add_argument('--resource-inventory',action='store_true');args=ap.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo),indent=2))
    if args.resource_inventory:print(json.dumps([dict(path=p,mode='resource-adapt',features=['appearance-language'],sha256=hashlib.sha256((_desktop_canonical_source(args.repo, p)).read_text(encoding='utf-8').encode()).hexdigest()) for p in RESOURCES.values()],indent=2))
    if args.output:generate(args.repo,args.output,args.resource_output,args.policy_only)

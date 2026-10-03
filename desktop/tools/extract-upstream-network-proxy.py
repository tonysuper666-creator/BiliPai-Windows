"""Source-preserving application HTTP proxy store/policy and actual proxy-only settings UI."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,textwrap
BASE='app/src/main/java/com/android/purebilibili/'
STORE=BASE+'core/store/NetworkProxyStore.kt'
POLICY='network-core/src/main/java/com/android/purebilibili/core/network/policy/NetworkProxyPolicy.kt'
API=BASE+'core/network/ApiClient.kt'
SECTIONS=BASE+'feature/settings/ui/SettingsSections.kt'
ASSETS=['ms_lan_24','ms_hub_24']
SOURCES={STORE:'platform-rewrite',POLICY:'direct',API:'policy-extract',SECTIONS:'policy-extract'}
def load(path,name):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def generate(repo,output,standalone=False):
 host=load(repo/'desktop/tools/extract-upstream-plugins.py','proxy_helpers');media=host.media_extractor(repo);parser=media.parser_for(repo)
 files=[];output.mkdir(parents=True,exist_ok=True)
 original=read(repo,STORE)
 source=host.substitute(original,'import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context')
 files.append(host.write(output,STORE,original,source))
 original=read(repo,POLICY)
 host.prune_old_direct(output,POLICY,original)
 if standalone:files.append(host.write(output,POLICY,original,original))
 original=read(repo,API)
 selector=media.function(original,'buildAppProxySelector',parser)
 selector=host.substitute(selector,'buildAppProxySelector():','buildAppProxySelector(systemSelector: () -> java.net.ProxySelector? = { java.net.ProxySelector.getDefault() }):')
 selector=host.substitute(selector,'getDefault()?.select(uri).orEmpty()','systemSelector()?.select(uri).orEmpty()')
 unsafe='com.android.purebilibili.core.network.CoreDataLog.w(\n                "ApiClient",\n                "Proxy connect failed uri=$uri sa=$sa: ${ioe?.message}"\n            )'
 selector=host.substitute(selector,unsafe,'recordDesktopProxyConnectionFailure(ioe)')
 playback=media.function(original,'buildPlaybackOkHttpClient',parser)
 source='package com.bilipai.desktop.network\nimport okhttp3.OkHttpClient\nimport java.net.Proxy\n\ninternal object DesktopNetworkProxyPlatform {\n'+selector+'\n\n'+playback+'\n}\n'
 files.append(host.write(output,API,original,source,'DesktopNetworkProxyPlatform.kt'))
 original=read(repo,SECTIONS);section=media.function(original,'DiagnosticsSection',parser)
 controls_start=section.index('        SettingSwitchItem(\n            icon = com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_lan_24)')
 controls_end=section.index('        SettingsAdaptiveDivider()',section.index('        SettingClickableItem(',controls_start))
 controls=textwrap.dedent(section[controls_start:controls_end]).rstrip()
 dialog_start=section.index('    if (showProxyDialog) {');dialog_end=section.index('    if (showEnhancedDiagnosticConsent)',dialog_start)
 modal=section[dialog_start:dialog_end].rstrip()
 source='package com.android.purebilibili.feature.settings\nimport androidx.compose.runtime.*\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.unit.dp\nimport com.android.purebilibili.core.store.NetworkProxyStore\nimport com.android.purebilibili.core.network.policy.*\nimport com.android.purebilibili.core.ui.AppAlertDialog\nimport com.android.purebilibili.core.ui.AppDialogAction\nimport com.android.purebilibili.core.ui.components.*\nimport com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem\nimport com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem\nimport com.bilipai.desktop.settings.LocalDesktopNetworkProxyBindings\nimport com.bilipai.desktop.settings.DesktopProxySettingsVectors\nimport com.bilipai.desktop.settings.DesktopProxySettingsSymbols\n@Composable\ninternal fun DesktopNetworkProxyFields() {\n    val bindings=LocalDesktopNetworkProxyBindings.current\n    val context=bindings.context\n    val siblingTints = remember { resolveSettingsSiblingIconTints(6, paletteOffset = 5) }\n    val proxySettings by NetworkProxyStore.settings.collectAsState()\n    var showProxyDialog by remember { mutableStateOf(false) }\n    SettingsCardGroup {\n'+textwrap.indent(controls,'        ')+'\n    }\n'+modal+'\n}\n\n@Composable\n'+media.function(original,'NetworkProxyEditDialog',parser)
 source=host.substitute(source,'NetworkProxyStore.save(context, proxySettings.copy(enabled = enabled))','bindings.save(proxySettings.copy(enabled = enabled))')
 source=host.substitute(source,'NetworkProxyStore.save(context, next)','bindings.save(next)')
 for asset in ASSETS:
  source=host.substitute(source,'com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.'+asset+')','DesktopProxySettingsVectors.vector(DesktopProxySettingsSymbols.'+asset+')')
 files.append(host.write(output,SECTIONS,original,source,'SettingsNetworkProxyFields.kt'))
 # Original enhanced-logging consent and full DiagnosticsSection remain reviewed reference only.
 write_ref=output.parent/'reference-only';write_ref.mkdir(exist_ok=True)
 (write_ref/'OriginalDiagnosticsSection.kt').write_text('@Composable\n'+section,encoding='utf-8',newline='\n')
 converter=load(repo/'desktop/tools/extract-upstream-settings-search.py','proxy_vectors')
 if any(asset in converter.symbol_names(repo) for asset in ASSETS):raise ValueError('Shared vector owner gained a proxy asset; reuse it instead')
 converter.symbol_names=lambda _ : ASSETS
 vectors=converter.vectors(repo).replace('DesktopSettingsSymbols','DesktopProxySettingsSymbols').replace('DesktopSettingsVectors','DesktopProxySettingsVectors')
 files.append(media.write(output,'com/bilipai/desktop/settings/DesktopProxySettingsVectors.kt','app/src/main/res/drawable/'+ASSETS[0]+'.xml',read(repo,'app/src/main/res/drawable/'+ASSETS[0]+'.xml'),vectors))
 return files

def inventory(repo):return [dict(path=p,mode=mode,features=['settings-network-proxy-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p,mode in SOURCES.items()]
def resources(repo):return [dict(path='app/src/main/res/drawable/'+n+'.xml',features=['settings-network-proxy-symbols'],sha256=hashlib.sha256(read(repo,'app/src/main/res/drawable/'+n+'.xml').encode()).hexdigest()) for n in ASSETS]
if __name__=='__main__':
 c=argparse.ArgumentParser(description=__doc__);c.add_argument('--repo',type=Path,required=True);c.add_argument('--output',type=Path);c.add_argument('--standalone',action='store_true');c.add_argument('--inventory',action='store_true');c.add_argument('--resource-inventory',action='store_true');a=c.parse_args()
 if a.inventory:print(json.dumps(inventory(a.repo.resolve()),indent=2))
 if a.resource_inventory:print(json.dumps(resources(a.repo.resolve()),indent=2))
 if a.output:print('Generated',len(generate(a.repo.resolve(),a.output.resolve(),a.standalone)))

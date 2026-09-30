from pathlib import Path
import importlib.util,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
spec=importlib.util.spec_from_file_location('proxy',HERE/'extract-upstream-network-proxy.py');tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
h=tool.load(REPO/'desktop/tools/extract-upstream-plugins.py','host');media=h.media_extractor(REPO);parser=media.parser_for(REPO)
def emitted(name):
 p=next(p for p in (HERE/'generated').rglob('*.kt') if p.name==name);s=p.read_text(encoding='utf-8');return s[s.index('package '):]
assert emitted('NetworkProxyStore.kt')==tool.read(REPO,tool.STORE).replace('import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context')
assert emitted('NetworkProxyPolicy.kt')==tool.read(REPO,tool.POLICY)
original=tool.read(REPO,tool.API);source=emitted('DesktopNetworkProxyPlatform.kt')
selector=media.function(source,'buildAppProxySelector',parser)
selector=selector.replace('buildAppProxySelector(systemSelector: () -> java.net.ProxySelector? = { java.net.ProxySelector.getDefault() }):','buildAppProxySelector():').replace('systemSelector()?.select(uri).orEmpty()','getDefault()?.select(uri).orEmpty()').replace('recordDesktopProxyConnectionFailure(ioe)','com.android.purebilibili.core.util.Logger.w(\n                "ApiClient",\n                "Proxy connect failed uri=$uri sa=$sa: ${ioe?.message}"\n            )')
assert selector==media.function(original,'buildAppProxySelector',parser)
assert media.function(source,'buildPlaybackOkHttpClient',parser)==media.function(original,'buildPlaybackOkHttpClient',parser)
original=tool.read(REPO,tool.SECTIONS);source=emitted('SettingsNetworkProxyFields.kt')
assert media.function(source,'NetworkProxyEditDialog',parser)==media.function(original,'NetworkProxyEditDialog',parser)
section=media.function(original,'DiagnosticsSection',parser)
start=section.index('        SettingSwitchItem(\n            icon = com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_lan_24)')
end=section.index('        SettingsAdaptiveDivider()',section.index('        SettingClickableItem(',start))
controls=section[start:end].rstrip();reconstructed=source.replace('bindings.save(proxySettings.copy(enabled = enabled))','NetworkProxyStore.save(context, proxySettings.copy(enabled = enabled))')
for asset in tool.ASSETS:reconstructed=reconstructed.replace('DesktopProxySettingsVectors.vector(DesktopProxySettingsSymbols.'+asset+')','com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.'+asset+')')
assert controls in reconstructed
assert 'showEnhancedDiagnosticConsent' not in source and 'crashTrackingEnabled' not in source and 'analyticsEnabled' not in source
print(json.dumps(dict(passed=True,originalStoreImportOnly=True,originalPolicyUnchanged=True,originalSelectorOnlyReviewedBindings=True,originalMediaNoProxyUnchanged=True,originalTwoControlsRetained=True,originalFullEditDialogUnchanged=True,inertDiagnosticsTogglesEmitted=False)))

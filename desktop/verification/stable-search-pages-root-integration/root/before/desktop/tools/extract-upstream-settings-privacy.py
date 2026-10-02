"""Original privacy section/store, with explicit Windows authentication boundary."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json

BASE = 'app/src/main/java/com/android/purebilibili/'
SECTION = BASE+'feature/settings/ui/SettingsSections.kt'
HINT = BASE+'core/store/SearchHintSettingsStore.kt'
MANAGER = BASE+'core/store/SettingsManager.kt'
SEARCH = BASE+'feature/search/SearchScreen.kt'
SHIELD = 'ms_shield_24'
ASSET = 'app/src/main/res/drawable/'+SHIELD+'.xml'
SOURCES = {SECTION:'policy-extract', HINT:'platform-rewrite', MANAGER:'policy-extract', SEARCH:'policy-extract'}

def read(repo, path):
    return (repo/path).read_text(encoding='utf-8').replace('\r\n','\n')

def load(path, name):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    return result

def generate(repo, output):
    host=load(repo/'desktop/tools/extract-upstream-plugins.py','privacy_plugins')
    media=host.media_extractor(repo);parser=media.parser_for(repo)
    output.mkdir(parents=True,exist_ok=True)
    result=[]
    original=read(repo,HINT)
    source=host.substitute(original,'import android.content.Context',
        'import com.bilipai.desktop.plugins.DesktopPluginContext as Context')
    source=host.substitute(source,'import androidx.datastore.preferences.core.booleanPreferencesKey',
        'import com.bilipai.desktop.plugins.booleanPreferencesKey')
    source=host.substitute(source,'import androidx.datastore.preferences.core.edit',
        'import com.bilipai.desktop.settings.privacySettingsDataStore')
    source=host.substitute(source,'context.settingsDataStore','context.privacySettingsDataStore',count=2)
    result.append(host.write(output,HINT,original,source))
    original=read(repo,MANAGER)
    key='private val KEY_PRIVACY_CONTENT_AUTHENTICATION_ENABLED =\n        booleanPreferencesKey("privacy_content_authentication_enabled")'
    if original.count(key)!=1:raise ValueError('Original authentication key/schema changed')
    pieces=[key,media.function(original,'getPrivacyContentAuthenticationEnabled',parser)]
    source='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.privacySettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopPrivacyAuthenticationSettings {
'''+ '\n'.join(pieces)+'\n}\n'
    source=host.substitute(source,'context.settingsDataStore','context.privacySettingsDataStore')
    result.append(host.write(output,MANAGER,original,source,'DesktopPrivacyAuthenticationSettings.kt'))
    original=read(repo,SECTION)
    source=media.function(original,'PrivacySection',parser)
    source=host.substitute(source,'val context = LocalContext.current',
        'val bindings = LocalDesktopPrivacySectionBindings.current\n    val context = bindings.context')
    source=host.substitute(source,'collectAsStateWithLifecycle(initialValue = true)','collectAsState(initial = true)')
    source=host.substitute(source,'com.android.purebilibili.core.store.SearchHintSettingsStore.setEnabled(context, enabled)',
        'bindings.setDefaultHintEnabled(enabled)')
    source=host.substitute(source,'painterResource(id = it)',
        'rememberVectorPainter(DesktopSettingsVectors.vector(it))',count=3)
    source=host.substitute(source,
        'com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_shield_24)',
        'DesktopPrivacySettingsVectors.vector(DesktopPrivacySettingsSymbols.ms_shield_24)')
    source=host.substitute(source,'subtitle = "进入收藏、历史等页面前使用指纹、人脸或锁屏密码确认身份",',
        'subtitle = "Windows 身份验证尚未接入；当前不会验证或保护隐私内容",\n            enabled = false,')
    imports='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.bilipai.desktop.settings.DesktopSettingsVectors
import com.bilipai.desktop.settings.DesktopPrivacySettingsSymbols
import com.bilipai.desktop.settings.DesktopPrivacySettingsVectors
import com.bilipai.desktop.settings.LocalDesktopPrivacySectionBindings
import kotlinx.coroutines.launch
'''
    result.append(host.write(output,SECTION,original,imports+'\n@Composable\n'+source,'SettingsPrivacySection.kt'))
    original=read(repo,SEARCH)
    source='package com.android.purebilibili.feature.search\n\n'+ '\n\n'.join(
        media.function(original,name,parser) for name in ['resolveSearchSubmitKeyword','resolveSearchDefaultPlaceholder'])
    result.append(host.write(output,SEARCH,original,source,'PrivacySearchHintPolicy.kt'))
    converter=load(repo/'desktop/tools/extract-upstream-settings-search.py','privacy_vector_converter')
    if SHIELD in converter.symbol_names(repo):raise ValueError('Shared vectors already own shield; reuse that owner')
    converter.symbol_names=lambda _: [SHIELD]
    source=converter.vectors(repo).replace('DesktopSettingsSymbols','DesktopPrivacySettingsSymbols').replace('DesktopSettingsVectors','DesktopPrivacySettingsVectors')
    result.append(media.write(output,'com/bilipai/desktop/settings/DesktopPrivacySettingsVectors.kt',ASSET,read(repo,ASSET),source))
    return result

def inventory(repo):
    return [dict(path=path,mode=mode,features=['settings-privacy-section-parity'],
        sha256=hashlib.sha256(read(repo,path).encode()).hexdigest()) for path,mode in SOURCES.items()]

def resources(repo):
    return [dict(path=ASSET,features=['settings-privacy-symbols'],sha256=hashlib.sha256(read(repo,ASSET).encode()).hexdigest())]

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path)
    cli.add_argument('--inventory',action='store_true');cli.add_argument('--resource-inventory',action='store_true')
    args=cli.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
    if args.resource_inventory:print(json.dumps(resources(args.repo.resolve()),indent=2))
    if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve())))

"""Original settings index/controllers/visuals with thin Windows storage/resource bindings."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,xml.etree.ElementTree as ET
BASE='app/src/main/java/com/android/purebilibili/'
S=BASE+'feature/settings/'
DIRECT=[S+n+'.kt' for n in ['SettingsSearchPolicy','SettingsSearchFocusPolicy','SettingsRootCategoryPolicy','SettingsDestinationCopy','SettingsSiblingIconPalettePolicy']]
DIRECT += [BASE+'core/util/PinyinUtils.kt',S+'screen/SettingsSearchHistorySection.kt']
ADAPTED=[S+'SettingsSemanticIconPolicy.kt',S+'SettingsEntryVisualPolicy.kt',BASE+'core/store/SettingsSearchHistoryStore.kt',S+'SettingsSearchNavigationPolicy.kt']
SELECTED=[S+'screen/SettingsSearchUi.kt',S+'SettingsNavHierarchyPolicy.kt',S+'SettingsViewModel.kt']
A='{http://schemas.android.com/apk/res/android}'

def helper(repo):
 spec=importlib.util.spec_from_file_location('settings_helpers',repo/'desktop/tools/extract-upstream-plugins.py')
 h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h);return h
def read(repo,p):return (_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').replace('\r\n','\n')
def symbol_names(repo):return sorted(set(re.findall(r'R\.drawable\.(\w+)',read(repo,S+'SettingsSemanticIconPolicy.kt')+read(repo,S+'SettingsEntryVisualPolicy.kt'))))

def vectors(repo):
 names=symbol_names(repo); functions=[]
 def number(v):return str(float(v))+'f'
 for name in names:
  root=ET.fromstring(read(repo,'app/src/main/res/drawable/'+name+'.xml'))
  if root.tag!='vector':raise ValueError('Non-vector settings asset: '+name)
  allowed={'width','height','viewportWidth','viewportHeight','tint','autoMirrored'}
  if any(k.removeprefix(A) not in allowed for k in root.attrib):raise ValueError('Unknown vector attribute: '+name)
  body=[]
  for node in root:
   if node.tag!='path':raise ValueError('Unsupported original settings vector node: '+node.tag)
   attrs={k.removeprefix(A):v for k,v in node.attrib.items()}
   if set(attrs)-{'pathData','fillColor','fillAlpha','fillType'}:raise ValueError('Unbound path attributes: '+name+str(attrs))
   color=attrs.get('fillColor','#000000')
   if color=='@android:color/white':color='#FFFFFFFF'
   if re.fullmatch(r'#[0-9a-fA-F]{6}',color):color='#FF'+color[1:]
   if not re.fullmatch(r'#[0-9a-fA-F]{8}',color):raise ValueError('Unbound original fillColor: '+color)
   fill='EvenOdd' if attrs.get('fillType')=='evenOdd' else 'NonZero'
   body.append('addPath(pathData=addPathNodes('+json.dumps(attrs['pathData'])+'),fill=SolidColor(Color(0x'+color[1:]+')),fillAlpha='+number(attrs.get('fillAlpha','1'))+',pathFillType=PathFillType.'+fill+')')
  size=lambda key:number(root.attrib[A+key].removesuffix('dp'))+'.dp'
  functions.append('"'+name+'" -> ImageVector.Builder(name="'+name+'",defaultWidth='+size('width')+',defaultHeight='+size('height')+',viewportWidth='+number(root.attrib[A+'viewportWidth'])+',viewportHeight='+number(root.attrib[A+'viewportHeight'])+',autoMirror='+str(root.attrib.get(A+'autoMirrored','false')).lower()+').apply{\n'+'\n'.join(body)+'\n}.build()')
 return '''package com.bilipai.desktop.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
/** Exact original XML paths/viewports. Native list icon tint remains authoritative. */
internal object DesktopSettingsSymbols {
'''+''.join('const val '+n+'="'+n+'"\n' for n in names)+'''}
internal object DesktopSettingsVectors {
 private val cache=ConcurrentHashMap<String,ImageVector>()
 @Composable fun vector(name:String):ImageVector=remember(name){load(name)}
 fun load(name:String):ImageVector=cache.computeIfAbsent(name){build(it)}
 private fun build(name:String):ImageVector=when(name){
'''+ '\n'.join(functions)+ '\nelse->error("Unknown original settings vector: $name")\n}\n}\n'

def generate(repo,output,standalone=False):
 h=helper(repo);m=h.media_extractor(repo);parser=m.parser_for(repo);files=[];output.mkdir(parents=True,exist_ok=True)
 for path in DIRECT:
  original=read(repo,path);h.prune_old_direct(output,path,original)
  if standalone:files.append(h.write(output,path,original,original))
 for path in ADAPTED+SELECTED:
  original=source=read(repo,path);name=None
  if path.endswith(('SettingsSemanticIconPolicy.kt','SettingsEntryVisualPolicy.kt')):
   if path.endswith('SettingsSemanticIconPolicy.kt'):
    # Active original rememberSettingsSemanticIcon always loads its local XML.
    # The old unused extended-library lookup is outside this source-closed slice.
    source=source[:source.index('/**\n * Miuix 设置页只使用 miuix-icons 字形。')]
    source=source.replace('import top.yukonga.miuix.kmp.icon.MiuixIcons\n','').replace('import top.yukonga.miuix.kmp.icon.extended.*\n','')
   source=source.replace('import androidx.annotation.DrawableRes\n','').replace('@DrawableRes ','').replace('import androidx.compose.ui.res.vectorResource\n','')
   source=source.replace('import com.android.purebilibili.R','import com.bilipai.desktop.settings.DesktopSettingsSymbols\nimport com.bilipai.desktop.settings.DesktopSettingsVectors')
   source=source.replace('R.drawable.','DesktopSettingsSymbols.').replace('ImageVector.vectorResource(','DesktopSettingsVectors.vector(')
   source=source.replace('materialSymbolResource: Int','materialSymbolResource: String').replace('iconResId: Int?','iconResId: String?').replace('Resource(role: SettingsIconRole): Int','Resource(role: SettingsIconRole): String')
  elif path.endswith('SettingsSearchHistoryStore.kt'):
   source=h.substitute(source,'import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context\nimport com.bilipai.desktop.settings.settingsSearchDataStore')
   source=h.substitute(source,'import androidx.datastore.preferences.core.edit\n','')
   source=h.substitute(source,'import androidx.datastore.preferences.core.stringPreferencesKey','import com.bilipai.desktop.plugins.stringPreferencesKey')
   source=source.replace('context.settingsDataStore','context.settingsSearchDataStore')
  elif path.endswith('SettingsSearchNavigationPolicy.kt'):
   source=h.substitute(source,'import com.android.purebilibili.navigation3.BiliPaiNavKey\n','')
   source=h.substitute(source,'): BiliPaiNavKey?','): SettingsSearchTarget?')
   mapping={'AppearanceSettings':'APPEARANCE','HomeSettings':'HOME_FEED','AnimationSettings':'ANIMATION','PlaybackSettings':'PLAYBACK','BottomBarSettings':'BOTTOM_BAR','PermissionSettings':'PERMISSION','MessageNotificationSettings':'MESSAGE_NOTIFICATION','PluginsSettings()':'PLUGINS','SettingsShare':'SETTINGS_SHARE','WebDavBackup':'WEBDAV_BACKUP','OpenSourceLicenses':'OPEN_SOURCE_LICENSES','TipsSettings':'TIPS'}
   for key,target in mapping.items():source=source.replace('BiliPaiNavKey.'+key,'SettingsSearchTarget.'+target)
   source=h.substitute(source,'BiliPaiNavKey.SettingsCategory(category)','category.searchTarget')
   if 'BiliPaiNavKey' in source:raise ValueError('New Android navigation binding needed')
  elif path.endswith('SettingsSearchUi.kt'):
   body='\n\n'.join('@Composable\n'+m.function(source,n,parser) for n in ['SettingsSearchBarSection','SettingsSearchResultsSection'])
   source=source[:source.index('@Composable')]+body
   source=source.replace('import androidx.compose.ui.res.painterResource','import androidx.compose.ui.graphics.vector.rememberVectorPainter\nimport com.bilipai.desktop.settings.DesktopSettingsVectors')
   source=source.replace('import androidx.compose.ui.res.stringResource','import com.bilipai.desktop.appearance.LocalDesktopStrings')
   source=source.replace('import com.android.purebilibili.R\n','')
   source=source.replace('import com.android.purebilibili.core.ui.components.AppLiquidAwareSearchField','import com.android.purebilibili.core.ui.components.AppSearchField as AppLiquidAwareSearchField')
   source=re.sub(r'stringResource\(R\.string\.(\w+)\)',r'LocalDesktopStrings.current["\1"]',source)
   source=source.replace('painterResource(id = it)','rememberVectorPainter(DesktopSettingsVectors.vector(it))')
  elif path.endswith('SettingsNavHierarchyPolicy.kt'):
   start=source.index('internal const val SETTINGS_ROUTE_BASE');end=source.index('/**\n * 分类级直跳')
   constants=source[start:end]
   route_map=source[source.index('private val ROUTE_TO_CATEGORY'):source.index('internal fun isSettingsSubtreeRoute')]
   functions='\n\n'.join(m.function(source,n,parser) for n in ['isSettingsSubtreeRoute','resolveSettingsNavDepth','resolveSettingsNavParentRoute','resolveSettingsRootCategoryForRoute','isSettingsNavHierarchyTransition'])
   direct_target=m.function(source,'resolveSettingsCategoryDirectTargetKey',parser)
   direct_target=direct_target.replace('resolveSettingsCategoryDirectTargetKey','resolveDesktopSettingsCategoryDirectTarget').replace('BiliPaiNavKey?','SettingsSearchTarget?').replace('BiliPaiNavKey.AppearanceSettings','SettingsSearchTarget.APPEARANCE').replace('BiliPaiNavKey.PluginsSettings()','SettingsSearchTarget.PLUGINS')
   if 'BiliPaiNavKey' in direct_target:raise ValueError('New category navigation binding needed')
   source='package com.android.purebilibili.feature.settings\n'+constants+route_map+functions+'\n\n'+direct_target;name='DesktopSettingsRouteHierarchy.kt'
  elif path.endswith('SettingsViewModel.kt'):
   functions='\n\n'.join(m.function(source,n,parser) for n in ['recordSearchQuery','deleteSearchHistory','clearSearchHistory'])
   functions=functions.replace('fun ','suspend fun ').replace('viewModelScope.launch {','run {').replace('SettingsManager.getPrivacyModeEnabled(getApplication()).first()','privacyModeEnabled()').replace('getApplication()','context')
   source='''package com.android.purebilibili.feature.settings
import com.bilipai.desktop.plugins.DesktopPluginContext
internal class SettingsSearchHistoryOperations(private val context:DesktopPluginContext,private val privacyModeEnabled:()->Boolean){
'''+functions+'\n}\n';name='SettingsSearchHistoryOperations.kt'
  else:raise ValueError(path)
  if re.search(r'^import (android\.|androidx\.datastore|androidx\.compose\.ui\.res\.)',source,re.M):raise ValueError('Android binding remains: '+path)
  files.append(h.write(output,path,original,source,name))
 vector=output/'com/bilipai/desktop/settings/DesktopSettingsVectors.kt';vector.parent.mkdir(parents=True,exist_ok=True);vector.write_text(vectors(repo),encoding='utf-8');files.append(vector)
 return files

def inventory(repo):return [dict(path=p,mode='direct' if p in DIRECT else 'platform-adapter-reference' if p in ADAPTED else 'policy-extract',features=['settings-search-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in DIRECT+ADAPTED+SELECTED]
def resource_inventory(repo):return [dict(path='app/src/main/res/drawable/'+n+'.xml',sha256=hashlib.sha256(read(repo,'app/src/main/res/drawable/'+n+'.xml').encode()).hexdigest(),features=['settings-search-symbols']) for n in symbol_names(repo)]
if __name__=='__main__':
 cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path);cli.add_argument('--standalone',action='store_true');cli.add_argument('--inventory',action='store_true');cli.add_argument('--resource-inventory',action='store_true');args=cli.parse_args()
 if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
 if args.resource_inventory:print(json.dumps(resource_inventory(args.repo.resolve()),indent=2))
 if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve(),args.standalone)))

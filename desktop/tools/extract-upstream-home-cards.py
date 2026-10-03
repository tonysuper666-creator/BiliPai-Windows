"""Exact original Home card enums/read defaults/layout and three live controls.

The Windows consumer is the existing grid display mode only. Full VideoCard,
pinch/fold/hero behavior is audited separately, never replaced by fake switches.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,textwrap
BASE='app/src/main/java/com/android/purebilibili/'
DIRECT=['feature/home/HomeFeedCardDensityPolicy.kt','feature/home/HomeFeedCardStylePolicy.kt']
SELECTED=['core/store/SettingsManager.kt','core/util/WindowSizeUtils.kt','feature/home/HomeFeedGridPolicy.kt',
 'feature/settings/PlaybackSettingsSelectionPolicy.kt','feature/settings/screen/AppearanceSettingsScreen.kt']
def module(repo,name,path):
 spec=importlib.util.spec_from_file_location(name,repo/path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def inventory(repo):return [dict(path=BASE+p,mode='direct' if p in DIRECT else 'policy-extract',features=['settings-home-card-parity'],
 sha256=hashlib.sha256(read(repo,BASE+p).encode()).hexdigest()) for p in DIRECT+SELECTED]
def generate(repo,out,standalone=False):
 host=module(repo,'hc_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
 appearance=module(repo,'hc_declarations','desktop/tools/extract-appearance-platform.py')
 home=module(repo,'hc_calls','desktop/tools/extract-upstream-settings-home.py');files=[];out.mkdir(parents=True,exist_ok=True)
 def emit(path,body,name):files.append(host.write(out,path,read(repo,path),body,name))
 for short in DIRECT:
  path=BASE+short;original=read(repo,path);host.prune_old_direct(out,path,original)
  if standalone:files.append(host.write(out,path,original,original))
 path=BASE+'core/store/SettingsManager.kt';source=read(repo,path)
 enums=appearance.declarations(parser,source,['HomeFeedCardWidthPreset','HomeFeedCardStyle'])
 keys=['KEY_GRID_COLUMN_COUNT','KEY_GRID_COLUMN_COUNT_COMPACT','KEY_HOME_FEED_CARD_WIDTH_PRESET','KEY_HOME_FEED_CARD_STYLE']
 lines=[]
 for key in keys:
  found=re.findall(r'private val '+key+r'\s*=\s*intPreferencesKey\("[^"]+"\)',source);assert len(found)==1,key;lines+=found
 begin=source.index('    internal fun mapHomeSettingsFromPreferences(preferences: Preferences): HomeSettings {')
 body=source[begin:source.index('\n    fun getHomeSettings(',begin)]
 body=body[body.index('        return HomeSettings(')+len('        return HomeSettings('):body.rindex('\n        )')]
 matches=list(re.finditer(r'^            (\w+) =[ \t]*',body,re.M));wanted=['gridColumnCount','gridColumnCountCompact','homeFeedCardWidthPreset','homeFeedCardStyle'];expressions={}
 for i,m in enumerate(matches):
  if m.group(1) in wanted:expressions[m.group(1)]=re.sub(r'\n\s*//[^\n]*','',body[m.end():matches[i+1].start() if i+1<len(matches) else len(body)]).strip().rstrip(',')
 assert set(expressions)==set(wanted)
 funcs=[media.function(source,n,parser) for n in ['setGridColumnCount','setGridColumnCountCompact','setHomeFeedCardWidthPreset','setHomeFeedCardStyle']]
 body='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot as Preferences
import com.bilipai.desktop.settings.homeCardSettingsDataStore as settingsDataStore
import com.bilipai.desktop.settings.homeCardIntPreferencesKey as intPreferencesKey
import kotlinx.coroutines.flow.map

'''+enums+'''
/** Windows projection: no constructor defaults competing with the actual persisted mapper. */
data class DesktopHomeCardSettings(val gridColumnCount:Int,val gridColumnCountCompact:Int,
 val homeFeedCardWidthPreset:HomeFeedCardWidthPreset,val homeFeedCardStyle:HomeFeedCardStyle)

object DesktopOriginalHomeCardSettings {
'''+textwrap.indent('\n'.join(lines),'    ')+'''
    fun decode(preferences:Preferences):DesktopHomeCardSettings = DesktopHomeCardSettings(
'''+''.join('        '+field+' = '+textwrap.indent(expr,'        ').lstrip()+',\n' for field,expr in expressions.items())+'''
    )
    fun getSettings(context:Context)=context.settingsDataStore.data.map(::decode)

'''+textwrap.indent('\n\n'.join(funcs),'    ')+'\n}\n'
 emit(path,body,'DesktopOriginalHomeCardSettings.kt')
 path=BASE+'core/util/WindowSizeUtils.kt';source=read(repo,path)
 # Keep the original Dp overload only; androidx.window's Android resolver is not a desktop binding.
 begin=source.index('internal fun resolveWindowWidthSizeClass(widthDp: Dp)');end=source.index('\ninternal fun ',begin+1)
 body='package com.android.purebilibili.core.util\nimport androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp\n\n'+appearance.declarations(parser,source,['WindowWidthSizeClass'])+'\n'+source[begin:end].strip()+'\n'
 emit(path,body,'DesktopOriginalHomeCardWidthClass.kt')
 path=BASE+'feature/home/HomeFeedGridPolicy.kt';source=read(repo,path)
 body='package com.android.purebilibili.feature.home\nimport androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp\nimport com.android.purebilibili.core.util.WindowWidthSizeClass\nimport com.android.purebilibili.core.store.HomeFeedCardWidthPreset\n\n'+appearance.declarations(parser,source,['resolveHomeFeedMaxContentWidth','isCompactHomeFeedScreen','resolveHomeFeedStoredColumnCount','resolveHomeFeedGridColumns'])
 emit(path,body,'DesktopOriginalHomeCardGridPolicy.kt')
 path=BASE+'feature/settings/PlaybackSettingsSelectionPolicy.kt';source=read(repo,path)
 emit(path,'package com.android.purebilibili.feature.settings\nimport com.android.purebilibili.core.store.HomeFeedCardWidthPreset\nimport com.android.purebilibili.core.ui.components.AppSegmentOption\n\n'+media.function(source,'resolveHomeFeedCardWidthPresetSegmentOptions',parser),'DesktopOriginalHomeCardWidthOptions.kt')
 path=BASE+'feature/settings/screen/AppearanceSettingsScreen.kt';source=read(repo,path)
 calls=[]
 for title in ['title = "网格列数"','title = "推荐流卡片宽度"','title = "卡片封面比例：']:
  marker=source.index(title);begin=source.rfind('SettingsSingleChoicePreference(',0,marker)
  end=source.index('\n                        ',marker+len(title)) if False else source.find('AppPreferenceDivider(',marker)
  calls.append(home.call(source[begin:end],'SettingsSingleChoicePreference',parser))
 calls[0]=calls[0].replace('com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_format_list_bulleted_24)','rememberSettingsSemanticIcon(SettingsIconRole.HOME_FEED)')
 # The unsupported fold/pinch and all-surfaces claims are truthful Windows text bindings.
 calls[0]=calls[0].replace('自适应（默认）；折叠屏外屏与窄屏独立记忆，可用双指缩放调整','自适应（默认）；窄屏独立记忆').replace('；折叠屏外屏与窄屏独立记忆','；窄屏独立记忆')
 calls[0]=calls[0].replace('viewModel::setGridColumnCount','onGridColumnCount')
 calls[1]=calls[1].replace('viewModel::setHomeFeedCardWidthPreset','onWidthPreset')
 calls[2]=calls[2].replace('（首页、搜索、列表、相关推荐等同步）','（推荐、热门、分区等视频列表）')
 calls[2]=calls[2].replace('scope.launch {\n        SettingsManager.setHomeFeedCardStyle(context, it)\n    }','onStyle(it)')
 # Dedent differences in the original lambda are handled without changing its selected value.
 calls[2]=re.sub(r'scope\.launch\s*\{\s*SettingsManager\.setHomeFeedCardStyle\(context, it\)\s*\}','onStyle(it)',calls[2])
 body='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.ui.components.AppSegmentOption

/** Three real original controls, bound to the actual four-key desktop grid consumer. */
@Composable
internal fun DesktopOriginalHomeCardFields(state:DesktopHomeCardSettings,isTablet:Boolean,
 onGridColumnCount:(Int)->Unit,onWidthPreset:(HomeFeedCardWidthPreset)->Unit,onStyle:(HomeFeedCardStyle)->Unit) {
 val homeFeedCardStyle=state.homeFeedCardStyle
 SettingsCardGroup {
    if(isTablet) {
'''+textwrap.indent(calls[0],'        ')+'\n        SettingsAdaptiveDivider()\n'+textwrap.indent(calls[1],'        ')+'''
        SettingsAdaptiveDivider()
    }
'''+textwrap.indent(calls[2],'    ')+'\n }\n}\n'
 assert 'viewModel' not in body and 'SettingsManager' not in body
 emit(path,body,'DesktopOriginalHomeCardFields.kt')
 return files
if __name__=='__main__':
 cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path);cli.add_argument('--standalone',action='store_true');cli.add_argument('--inventory',action='store_true');args=cli.parse_args()
 if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
 if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve(),args.standalone)))

"""Original dynamic layout/incremental settings, fetch/page policies and masonry host.

Only the two settings with actual prepared consumers are emitted. Other original
FeedApiSection fields are audited, not replaced with inactive switches.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,textwrap

BASE='app/src/main/java/com/android/purebilibili/'
DIRECT=['data/repository/DynamicFeedFetchPolicy.kt','data/repository/DynamicFetchRetryPolicy.kt',
 'core/util/FeedMergeUtils.kt','core/ui/components/FeedVerticalStaggeredGrid.kt','core/ui/components/FeedPrependScrollPolicy.kt',
 'feature/dynamic/DynamicTabPolicy.kt']
SELECTED=['core/store/SettingsManager.kt','feature/settings/ui/SettingsSections.kt','data/repository/DynamicRepository.kt',
 'feature/dynamic/DynamicViewModel.kt','feature/dynamic/DynamicScreenStatePolicy.kt','feature/dynamic/DynamicIncrementalRefreshPolicy.kt',
 'feature/dynamic/DynamicLayoutPolicy.kt','feature/dynamic/DynamicScreen.kt']

def module(repo,name,path):
 spec=importlib.util.spec_from_file_location(name,repo/path);result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def inventory(repo):return [dict(path=BASE+p,mode='direct' if p in DIRECT else 'policy-extract',features=['settings-home-dynamic-parity'],
 sha256=hashlib.sha256(read(repo,BASE+p).encode()).hexdigest()) for p in DIRECT+SELECTED]

def generate(repo,output,standalone=False):
 host=module(repo,'dynamic_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
 appearance=module(repo,'dynamic_declarations','desktop/tools/extract-appearance-platform.py')
 home=module(repo,'dynamic_ui_calls','desktop/tools/extract-upstream-settings-home.py')
 output.mkdir(parents=True,exist_ok=True);files=[]
 def decl(source,kind,name):
  tokens=parser.kotlin_tokens(source)
  matches=[i for i,t in enumerate(tokens[:-1]) if t[0]==kind and tokens[i+1][0]==name]
  if len(matches)!=1:raise ValueError('Original declaration missing or ambiguous: '+name)
  start=matches[0]
  begin=source.rfind('\n',0,tokens[start][1])+1
  if source[begin:tokens[start][1]].strip().endswith('data'):return media.data_class(source,name,parser)
  end=start;parens=0
  while end<len(tokens):
   parens+=(tokens[end][0]=='(')-(tokens[end][0]==')')
   if tokens[end][0]=='{' and parens==0:break
   end+=1
  depth=1
  while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
  return textwrap.dedent(source[begin:tokens[end][2]])
 def emit(path,body,name):files.append(host.write(output,path,read(repo,path),body,name))
 for short in DIRECT:
  path=BASE+short;original=read(repo,path);host.prune_old_direct(output,path,original)
  if standalone:files.append(host.write(output,path,original,original))
 path=BASE+'core/store/SettingsManager.kt';source=read(repo,path)
 keys=['KEY_INCREMENTAL_TIMELINE_REFRESH','KEY_DYNAMIC_FEED_LAYOUT_MODE']
 keyLines=[]
 for key in keys:
  found=[line.strip() for line in source.splitlines() if line.strip().startswith('private val '+key+' =')]
  assert len(found)==1,key;keyLines+=found
 funcs=[media.function(source,name,parser) for name in ['getIncrementalTimelineRefresh','setIncrementalTimelineRefresh',
  'getDynamicFeedLayoutMode','setDynamicFeedLayoutMode']]
 body='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object DesktopDynamicSettings {
'''+textwrap.indent('\n'.join(keyLines)+'\n\n'+decl(source,'class','DynamicFeedLayoutMode')+'\n\n'+'\n\n'.join(funcs),'    ')+'\n}\n'
 emit(path,body,'DesktopDynamicSettings.kt')
 path=BASE+'feature/settings/ui/SettingsSections.kt';source=read(repo,path);section=media.function(source,'FeedApiSection',parser)
 # Boundaries retain complete original controls and their original title/default/help text.
 begin=section.index('        SettingSwitchItem(');end=section.index('        SettingsAdaptiveDivider()',begin)
 switch=home.call(section[begin:end],'SettingSwitchItem',parser)
 marker=section.index('            title = "动态页面布局"');begin=section.rfind('        SettingsSingleChoicePreference(',0,marker)
 end=section.index('        SettingsAdaptiveDivider()',marker);choice=home.call(section[begin:end],'SettingsSingleChoicePreference',parser)
 prelude='\n'.join(line for line in section.splitlines() if line.strip().startswith('val siblingTints =') or line.strip().startswith('val feedIcon =') or line.strip().startswith('val refreshIcon ='))
 body='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopDynamicSettings as SettingsManager
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem

/** Original FeedApiSection controls backed by both actual settings and feed consumers. */
@Composable
internal fun DesktopDynamicTimelineSettingsFields(
    incrementalTimelineRefreshEnabled: Boolean,
    onIncrementalTimelineRefreshChange: (Boolean) -> Unit,
    dynamicFeedLayoutMode: SettingsManager.DynamicFeedLayoutMode,
    onDynamicFeedLayoutModeChange: (SettingsManager.DynamicFeedLayoutMode) -> Unit,
) {
'''+prelude+'\n    SettingsCardGroup {\n'+textwrap.indent(switch,'        ')+'\n        SettingsAdaptiveDivider()\n'+textwrap.indent(choice,'        ')+'\n    }\n}\n'
 body=body.replace('com.android.purebilibili.core.store.SettingsManager.DynamicFeedLayoutMode','com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode')
 emit(path,body,'DesktopDynamicTimelineSettingsFields.kt')
 path=BASE+'feature/dynamic/DynamicViewModel.kt';source=read(repo,path)
 cacheKeys=[]
 for key in ['PREFS_DYNAMIC_CACHE','KEY_DYNAMIC_CACHE','KEY_DYNAMIC_CACHE_TIME','KEY_NOT_INTERESTED_DYNAMIC_IDS','MAX_CACHE_ITEMS','MAX_NOT_INTERESTED_DYNAMIC_IDS']:
  matches=[line.strip().replace('private const val','const val',1) for line in source.splitlines() if line.strip().startswith('private const val '+key+' =')]
  assert len(matches)==1,key;cacheKeys+=matches
 emit(path,'package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.data.model.response.DynamicItem\nimport kotlinx.collections.immutable.*\n\n'+
  media.data_class(source,'DynamicTimelinePageState',parser)+'\n\ninternal object DesktopOriginalDynamicCacheKeys {\n'+textwrap.indent('\n'.join(cacheKeys),'    ')+'\n}\n','DesktopOriginalDynamicTimelinePage.kt')
 path=BASE+'feature/dynamic/DynamicScreenStatePolicy.kt';source=read(repo,path)
 selected=['resolveDynamicTimelinePageForLoadStart','resolveDynamicTimelinePageAfterSuccess','resolveDynamicTimelinePageAfterFailure','sortDynamicTimelineItemsByPublishTime']
 body='package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.core.util.*\nimport com.android.purebilibili.data.model.response.DynamicItem\nimport kotlinx.collections.immutable.toImmutableList\n\n'+decl(source,'class','DynamicFeedErrorSource')+'\n\n'+'\n\n'.join(media.function(source,n,parser) for n in selected)
 emit(path,body,'DesktopOriginalDynamicTimelinePolicy.kt')
 path=BASE+'feature/dynamic/DynamicIncrementalRefreshPolicy.kt';source=read(repo,path)
 selected=['dynamicFeedItemKey','dynamicTimelineItemsOverlap','canPerformIncrementalTimelineRefresh','resolveIncrementalRefreshBoundary','resolveOldContentDividerIndex','resolveDynamicRefreshDividerGridIndex','shouldReloadFollowings','shouldStartDynamicRefresh','resolveDynamicRefreshUserId']
 ttl=next(line for line in source.splitlines() if line.startswith('internal const val FOLLOWINGS_REFRESH_TTL_MS:'))
 body='package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.data.model.response.DynamicItem\n\n'+ttl+'\n\n'+media.data_class(source,'IncrementalRefreshBoundary',parser)+'\n\n'+appearance.declarations(parser,source,selected)
 emit(path,body,'DesktopOriginalDynamicIncrementalPolicy.kt')
 path=BASE+'feature/dynamic/DynamicLayoutPolicy.kt';source=read(repo,path)
 selected=['resolveDynamicTimelineMaxWidth','resolveDynamicTimelineMinColumnWidth','resolveDynamicTimelineHorizontalSpacing','resolveDynamicTimelineVerticalSpacing','shouldUseDynamicManualPrependAnchor']
 body='package com.android.purebilibili.feature.dynamic\nimport androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp\nimport com.android.purebilibili.core.store.DesktopDynamicSettings as SettingsManager\n\n'+appearance.declarations(parser,source,selected)
 emit(path,body,'DesktopOriginalDynamicTimelineLayout.kt')
 path=BASE+'data/repository/DynamicRepository.kt';source=read(repo,path)
 methods=[media.function(source,name,parser) for name in ['getDynamicFeed','syncPaginationAfterRefresh','fetchDynamicFeedPageWithRetry']]
 # Both getters read the existing sole feedPagination registry, including HOME_FOLLOW/video.
 baselineStart='    fun currentUpdateBaseline('
 baselineEnd='    ): String = feedPagination.updateBaseline(scope, type)'
 assert source.count(baselineStart)==source.count(baselineEnd)==1
 begin=source.index(baselineStart);end=source.index(baselineEnd,begin)+len(baselineEnd)
 methods.extend([textwrap.dedent(source[begin:end]),media.function(source,'hasMoreData',parser)])
 methods[0]=host.substitute(methods[0],'NetworkModule.dynamicApi.getDynamicFeed(','getPage(')
 methods[0]=host.substitute(methods[0],'        e.printStackTrace()','        // Windows boundary never logs raw response/URL exception details.')
 methods=[method.replace('catch (e: Exception) {','catch (cancelled: kotlinx.coroutines.CancellationException) {\n        throw cancelled\n    } catch (e: Exception) {')
  .replace('catch (error: Exception) {','catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (error: Exception) {') for method in methods]
 body='''package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** Original fetch loop/pagination; only its task-owned transport and cancellation are bound. */
internal class DesktopOriginalDynamicTimelineRepository(
    private val requestPage: suspend (type:String,offset:String,updateBaseline:String)->DynamicFeedResponse,
    private val stillOwned: ()->Boolean = {true},
) {
    private val feedPagination=DynamicFeedPaginationRegistry()
    /** One request's transient checkpoint; the original registry remains the only cursor owner. */
    fun checkpointForFollowChange(type:String)=feedPagination.snapshot(DynamicFeedScope.DYNAMIC_SCREEN,type)
    fun restoreAfterFollowChange(type:String,before:DynamicPaginationState) {
        feedPagination.updateState(DynamicFeedScope.DYNAMIC_SCREEN,type,before)
    }
    private suspend fun getPage(type:String,offset:String,updateBaseline:String):DynamicFeedResponse {
        currentCoroutineContext().ensureActive()
        if(!stillOwned()) throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        val response=requestPage(type,offset,updateBaseline)
        currentCoroutineContext().ensureActive()
        if(!stillOwned()) throw kotlinx.coroutines.CancellationException("Dynamic source retired")
        return response
    }
'''+textwrap.indent('\n\n'.join(methods),'    ')+'\n}\n\n'
 body+='\n\n'.join(media.function(source,n,parser) for n in ['shouldUseDynamicIncrementalRefresh','resolveDynamicPaginationStateAfterPage'])+'\n\n'
 body+='\n\n'.join(decl(source,'class',n) for n in ['DynamicFeedScope','DynamicFeedFetchResult','DynamicPaginationState','DynamicFeedPaginationKey','DynamicFeedPaginationRegistry'])+'\n'
 emit(path,body,'DesktopOriginalDynamicTimelineRepository.kt')
 path=BASE+'feature/dynamic/DynamicScreen.kt';source=read(repo,path)
 body='''package com.android.purebilibili.feature.dynamic
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppText

@Composable
'''+media.function(source,'OldContentDivider',parser).replace('private fun','internal fun',1)
 emit(path,body,'DesktopOriginalDynamicOldContentDivider.kt')
 return files

if __name__=='__main__':
 cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path)
 cli.add_argument('--inventory',action='store_true');cli.add_argument('--standalone',action='store_true');args=cli.parse_args()
 if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
 if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve(),args.standalone)))

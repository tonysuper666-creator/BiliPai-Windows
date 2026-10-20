"""Original dynamic tabs/settings, UP request policies and native component closure.

Requires the previously extracted dynamic timeline source set. No Android ViewModel,
synthetic UI state or disabled glass branch is emitted as a working consumer.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,textwrap
BASE='app/src/main/java/com/android/purebilibili/'
DS='design-system/src/main/java/com/android/purebilibili/core/ui/'
DIRECT=[DS+p for p in ['AppSegmentedControlPolicy.kt','MatchedLiquidIndicatorGeometry.kt',
 'renderer/material3/AppTonalPillTabPolicy.kt',
 'renderer/material3/AppMaterial3SegmentedControl.kt','renderer/miuix/AppMiuixSegmentedControl.kt']]
PATHS=[BASE+p for p in ['core/store/SettingsManager.kt','feature/settings/ui/SettingsSections.kt',
 'feature/dynamic/DynamicViewModel.kt','feature/dynamic/DynamicScreenStatePolicy.kt',
 'feature/dynamic/DynamicUserRequestPolicy.kt','feature/dynamic/DynamicInteractionPolicy.kt',
 'feature/dynamic/DynamicLayoutPolicy.kt','data/repository/DynamicRepository.kt','data/repository/LiveRepository.kt',
 'feature/dynamic/DynamicScreen.kt','feature/dynamic/components/DynamicAdaptiveSegmentedControl.kt',
 'feature/dynamic/components/DynamicUserLiveBadge.kt']]+[DS+p for p in ['components/AppSegmentedControl.kt',
 'renderer/material3/AppMaterial3SegmentedControl.kt','renderer/miuix/AppMiuixSegmentedControl.kt']]+DIRECT[:3]+[DS+'AdaptiveChrome.kt',DS+'renderer/material3/AppMaterial3TonalPillTabRow.kt']
from v041_dynamic_author_search import MAIN_PATHS as AUTHOR_SEARCH_PATHS
PATHS += list(AUTHOR_SEARCH_PATHS)

def module(repo,name,path):
 s=importlib.util.spec_from_file_location(name,repo/path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def read(repo,path):
 from v041_dynamic_author_search import MAIN_PATHS,main_sources
 if path in MAIN_PATHS:return main_sources(repo)[path]
 return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def read_selected_before_category_merge(repo,path):
 from v037_shared_tabs_progress import selected_source
 source=selected_source(path,read(repo,path))
 from v042_search_dependencies import native_tabs_source
 source=native_tabs_source(repo,path,source)
 if path.endswith('/DynamicScreenStatePolicy.kt'):
  from v040_dynamic_pagination import compose_policy
  source,_=compose_policy(repo,source)
 return source
def read_selected(repo,path):
 # Last layer: the v0.4.6 dynamic category merge (tab policy aside) over every predecessor above.
 from v046_dynamic_category_merge import advance_selected
 return advance_selected(repo,path,read_selected_before_category_merge(repo,path))
def inventory(repo):
 from v041_dynamic_author_search import COMMIT
 rows=[dict(path=p,mode='direct' if p in DIRECT else 'platform-adapter-reference' if p.endswith('DynamicUserLiveBadge.kt') else 'policy-extract',
 features=['settings-dynamic-tabs-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in PATHS]
 for row in rows:
  if row['path'] in AUTHOR_SEARCH_PATHS:
   row.update(mode='extracted',upstreamCommit=COMMIT)
   row['features'].append('v041-dynamic-author-search')
 return rows
def generate(repo,output,standalone=False):
 from v046_dynamic_category_merge import validate_outputs as validate_category_merge_outputs
 validate_category_merge_outputs(repo,output)
 host=module(repo,'tabs_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
 a=module(repo,'tabs_decl','desktop/tools/extract-appearance-platform.py');home=module(repo,'tabs_ui','desktop/tools/extract-upstream-settings-home.py')
 output.mkdir(parents=True,exist_ok=True);files=[]
 def emit(path,body,name):files.append(host.write(output,path,read_selected(repo,path),body,name))
 def functions(source,names):return '\n\n'.join(a.declarations(parser,source,[n]).rstrip()
  if n=='normalizeDynamicNotInterestedIds' else media.function(source,n,parser) for n in names)
 path=BASE+'core/store/SettingsManager.kt';s=read_selected(repo,path)
 # v0.4.6 retired the persisted "All page strip" key and its accessors; "up" folds into "all" in the two tab getters.
 names=['KEY_DYNAMIC_TAB_VISIBLE_TABS','KEY_DYNAMIC_TAB_ORDER','DEFAULT_DYNAMIC_TAB_VISIBLE','DEFAULT_DYNAMIC_TAB_ORDER']
 lines=[]
 for name in names:
  matches=[line.strip() for line in s.splitlines() if (line.strip().startswith('private val '+name+' =') or line.strip().startswith('private const val '+name+' ='))]
  assert len(matches)==1,name
  if matches[0].endswith('='):
   rawLine=next(i for i,line in enumerate(s.splitlines()) if line.strip()==matches[0]);matches[0]+='\n'+s.splitlines()[rawLine+1].strip()
  lines+=matches
 body='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.stringPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopDynamicTabsSettings {
'''+textwrap.indent('\n'.join(lines)+'\n\n'+functions(s,['getDynamicTabVisibleTabs','setDynamicTabVisibleTabs','getDynamicTabOrder','setDynamicTabOrder']),'    ')+'\n}\n'
 emit(path,body,'DesktopDynamicTabsSettings.kt')
 path=BASE+'feature/settings/ui/SettingsSections.kt';s=read_selected(repo,path);section=media.function(s,'FeedApiSection',parser)
 # The "All page strip" switch left FeedApiSection in v0.4.6 (the strip is always on); only the tab item remains.
 marker=section.index('        FeedDynamicTabVisibilityItem(');end=section.index('        SettingsAdaptiveDivider()',marker)
 tabs=home.call(section[marker:end],'FeedDynamicTabVisibilityItem',parser)
 prelude='\n'.join(line for line in section.splitlines() if line.strip().startswith('val siblingTints =') or line.strip().startswith('val visibilityIcon ='))
 body='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.components.AppSwitchPreference as SettingSwitchItem
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppIconButton as IconButton
import com.android.purebilibili.feature.dynamic.*
@Composable internal fun DesktopDynamicTabsSettingsFields(
 dynamicVisibleTabIds:Set<String>,onDynamicTabVisibilityChange:(String)->Unit,
 dynamicTabOrder:List<String>,onDynamicTabOrderChange:(List<String>)->Unit,
) {
'''+prelude+'\n SettingsCardGroup {\n'+tabs+'\n }\n}\n\n@Composable\n'+media.function(s,'FeedDynamicTabVisibilityItem',parser)+'\n'
 emit(path,body,'DesktopOriginalDynamicTabsFields.kt')
 path=BASE+'feature/dynamic/DynamicViewModel.kt';s=read_selected(repo,path)
 mapped=functions(s,['extractUsersFromFollowings','extractUsersFromLive','applyUserPreferences'])
 mapped=mapped.replace('private fun extractUsers','internal fun extractUsers')
 mapped=mapped.replace('private fun applyUserPreferences(users: List<SidebarUser>)','internal fun applyUserPreferences(users: List<SidebarUser>, pinned:Set<Long>, hidden:Set<Long>, showHidden:Boolean)')
 for line in ['    val pinned = _pinnedUserIds.value\n','    val hidden = _hiddenUserIds.value\n','    val showHidden = _showHiddenUsers.value\n']:mapped=host.substitute(mapped,line,'')
 keyLines=[]
 for name in ['PREFS_DYNAMIC_USERS','KEY_PINNED_USERS','KEY_HIDDEN_USERS','KEY_SELECTED_TAB']:
  match=[line.strip().replace('private const val','const val',1) for line in s.splitlines() if line.strip().startswith('private const val '+name+' =')]
  assert len(match)==1,name;keyLines+=match
 emit(path,'''package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.*
private const val DYNAMIC_FOLLOWINGS_PAGE_SIZE = 50
'''+media.data_class(s,'SidebarUser',parser)+'\n\n'+media.data_class(s,'DynamicStartupLoadPlan',parser)+'\n\ninternal object DesktopOriginalDynamicUserPreferenceKeys {\n'+textwrap.indent('\n'.join(keyLines),'    ')+'\n}\n\n'+functions(s,['resolveDynamicStartupLoadPlan','resolveDynamicFollowingsPageLimit','hasLoadedAllDynamicFollowings'])+'\n\n'+mapped,'DesktopOriginalDynamicUsers.kt')
 path=BASE+'data/repository/LiveRepository.kt';s=read_selected(repo,path);method=media.function(s,'getFollowedLivePage',parser)
 start=method.index('        val followedRooms =');end=method.index('        val pageInfo =',start)
 pure=textwrap.dedent(method[start:end]);assert 'followedRooms.map { it.toLiveRoom() }' in pure
 emit(path,'package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.*\n\n'+
  'internal fun desktopOriginalDynamicFollowedLiveUsers(resp:FollowedLiveResponse):List<LiveRoom> {\n'+textwrap.indent(pure,'    ')+'    return liveRooms.distinctBy { it.roomid }\n}\n','DesktopOriginalDynamicFollowedLiveUsers.kt')
 path=BASE+'feature/dynamic/DynamicScreenStatePolicy.kt';s=read_selected(repo,path)
 from v040_dynamic_pagination import compose_policy
 _,pagination_proof=compose_policy(repo,read(repo,path))
 (output/'v040-original-dynamic-pagination-proof.json').write_text(json.dumps(pagination_proof,indent=2)+'\n',encoding='utf-8')
 from v046_dynamic_category_merge import SELECTED_PATHS as category_merge_paths,receipt as category_merge_receipt
 (output/'v046-dynamic-category-merge-proof.json').write_text(json.dumps(category_merge_receipt(repo,
  {p:read_selected_before_category_merge(repo,p) for p in category_merge_paths}),indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
 # v0.4.6 merged the UP tab into All: the tab/author hand-offs are gone, the strip panel boundary is new.
 selected=['resolveDynamicUpPanelUsers','isDynamicUpPanelAllShortcut','isDynamicUpPanelShortcut','isDynamicUpPanelItemSelected',
 'resolveDynamicSelectedUserIdAfterClick','shouldUseSelectedUserDynamicFeed',
 'resolveDynamicSelectedTab','resolveDynamicFeedRequestType','shouldShowDynamicUpPanel','shouldShowDynamicHorizontalUserList','resolveHorizontalUserListVerticalPaddingDp',
 'extractUsersFromDynamicItems','resolveMergedFollowedUsers','isDynamicItemRealUser','shouldLoadMoreDynamicFeed','normalizeDynamicNotInterestedIds']
 emit(path,'''package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.*
import kotlin.math.max
internal const val DYNAMIC_UP_PANEL_ALL_UID = -1L
'''+functions(s,selected),'DesktopOriginalDynamicUserStatePolicy.kt')
 path=BASE+'feature/dynamic/DynamicUserRequestPolicy.kt';s=read_selected(repo,path)
 # Whole v0.4.6 file: the content filter enum and its presentation extension left it with the category merge.
 emit(path,s,'DesktopOriginalDynamicUserRequestPolicy.kt')
 path=BASE+'feature/dynamic/DynamicInteractionPolicy.kt';s=read_selected(repo,path)
 emit(path,'package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.data.model.response.DynamicItem\n\n'+functions(s,
 ['shouldIncludeDynamicItemInVideoTab','shouldIncludeDynamicItemInPgcTab','shouldIncludeDynamicItemInArticleTab']),'DesktopOriginalDynamicUserItemFilter.kt')
 path=BASE+'feature/dynamic/DynamicLayoutPolicy.kt';s=read_selected(repo,path)
 emit(path,'package com.android.purebilibili.feature.dynamic\nimport androidx.compose.ui.unit.*\n\n'+a.declarations(parser,s,
 ['resolveDynamicHorizontalUserListHorizontalPadding','resolveDynamicHorizontalUserListSpacing','shouldShowDynamicUserLiveBadge','resolveDynamicUserLiveBadgeLabel']),'DesktopOriginalDynamicUserLayout.kt')
 path=BASE+'data/repository/DynamicRepository.kt';s=read_selected(repo,path)
 method=media.function(s,'getUserDynamicFeed',parser).replace('NetworkModule.dynamicApi.getUserDynamicFeed(','getPage(')
 method=method.replace('catch (e: Exception) {','catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Exception) {').replace('            e.printStackTrace()','            // Task boundary never prints URLs or raw response exceptions.')
 retry=media.function(s,'fetchDynamicFeedPageWithRetry',parser).replace('catch (error: Exception) {','catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {')
 # Existing extraction owns DynamicPaginationState; this class is the original separate UID registry.
 registry=a.declarations(parser,s,['DynamicUserPaginationRegistry'])
 body='''package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
internal class DesktopOriginalDynamicUserRepository(
 private val requestPage:suspend (Map<String,String>)->DynamicFeedResponse,
 private val stillOwned:()->Boolean={true},
) {
 private val userFeedPagination=DynamicUserPaginationRegistry()
 fun checkpointForFollowChange(uid:Long)=DynamicPaginationState(offset=userFeedPagination.offset(uid),hasMore=userFeedPagination.hasMore(uid))
 fun restoreAfterFollowChange(uid:Long,before:DynamicPaginationState) {
  userFeedPagination.update(uid,before.offset,before.hasMore)
 }
 fun hasMore(uid:Long)=userFeedPagination.hasMore(uid)
 private suspend fun getPage(params:Map<String,String>):DynamicFeedResponse {
  currentCoroutineContext().ensureActive();if(!stillOwned())throw CancellationException("Dynamic source retired")
  val response=requestPage(params)
  currentCoroutineContext().ensureActive();if(!stillOwned())throw CancellationException("Dynamic source retired")
  return response
 }
'''+textwrap.indent(method+'\n\n'+retry+'\n\n'+media.function(s,'buildSelectedUserDynamicFeedParams',parser),' ')+ '\n}\n\n'+registry
 emit(path,body,'DesktopOriginalDynamicUserRepository.kt')
 path=BASE+'feature/dynamic/DynamicScreen.kt';s=read_selected(repo,path)
 body='''package com.android.purebilibili.feature.dynamic
import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.dynamic.components.*
import coil3.compose.AsyncImage
import coil3.request.crossfade
import coil3.compose.LocalPlatformContext as LocalContext
@Composable
'''+media.function(s,'HorizontalUserList',parser).replace('private fun','internal fun',1)+'\n\n@Composable\n'+media.function(s,'DynamicSelectedUserFeedHeader',parser).replace('private fun','internal fun',1)+'\n'
 emit(path,body,'DesktopOriginalDynamicUserControls.kt')
 path=BASE+'feature/dynamic/components/DynamicAdaptiveSegmentedControl.kt';s=read_selected(repo,path)
 # Only the exact original native renderer branch is available on this host. Its liquid branch stays explicitly missing.
 fun=media.function(s,'DynamicAdaptiveSegmentedControl',parser);start=fun.index('        val options = remember(items)');native=fun[start:fun.rfind('\n    }')]
 signature=fun[:fun.index('    val context = LocalContext.current')]
 emit(path,'''package com.android.purebilibili.feature.dynamic.components
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.components.*
import top.yukonga.miuix.kmp.blur.Backdrop
@Composable
'''+signature+native+'\n}\n','DesktopOriginalDynamicNativeSegmentedControl.kt')
 path=BASE+'feature/dynamic/components/DynamicUserLiveBadge.kt';s=read_selected(repo,path)
 emit(path,s.replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion'),'DynamicUserLiveBadge.kt')
 path=DS+'components/AppSegmentedControl.kt';s=read_selected(repo,path)
 s=s.replace(media.data_class(s,'AppSegmentOption',parser),'').replace(a.declarations(parser,s,['resolvePiliPlusScrollableUnderlineMinWidth']),'')
 s=s.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopDynamicWindowConfiguration as LocalConfiguration')
 s=s.replace('import androidx.compose.foundation.isSystemInDarkTheme','import com.bilipai.desktop.appearance.isDesktopInDarkTheme as isSystemInDarkTheme')
 emit(path,s,'DesktopOriginalDynamicNativeTabs.kt')
 for path in DIRECT:
  if path.endswith("/AppTonalPillTabPolicy.kt") or path.endswith("/AppSegmentedControlPolicy.kt"):continue
  if standalone or read_selected(repo,path)!=read(repo,path):
   emit(path,read_selected(repo,path),Path(path).name)
 path=DS+'renderer/material3/AppMaterial3TonalPillTabRow.kt';s=read_selected(repo,path)
 emit(path,s.replace('androidx.compose.ui.platform.LocalConfiguration','com.bilipai.desktop.ui.DesktopDynamicWindowConfiguration'),Path(path).name)
 path=DS+'AdaptiveChrome.kt';s=read_selected(repo,path)
 line=next(line for line in s.splitlines() if line.startswith('val LocalImmersiveTopChromeActive ='))
 emit(path,'package com.android.purebilibili.core.ui\nimport androidx.compose.runtime.compositionLocalOf\n'+line+'\n','DesktopOriginalDynamicImmersiveChrome.kt')
 from v041_dynamic_author_search import generate_main as generate_author_search_main
 files += generate_author_search_main(repo,output,host)
 return files

def generate_tests(repo,output):
 from v041_dynamic_author_search import generate_tests as generate_author_search_tests
 host=module(repo,'author_search_test_host','desktop/tools/extract-upstream-plugins.py')
 return generate_author_search_tests(repo,output,host)

def generate_category_merge_tests(repo,output):
 """The two whole v0.4.6 policy tests of the dynamic category merge, next to the author-search test."""
 from v046_dynamic_category_merge import validate_outputs,original_tests
 validate_outputs(repo,output)
 host=module(repo,'category_merge_test_host','desktop/tools/extract-upstream-plugins.py')
 return [host.write(output,path,body,body) for path,body in original_tests(repo).items()]

if __name__=='__main__':
 c=argparse.ArgumentParser(description=__doc__);c.add_argument('--repo',type=Path,required=True);c.add_argument('--output',type=Path);c.add_argument('--inventory',action='store_true');c.add_argument('--standalone',action='store_true');c.add_argument('--tests-output',type=Path);a=c.parse_args()
 if a.inventory:print(json.dumps(inventory(a.repo.resolve()),indent=2))
 if a.output:print('Generated',len(generate(a.repo.resolve(),a.output.resolve(),standalone=a.standalone)))
 if a.tests_output:print('Generated tests',len(generate_tests(a.repo.resolve(),a.tests_output.resolve()))+len(generate_category_merge_tests(a.repo.resolve(),a.tests_output.resolve())))

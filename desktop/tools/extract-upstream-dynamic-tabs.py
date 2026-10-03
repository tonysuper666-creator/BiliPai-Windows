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
def module(repo,name,path):
 s=importlib.util.spec_from_file_location(name,repo/path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def inventory(repo):return [dict(path=p,mode='direct' if p in DIRECT else 'platform-adapter-reference' if p.endswith('DynamicUserLiveBadge.kt') else 'policy-extract',
 features=['settings-dynamic-tabs-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in PATHS]
def generate(repo,output,standalone=False):
 host=module(repo,'tabs_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
 a=module(repo,'tabs_decl','desktop/tools/extract-appearance-platform.py');home=module(repo,'tabs_ui','desktop/tools/extract-upstream-settings-home.py')
 output.mkdir(parents=True,exist_ok=True);files=[]
 def emit(path,body,name):files.append(host.write(output,path,read(repo,path),body,name))
 def functions(source,names):return '\n\n'.join(a.declarations(parser,source,[n]).rstrip()
  if n=='normalizeDynamicNotInterestedIds' else media.function(source,n,parser) for n in names)
 path=BASE+'core/store/SettingsManager.kt';s=read(repo,path)
 names=['KEY_DYNAMIC_TAB_VISIBLE_TABS','KEY_DYNAMIC_TAB_ORDER','KEY_DYNAMIC_ALL_TAB_HORIZONTAL_USER_LIST_VISIBLE','DEFAULT_DYNAMIC_TAB_VISIBLE','DEFAULT_DYNAMIC_TAB_ORDER']
 lines=[]
 for name in names:
  matches=[line.strip() for line in s.splitlines() if (line.strip().startswith('private val '+name+' =') or line.strip().startswith('private const val '+name+' ='))]
  assert len(matches)==1,name
  if matches[0].endswith('='):
   rawLine=next(i for i,line in enumerate(s.splitlines()) if line.strip()==matches[0]);matches[0]+='\n'+s.splitlines()[rawLine+1].strip()
  lines+=matches
 body='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.plugins.stringPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopDynamicTabsSettings {
'''+textwrap.indent('\n'.join(lines)+'\n\n'+functions(s,['getDynamicTabVisibleTabs','setDynamicTabVisibleTabs','getDynamicTabOrder','setDynamicTabOrder','getDynamicAllTabHorizontalUserListVisible','setDynamicAllTabHorizontalUserListVisible']),'    ')+'\n}\n'
 emit(path,body,'DesktopDynamicTabsSettings.kt')
 path=BASE+'feature/settings/ui/SettingsSections.kt';s=read(repo,path);section=media.function(s,'FeedApiSection',parser)
 marker=section.index('title = "“全部”页显示关注用户栏"');begin=section.rfind('        SettingSwitchItem(',0,marker);end=section.index('        SettingsAdaptiveDivider()',marker)
 switch=home.call(section[begin:end],'SettingSwitchItem',parser)
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
 dynamicAllTabHorizontalUserListVisible:Boolean,
 onDynamicAllTabHorizontalUserListVisibleChange:(Boolean)->Unit,
 dynamicVisibleTabIds:Set<String>,onDynamicTabVisibilityChange:(String)->Unit,
 dynamicTabOrder:List<String>,onDynamicTabOrderChange:(List<String>)->Unit,
) {
'''+prelude+'\n SettingsCardGroup {\n'+switch+'\n SettingsAdaptiveDivider()\n'+tabs+'\n }\n}\n\n@Composable\n'+media.function(s,'FeedDynamicTabVisibilityItem',parser)+'\n'
 emit(path,body,'DesktopOriginalDynamicTabsFields.kt')
 path=BASE+'feature/dynamic/DynamicViewModel.kt';s=read(repo,path)
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
 path=BASE+'data/repository/LiveRepository.kt';s=read(repo,path);method=media.function(s,'getFollowedLivePage',parser)
 start=method.index('        val followedRooms =');end=method.index('        val pageInfo =',start)
 pure=textwrap.dedent(method[start:end]);assert 'followedRooms.map { it.toLiveRoom() }' in pure
 emit(path,'package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.*\n\n'+
  'internal fun desktopOriginalDynamicFollowedLiveUsers(resp:FollowedLiveResponse):List<LiveRoom> {\n'+textwrap.indent(pure,'    ')+'    return liveRooms.distinctBy { it.roomid }\n}\n','DesktopOriginalDynamicFollowedLiveUsers.kt')
 path=BASE+'feature/dynamic/DynamicScreenStatePolicy.kt';s=read(repo,path)
 selected=['resolveDynamicUpPanelUsers','isDynamicUpPanelAllShortcut','isDynamicUpPanelShortcut','isDynamicUpPanelItemSelected',
 'resolveDynamicSelectedUserIdAfterClick','shouldUseSelectedUserDynamicFeed','resolveDynamicSelectedUserForTab','resolveDynamicTabAfterUserSelection',
 'resolveDynamicSelectedTab','resolveDynamicFeedRequestType','shouldShowDynamicHorizontalUserList','resolveHorizontalUserListVerticalPaddingDp',
 'extractUsersFromDynamicItems','resolveMergedFollowedUsers','isDynamicItemRealUser','shouldLoadMoreDynamicFeed','normalizeDynamicNotInterestedIds']
 emit(path,'''package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.*
import kotlin.math.max
internal const val DYNAMIC_UP_PANEL_ALL_UID = -1L
'''+functions(s,selected),'DesktopOriginalDynamicUserStatePolicy.kt')
 path=BASE+'feature/dynamic/DynamicUserRequestPolicy.kt';s=read(repo,path)
 # The one presentation extension requires Android ViewModel state; callers reuse its item filter directly.
 extension=media.function(s,'withUserContentFilter',parser)
 body=s.replace(extension+'\n','')
 emit(path,body,'DesktopOriginalDynamicUserRequestPolicy.kt')
 path=BASE+'feature/dynamic/DynamicInteractionPolicy.kt';s=read(repo,path)
 emit(path,'package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.data.model.response.DynamicItem\n\n'+functions(s,
 ['shouldIncludeDynamicItemInVideoTab','shouldIncludeDynamicItemInPgcTab','shouldIncludeDynamicItemInArticleTab']),'DesktopOriginalDynamicUserItemFilter.kt')
 path=BASE+'feature/dynamic/DynamicLayoutPolicy.kt';s=read(repo,path)
 emit(path,'package com.android.purebilibili.feature.dynamic\nimport androidx.compose.ui.unit.*\n\n'+a.declarations(parser,s,
 ['resolveDynamicHorizontalUserListHorizontalPadding','resolveDynamicHorizontalUserListSpacing','shouldShowDynamicUserLiveBadge','resolveDynamicUserLiveBadgeLabel']),'DesktopOriginalDynamicUserLayout.kt')
 path=BASE+'data/repository/DynamicRepository.kt';s=read(repo,path)
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
 path=BASE+'feature/dynamic/DynamicScreen.kt';s=read(repo,path)
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
 path=BASE+'feature/dynamic/components/DynamicAdaptiveSegmentedControl.kt';s=read(repo,path)
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
 path=BASE+'feature/dynamic/components/DynamicUserLiveBadge.kt';s=read(repo,path)
 emit(path,s.replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion'),'DynamicUserLiveBadge.kt')
 path=DS+'components/AppSegmentedControl.kt';s=read(repo,path)
 s=s.replace(media.data_class(s,'AppSegmentOption',parser),'').replace(a.declarations(parser,s,['resolvePiliPlusScrollableUnderlineMinWidth']),'')
 s=s.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopDynamicWindowConfiguration as LocalConfiguration')
 s=s.replace('import androidx.compose.foundation.isSystemInDarkTheme','import com.bilipai.desktop.appearance.isDesktopInDarkTheme as isSystemInDarkTheme')
 emit(path,s,'DesktopOriginalDynamicNativeTabs.kt')
 if standalone:
  for path in DIRECT:
   emit(path,read(repo,path),Path(path).name)
 path=DS+'renderer/material3/AppMaterial3TonalPillTabRow.kt';s=read(repo,path)
 emit(path,s.replace('androidx.compose.ui.platform.LocalConfiguration','com.bilipai.desktop.ui.DesktopDynamicWindowConfiguration'),Path(path).name)
 path=DS+'AdaptiveChrome.kt';s=read(repo,path)
 line=next(line for line in s.splitlines() if line.startswith('val LocalImmersiveTopChromeActive ='))
 emit(path,'package com.android.purebilibili.core.ui\nimport androidx.compose.runtime.compositionLocalOf\n'+line+'\n','DesktopOriginalDynamicImmersiveChrome.kt')
 return files
if __name__=='__main__':
 c=argparse.ArgumentParser(description=__doc__);c.add_argument('--repo',type=Path,required=True);c.add_argument('--output',type=Path);c.add_argument('--inventory',action='store_true');c.add_argument('--standalone',action='store_true');a=c.parse_args()
 if a.inventory:print(json.dumps(inventory(a.repo.resolve()),indent=2))
 if a.output:print('Generated',len(generate(a.repo.resolve(),a.output.resolve(),standalone=a.standalone)))

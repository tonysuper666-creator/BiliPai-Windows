from pathlib import Path
import hashlib,json,subprocess,importlib.util,textwrap
LANE=Path(__file__).resolve().parent
ROOT=LANE.parents[4]
REPO=ROOT/'work/BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
paths=[BASE+'feature/live/'+n+'.kt' for n in ['LiveListScreen','LiveRoomCard','LiveChromePalette','LiveBiliPaiVisualPolicy','LiveHomeSelectableChip','LiveHomeCategoryIndicatorPolicy','LiveHomeAreaSelectionPolicy','LiveListTabColorPolicy','LiveRoomLayoutPolicy']]+[BASE+'data/repository/'+n+'.kt' for n in ['LiveRepository','LiveFeedParsePolicy','LiveAreaRoomsPolicy']]+[BASE+'core/ui/transition/BiliPaiSharedElementKey.kt',BASE+'core/network/ApiClient.kt',BASE+'feature/home/HomeScreen.kt',BASE+'navigation/AppNavigation.kt',BASE+'core/util/ResultExtensions.kt']
pins={}
for p in paths:
 blob=subprocess.run(['git','show',COMMIT+':'+p],cwd=REPO,capture_output=True,check=True).stdout.decode('utf-8').replace('\r\n','\n')
 assert read(REPO/p)==blob,p
 pins[p]=sha(blob);write(LANE/'original-stable'/p,blob)
producer='''"""Full original stable Live home list closure; required Windows owner/platform ports only.
Existing Live DTO, LiveAreaRoomsPage, BiliPai shared element keys, skeletons and raw694
popular/followed protocol remain sole authorities. No HTTP client/store/DTO is emitted.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,json,textwrap
COMMIT=COMMIT_VALUE
SOURCE_PINS=PIN_VALUE
BASE='app/src/main/java/com/android/purebilibili/'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\\r\\n','\\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
def generate(repo,output,standalone=False):
 repo=Path(repo);output=Path(output);records=[]
 originals={p:read(repo/p) for p in SOURCE_PINS}
 for p,s in originals.items():assert sha(s)==SOURCE_PINS[p],p
 parser=load(repo/'desktop/tools/sync-upstream.py','live_list_parser')
 media=load(repo/'desktop/tools/extract-upstream-media.py','live_list_selector')
 def emit(p,s,path=None):
  raw=originals[p];dst=path or p.removeprefix(BASE)
  if path is None:dst='com/android/purebilibili/'+dst
  write(output/dst,s);records.append(dict(source=p,output=dst,originalSha256LF=sha(raw),preparedSha256LF=sha(s),wholeOriginalFile=path is None,adapted=raw!=s))
 for n in ['LiveRoomCard','LiveChromePalette','LiveBiliPaiVisualPolicy','LiveHomeSelectableChip','LiveHomeCategoryIndicatorPolicy','LiveHomeAreaSelectionPolicy','LiveListTabColorPolicy']:
  p=BASE+'feature/live/'+n+'.kt'
  if standalone:emit(p,originals[p])
 for n in ['LiveFeedParsePolicy','LiveAreaRoomsPolicy']:
  p=BASE+'data/repository/'+n+'.kt'
  if standalone:emit(p,originals[p])
 p=BASE+'feature/live/LiveRoomLayoutPolicy.kt'
 raw=media.function(originals[p],'formatLiveViewerCount',parser)
 emit(p,'package com.android.purebilibili.feature.live\\n\\n'+raw+'\\n','com/android/purebilibili/feature/live/DesktopLiveViewerCount.kt')
 p=BASE+'core/util/ResultExtensions.kt';raw=originals[p]
 begin=raw.index('object ApiErrorCodes {');end=raw.index('\\n}\\n',begin)+3
 body=raw[begin:end]+'\\n\\n'+media.function(raw,'getApiErrorMessage',parser)
 emit(p,'package com.android.purebilibili.core.util\\n\\n'+body+'\\n','com/android/purebilibili/core/util/DesktopOriginalLiveApiErrors.kt')
 p=BASE+'feature/live/LiveListScreen.kt';s=originals[p]
 for line in ['import android.app.Application\\n','import android.widget.Toast\\n','import androidx.compose.ui.platform.LocalContext\\n','import androidx.lifecycle.AndroidViewModel\\n','import androidx.lifecycle.viewModelScope\\n','import androidx.lifecycle.viewmodel.compose.viewModel\\n','import com.android.purebilibili.core.network.NetworkModule\\n','import com.android.purebilibili.data.repository.LiveRepository\\n']:
  assert s.count(line)==1,line;s=s.replace(line,'')
 s=s.replace('import kotlinx.coroutines.launch','import kotlinx.coroutines.launch\\nimport kotlinx.coroutines.CoroutineScope\\nimport kotlinx.coroutines.CancellationException\\nimport com.bilipai.desktop.ui.DesktopLiveListOwner\\nimport com.bilipai.desktop.ui.DesktopOwnedLiveListState\\nimport com.bilipai.desktop.ui.DesktopOriginalLiveListRequests\\nimport com.bilipai.desktop.ui.DesktopLiveListPlatform')
 old='class LiveListViewModel(application: Application) : AndroidViewModel(application) {'
 assert s.count(old)==1
 s=s.replace(old,'class LiveListViewModel(\\n    private val viewModelScope: CoroutineScope,\\n    private val requests: DesktopOriginalLiveListRequests,\\n    private val owner: DesktopLiveListOwner,\\n) {')
 s=s.replace('MutableStateFlow(LiveListUiState(isLoading = true))','DesktopOwnedLiveListState(LiveListUiState(isLoading = true), owner)')
 for n in ['refresh','loadMore','selectHomeArea','selectSortTag','toggleShowFirstFrame']:
  # Windows page/account retirement rejects clicks before original ephemeral state changes.
  import re
  pattern=r'(    fun '+n+r'\\([^\\n]*\\) \\{\\n)'
  s,count=re.subn(pattern,r'\\1        if (!owner.isCurrent()) return\\n',s);assert count==1,n
 s=s.replace('NetworkModule.api.getLiveAreaList()','requests.getLiveAreaList()')
 s=s.replace('LiveRepository.getLiveFeedHome','requests.getLiveFeedHome').replace('LiveRepository.getFollowedLivePage','requests.getFollowedLivePage').replace('LiveRepository.getLiveSecondHome','requests.getLiveSecondHome')
 s=s.replace('                runCatching {\\n                    val response = requests.getLiveAreaList()', '                try {\\n                    val response = requests.getLiveAreaList()')
 old="""                    }
                }
            }
        }
    }

    fun loadMore()"""
 new="""                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { /* Original optional area-list fallback ignores failure. */ }
            }
        }
    }

    fun loadMore()"""
 assert s.count(old)==1;s=s.replace(old,new)
 s=s.replace('onMatchClick: () -> Unit = {}','onMatchClick: () -> Unit')
 s=s.replace('onLongPressCard: (LiveRoomCardUiModel) -> Unit = {}','onLongPressCard: (LiveRoomCardUiModel) -> Unit')
 s=s.replace('viewModel: LiveListViewModel = viewModel(),','viewModel: LiveListViewModel,\\n    platform: DesktopLiveListPlatform,')
 assert s.count('    val context = LocalContext.current\\n')==1;s=s.replace('    val context = LocalContext.current\\n','')
 start=s.index('                                    val success = com.android.purebilibili.feature.download.DownloadManager')
 end=s.index('                                    ).show()',start)+len('                                    ).show()')
 replacement="""                                    if (!platform.isCurrent()) return@launch
                                    val success = platform.saveCover(card.coverUrl, card.title)
                                    if (platform.isCurrent()) platform.feedback(
                                        if (success) "封面已保存到相册" else "封面保存失败，请稍后重试"
                                    )"""
 s=s[:start]+replacement+s[end:]
 emit(p,s)
 p=BASE+'data/repository/LiveRepository.kt';s=originals[p]
 methods=['getRecommendedLiveRooms','getLiveFeedHome','getLiveSecondHome','buildLiveAppFeedParams','buildLiveAppSecondListParams','fallbackLiveFeedHome','getLiveAreaIndex','getAreaRoomsPage']
 bodies=[]
 for n in methods:
  raw=media.function(s,n,parser);adapt=raw.replace('TokenManager.accessTokenCache','environment.accessToken()')
  adapt=adapt.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\\n        } catch (e: Exception) {')
  adapt=adapt.replace('} catch (_: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\\n        } catch (_: Exception) {')
  bodies.append(textwrap.indent(adapt,'    '));records.append(dict(source=p,declaration=n,originalBodySha256LF=sha(raw),preparedBodySha256LF=sha(adapt),adapted=raw!=adapt))
 imports="""package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DesktopOriginalLiveListProtocol(private val environment: DesktopHomeProtocolEnvironment) {
    private val api get() = environment.api
    private val existingLive = DesktopOriginalHomeLiveProtocol(environment.api)
    private suspend fun getLiveRooms(page: Int) = existingLive.getLiveRooms(page)
    private suspend fun getFollowedLive(page: Int) = existingLive.getFollowedLive(page)
"""
 out='com/android/purebilibili/data/repository/DesktopOriginalLiveListProtocol.kt'
 emit(p,imports+'\\n\\n'.join(bodies)+'\\n}\\n',out)
 write(output/'live-list-selection-proof.json',json.dumps(dict(pinnedCommit=COMMIT,selectedSources=records),ensure_ascii=False,indent=2))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--output',required=True);p.add_argument('--standalone',action='store_true');a=p.parse_args();generate(a.repo,a.output,a.standalone)
'''.replace('COMMIT_VALUE',repr(COMMIT)).replace('PIN_VALUE',repr(pins))
write(LANE/'prepared/tools/extract-upstream-live-list.py',producer)
generator=load(LANE/'prepared/tools/extract-upstream-live-list.py','live_list_producer');generator.generate(REPO,LANE/'prepared/generated',True)
write(LANE/'source-inventory.json',json.dumps({'pinnedCommit':COMMIT,'sources':[{'path':p,'sha256LF':h} for p,h in pins.items()]},ensure_ascii=False,indent=2))
print('Generated full Live list closure: '+str(len(list(safe(LANE/'prepared/generated').rglob('*.kt'))))+' original selected Kotlin files.')

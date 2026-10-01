from pathlib import Path
import hashlib,importlib.util,json,textwrap,re

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
REPO=ROOT/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
BASE='app/src/main/java/com/android/purebilibili/'
OUT=HERE/'prepared/generated'

def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
parser=load(REPO/'desktop/tools/sync-upstream.py','home_port_parser')
media=load(REPO/'desktop/tools/extract-upstream-media.py','home_port_selector')
rows=[];pins={}
def source(name):
 path=BASE+'data/repository/'+name+'.kt';s=read(REPO/path);pins[path]=sha(s);return path,s
def exact_replace(s,old,new):
 assert s.count(old)==1,(old[:80],s.count(old));return s.replace(old,new)
def method(path,s,name,adapt=None):
 raw=media.function(s,name,parser);desired=raw if adapt is None else adapt(raw)
 rows.append({'source':path,'declaration':name,'originalSha256LF':sha(raw),'desiredSha256LF':sha(desired),'adapted':raw!=desired})
 return desired

videoPath,video=source('VideoRepository')
def video_adapt(name,body):
 s=body.replace('com.android.purebilibili.core.store.SettingsManager.FeedApiType','DesktopFeedSettings.FeedApiType')
 s=s.replace('SettingsManager.FeedApiType','DesktopFeedSettings.FeedApiType')
 s=s.replace('TokenManager.accessTokenCache','environment.accessToken()')
 s=s.replace('TokenManager.buvid3Cache','environment.buvid3()')
 s=s.replace('TokenManager.awaitRestore()','environment.awaitSessionRestored()')
 s=s.replace('ensureBuvid3FromSpi()','environment.ensureBuvid3FromSpi()')
 s=s.replace('WbiKeyManager.getWbiKeys()','environment.wbiKeys()')
 s=s.replace('NetworkModule.guestApi','environment.guestApi')
 s=s.replace('scope: CoroutineScope = AppScope.ioScope','scope: CoroutineScope = environment.parentScope')
 if name=='getHomeVideosInternal':
  token=s.index('val context = com.android.purebilibili.core.network.NetworkModule.appContext')
  begin=s.rfind('\n',0,token)+1;indent=s[begin:token]
  end=s.rfind('\n',0,s.index('com.android.purebilibili.core.util.Logger.d(',token))+1
  s=s[:begin]+indent+'val feedApiType = environment.feedApiType()\n'+indent+'val refreshCount = environment.refreshCount()\n\n'+s[end:]
 if name=='preloadHomeData':
  token=s.index('val feedApiType = NetworkModule.appContext');begin=s.rfind('\n',0,token)+1;indent=s[begin:token]
  end=s.rfind('\n',0,s.index('if (shouldPrimeBuvidForHomePreload',token))+1
  s=s[:begin]+indent+'val feedApiType = environment.feedApiType()\n'+s[end:]
  s=s.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\n        } catch (e: Exception) {')
 s=s.replace('e.printStackTrace()','// Windows boundary does not print raw URL/response exceptions.')
 if name in ['getNavInfo','fetchAsGuestFallback']:
  s=s.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\n    } catch (e: Exception) {')
 return s

helpers=['shouldStartHomePreload','shouldPrimeBuvidForHomePreload','shouldReuseInFlightPreloadForHomeRequest','shouldReportHomeDataReadyForSplash','resolveHomeFeedWbiKeys']
helperBody='\n\n'.join(method(videoPath,video,n,lambda s,n=n:video_adapt(n,s)) for n in helpers)
imports='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.*
'''
write(OUT/'com/android/purebilibili/data/repository/DesktopHomePreloadPolicy.kt',imports+'\n'+helperBody+'\n')
methods=['isHomeDataReady','preloadHomeData','awaitHomePreloadResult','consumePreloadedHomeVideos','getHomeVideos','getHomeVideosInternal','fetchWebFeed','fetchMobileFeed','fetchMergedWebFeed','fetchMergedMobileFeed','getPopularVideos','getRankingVideos','getPreciousVideos','getWeeklyMustWatchVideos','getRegionVideos','getNavInfo','getPreviewVideoUrl','fetchAsGuestFallback']
originalFields='''    @Volatile private var preloadedHomeVideos: Result<List<VideoItem>>? = null
    @Volatile private var homePreloadDeferred: Deferred<Result<List<VideoItem>>>? = null
    @Volatile private var hasCompletedHomePreload = false'''
for l in originalFields.splitlines():assert l.strip() in video,l
body='\n\n'.join(textwrap.indent(method(videoPath,video,n,lambda s,n=n:video_adapt(n,s)),'    ') for n in methods)
write(OUT/'com/android/purebilibili/data/repository/DesktopOriginalHomeVideoProtocol.kt',imports+'''
/** Selected complete original home request bodies; required services are views of Root's graph. */
internal class DesktopOriginalHomeVideoProtocol(private val environment:DesktopHomeProtocolEnvironment) : AutoCloseable {
    private val api get()=environment.api
'''+originalFields+'\n\n'+body+'''
    override fun close() { homePreloadDeferred?.cancel(); homePreloadDeferred=null; preloadedHomeVideos=null }
}
''')

def general_adapt(s):
 s=s.replace('e.printStackTrace()','// Windows boundary does not print raw URL/response exceptions.')
 s=s.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\n    } catch (e: Exception) {')
 return s

historyPath,history=source('HistoryRepository')
historyMethod=method(historyPath,history,'getHistoryList',general_adapt)
write(OUT/'com/android/purebilibili/data/repository/DesktopOriginalHomeHistoryProtocol.kt',imports+'\ninternal class DesktopOriginalHomeHistoryProtocol(private val api:BilibiliApi) {\n'+textwrap.indent(historyMethod,'    ')+'\n}\n')
livePath,live=source('LiveRepository')
liveMethods=[method(livePath,live,n,general_adapt) for n in ['getLiveRooms','getFollowedLive','getFollowedLivePage']]
write(OUT/'com/android/purebilibili/data/repository/DesktopOriginalHomeLiveProtocol.kt',imports+'\ninternal class DesktopOriginalHomeLiveProtocol(private val api:BilibiliApi) {\n'+'\n\n'.join(textwrap.indent(s,'    ') for s in liveMethods)+'\n}\n')
messagePath,message=source('MessageRepository')
msgMethods=[method(messagePath,message,n,general_adapt) for n in ['getUnreadCount','getFeedUnread']]
write(OUT/'com/android/purebilibili/data/repository/DesktopOriginalHomeMessageProtocol.kt',imports+'\ninternal class DesktopOriginalHomeMessageProtocol(private val api:MessageApi) {\n'+'\n\n'.join(textwrap.indent(s,'    ') for s in msgMethods)+'\n}\n')
actionPath,action=source('ActionRepository')
actionMethods=[method(actionPath,action,n,lambda s:general_adapt(s).replace('TokenManager.accessTokenCache','environment.accessToken()').replace('TokenManager.csrfCache','environment.csrf()')) for n in ['submitRecommendationFeedback','toggleWatchLater']]
write(OUT/'com/android/purebilibili/data/repository/DesktopOriginalHomeActionProtocol.kt',imports+'\nimport com.android.purebilibili.core.refresh.WatchLaterRefreshBus\n\ninternal class DesktopOriginalHomeActionProtocol(private val environment:DesktopHomeProtocolEnvironment) {\n    private val api get()=environment.api\n'+'\n\n'.join(textwrap.indent(s,'    ') for s in actionMethods)+'\n}\n')
busPath=BASE+'core/refresh/WatchLaterRefreshBus.kt';bus=read(REPO/busPath);pins[busPath]=sha(bus)
# Standalone compile copy. Production inventory declares this sole source DIRECT, and the final
# production producer will skip it, just as the previous HomeVM DIRECT3 replay did.
write(OUT/'com/android/purebilibili/core/refresh/WatchLaterRefreshBus.kt',bus)
write(HERE/'selected-declaration-identities.json',json.dumps({'pinnedCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','sourcePins':pins,'declarations':rows},indent=2)+'\n')
print(json.dumps({'outputs':len(list(OUT.rglob('*.kt'))),'declarations':len(rows),'sourcePins':len(pins)}))

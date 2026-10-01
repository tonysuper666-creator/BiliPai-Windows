"""Complete original metadata functions share the installed protocol's WBI/cache.
Only platform getters, actual request admission and original cancellation propagation
are added. No Retrofit/client/store, WBI map or subtitle downloader is constructed.
"""
from pathlib import Path
import hashlib,importlib.util,json,subprocess
H=Path(__file__).resolve().parent; R=H.parents[2].parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
path='app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt'
original=subprocess.check_output(['git','-C',str(R),'show',COMMIT+':'+path]).decode().replace('\r\n','\n')
assert hashlib.sha256(original.encode()).hexdigest()=='1aa112f16f2ccecaf3d26e00e6092e24d96ac6020c7624eff32121dd5199a496'
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path); result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
selector=module('metadata_selector',R/'desktop/tools/extract-upstream-video-detail-full-units.py')
selector.parser=module('metadata_tokens',R/'desktop/tools/sync-upstream.py')
methods=['refreshVipStatusForPreferredQualityIfNeeded','getAiSummary','buildAiSummaryParams','logAiSummaryPreflight','logAiSummaryResponse','getVideoshot','getPlayerInfo','getPbpProgressData','getInteractEdgeInfo']
body='\n\n'.join(selector.func(original,name,False) for name in methods)
changes=[]
def swap(a,b,label):
 global body
 count=body.count(a);assert count,label
 body=body.replace(a,b);changes.append(dict(before=a,after=b,count=count,label=label))
swap('TokenManager.isVipCache = isVip','environment.updatePrimaryVip(isVip)','SAME primary Store admission; dedicated playback projection is never written')
swap('!TokenManager.sessDataCache.isNullOrEmpty()','environment.hasPrimarySession()','Admitted presence only; no primary credentials exposed to diagnostics')
swap('!TokenManager.csrfCache.isNullOrEmpty()','environment.hasPrimaryCsrf()','Admitted presence only')
swap('!TokenManager.buvid3Cache.isNullOrEmpty()','environment.hasPrimaryBuvid()','Admitted presence only')
swap('!TokenManager.accessTokenCache.isNullOrEmpty()','environment.hasPrimaryAccessToken()','Admitted presence only')
swap('buvidInitialized=$buvidInitialized','buvidInitialized=${environment.isBuvidInitialized()}','Actual same bootstrap authority; no fabricated initialized flag')
swap('com.android.purebilibili.core.util.Logger','Logger','Existing sole safe diagnostic logger')
swap('android.util.Log.w','Logger.w','Existing sole safe diagnostic logger')
swap('wbiKeysCache = null','environment.load.state.wbiKeys = null','SAME installed protocol state, no metadata cache')
swap('wbiKeysTimestamp = 0L','environment.load.state.wbiKeysTimestamp = 0L','SAME installed protocol state')
swap('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n        currentCoroutineContext().ensureActive(); environment.assertOwned()','Actual continuation/request admission')
swap('} catch (e: Exception) {','} catch (e: Exception) {\n            if (e is CancellationException) throw e\n            currentCoroutineContext().ensureActive(); environment.assertOwned()','Retirement/cancellation never downgraded to UI failure')
swap('val response = api.','val response = api.','marker') if False else None
for literal in ['val response = api.getVideoshot(bvid = bvid, cid = cid)','val response = api.getPlayerInfo(signedParams)','val response = api.getAiConclusion(signedParams)','val response = api.getInteractEdgeInfo(bvid = bvid, graphVersion = graphVersion, edgeId = edgeId)']:
 swap(literal,literal+'\n            currentCoroutineContext().ensureActive(); environment.assertOwned()','Reject late '+literal.split('api.')[1].split('(')[0]+' response')
swap('Result.success(parsePbpProgressData(body.string()))','parsePbpProgressData(body.string()).let { parsed ->\n                currentCoroutineContext().ensureActive(); environment.assertOwned()\n                Result.success(parsed)\n            }','Body decode belongs to the same admitted PBP call')
header='''package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.feature.video.progress.PbpProgressData
import com.android.purebilibili.feature.video.progress.parsePbpProgressData
import android.util.Log as Logger
import kotlinx.coroutines.*
import com.bilipai.desktop.ui.DesktopOriginalVideoMetadataEnvironment

internal class DesktopOriginalVideoOwnerMetadataProtocol(private val environment:DesktopOriginalVideoMetadataEnvironment) {
    private val api get() = environment.load.api
    private suspend fun ensureBuvid3FromSpi() = environment.load.ensureBuvid()
    private suspend fun getWbiKeys() = environment.protocol.getWbiKeys()
    private suspend fun getPlaybackNavInfo() = environment.protocol.getPlaybackNavInfo()
    private fun isUsingDedicatedPlaybackAccount() = environment.protocol.isUsingDedicatedPlaybackAccount()
'''
target=H/'prepared/metadata/com/android/purebilibili/data/repository/DesktopOriginalVideoOwnerMetadataProtocol.kt';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(header+body+'\n}\n',encoding='utf-8',newline='\n')
# Installed protocol's existing WBI algorithm remains the only algorithm/cache. This
# visibility-only hunk is appended to the CURRENT sole generator after Portrait members.
producerPath='desktop/tools/extract-upstream-video-state-core.py';producer=(R/producerPath).read_text(encoding='utf-8').replace('\r\n','\n')
anchor="s+='\\n'+pager.protocol_members(REPO)\n";assert producer.count(anchor)==1
addition="s=s.replace('private suspend fun getWbiKeys()', 'internal suspend fun getWbiKeys()', 1)\n"
desired=producer.replace(anchor,anchor+addition)
generatedPath='desktop/build/generated/original-video-state-core/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt'
generated=(R/generatedPath).read_text(encoding='utf-8').replace('\r\n','\n');assert generated.count('private suspend fun getWbiKeys()')==1
generatedDesired=generated.replace('private suspend fun getWbiKeys()', 'internal suspend fun getWbiKeys()',1)
out=H/'prepared/legacy/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(generatedDesired,encoding='utf-8',newline='\n')
receipt=dict(preparedOnly=True,upstreamCommit=COMMIT,originalPath=path,originalSha256LF=hashlib.sha256(original.encode()).hexdigest(),methods=methods,modifications=changes,output=str(target.relative_to(H)),outputSha256Bytes=hashlib.sha256(target.read_bytes()).hexdigest(),soleCoreHunk=dict(path=producerPath,baseSha256LF=hashlib.sha256(producer.encode()).hexdigest(),desiredSha256LF=hashlib.sha256(desired.encode()).hexdigest(),exactAnchor=anchor,insertAfter=addition,generatedBaseSha256LF=hashlib.sha256(generated.encode()).hexdigest(),generatedDesiredSha256LF=hashlib.sha256(generatedDesired.encode()).hexdigest()),scope='Prepared metadata functions; actual Root factory/runtime not accepted')
(H/'metadata-original-binding.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print('Original metadata methods',len(methods),'shared WBI visibility-only hunk; no second cache')

from pathlib import Path
import subprocess,hashlib,json
H=Path(__file__).resolve().parent;R=H.parents[2].parent/'BiliPai-v023';commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
path='app/src/main/java/com/android/purebilibili/feature/plugin/CdnDashSegmentPrefetcher.kt'
raw=subprocess.check_output(['git','-C',str(R),'show',commit+':'+path]).decode().replace('\r\n','\n');body=raw
for line in ['import android.annotation.SuppressLint\n','import android.content.Context\n','import android.net.Uri\n','import androidx.media3.datasource.okhttp.OkHttpDataSource\n','import com.android.purebilibili.core.player.PlaybackMediaCache\n','import okhttp3.OkHttpClient\n']:
 body=body.replace(line,'')
body=body.replace('    private val context: Context,\n    private val client: OkHttpClient','    private val cache: com.bilipai.desktop.ui.DesktopOriginalCdnRangeCache,\n    private val client: okhttp3.Call.Factory')
body=body.replace('    @SuppressLint("UnsafeOptInUsageError")\n','')
body=body.replace('        val upstreamFactory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(PLAYBACK_HEADERS)\n','')
old='''                PlaybackMediaCache.prefetchRange(
                    context = context,
                    upstreamFactory = upstreamFactory,
                    url = Uri.parse(winner),
                    cacheKey = request.trackCacheKey,
                    position = segment.range.start,
                    length = segment.range.length
                )'''
new='''                cache.prefetchRange(
                    url = winner,
                    cacheKey = request.trackCacheKey,
                    position = segment.range.start,
                    length = segment.range.length,
                    headers = PLAYBACK_HEADERS
                )'''
assert body.count(old)==1;body=body.replace(old,new)
out=H/'prepared/prerequisites/com/android/purebilibili/feature/plugin/CdnDashSegmentPrefetcher.kt';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(body,encoding='utf-8',newline='\n')
(H/'cdn-prefetch-boundary.json').write_text(json.dumps(dict(preparedOnly=True,originalPath=path,originalSha256LF=hashlib.sha256(raw.encode()).hexdigest(),outputSha256Bytes=hashlib.sha256(body.encode()).hexdigest(),retained=['Original segment-count/index/frontier/SIDX/range/rank/winner algorithm','Same captured Call.Factory'],requiredNotYetImplemented='Existing playback segment cache range writer/read consumer; MPV demux cache does not accept Media3 CacheWriter ranges',noFakeSuccess=True),indent=2)+'\n',encoding='utf-8')

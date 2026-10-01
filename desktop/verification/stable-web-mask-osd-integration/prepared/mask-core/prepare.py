from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';ADV=L.parent/'stable-danmaku-render-config-consumers-parity';MON=L.parent/'stable-danmaku-monitor-passive-delta'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,o):write(p,json.dumps(o,ensure_ascii=False,indent=2)+'\n')
H=[];B=[]
def patch(path,s,old,new,reason):
 assert s.count(old)==1,(path,old[:180],s.count(old));out=s.replace(old,new)
 H.append(dict(path=path,old=old,new=new,beforeSha256LF=sha(s),afterSha256LF=sha(out),reason=reason));return out
producer='desktop/tools/extract-upstream-danmaku-list-menu.py'
old=read(ADV/'prepared'/producer);write(L/'baseline'/producer,old);B.append(dict(path=producer,sha256LF=sha(old),canonical='Advanced213 sole producer'))
s=patch(producer,old,"BASE+'feature/video/ui/overlay/LiveDanmakuOverlay.kt','danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt']","BASE+'feature/video/ui/overlay/LiveDanmakuOverlay.kt','danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt',BASE+'feature/video/danmaku/WebMaskParser.kt',BASE+'feature/video/danmaku/DanmakuPlaybackSyncPolicy.kt']",'Pin complete parser and original refresh interval source to the existing sole producer')
extension=r'''
 # Complete original MASK index/gzip/base64/SVG framing parser. Only its real Windows path carrier changes.
 raw=source[PATHS[15]];rows=[];s=raw
 s=adapt(s,'import android.graphics.Path','import com.bilipai.desktop.danmaku.DesktopWebMaskPath as Path',rows)
 s=adapt(s,'import androidx.core.graphics.PathParser','import com.bilipai.desktop.danmaku.DesktopWebMaskPath as PathParser',rows)
 emit('com/android/purebilibili/feature/video/danmaku/WebMaskParser.kt',s,PATHS[15],'selected-full-original-web-mask-parser',rows,raw)
 models=source[PATHS[11]];start=models.index('data class DanmakuMaskFrame(');end=balanced(masked(models),models.index('(',start));raw=models[start:end]
 emit('com/android/purebilibili/danmaku/engine/DanmakuMaskFrame.kt','package com.android.purebilibili.danmaku.engine\nimport com.bilipai.desktop.danmaku.DesktopWebMaskPath as Path\n\n'+raw+'\n',PATHS[11],'selected-complete-mask-frame-schema-platform-path')
 # Same sole bounded special route; no Retrofit/HTTP/client creation.
 raw=function(source[PATHS[7]],'getWebMask')[0];rows=[];s=raw
 s=adapt(s,'    suspend fun getWebMask(url: String)','internal suspend fun getDesktopOriginalWebMask(source: com.bilipai.desktop.danmaku.DesktopDanmakuSource, url: String)',rows)
 s=adapt(s,'api.getDanmakuSpecialDm(resolvedUrl).bytes()','source.special(resolvedUrl)',rows)
 s=adapt(s,'            android.util.Log.w("DanmakuRepo", "Webmask fetch failed: ${e.message}")','            // Optional mask failure: existing source transport keeps bounded-body ownership.',rows)
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalWebMaskProtocol.kt','package com.android.purebilibili.feature.video.danmaku\nimport kotlinx.coroutines.*\n\n'+s+'\n',PATHS[7],'selected-full-original-get-web-mask',rows)
 # Original window methods are members of the existing Overlay via a scope-free inherited owner.
 manager=source[PATHS[5]];members=[];rows=[];selected=[]
 raw=function(manager,'shouldApplyDanmakuLoadResult','')[0];members.append(raw);selected.append(dict(method='shouldApplyDanmakuLoadResult',originalSha256LF=sha(raw),adaptedSha256LF=sha(raw),adaptations=[]))
 raw=function(manager,'loadWebMask')[0];patches=[];body=raw
 body=adapt(body,'com.android.purebilibili.data.repository.DanmakuRepository.getWebMask(url)','getDesktopOriginalWebMask(webMaskTransport, url)',patches)
 body=adapt(body,'        if (!shouldApplyDanmakuLoadResult(cid, requestGeneration, cachedCid, loadGeneration)) return','        kotlinx.coroutines.currentCoroutineContext().ensureActive()\n        synchronized(webMaskLock) {\n        if (!webMaskEnabled || !owned(cid, requestGeneration)) return',patches)
 body=adapt(body,'        applyConfigToController("webmask_ready")','        invalidateWebMaskPaint()',patches)
 body=adapt(body,'\n    }','\n        }\n    }',patches)
 selected.append(dict(method='loadWebMask',originalSha256LF=sha(raw),adaptedSha256LF=sha(body),adaptations=patches,extraAdaptation='atomic same Overlay lock admission around publication and request'))
 members.append(body)
 raw=function(manager,'requestWebMaskWindow')[0];patches=[];body=raw
 body=adapt(body,'if (!config.smartOcclusionEnabled) return','if (!webMaskEnabled || !owned(expectedCid, requestGeneration)) return',patches)
 guard='        if (\n            positionMs >= webMaskWindowStartMs + WEB_MASK_REFRESH_GUARD_MS &&\n            positionMs <= webMaskWindowEndMs - WEB_MASK_REFRESH_GUARD_MS\n        ) return'
 body=adapt(body,guard,'        if (isWithinWebMaskWindowGuard(positionMs)) return',patches)
 body=adapt(body,'            controller?.replaceMaskFrames(frames, positionMs)','            kotlinx.coroutines.currentCoroutineContext().ensureActive()\n            replaceMaskFrames(frames, positionMs, expectedCid, requestGeneration, requestWindowGeneration)',patches)
 members.append(body);selected.append(dict(method='requestWebMaskWindow',originalSha256LF=sha(raw),adaptedSha256LF=sha(body),adaptations=patches))
 a=manager.index('    private fun isCurrentSegmentWindowRequest(');b=manager.index('\n    private fun cancelObsoleteWindowRequest',a);raw=manager[a:b];patches=[]
 body=adapt(raw,'    ) && requestWindowGeneration == windowGeneration','    ) && requestWindowGeneration == windowGeneration && owned()',patches)
 members.append(body);selected.append(dict(method='isCurrentSegmentWindowRequest',originalSha256LF=sha(raw),adaptedSha256LF=sha(body),adaptations=patches))
 template=WEB_MASK_OWNER_TEMPLATE
 for name in ['WEB_MASK_LOOK_BEHIND_MS','WEB_MASK_LOOK_AHEAD_MS','WEB_MASK_REFRESH_GUARD_MS']:
  line=re.search(r'(?m)^        private const val '+name+r' = [^\n]+',manager).group(0)
  assert line in template,name
 originalGuardExpression=guard[guard.index('positionMs'):guard.index('\n        )')].strip()
 template=template.replace('// SELECTED_ORIGINAL_WINDOW_GUARD',originalGuardExpression)
 template=template.replace('// SELECTED_ORIGINAL_METHODS','\n\n'.join(members[1:]))
 template=template.replace('\n/** The existing Overlay', '\n'+members[0]+'\n\n/** The existing Overlay')
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalWebMaskOwner.kt',template,PATHS[5],'selected-full-original-mask-window-methods-same-overlay-owner',selected)
 sync=source[PATHS[16]];raw=function(sync,'normalizeDanmakuPlaybackSpeed','')[0]+'\n\n'+function(sync,'resolveDanmakuDriftSyncIntervalMs','')[0]
 constants=[re.search(r'(?m)^private const val '+name+r' = [^\n]+',sync).group(0) for name in ['MIN_ENGINE_PLAYBACK_SPEED','MAX_ENGINE_PLAYBACK_SPEED','NORMAL_SYNC_INTERVAL_MS']]
 imports='package com.android.purebilibili.feature.video.danmaku\n'+'\n'.join(constants)+'\n\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalWebMaskRefreshPolicy.kt',imports+raw+'\n',PATHS[16],'selected-complete-original-refresh-interval-and-normalization')
'''
s=patch(producer,s,' inventory=dict(upstreamCommit=COMMIT',extension+'\n inventory=dict(upstreamCommit=COMMIT','Full parser/model/protocol/window methods/refresh policy without a second scope/client/store')
template=read(L/'owner-template.kt')
s=patch(producer,s,"COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'","WEB_MASK_OWNER_TEMPLATE="+repr(template)+"\n\nCOMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'",'Embed fixed explicit Windows lifecycle platform seam, selected methods retain separate origin hashes')
s=patch(producer,s,"'Native smart mask acquisition/bitmap ownership','Transparent native overlay pointer/hit testing'","'Actual Root smart SVG mask/native paint acceptance','Separate command-vote native acceptance (ordinary original layers are passive)'",'Truthful source-only pending scope; standard mask is now acquired and painted, ordinary passive default already source-proven')
write(L/'prepared'/producer,s)
spec=importlib.util.spec_from_file_location('candidate',L/'prepared'/producer);p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p);inventory=p.generate(ROOT,L/'generated',False)
for row in inventory['sources']:
 path=row['path'];blob=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=ROOT).decode('utf-8').replace('\r\n','\n');write(L/'original-stable'/path,blob)
save(L/'source-inventory.json',inventory)
# Actual31 source consumers, with post213+monitor Overlay bytes verified separately in audit.
for path in ['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt','desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt','desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDanmakuSettingsProjection.kt','desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopOriginalDanmakuRenderPlatform.kt']:
 write(L/'baseline'/path,read(ROOT/path));B.append(dict(path=path,sha256LF=sha(read(ROOT/path)),canonical='Actual31 complete source capture, exact LOCAL hunks only'))
save(L/'baseline-manifest.json',B);save(L/'local-hunks.json',H)
print('GENERATED',len(inventory['emitted']),'source parents',len(inventory['sources']))

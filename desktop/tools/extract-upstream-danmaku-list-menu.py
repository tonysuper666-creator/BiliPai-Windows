from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,json,re,subprocess,sys

def sha(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()
def write(p,s): p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def save(p,s): write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def masked(text):
 out=list(text);i=0;n=len(text)
 while i<n:
  if text.startswith('//',i): end=text.find('\n',i);end=n if end<0 else end
  elif text.startswith('/*',i):
   end=i+2;depth=1
   while end<n and depth:
    if text.startswith('/*',end):depth+=1;end+=2
    elif text.startswith('*/',end):depth-=1;end+=2
    else:end+=1
  elif text.startswith('"""',i):end=text.find('"""',i+3);assert end>=0;end+=3
  elif text[i] in ('"',"'"):
   quote=text[i];end=i+1
   while end<n:
    if text[end]=='\\':end+=2
    elif text[end]==quote:end+=1;break
    else:end+=1
  else:i+=1;continue
  for j in range(i,end):
   if out[j]!='\n':out[j]=' '
  i=end
 return ''.join(out)
def balanced(mask,start,left='(',right=')'):
 assert mask[start]==left;depth=0
 for i in range(start,len(mask)):
  if mask[i]==left:depth+=1
  elif mask[i]==right:
   depth-=1
   if not depth:return i+1
 raise ValueError('unclosed token')
def function(text,name,indent='    ',occurrence=0):
 mask=masked(text);matches=list(re.finditer('(?m)^'+indent+r'(?:(?:private|internal|suspend|inline)\s+)*fun(?:\s*<[^>]*>)?\s+'+name+r'\s*\(',mask));assert len(matches)>occurrence,name;m=matches[occurrence]
 a=mask.index('(',m.start());b=balanced(mask,a);c=mask.index('{',b);d=balanced(mask,c,'{','}')
 return text[m.start():d],m.start(),d
def adapt(text,before,after,rows):
 assert text.count(before)==1,(before,text.count(before));rows.append(dict(before=before,after=after));return text.replace(before,after)

WEB_MASK_OWNER_TEMPLATE='package com.android.purebilibili.feature.video.danmaku\n\nimport com.android.purebilibili.danmaku.engine.DanmakuMaskFrame\nimport com.bilipai.desktop.danmaku.DesktopDanmakuSource\nimport com.bilipai.desktop.danmaku.DesktopOwnedWebMaskSource\nimport kotlinx.coroutines.*\n\n/** The existing Overlay inherits these original mask members; this creates no scope, actor or transport. */\nabstract class DesktopOriginalWebMaskOwner internal constructor() {\n    protected abstract val webMaskLock: Any\n    protected abstract val scope: CoroutineScope\n    protected abstract val cachedCid: Long\n    protected abstract val loadGeneration: Long\n    protected abstract val webMaskEnabled: Boolean\n    protected abstract val webMaskTransport: DesktopDanmakuSource\n    protected abstract fun webMaskPositionMs(): Long\n    protected abstract fun invalidateWebMaskPaint()\n    private var target: DesktopOwnedWebMaskSource? = null\n    private var maskLoadJob: Job? = null\n    private var maskFetchJob: Job? = null\n    private var windowGeneration: Long = 0L\n    private var webMaskBytes: ByteArray? = null\n    private var webMaskFps: Int = 0\n    private var webMaskWindowStartMs: Long = Long.MIN_VALUE\n    private var webMaskWindowEndMs: Long = Long.MIN_VALUE\n    private var currentMaskFrames: List<DanmakuMaskFrame> = emptyList()\n    private var publishedWindowGeneration = Long.MIN_VALUE\n    private var toggleGeneration = 0L\n\n    internal fun bindWebMaskSource(next: DesktopOwnedWebMaskSource?) = synchronized(webMaskLock) {\n        retireWebMaskSource()\n        target = next\n        if (next != null && next.cid == cachedCid && webMaskEnabled && next.stillOwned()) fetchWebMask()\n    }\n\n    internal fun retireWebMaskSource() = synchronized(webMaskLock) {\n        ++toggleGeneration; ++windowGeneration\n        maskFetchJob?.cancel(); maskFetchJob = null\n        maskLoadJob?.cancel(); maskLoadJob = null\n        target = null\n        webMaskBytes = null; webMaskFps = 0\n        webMaskWindowStartMs = Long.MIN_VALUE; webMaskWindowEndMs = Long.MIN_VALUE\n        currentMaskFrames = emptyList(); publishedWindowGeneration = Long.MIN_VALUE\n        invalidateWebMaskPaint()\n    }\n\n    internal fun onWebMaskSettingChanged() = synchronized(webMaskLock) {\n        ++toggleGeneration; ++windowGeneration\n        maskFetchJob?.cancel(); maskFetchJob = null\n        maskLoadJob?.cancel(); maskLoadJob = null\n        currentMaskFrames = emptyList(); publishedWindowGeneration = Long.MIN_VALUE\n        webMaskWindowStartMs = Long.MIN_VALUE; webMaskWindowEndMs = Long.MIN_VALUE\n        if (webMaskEnabled && owned()) {\n            if (webMaskBytes == null) fetchWebMask() else requestWebMaskWindow(webMaskPositionMs())\n        }\n        invalidateWebMaskPaint()\n    }\n\n    internal fun refreshWebMaskWindow(positionMs: Long) = synchronized(webMaskLock) {\n        if (!owned()) { if (target != null) retireWebMaskSource(); return@synchronized }\n        if (!webMaskEnabled) return@synchronized\n        // A replacement parse retires the previous decode before its owned publication.\n        if (!isWithinWebMaskWindowGuard(positionMs)) {\n            ++windowGeneration\n            requestWebMaskWindow(positionMs)\n        }\n    }\n\n    internal fun validateWebMaskOwnership() = synchronized(webMaskLock) {\n        if (target != null && !owned()) retireWebMaskSource()\n    }\n\n    internal fun onWebMaskSegmentWindowChanged() = synchronized(webMaskLock) {\n        ++windowGeneration; maskLoadJob?.cancel(); maskLoadJob = null\n        webMaskWindowStartMs = Long.MIN_VALUE; webMaskWindowEndMs = Long.MIN_VALUE\n    }\n\n    internal fun currentWebMaskFrame(positionMs: Long): DanmakuMaskFrame? = synchronized(webMaskLock) {\n        if (!webMaskEnabled || !owned() || publishedWindowGeneration != windowGeneration) return@synchronized null\n        // Windows compositor uses the current half-open frame, so a shared boundary cannot union two masks.\n        currentMaskFrames.lastOrNull { positionMs >= it.startTimeMs && positionMs < it.endTimeMs }\n    }\n\n    internal fun webMaskAvailable():Boolean = synchronized(webMaskLock) {webMaskEnabled && owned() && webMaskBytes!=null}\n\n    private fun owned(): Boolean = target?.let { it.cid == cachedCid && it.stillOwned() } == true\n    private fun isWithinWebMaskWindowGuard(positionMs:Long):Boolean {\n        if(webMaskWindowStartMs==Long.MIN_VALUE || webMaskWindowEndMs==Long.MIN_VALUE || webMaskWindowEndMs<=webMaskWindowStartMs)return false\n        return // SELECTED_ORIGINAL_WINDOW_GUARD\n    }\n    private fun owned(cid: Long, generation: Long): Boolean =\n        shouldApplyDanmakuLoadResult(cid, generation, cachedCid, loadGeneration) && owned()\n    private fun fetchWebMask() {\n        val source = target ?: return\n        val expectedCid = cachedCid\n        val expectedGeneration = loadGeneration\n        val expectedToggleGeneration = toggleGeneration\n        maskFetchJob?.cancel()\n        maskFetchJob = scope.launch {\n            try {\n                if (!source.stillOwned()) return@launch\n                val maskInfo = source.metadata(source.bvid, expectedCid).dmMask ?: return@launch\n                ensureActive()\n                if (!synchronized(webMaskLock) { owned(expectedCid, expectedGeneration) && webMaskEnabled && expectedToggleGeneration == toggleGeneration }) return@launch\n                loadWebMask(expectedCid, maskInfo.maskUrl, maskInfo.fps, webMaskPositionMs(), expectedGeneration)\n            } catch (cancelled: CancellationException) { throw cancelled }\n            catch (_: Exception) { /* Same optional metadata failure behavior as original Result.getOrNull(). */ }\n        }\n    }\n\n    private fun replaceMaskFrames(frames: List<DanmakuMaskFrame>, positionMs: Long, expectedCid: Long, requestGeneration: Long, requestWindowGeneration: Long) = synchronized(webMaskLock) {\n        if (!webMaskEnabled || !isCurrentSegmentWindowRequest(expectedCid, requestGeneration, requestWindowGeneration)) return@synchronized\n        // A retained window is limited to the original 40s at max60fps (+ two inclusive parser boundaries).\n        // Path commands are also bounded in the actual Windows carrier; rejected optional masks leave video/danmaku active.\n        currentMaskFrames = if(frames.size>MAX_CACHED_MASK_FRAMES || frames.sumOf {it.path.commandCount.toLong()}>MAX_CACHED_PATH_COMMANDS)emptyList()\n            else frames.sortedBy(DanmakuMaskFrame::startTimeMs)\n        publishedWindowGeneration = requestWindowGeneration\n        invalidateWebMaskPaint()\n    }\n\n// SELECTED_ORIGINAL_METHODS\n\n    companion object {\n        private const val WEB_MASK_LOOK_BEHIND_MS = 10_000L\n        private const val WEB_MASK_LOOK_AHEAD_MS = 30_000L\n        private const val WEB_MASK_REFRESH_GUARD_MS = 5_000L\n        private const val MAX_CACHED_MASK_FRAMES = 2402\n        private const val MAX_CACHED_PATH_COMMANDS = 2_000_000L\n    }\n}\n'

COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
PATHS=[BASE+x for x in ['feature/video/ui/components/DanmakuPoolSheet.kt','feature/video/ui/components/DanmakuContextMenu.kt','feature/video/viewmodel/VideoPlaybackViewModel.kt','feature/video/danmaku/DanmakuParser.kt','feature/video/danmaku/WeightedTextData.kt','feature/video/danmaku/DanmakuManager.kt','feature/video/danmaku/DanmakuConfig.kt','data/repository/DanmakuRepository.kt','core/store/SettingsManager.kt','feature/video/screen/VideoDetailOverlayHost.kt','feature/video/ui/section/VideoPlayerSection.kt']]+['danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/DanmakuModels.kt',BASE+'feature/video/danmaku/FaceOcclusionPolicy.kt',BASE+'feature/video/ui/overlay/LiveDanmakuOverlay.kt','danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt',BASE+'feature/video/danmaku/WebMaskParser.kt',BASE+'feature/video/danmaku/DanmakuPlaybackSyncPolicy.kt']

V027_CONFIG_COMMIT='e5a6b59a69ed2de9ea4e69dc7675ea05de495ebf'
V027_CONFIG_ARCHIVE=Path('desktop/upstream-slices/v027-danmaku-config')
V027_CONFIG_SHA256='147f68bcc1ed0517d1b25476254cf6a55cc9d8f09d82003027ed6f92752adfd4'

def fixed_v027_config(repo):
 # One explicit complete source slice; the canonical v025 files and pins above
 # remain checked against their original commit, not silently replaced.
 import os
 def raw(path):
  path=Path(path).resolve()
  return (Path('\\\\?\\'+str(path)) if os.name=='nt' else path).read_bytes()
 root=repo/V027_CONFIG_ARCHIVE
 manifest=json.loads(raw(root/'manifest.json'))
 assert manifest.get('schemaVersion')==1 and manifest.get('fixedUpstreamCommit')==V027_CONFIG_COMMIT,'Unknown v027 config manifest'
 assert manifest.get('originalPath')==PATHS[6] and manifest.get('archiveFile')=='DanmakuConfig.kt','Unknown v027 config source'
 assert manifest.get('sha256Bytes')==V027_CONFIG_SHA256,'Changed v027 config pin'
 blob=raw(root/'DanmakuConfig.kt')
 assert hashlib.sha256(blob).hexdigest()==V027_CONFIG_SHA256 and len(blob)==manifest.get('bytes'),'Changed fixed v027 config bytes'
 normalized=blob.replace(b'\r\n',b'\n')
 assert hashlib.sha256(normalized).hexdigest()==manifest.get('sha256LF'),'Changed v027 config normalized identity'
 return normalized.decode('utf-8'),dict(path=(V027_CONFIG_ARCHIVE/'DanmakuConfig.kt').as_posix(),previousPath=PATHS[6],
  pinnedCommit=V027_CONFIG_COMMIT,sha256Bytes=V027_CONFIG_SHA256,sha256LF=hashlib.sha256(normalized).hexdigest(),
  mode='explicit-complete-v027-config-slice-not-overall-canonical-advance')

def class_body(s,name,indent=''):
 m=re.search(r'(?m)^'+indent+r'(?:(?:internal|private|open|data|enum)\s+)*class '+name+r'\b',masked(s));assert m,name
 mask=masked(s);a=mask.index('{',m.start());b=balanced(mask,a,'{','}');return s[m.start():b]
def drop_logs(body,patches):
 while True:
  mask=masked(body);m=re.search(r'(?:com\.android\.purebilibili\.core\.util\.Logger|android\.util\.Log)\.[dwei]\s*\(',mask)
  if not m:return body
  a=body.rfind('\n',0,m.start())+1;b=balanced(mask,mask.index('(',m.start()))
  assert not body[a:m.start()].strip();before=body[a:b]+'\n';assert body.count(before)==1
  body=adapt(body,before,'',patches)

def adapt_complete_v027_config(full_config):
 # Complete original configuration and every pure policy are retained. These are
 # the existing required Windows font/chrome/log carriers, not reserve formulas.
 rows=[];s=full_config
 s=adapt(s,'import android.content.Context','import com.bilipai.desktop.danmaku.DesktopOriginalDanmakuRenderPlatform',rows)
 s=adapt(s,'import android.graphics.Typeface','import java.awt.Font as Typeface',rows)
 s=adapt(s,'import android.os.Build','import com.bilipai.desktop.danmaku.DesktopDanmakuConfigLog',rows)
 s=adapt(s,'class DanmakuConfig {','class DanmakuConfig internal constructor(private val platform: DesktopOriginalDanmakuRenderPlatform) {',rows)
 s=adapt(s,'typeface = resolveDanmakuTypeface(fontWeight),','typeface = resolveDanmakuTypeface(fontWeight, platform),',rows)
 s=adapt(s,'strokeColor = android.graphics.Color.BLACK,','strokeColor = java.awt.Color.BLACK.rgb,',rows)
 status=function(full_config,'getStatusBarHeight','        ')[0]
 s=adapt(s,status,'        internal fun getStatusBarHeight(platform: DesktopOriginalDanmakuRenderPlatform): Int = platform.systemChromeInsetPx()',rows)
 font=function(full_config,'resolveDanmakuTypeface','')[0]
 s=adapt(s,font,'internal fun resolveDanmakuTypeface(fontWeight: Int, platform: DesktopOriginalDanmakuRenderPlatform): Typeface = platform.resolveTypeface(fontWeight)',rows)
 s=adapt(s,'        android.util.Log.i(','        DesktopDanmakuConfigLog.i(',rows)
 reverse=s
 for patch in reversed(rows):
  assert patch['after'] and reverse.count(patch['after'])==1
  reverse=reverse.replace(patch['after'],patch['before'])
 assert reverse==full_config,'Complete fixed-v027 config inverse failed'
 return s,rows

V029_CONFIG_COMMIT='a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
V029_CONFIG_ARCHIVE=Path('desktop/upstream-slices/v029-danmaku-config')
V029_CONFIG_MANIFEST_SHA256='cabd015637886290b93dd357d247ccedb919d605fc0d4459337588a47d97706f'
V029_CONFIG_PINS={'DanmakuConfig.kt': ('app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuConfig.kt', '75e2ec45b13d175b35f5a477c99f38cff007e11776414ea68062c3439e532c37'), 'DanmakuConfigPolicyTest.kt': ('app/src/test/java/com/android/purebilibili/feature/video/danmaku/DanmakuConfigPolicyTest.kt', '427e5c4c7a8ab91965e6525abc5edc47a68440a2b26265d85ceee8a7a9fafd64'), 'LiveDanmakuOverlay.kt': ('app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/LiveDanmakuOverlay.kt', '5a2006af013f34f13b11d02adfb4d6b6642d11f6baa9a1d4ecef193969ed0c99')}

def fixed_v029_config_files(repo):
 root=repo/V029_CONFIG_ARCHIVE
 raw=(root/'manifest.json').read_bytes()
 assert hashlib.sha256(raw).hexdigest()==V029_CONFIG_MANIFEST_SHA256,'Changed v029 config manifest bytes'
 manifest=json.loads(raw)
 assert manifest.get('schemaVersion')==1 and manifest.get('fixedUpstreamCommit')==V029_CONFIG_COMMIT,'Unknown v029 config manifest'
 rows=manifest.get('files',[])
 assert len(rows)==3 and {r['archiveFile'] for r in rows}==set(V029_CONFIG_PINS),'Unknown v029 source set'
 source={};identities=[]
 for row in rows:
  name=row['archiveFile'];original_path,pin=V029_CONFIG_PINS[name]
  assert row['originalPath']==original_path and row['sha256Bytes']==pin,'Changed v029 config source pin'
  blob=(root/name).read_bytes();normalized=blob.replace(b'\r\n',b'\n')
  assert hashlib.sha256(blob).hexdigest()==pin and len(blob)==row['bytes'],'Changed fixed v029 source bytes'
  assert hashlib.sha256(normalized).hexdigest()==row['sha256LF'],'Changed v029 source LF bytes'
  assert hashlib.sha1(b'blob '+str(len(blob)).encode()+b'\0'+blob).hexdigest()==row['gitBlob'],'Changed v029 Git blob'
  source[name]=normalized.decode('utf-8')
  identities.append(dict(path=(V029_CONFIG_ARCHIVE/name).as_posix(),previousPath=original_path,pinnedCommit=V029_CONFIG_COMMIT,sha256Bytes=pin,sha256LF=row['sha256LF'],mode='explicit-v029-font-config-slice-not-overall-canonical-advance'))
 return source,identities

def adapt_complete_v029_config(full_config):
 s,rows=adapt_complete_v027_config(full_config)
 # Compatibility for existing original callers; production supplies the actual Root presentation explicitly.
 s=adapt(s,'fun resolveRenderConfig(viewport: DanmakuViewport, isFullscreen: Boolean):',
     'fun resolveRenderConfig(viewport: DanmakuViewport, isFullscreen: Boolean = false):',rows)
 reverse=s
 for patch in reversed(rows):
  assert reverse.count(patch['after'])==1
  reverse=reverse.replace(patch['after'],patch['before'])
 assert reverse==full_config,'Complete fixed-v029 config inverse failed'
 return s,rows

def generate(repo:Path,output:Path,standalone=False):
 source={};identities=[];emitted=[]
 for path in PATHS:
  from v025_source_paths import canonical_source
  canonical_file=canonical_source(repo,path)
  canonical_path=canonical_file.relative_to(repo.resolve()).as_posix()
  blob=subprocess.check_output(['git','show',COMMIT+':'+canonical_path],cwd=repo).decode('utf-8').replace('\r\n','\n')
  assert canonical_file.read_text(encoding='utf-8').replace('\r\n','\n')==blob,path
  source[path]=blob;identities.append(dict(path=canonical_path,previousPath=path,pinnedCommit=COMMIT,sha256LF=sha(blob),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+canonical_path],cwd=repo,text=True).strip()))
 v029_source,v029_identities=fixed_v029_config_files(repo)
 full_config=v029_source['DanmakuConfig.kt'];config_identity=v029_identities[0]
 adapted_config,config_patches=adapt_complete_v029_config(full_config)
 from v030_up_danmaku import fixed_v030_up_files,original_up_badge_adapter,COMMIT as V030_UP_COMMIT
 v030_source,v030_identities=fixed_v030_up_files()
 source[PATHS[0]]=v030_source["DanmakuPoolSheet.kt"]
 source[PATHS[11]]=v030_source["DanmakuModels.kt"]
 identities.extend(dict(path=row["upstreamPath"],pinnedCommit=row["commit"],sha256Raw=row["sha256"],gitBlob=row["gitBlob"]) for row in v030_identities)
 def emit(path,text,origin,mode,patches=None,original=None):
  write(output/path,text);row=dict(path=path,origin=origin,mode=mode,sha256LF=sha(text),adaptations=patches or [])
  if origin in [r['upstreamPath'] for r in v030_identities]:row['selectedUpstreamCommit']=V030_UP_COMMIT
  if original is not None:
   reverse=text
   for p in reversed(patches or []):
    assert p['after'] and reverse.count(p['after'])==1
    reverse=reverse.replace(p['after'],p['before'])
   assert reverse==original,path;row['reverseNormalizedOriginalByteEqual']=True
  emitted.append(row)
 badge_body,badge_proof=original_up_badge_adapter(v030_source["TextDrawItem.kt"],function,adapt)
 emit("com/bilipai/desktop/danmaku/DesktopOriginalUpDanmakuBadge.kt",badge_body,"danmaku-engine/src/main/java/com/bytedance/danmaku/render/engine/render/draw/text/TextDrawItem.kt","selected-complete-v030-UP-measure-and-draw-functions")
 save(output/"v030-up-badge-adaptation.json",dict(upstreamCommit=V030_UP_COMMIT,functions=badge_proof,completeFunctionInverse=True))
 # Full fixed-v030 original pool/model; same-send component belongs to its existing unique HotBar producer.
 s=source[PATHS[0]];rows=[]
 for line in ['import android.content.ClipData\n','import android.content.ClipboardManager\n','import android.content.Context\n','import android.widget.Toast\n']:
  s=adapt(s,line,'// Windows port: '+line.rstrip()+'\n',rows)
 s=adapt(s,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopDanmakuBindings as LocalContext',rows)
 s=adapt(s,'import com.android.purebilibili.core.ui.AppModalBottomSheet','import com.bilipai.desktop.ui.DesktopWindowsDanmakuPoolSheet as AppModalBottomSheet',rows)
 before='''                                Toast.makeText(
                                    context,
                                    "已跳转至 ${FormatUtils.formatDuration(item.showAtTime)}",
                                    Toast.LENGTH_SHORT
                                ).show()'''
 s=adapt(s,before,'                                context.showFeedback("已跳转至 ${FormatUtils.formatDuration(item.showAtTime)}")',rows)
 before='''                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("danmaku", item.text.orEmpty())
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "已复制弹幕内容", Toast.LENGTH_SHORT).show()'''
 s=adapt(s,before,'                                    context.copyText(item.text.orEmpty(), "danmaku")\n                                    context.showFeedback("已复制弹幕内容")',rows)
 s=adapt(s,'.clickable(onClick = onItemClick)', '.combinedClickable(onClick = onItemClick, onLongClick = onLongClick)',rows)
 s=adapt(s,'import androidx.compose.foundation.clickable','import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.combinedClickable',rows)
 emit('com/android/purebilibili/feature/video/ui/components/DanmakuPoolSheet.kt',s,PATHS[0],'selected-full-renderer',rows,source[PATHS[0]])
 # Complete menu/report/recall/copy/select-text/timestamp/block UI and policies.
 s=source[PATHS[1]];rows=[]
 s=adapt(s,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopDanmakuBindings as LocalContext',rows)
 s=adapt(s,'import com.android.purebilibili.core.ui.common.copyPlainTextToClipboard','import com.bilipai.desktop.ui.copyDesktopDanmakuText as copyPlainTextToClipboard',rows)
 s=adapt(s,'import com.android.purebilibili.core.ui.common.TextSelectionBottomSheet','import com.bilipai.desktop.ui.DesktopDanmakuTextSelection as TextSelectionBottomSheet',rows)
 emit('com/android/purebilibili/feature/video/ui/components/DanmakuContextMenu.kt',s,PATHS[1],'selected-full-renderer',rows,source[PATHS[1]])
 # Complete original renderer-neutral model. Only irrelevant platform bitmap/font carriers use their actual AWT types.
 original=source[PATHS[11]];model=original[:original.index('\ndata class DanmakuWindow(')]
 rows=[];s=adapt(model,'import android.graphics.Bitmap','import java.awt.image.BufferedImage as Bitmap',rows)
 s=adapt(s,'import android.graphics.Typeface','import java.awt.Font as Typeface',rows)
 s=adapt(s,'import android.graphics.Path','// Android Path is outside this selected standard-item class.',rows)
 emit('com/android/purebilibili/danmaku/engine/DanmakuItem.kt',s,PATHS[11],'selected-complete-schema',rows,model)
 weighted=source[PATHS[4]]
 if standalone:emit('com/android/purebilibili/feature/video/danmaku/WeightedTextData.kt',weighted,PATHS[4],'direct',[],weighted)
 parser=source[PATHS[3]];methods=[function(parser,n)[0] for n in ['createTextDataFromProto','formatDanmakuTextWithCount','createTextData','mapLayerType']]
 # Expose the original private factories to the Windows source projection only; bodies remain byte-identical.
 methods[0]=methods[0].replace('private fun createTextDataFromProto','fun createTextDataFromProto');methods[2]=methods[2].replace('private fun createTextData(','fun createTextData(')
 manager=source[PATHS[5]];m=re.search(r'(?m)^internal fun resolveDanmakuClickUserHash[^\n]*',manager);assert m
 policies=[m.group(0),function(manager,'resolveDanmakuClickIsSelf','')[0]]
 # The full canonical parser owns its original font-grade policy once.
 body='''package com.android.purebilibili.feature.video.danmaku
import com.android.purebilibili.danmaku.engine.*
import com.android.purebilibili.danmaku.parser.*
import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.danmaku.parser.WeightedTextData
import com.android.purebilibili.danmaku.parser.resolveBilibiliDanmakuFontScale

'''+ '\n\n'.join(policies)+'\n\ninternal object DesktopOriginalDanmakuItemParser {\n'+'\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuItemParser.kt',body,PATHS[3],'selected-original-factories-and-click-policies')

 # Full original neutral config and pure geometry/timing algorithms. Only actual Windows platform carriers change.
 identities.extend(v029_identities)
 emit('com/android/purebilibili/feature/video/danmaku/DanmakuConfig.kt',adapted_config,config_identity['path'],'fixed-v029-full-config-with-required-windows-platform',config_patches,full_config)
 emitted[-1]['upstreamCommit']=V029_CONFIG_COMMIT
 # Full original constructor, preserving every default except the required real platform font carrier.
 models=source[PATHS[11]];start=models.index('data class DanmakuRenderConfig(')
 end=balanced(masked(models),models.index('(',start));raw=models[start:end];rows=[]
 model=adapt(raw,'val typeface: Typeface? = Typeface.DEFAULT,','val typeface: Typeface,',rows)
 model='package com.android.purebilibili.danmaku.engine\nimport java.awt.Font as Typeface\n\n'+model+'\n'
 emit('com/android/purebilibili/danmaku/engine/DanmakuRenderConfig.kt',model,PATHS[11],'selected-complete-neutral-render-schema',rows)
 band=class_body(source[PATHS[12]],'DanmakuDisplayBand')
 emit('com/android/purebilibili/feature/video/danmaku/DanmakuDisplayBand.kt','package com.android.purebilibili.feature.video.danmaku\n\n'+band+'\n',PATHS[12],'selected-complete-original-display-band')
 manager=source[PATHS[5]]
 render_layer=function(manager,'resolveDanmakuRenderLayerType','')[0]
 original_map=function(manager,'mapLayerTypeToDanmakuType')[0]
 layer_map=original_map.replace('    private fun','internal fun',1)
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuLayerPolicy.kt','package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.danmaku.engine.*\n\n'+render_layer+'\n\n'+layer_map+'\n',PATHS[5],'selected-complete-layer-mapping')
 live=v029_source['LiveDanmakuOverlay.kt'];start=live.index('            val textSize = resolveDanmakuTextSizePx(');end=live.index('\n        }\n    )',start);raw=live[start:end];rows=[];s=raw
 s=adapt(s,'            view.engine.updateConfig(','            return (',rows)
 s=adapt(s,'typeface = resolveDanmakuTypeface(danmakuSettings.fontWeight),','typeface = resolveDanmakuTypeface(danmakuSettings.fontWeight, platform),',rows)
 s=adapt(s,'speedFactor = danmakuSettings.speed,','speedFactor = danmakuSettings.speedFactor,',rows)
 s=adapt(s,'viewportWidthPx = view.width','viewportWidthPx = viewWidthPx',rows)
 s=adapt(s,'visibleHeightPx = view.height.toFloat(),','visibleHeightPx = viewHeightPx.toFloat(),',rows)
 s=adapt(s,'val strokeWidth = resolveDanmakuStrokeWidthPx(density, danmakuSettings.strokeWidth)','val strokeWidth = if(danmakuSettings.strokeEnabled)resolveDanmakuStrokeWidthPx(density, danmakuSettings.strokeWidth) else 0f',rows)
 body='package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.danmaku.engine.DanmakuRenderConfig\nimport com.bilipai.desktop.danmaku.DanmakuSettings\nimport com.bilipai.desktop.danmaku.DesktopOriginalDanmakuRenderPlatform\n\ninternal fun resolveDesktopOriginalLiveDanmakuRenderConfig(danmakuSettings:DanmakuSettings,viewWidthPx:Int,viewHeightPx:Int,safeDisplayArea:Float,density:Float,platform:DesktopOriginalDanmakuRenderPlatform,isFullscreen:Boolean=false):DanmakuRenderConfig {\n'+s+'\n}\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalLiveDanmakuRenderConfig.kt',body,v029_identities[2]['path'],'selected-complete-original-live-config-constructor',rows)
 live=source[PATHS[13]]
 start=live.index('            val typeFilter = DanmakuTypeFilterSettings(');end=live.index('            val currentEngine = engine',start);raw=live[start:end];rows=[];s=raw
 s=adapt(s,'                return@collect','                return false',rows) if s.count('                return@collect')==1 else s
 # Three original rejection paths become the corresponding Boolean-policy returns, with exact count-bound replacement.
 before='                return@collect';count=s.count(before)
 if count:
  assert count==3
  s=s.replace(before,'                return false');rows.append(dict(before=before,after='                return false',occurrenceCount=3))
 s=adapt(s,'settings.blockRules,','settings.blockedRules + settings.blockedKeywords,',rows)
 body='package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.feature.live.LiveDanmakuItem\nimport com.bilipai.desktop.danmaku.DanmakuSettings\n\ninternal fun desktopOriginalLiveDanmakuAllows(item:LiveDanmakuItem,settings:DanmakuSettings):Boolean {\n'+s+'            return true\n}\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalLiveDanmakuAdmission.kt',body,PATHS[13],'selected-complete-original-live-admission',rows)
 engine=source[PATHS[14]]
 pinned=re.search(r'(?m)^            top\.lineCount = ([^\n]+)$',engine);assert pinned
 assert '            bottom.lineCount = '+pinned.group(1) in engine
 margin=re.search(r'(?m)^            scroll\.itemMargin = ([^\n]+)$',engine);assert margin
 body='package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.danmaku.engine.DanmakuRenderConfig\n\n/** Exact original updateConfig expressions, exposed as explicit Windows consumer policies; not a replacement engine. */\ninternal fun desktopOriginalDanmakuPinnedLineCount(config:DanmakuRenderConfig):Int = '+pinned.group(1)+'\n\ninternal fun desktopOriginalDanmakuItemMargin(config:DanmakuRenderConfig):Float = '+margin.group(1)+'\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuEngineBudgets.kt',body,PATHS[14],'selected-exact-original-engine-budget-expressions')

 repo_source=source[PATHS[7]];state=class_body(repo_source,'DanmakuThumbupState') if '{' in repo_source[repo_source.index('internal data class DanmakuThumbupState'):repo_source.index('internal data class DanmakuCloudSyncSettings')] else repo_source[repo_source.index('internal data class DanmakuThumbupState'):repo_source.index('internal data class DanmakuCloudSyncSettings')].strip()
 resolve=function(repo_source,'resolveDanmakuThumbupState','')[0]
 body='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.DanmakuThumbupStatsItem\n\n'+state+'\n\n'+resolve+'\n'
 emit('com/android/purebilibili/data/repository/DesktopOriginalDanmakuThumbupPolicy.kt',body,PATHS[7],'selected-original-schema-and-policy')
 methods=[];methodrows=[]
 for name in ['getDanmakuThumbupState','recallDanmaku','likeDanmaku','reportDanmaku']:
  raw,start,end=function(repo_source,name);patch=[];s=drop_logs(raw,patch)
  if 'com.android.purebilibili.core.store.TokenManager.csrfCache' in s:s=adapt(s,'com.android.purebilibili.core.store.TokenManager.csrfCache','readCsrf()',patch)
  s=adapt(s,'        try {','        kotlinx.coroutines.currentCoroutineContext().ensureActive()\n        assertOwned()\n        try {',patch)
  # API returns before any success/error receipt. The original parameters and all server error mappings remain unchanged.
  mask=masked(s);m=re.search(r'\bval response = api\.',mask);assert m
  opening=mask.index('(',m.end());closing=balanced(mask,opening);before=s[closing:closing+1]
  assert before=='\n',name
  s=s[:closing]+'\n            kotlinx.coroutines.currentCoroutineContext().ensureActive()\n            assertOwned()'+s[closing:]
  s=adapt(s,'        } catch (e: Exception) {','        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {\n            assertOwned()',patch)
  methods.append(s);methodrows.append(dict(member=name,originalStartLine=repo_source[:start].count('\n')+1,originalBodySha256LF=sha(raw),adaptedBodySha256LF=sha(s),changes='Only platform logs/identity injection and caller cancellation/owner guards. Request fields and response code/message rules unchanged.'))
 body='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Same owned Root API and CSRF. No Retrofit/client/cookie/account construction. */
internal class DesktopOriginalDanmakuProtocol(
    private val api:BilibiliApi,
    private val readCsrf:()->String?,
    private val assertOwned:()->Unit,
) {
'''+ '\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/data/repository/DesktopOriginalDanmakuProtocol.kt',body,PATHS[7],'selected-complete-protocol',methodrows)
 settings=source[PATHS[8]];scope=class_body(settings,'DanmakuSettingsScope')
 emit('com/android/purebilibili/core/store/DanmakuSettingsScope.kt','package com.android.purebilibili.core.store\n\n'+scope+'\n',PATHS[8],'selected-complete-original-scope')
 # Select only the original persisted block-rule contract. Full settings panel/cloud/smart occlusion is explicitly outside this slice.
 read_policy=function(settings,'readScopedDanmakuPreference')[0].replace('Preferences.Key<T>','DesktopPreferenceKey<T>').replace('preferences: Preferences','preferences: DesktopPreferenceSnapshot')
 key_builder=function(settings,'buildScopedDanmakuKeyName')[0]
 key=function(settings,'keyDanmakuBlockRules')[0] if False else '    private fun keyDanmakuBlockRules(scope: DanmakuSettingsScope) =\n        stringPreferencesKey(buildScopedDanmakuKeyName(scope, "block_rules"))'
 getter=function(settings,'getDanmakuBlockRulesRaw')[0] if False else settings[settings.index('    fun getDanmakuBlockRulesRaw('):settings.index('    fun getDanmakuBlockRules(')].strip()
 setter=function(settings,'setDanmakuBlockRulesRaw')[0]
 setter=setter.replace('context: Context,\n','').replace('        context.settingsDataStore.edit { preferences ->\n            preferences[keyDanmakuBlockRules(scope)] = normalized\n        }','        writeOriginalScopedValue(keyDanmakuBlockRules(scope), normalized)')
 getter=getter.replace('context: Context,\n','').replace('context.settingsDataStore.data','store.snapshot("settings")')
 body='''package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.feature.video.danmaku.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive

/** Reads/writes the one Root global settings backing. Scope is portrait/landscape, never MID. */
internal class DesktopDanmakuBlockPreferences(
    private val store:DesktopPluginStore,
    private val withOwnedAdmission:((()->Unit)->Boolean),
) {
    init {store.requireObjectNamespace("settings")}
    private val KEY_DANMAKU_BLOCK_RULES=stringPreferencesKey("danmaku_block_rules")
    private suspend fun writeOriginalScopedValue(key:DesktopPreferenceKey<String>,value:String) {
        val caller=currentCoroutineContext()
        withContext(Dispatchers.IO) {
            if(!withOwnedAdmission {
                store.updateFromSnapshot("settings") { caller.ensureActive(); mapOf(key.name to JsonPrimitive(value)) }
            }) throw CancellationException("Danmaku settings owner retired")
        }
    }
'''+key_builder+'\n'+key+'\n'+read_policy+'\n'+getter+'\n'+setter+'\n}\n'
 emit('com/bilipai/desktop/settings/DesktopDanmakuBlockPreferences.kt',body,PATHS[8],'selected-original-key-getter-normalization')
 vm=source[PATHS[2]];state=vm[vm.index('    data class DanmakuMenuState('):vm.index('    private val _danmakuMenuState')].rstrip()
 methods=[];memberrows=[]
 for name in ['showDanmakuMenu','hideDanmakuMenu','refreshDanmakuThumbupState','recallDanmaku','likeDanmaku','reportDanmaku']:
  raw,start,end=function(vm,name,occurrence=1 if name in ('likeDanmaku','reportDanmaku') else 0);s=raw
  s=s.replace('com.android.purebilibili.data.repository.DanmakuRepository','environment.actions')
  if name=='showDanmakuMenu':
   s=s.replace('        val supportsVote =','        environment.assertOwned()\n        ++menuGeneration\n        val supportsVote =')
  elif name=='hideDanmakuMenu':
   s=s.replace('        _danmakuMenuState.value =','        ++menuGeneration\n        _danmakuMenuState.value =')
  elif name=='refreshDanmakuThumbupState':
   s=s.replace('        viewModelScope.launch {','''        val request=++statsGeneration
        statsRequests[dmid]=request
        val menuRequest=menuGeneration
        statsJobs.remove(dmid)?.cancel()
        val loading=viewModelScope.launch {
            try {''')
   s=s.replace('                .onSuccess { thumbupState ->','''                .also { currentCoroutineContext().ensureActive(); environment.assertOwned(); if(request!=statsRequests[dmid])return@launch }
                .onSuccess { thumbupState ->''')
   s=s.replace('if (!current.visible || current.dmid != dmid)','if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid)')
   s=s[:-1].rstrip();assert s.endswith('}');s=s[:-1]+'''            } finally {
                if (request==statsRequests[dmid] && menuRequest==menuGeneration && environment.isOwned()) {
                    _danmakuMenuState.update { current -> if(!current.visible||current.dmid!=dmid)current else current.copy(voteLoading=false) }
                }
            }
        }
        if(!loading.isCompleted)statsJobs[dmid]=loading
    }'''
  elif name=='likeDanmaku':
   s=s.replace('        _danmakuMenuState.update { current ->','''        environment.assertOwned()
        val menuRequest=menuGeneration
        val request=++likeGeneration
        if(_danmakuMenuState.value.visible && _danmakuMenuState.value.dmid==dmid)menuLikeRequest=request
        statsJobs.remove(dmid)?.cancel(); statsRequests[dmid]=++statsGeneration
        _danmakuMenuState.update { current ->''',1)
   s=s.replace('        viewModelScope.launch {','        viewModelScope.launch {',1) # First launch is the original invalid-CID feedback branch.
   # Guard the actual mutation coroutine, preserving the original confirmed update and all messages.
   marker='''        viewModelScope.launch {
            environment.actions''';assert s.count(marker)==1
   s=s.replace(marker,'''        viewModelScope.launch {
            try {
            environment.actions''')
   s=s.replace('                .onSuccess {','                .also { currentCoroutineContext().ensureActive(); environment.assertOwned() }\n                .onSuccess {',1)
   s=s.replace('if (!current.visible || current.dmid != dmid)','if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid)')
   s=s[:-1].rstrip();assert s.endswith('}');s=s[:-1]+'''            } finally {
                if(request==menuLikeRequest && menuRequest==menuGeneration && environment.isOwned() && ownerJob.isActive) {
                    _danmakuMenuState.update { current -> if(!current.visible||current.dmid!=dmid)current else current.copy(voteLoading=false) }
                }
            }
        }
    }'''
  else:
   s=s.replace('        viewModelScope.launch {\n            environment.actions','        environment.assertOwned()\n        viewModelScope.launch {\n            environment.actions')
   s=s.replace('                .onSuccess {','                .also { currentCoroutineContext().ensureActive(); environment.assertOwned() }\n                .onSuccess {',1)
  s=s.replace('environment.assertOwned()','assertOwned()')
  s=s.replace('environment.isOwned())','environment.isOwned() && ownerJob.isActive)')
  if name=='likeDanmaku':methods.append(function(vm,name)[0].replace('        if (dmid <= 0L)', '        assertOwned()\n        if (dmid <= 0L)'))
  if name=='reportDanmaku':methods.append(function(vm,name)[0])
  methods.append(s);memberrows.append(dict(member=name,originalStartLine=vm[:start].count('\n')+1,originalBodySha256LF=sha(raw),adaptedBodySha256LF=sha(s)))
 body='''package com.android.purebilibili.feature.video.viewmodel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.bilipai.desktop.ui.DesktopDanmakuSessionEnvironment

/** Original menu and list share one confirmed liked-ID set; no replacement PlaybackVM/list/cache. */
internal class DesktopOriginalDanmakuSession(private val environment:DesktopDanmakuSessionEnvironment):AutoCloseable {
    private val ownerJob=SupervisorJob(environment.scope.coroutineContext[Job])
    private val viewModelScope=CoroutineScope(environment.scope.coroutineContext+ownerJob)
    private val currentCid:Long get()=environment.cid
    private var menuGeneration=0L
    private var statsGeneration=0L
    private var likeGeneration=0L
    private var menuLikeRequest=0L
    private val statsJobs=mutableMapOf<Long,Job>()
    private val statsRequests=mutableMapOf<Long,Long>()
    private fun toast(message:String) {if(environment.isOwned()&&ownerJob.isActive)environment.showFeedback(message)}
    private fun assertOwned(){environment.assertOwned();if(!ownerJob.isActive)throw CancellationException("Danmaku session closed")}
    private val _danmakuMenuState=MutableStateFlow(DanmakuMenuState())
    val danmakuMenuState=_danmakuMenuState.asStateFlow()
    private val _likedDanmakuIds=MutableStateFlow<Set<Long>>(emptySet())
    val likedDanmakuIds=_likedDanmakuIds.asStateFlow()
    override fun close(){++menuGeneration;++statsGeneration;++likeGeneration;ownerJob.cancel();statsJobs.clear();statsRequests.clear()}
'''+state+'\n\n'+'\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/feature/video/viewmodel/DesktopOriginalDanmakuSession.kt',body,PATHS[2],'selected-original-menu-session',memberrows)

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

 # Selected original neutral local-item builder; only the configuration carrier is adapted.
 local_method=function(manager,'addLocalDanmaku')[0]; local_mask=masked(local_method)
 local_start=local_method.index('        val danmakuData = DanmakuItem().apply {')
 local_open=local_mask.index('{',local_start);local_end=balanced(local_mask,local_open,'{','}')
 local_original=local_method[local_start:local_end]
 local_patches=[]
 local_body=adapt(local_original,'        val danmakuData = DanmakuItem().apply {','    return DanmakuItem().apply {',local_patches)
 local_body=adapt(local_body,'staticDanmakuToScroll = config.staticDanmakuToScroll','staticDanmakuToScroll = staticDanmakuToScroll',local_patches)
 reverse=local_body
 for patch in reversed(local_patches):reverse=reverse.replace(patch['after'],patch['before'])
 assert reverse==local_original
 local_tag=re.search(r'(?m)^        private const val TAG = [^\n]+',manager).group(0).strip()
 local_wrapper='package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.danmaku.engine.*\nimport com.android.purebilibili.danmaku.parser.resolveBilibiliDanmakuFontScale\nimport com.android.purebilibili.core.util.Logger as Log\n'+local_tag+'\n\ninternal fun desktopOriginalLocalDanmakuItem(text:String,color:Int,mode:Int,fontSize:Int,currentPosition:Long,staticDanmakuToScroll:Boolean):DanmakuItem {\n'+local_body+'\n}\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuLocalItemFactory.kt',local_wrapper,PATHS[5],'selected-complete-original-local-item-apply',local_patches)
 save(output/'local-item-selection.json',dict(originalSelectedSHA256LF=sha(local_original),adaptedSelectedSHA256LF=sha(local_body),exactSelectedInverse=True,adaptations=local_patches))
 sync=source[PATHS[16]];raw=function(sync,'normalizeDanmakuPlaybackSpeed','')[0]+'\n\n'+function(sync,'resolveDanmakuDriftSyncIntervalMs','')[0]
 sync_mask=masked(sync);enum_start=sync.index('internal enum class DanmakuSyncAction {');enum_open=sync_mask.index('{',enum_start)
 enum_source=sync[enum_start:balanced(sync_mask,enum_open,'{','}')]
 recovery_source=function(sync,'resolveDanmakuActionForForegroundRecovery','')[0]
 recovery_patches=[]
 recovery_body=adapt(recovery_source,'androidx.media3.common.Player.STATE_ENDED','com.bilipai.desktop.ui.DesktopOriginalPlaybackStates.STATE_ENDED',recovery_patches)
 assert recovery_body.replace(recovery_patches[0]['after'],recovery_patches[0]['before'])==recovery_source
 raw+='\n\n'+enum_source+'\n\n'+recovery_body
 save(output/'foreground-recovery-selection.json',dict(enumSHA256LF=sha(enum_source),originalSelectedSHA256LF=sha(recovery_source),adaptedSelectedSHA256LF=sha(recovery_body),exactSelectedInverse=True,adaptations=recovery_patches))
 constants=[re.search(r'(?m)^private const val '+name+r' = [^\n]+',sync).group(0) for name in ['MIN_ENGINE_PLAYBACK_SPEED','MAX_ENGINE_PLAYBACK_SPEED','NORMAL_SYNC_INTERVAL_MS']]
 imports='package com.android.purebilibili.feature.video.danmaku\n'+'\n'.join(constants)+'\n\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalWebMaskRefreshPolicy.kt',imports+raw+'\n',PATHS[16],'selected-complete-original-refresh-interval-and-normalization')

 inventory=dict(upstreamCommit=COMMIT,ordinaryPoolUpstreamCommit=V030_UP_COMMIT,upBadgeFunctionUpstreamCommit=V030_UP_COMMIT,originalConfigUpstreamCommit=V029_CONFIG_COMMIT,retainedLegacyConfigUpstreamCommit=V027_CONFIG_COMMIT,sources=identities,emitted=emitted,standalone=standalone,
  directReferences=[dict(path=PATHS[4],sha256LF=sha(weighted))],
  pending=['Actual Root smart SVG mask/native paint acceptance','Separate command-vote native acceptance (ordinary original layers are passive)','Portrait SCREEN_TOP placement and separate portrait-fullscreen renderer','Full original Android Live append-only queue/bitmap release closure','ByteDance collision/native engine parity and original special-mode renderer closure','Actual Root window/DPI/runtime acceptance for this source-only delta'],
  originalDefectAdaptation='DanmakuPoolItemRow ignored supplied onLongClick; only clickable->combinedClickable plus import changed.',
  singleAuthority='Root API/Operations/Repository/accountEpoch/player sourceVersion/Overlay rawDocument/global PluginStore remain sole authorities')
 save(output/'source-inventory.json',inventory);return inventory

if __name__=='__main__':
 import argparse
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--standalone',action='store_true')
 a=ap.parse_args();generate(a.repo,a.output,a.standalone)


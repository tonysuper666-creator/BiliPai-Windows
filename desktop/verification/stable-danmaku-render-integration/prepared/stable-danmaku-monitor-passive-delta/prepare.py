from pathlib import Path
import hashlib,json,re,subprocess
L=Path(__file__).resolve().parent;ROOT=L.parents[2];ADV=L.parent/'stable-danmaku-render-config-consumers-parity';MAIN=ROOT.parent/'BiliPai';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def bytesha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
assert bytesha(ADV/'frozen-handoff.json')=='6d221c053309abd218b55a129634726bf5f99f28d4458a374069351468b69103'
H=[];B=[]
def patch(p,s,old,new,reason):
 assert s.count(old)==1,(p,old[:140],s.count(old));out=s.replace(old,new);H.append(dict(path=p,old=old,new=new,beforeSha256LF=sha(s),afterSha256LF=sha(out),reason=reason));return out
def baseline(p,source):
 s=read(source);write(L/'baseline'/p,s);B.append(dict(path=p,source=str(source),sha256LF=sha(s),sourceSHA256Bytes=bytesha(source)));return s
p='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopOriginalDanmakuRenderPlatform.kt';s=baseline(p,ADV/'prepared'/p)
s=patch(p,s,'    fun systemChromeInsetPx():Int','    fun systemChromeInsetPx():Int\n    fun maximumDisplayShortSidePx():Float','Required original maximum display reference, no360 production fallback')
s=patch(p,s,'    override fun systemChromeInsetPx():Int {','    override fun maximumDisplayShortSidePx():Float {\n        val mode=requireNotNull(rootWindow().graphicsConfiguration) { "Mounted Root has no monitor configuration" }.device.displayMode\n        check(mode.width>0 && mode.height>0) { "Current physical monitor has no valid display mode" }\n        return minOf(mode.width,mode.height).toFloat()\n    }\n    override fun systemChromeInsetPx():Int {','Actual current Root Window physical monitor display mode, not window/video aspect ratio')
s=patch(p,s,'fun from(width:Int,height:Int,transform:AffineTransform):DesktopDanmakuPaintGeometry?','fun from(width:Int,height:Int,transform:AffineTransform,referenceShortSidePx:Float):DesktopDanmakuPaintGeometry?','Required reference port at all actual physical geometry callsites')
s=patch(p,s,'y.toFloat(),360f)','y.toFloat(),referenceShortSidePx)','Whole original resolveDanmakuViewport consumes actual required physical monitor short side')
write(L/'review-only'/p,s)
p='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt';s=baseline(p,ADV/'review-only'/p)
s=patch(p,s,'    val currentSettings: DanmakuSettings get() = settings','    val currentSettings: DanmakuSettings get() = settings\n    internal fun maximumDisplayShortSidePx():Float=renderPlatform.maximumDisplayShortSidePx()','Independent command UI uses same existing Overlay Root Window reference; no second actor/parameter')
old='''                val density = graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
                val viewport = resolveDanmakuViewport(width, height, density, 360f) ?: return'''
new='''                val geometry=DesktopDanmakuPaintGeometry.from(width,height,context.transform,renderPlatform.maximumDisplayShortSidePx()) ?: return
                val viewport=geometry.viewport'''
s=patch(p,s,old,new,'One actual physical viewport reference shared by ordinary/authored/live consumers')
s=patch(p,s,'                    val geometry=DesktopDanmakuPaintGeometry.from(width,height,context.transform) ?: return','                    // Uses the actual Root-monitor geometry resolved once above.','Reuse the same actual measured geometry for live config')
s=patch(p,s,'                val geometry=DesktopDanmakuPaintGeometry.from(width,height,context.transform) ?: return','                // Uses the actual Root-monitor geometry resolved once above.','Reuse the same actual measured geometry for standard config')
write(L/'review-only'/p,s)
p='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoVotes.kt';s=baseline(p,ROOT/p)
s=patch(p,s,'resolveDanmakuViewport(measured.width, measured.height, density.density, 360f)','resolveDanmakuViewport(measured.width, measured.height, density.density, danmaku.maximumDisplayShortSidePx())','Original dedicated command/vote viewport uses same required actual Overlay Root monitor getter')
write(L/'review-only-callers'/p,s)
sourcePaths=[
 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/DanmakuViewportHost.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuOverlayViewPolicy.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt',
 'app/src/main/java/com/android/purebilibili/feature/download/OfflineVideoPlayerScreen.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/FullscreenPlayerOverlay.kt',
 'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/DanmakuRenderView.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuManager.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPlayerSettingsOverlayAdapter.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionPolicy.kt',
]
records=[];calls=[]
for p in sourcePaths:
 data=subprocess.check_output(['git','-C',str(ROOT),'show',COMMIT+':'+p]);text=data.decode();blob=subprocess.check_output(['git','-C',str(ROOT),'rev-parse',COMMIT+':'+p],text=True).strip();target=safe((L/'original-retained'/p).with_suffix('.kt.txt'));target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
 records.append(dict(path=p,commit=COMMIT,gitBlob=blob,sha256Bytes=hashlib.sha256(data).hexdigest(),retained=str(target)))
 for n,line in enumerate(text.splitlines(),1):
  if 'configureAsPassiveDanmakuOverlay()' in line and 'fun ' not in line:calls.append(dict(path=p,line=n,source=line.strip(),sourceSHA256Bytes=records[-1]['sha256Bytes']))
assert len(calls)==5 and len({x['path'] for x in calls})==4
policy=next(x for x in records if x['path'].endswith('/DanmakuOverlayViewPolicy.kt'));text=read(policy['retained'])
for part in ['isClickable = false','isLongClickable = false','isFocusable = false','isFocusableInTouchMode = false','importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO','setRendererTouchable(false)','setOnTouchListener { _, _ -> false }']:assert part in text
save(L/'source-inventory.json',records);save(L/'passive-default-source-evidence.json',dict(fixedCommit=COMMIT,fullPolicySHA256=policy['sha256Bytes'],allCallsites=calls,defaultPolicy='ordinary/offline/portrait/fullscreen passive; preserve Windows native mouse-through',listLongPress='original DanmakuPoolSheet combinedClickable separately wired',dedicatedCommandVotes='original dedicated interactive viewport remains independently hit-tested by Root; does not enable ordinary interception',correctionToHistorical213Pending='Ordinary native interception is not a missing stable-tag default feature. Original passive body and all5 calls override that historical pending phrasing.',runtimeProofClaim=False))
save(L/'local-hunks.json',H);save(L/'baseline-manifest.json',B);print('PREPARED',len(H),'LOCAL monitor hunks, full passive policy and all5 original calls pinned')

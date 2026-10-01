from pathlib import Path
import hashlib,json
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';MEDIA=MAIN/'desktop/.local/stable-home-platform-media-parity';MASK=L.parent/'stable-danmaku-web-mask-parity'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def byteSha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
assert byteSha(MEDIA/'frozen-handoff.json')=='9b9ced6df9540109469a25da9917407126e97bdce5656688b04627f2f05fe966'
assert byteSha(MASK/'frozen-handoff.json')=='5faef2a880cc1376185165edd4f71eefa09fb4711133ba7706a7bc79cfbd82c5'
H=[];B=[]
def baseline(path,source):
 s=read(source);write(L/'baseline'/path,s);B.append(dict(path=path,source=str(source),sha256LF=sha(s)));return s
def patch(path,s,old,new,reason,count=1):
 assert s.count(old)==count,(path,old,s.count(old));out=s.replace(old,new);H.append(dict(path=path,old=old,new=new,requiredOldCount=count,beforeSha256LF=sha(s),afterSha256LF=sha(out),reason=reason));return out
path='desktop/src/main/kotlin/com/bilipai/desktop/player/PlayerVideoOutput.kt';s=baseline(path,ROOT/path)
old='    val dolbyVisionProfile: Int? = null,\n)'
new='''    val dolbyVisionProfile: Int? = null,
    /** Actual same-poll OSD video bounds; absent while no owned native video is ready. */
    val viewport:PlayerVideoViewport? = null,
)

/** Native OSD observations, including legitimate negative crop/pan margins. No aspect/fit inference. */
data class PlayerVideoViewport(val osdWidth:Int,val osdHeight:Int,val left:Int,val top:Int,val contentWidth:Int,val contentHeight:Int) {
    init {require(osdWidth>0&&osdHeight>0&&contentWidth>0&&contentHeight>0)}
    fun sourceToPhysicalTransform(width:Int,height:Int,sourceWidth:Int,sourceHeight:Int):java.awt.geom.AffineTransform {
        require(width>0&&height>0&&sourceWidth>0&&sourceHeight>0)
        return java.awt.geom.AffineTransform.getScaleInstance(width.toDouble()/osdWidth,height.toDouble()/osdHeight).apply {
            translate(left.toDouble(),top.toDouble())
            scale(contentWidth.toDouble()/sourceWidth,contentHeight.toDouble()/sourceHeight)
        }
    }
}'''
s=patch(path,s,old,new,'Expose actual already-observed native OSD rectangle, with one source-to-physical transform and nullable not-ready state')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt';s=baseline(path,MEDIA/'prepared'/path)
assert sha(s)=='8e4ac49b91d7279c10ddd5c8c6e8a045ed9d11f1f6d26b773f6322f1cfa0e6c5'
s=patch(path,s,'displayWidth = 0, displayHeight = 0, gamma = null','displayWidth = 0, displayHeight = 0, viewport = null, gamma = null','Both existing load/stop source resets clear the actual rectangle',2)
old='            val gamma = if (fileLoaded) property(native, handle, "video-params/gamma")'
new='''            val videoViewport = if(osdWidth!=null&&osdWidth>0&&osdHeight!=null&&osdHeight>0&&marginLeft!=null&&marginTop!=null&&marginRight!=null&&marginBottom!=null&&displayWidth>0&&displayHeight>0)
                PlayerVideoViewport(osdWidth,osdHeight,marginLeft,marginTop,displayWidth,displayHeight) else null
            val gamma = if (fileLoaded) property(native, handle, "video-params/gamma")'''
s=patch(path,s,old,new,'Retain the OSD dimensions/margins already read in the one native poll; no new property call, actor or fit calculation')
s=patch(path,s,'displayWidth = displayWidth, displayHeight = displayHeight, gamma = gamma','displayWidth = displayWidth, displayHeight = displayHeight, viewport = videoViewport, gamma = gamma','Publish rectangle with the same sourceVersion/session/playbackRevision lock admission and same output cycle')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopWebMaskPath.kt';s=baseline(path,MASK/'prepared'/path)
old='    internal fun scaledArea(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): Area =\n        Area(AffineTransform.getScaleInstance(width.toDouble()/sourceWidth, height.toDouble()/sourceHeight).createTransformedShape(path))'
new=old+'\n    internal fun transformedArea(transform:AffineTransform):Area=Area(transform.createTransformedShape(path))'
s=patch(path,s,old,new,'Same bounded immutable path carrier consumes the one actual video-to-physical transform')
old='''internal fun applyDesktopWebMaskClip(graphics: Graphics2D, width: Int, height: Int, frame: com.android.purebilibili.danmaku.engine.DanmakuMaskFrame?) {
    if (frame == null || width <= 0 || height <= 0) return
    val visible = Area(Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()))
    visible.subtract(frame.path.scaledArea(width, height, frame.sourceWidth, frame.sourceHeight))
    graphics.clip(visible)
}'''
new='''internal fun applyDesktopWebMaskClip(graphics: Graphics2D, width: Int, height: Int, frame: com.android.purebilibili.danmaku.engine.DanmakuMaskFrame?, videoViewport:com.bilipai.desktop.player.PlayerVideoViewport?) {
    if (frame == null || videoViewport == null || width <= 0 || height <= 0) return
    val visible = Area(Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()))
    visible.subtract(frame.path.transformedArea(videoViewport.sourceToPhysicalTransform(width,height,frame.sourceWidth,frame.sourceHeight)))
    graphics.clip(visible)
}'''
s=patch(path,s,old,new,'Actual OSD rectangle is required for masking; when it is not observed, video/danmaku continue without a guessed transform')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt';s=baseline(path,MASK/'review-only'/path)
old='applyDesktopWebMaskClip(physical,geometry.viewport.widthPx,geometry.viewport.heightPx,currentWebMaskFrame((displayTime*1000).toLong()))'
new='applyDesktopWebMaskClip(physical,geometry.viewport.widthPx,geometry.viewport.heightPx,currentWebMaskFrame((displayTime*1000).toLong()),player.videoOutput.value.takeIf {it.sourceVersion==poolSourceVersion && poolSourceVersion?.let(player::ownsSourceVersion)==true}?.viewport)'
s=patch(path,s,old,new,'Same native player/source owns both mask frame and observed video bounds; no second getter parameter/actor')
write(L/'review-only'/path,s)
assert len(H)==7
save(L/'baseline-manifest.json',B);save(L/'local-hunks.json',H);print('OSD7 PREPARED')

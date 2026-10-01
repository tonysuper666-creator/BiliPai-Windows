from pathlib import Path
import importlib.util,hashlib,json,re,sys
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ROOT=L.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
spec=importlib.util.spec_from_file_location('generator',L/'prepared/desktop/tools/extract-upstream-danmaku-list-menu.py');g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
path='desktop/src/test/kotlin/com/bilipai/desktop/danmaku/DanmakuTest.kt';base=read(ROOT/path);write(L/'baseline'/path,base);s=base;rows=[]
matches=list(re.finditer(r'\bDanmakuScheduler\(',g.masked(s)))
for m in reversed(matches):
 mask=g.masked(s);end=g.balanced(mask,mask.index('(',m.start()));old=s[m.start():end]
 inner=g.masked(old);depth=0;commas=0
 for c in inner[inner.index('(')+1:-1]:
  if c in '({[':depth+=1
  elif c in ')}]':depth-=1
  elif c==',' and depth==0:commas+=1
 new=old[:-1]+(', DanmakuSettings(displayAreaRatio=1f)' if commas==0 else '')+', liveAdmission=false)'
 before=s;s=s[:m.start()]+new+s[end:];rows.append(dict(path=path,old=old,new=new,occurrenceOffset=m.start(),beforeSha256LF=sha(before),afterSha256LF=sha(s),testOnly=True))
helper='''
/** Collision fixtures supply explicit geometry/widths. Production has no rowHeight/config fallback. */
private fun DanmakuScheduler.frame(time:Double,width:Int,height:Int,rowHeight:Int,measure:(DanmakuComment)->Int):List<PositionedDanmaku> {
    val font=requireNotNull(javax.swing.UIManager.getFont("Label.font"))
    val platform=object:DesktopOriginalDanmakuRenderPlatform {
        override fun resolveTypeface(fontWeight:Int)=font
        override fun systemChromeInsetPx()=0 // Declared unused fixture chrome, never a production default.
    }
    val settings=currentSettings
    val config=settings.originalConfig(platform).resolveRenderConfig(
        com.android.purebilibili.feature.video.danmaku.DanmakuViewport(width,height,1f,1f)
    ).copy(lineHeightPx=rowHeight.toFloat(),lineMarginPx=0f,
        lineCount=(height*settings.displayAreaRatio/rowHeight).toInt().coerceAtLeast(0))
    return frame(time,width,height,config) {DesktopDanmakuTextMetrics(measure(it),rowHeight-6.0)}
}
'''
before=s;s+=helper;rows.append(dict(path=path,old=base[base.rfind('\n}'):] if False else '',append=helper,beforeSha256LF=sha(before),afterSha256LF=sha(s),testOnly=True))
write(L/'review-only-tests'/path,s);write(L/'test-only-hunks.json',json.dumps(rows,ensure_ascii=False,indent=2)+'\n');print('TEST ONLY adapter',len(rows),'hunks')

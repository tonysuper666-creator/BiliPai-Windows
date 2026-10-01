from pathlib import Path
import hashlib, json
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2].parent/'BiliPai-v023'
def read(p): return p.read_text(encoding='utf-8').replace('\r\n','\n')
def sha(t): return hashlib.sha256(t.encode()).hexdigest()
changes=[]
p=REPO/'desktop/tools/extract-upstream-shared-liquid-tabs.py'; before=read(p)
assert sha(before)=='d8fba34853f4ad435d32503617e02a997d0d770cb5602d4447d35459ca4dbe4e'
old="('private val iosIndicatorSpecular','internal val iosIndicatorSpecular')"
new="('private val iosIndicatorSpecular','internal val desktopOriginalBottomBarIosIndicatorSpecular')"
assert before.count(old)==1
after=before.replace(old,new,1)
p.write_text(after,encoding='utf-8',newline='\n')
changes.append(dict(path=str(p.relative_to(REPO)),beforeLF=sha(before),afterLF=sha(after),old=old,new=new))
p=REPO/'desktop/tools/extract-upstream-frosted-audio-renderer.py'; before=read(p)
assert sha(before)=='f80aae1c9fb2f690efe7452cf25265cb3882181dde2002be3aa7f543c102a0fe'
old=" t=header+'\\n'.join(b for _,b in selected)"
new=" header+='import com.android.purebilibili.feature.home.components.desktopOriginalBottomBarIosIndicatorSpecular as iosIndicatorSpecular\\n'\n"+old
assert before.count(old)==1
after=before.replace(old,new,1)
p.write_text(after,encoding='utf-8',newline='\n')
changes.append(dict(path=str(p.relative_to(REPO)),beforeLF=sha(before),afterLF=sha(after),old=old,new=new))
report=dict(cause='Full shared source also contains FloatingDockChrome file-private iosIndicatorSpecular; widening another original file-private name causes a package-level collision.',
            originalTwoValuesRetained=True, originalExpressionsDifferent=True,
            correction='Rename only the shared BottomBar generated value and add an explicit import alias to the complete Frosted renderer; keep all original renderer expressions and private FloatingDockChrome value.',
            historicalFrozenHandoffsUnchanged=True, originalSourceTreesUnchanged=True,
            failedClassesPhase=23, changes=changes)
(HERE/'full-dock-name-collision-correction.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report))

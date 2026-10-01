from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2].parent/'BiliPai-v023'
p=REPO/'desktop/tools/extract-upstream-shared-liquid-tabs.py'
before=p.read_text(encoding='utf-8').replace('\r\n','\n')
sha=lambda t:hashlib.sha256(t.encode()).hexdigest()
assert sha(before)=='7ff57928bc54dc61d9f4a7b1f37b4a6cb1575061901f78ae3c6bcf7583ff3639'
old="('private val iosIndicatorSpecular','internal val desktopOriginalBottomBarIosIndicatorSpecular'),"
new=old+"('rememberBiliPaiGravityHighlight(iosIndicatorSpecular, extraDegrees = -45f)','rememberBiliPaiGravityHighlight(desktopOriginalBottomBarIosIndicatorSpecular, extraDegrees = -45f)'),"
assert before.count(old)==1
after=before.replace(old,new,1)
p.write_text(after,encoding='utf-8',newline='\n')
report=dict(beforeLF=sha(before),afterLF=sha(after),before=old,after=new,failedPhase=24,
            cause='The original selected biliPaiMiuixFloatingDockSurface remains in the same shared producer and also references the renamed BottomBar file-private value.',
            correction='Apply the same identifier mapping to its one exact call expression. Original parameters/body/math are retained; unrelated FloatingDockChrome default remains private and distinct.',
            historicalFrozenHandoffsUnchanged=True,originalTreesUnchanged=True)
(HERE/'full-dock-shared-reference-correction.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(afterLF=sha(after),exactReferenceMapped=True)))

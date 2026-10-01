from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
path=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
fragment=(MAIN/'desktop/.local/stable-danmaku-list-menu-parity/fragments/DesktopDynamicCardOperations.danmaku.ktfrag').read_text().replace('\r\n','\n')+'\n'
before=path.read_text(encoding='utf-8').replace('\r\n','\n');assert before.count(fragment)==1
marker='// GENERATED original editor members; do not hand-maintain a second request algorithm.'
assert before.count(marker)==1
after=before.replace(fragment,'').replace(marker,fragment+marker)
path.write_text(after,encoding='utf-8')
report=dict(wholeClasses19Passed=False,failure='Strict editor protocol verifier includes all bytes until the comment marker; initial insertion entered that range',
 correction='Move unchanged sole danmaku fragment before the editor marker; preserve every existing guarded protocol family byte-for-byte',
 beforeSha256LF=hashlib.sha256(before.encode()).hexdigest(),afterSha256LF=hashlib.sha256(after.encode()).hexdigest(),
 fragmentSha256LF=hashlib.sha256(fragment.rstrip('\n').encode()).hexdigest(),verifierChanged=False)
(HERE/'danmaku-placement-correction.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print('Moved unchanged members outside the strict editor range; verifier unchanged')

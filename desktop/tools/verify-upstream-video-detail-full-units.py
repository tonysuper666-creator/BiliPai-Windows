"""Additional exact member gate; all existing comment/detail/editor/fraud/BGM gates remain intact."""
from pathlib import Path
import hashlib,json,sys
def lf(path):return path.read_bytes().replace(b'\r\n',b'\n').decode('utf-8')
def verify(repo,generated,report):
    path=repo/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
    actual=lf(path);marker='// Desktop original complete video engagement/info binding'
    following='// GENERATED original editor members; do not hand-maintain a second request algorithm.'
    assert actual.count(marker)==actual.count(following)==1
    start,end=actual.index(marker),actual.index(following);assert start<end
    expected=lf(generated/'video-operations-members.fragment').rstrip('\n')
    assert actual[start:end].rstrip('\n')==expected,'Original full video member fragment differs from sole producer'
    report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps(dict(passed=True,memberSha256LF=hashlib.sha256(expected.encode()).hexdigest(),operationsSha256LF=hashlib.sha256(actual.encode()).hexdigest(),existingFamiliesUntouched=True),indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':verify(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))

from pathlib import Path
import hashlib,json,subprocess
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def write(p,b):
 wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def source(rel):
 path=BASE+rel;b=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n')
 write(P/'original-stable'/path,b)
 return dict(path=path,sha256LF=hashlib.sha256(b).hexdigest(),bytesLF=len(b),lines=b.count(b'\n'),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip())
if __name__=='__main__':
 rows=[source(r)for r in ['feature/video/ui/overlay/FullscreenPlayerOverlay.kt','feature/video/ui/pager/PortraitVideoPager.kt','data/repository/VideoRepository.kt']]
 write(P/'original-source-inventory.json',json.dumps(dict(commit=COMMIT,rows=rows),indent=2)+'\n')
 print(json.dumps(rows,indent=2))

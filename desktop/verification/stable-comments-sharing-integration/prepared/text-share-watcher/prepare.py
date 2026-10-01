from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());STABLE=MAIN.parent/'BiliPai-v023'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def main():
 path='desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeTextShare.kt';source=STABLE/path;raw=safe(source).read_bytes();before=raw.decode().replace('\r\n','\n')
 old='                        if (active != token) true else {\n                            val state = IntByReference()'
 new='                        if (active != token) true else if (!stillOwned()) {\n                            closeActive(); true\n                        } else {\n                            val state = IntByReference()'
 assert before.count(old)==1
 after=before.replace(old,new,1);write(HERE/'base-inputs/DesktopNativeTextShare.kt',before);write(HERE/'prepared'/path,after)
 save(HERE/'patch-plan.json',dict(path=path,baseSha256Bytes=hashlib.sha256(raw).hexdigest(),baseSha256LF=sha(HERE/'base-inputs/DesktopNativeTextShare.kt'),candidateSha256LF=sha(HERE/'prepared'/path),hunk=dict(before=old,after=new),applyHunkOnly=True,actorAuthorityUnchanged=True,cppNativeApiUnchanged=True,maximumNormalDetectionCadenceMs=500,noSynchronousNativeDataRequestedPredicate=True))
 print(json.dumps(dict(path=path,candidateSha256LF=sha(HERE/'prepared'/path),patchPlanSha256=sha(HERE/'patch-plan.json'))))
if __name__=='__main__':main()

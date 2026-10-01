from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
FILES=['feature/live/'+n+'.kt' for n in ['LiveSearchScreen','LiveAreaScreen','LiveAreaDetailScreen','LiveFollowingScreen','LiveAreaScreenPolicy']]+['data/repository/SearchRepository.kt','core/store/SettingsManager.kt','navigation/AppNavigation.kt']
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
def main():
 original={}
 for f in FILES:
  p=BASE+f
  s=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO).decode().replace('\r\n','\n')
  assert read(REPO/p)==s,p
  original[p]=s
 pins={p:sha(s) for p,s in original.items()}
 producer=read(HERE/'producer-template.py').replace('SOURCE_PINS={}', 'SOURCE_PINS='+repr(pins))
 tool=HERE/'prepared/desktop/tools/extract-upstream-live-navigation.py'
 write(tool,producer)
 m=load(tool,'prepared_live_nav');m.generate(REPO,HERE/'prepared/generated',True)
 m.generate(REPO,HERE/'production-generated',False)
 write(HERE/'original-source-inventory.json',json.dumps({'commit':COMMIT,'sources':[{'path':p,'sha256LF':pins[p],'physicalLines':len(s.splitlines())} for p,s in original.items()]},ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()

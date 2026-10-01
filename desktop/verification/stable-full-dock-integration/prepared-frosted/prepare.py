from pathlib import Path
import importlib.util,sys,json,subprocess
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
path=HERE/'prepared/desktop/tools/extract-upstream-frosted-audio-renderer.py';safe(path.parent).mkdir(parents=True,exist_ok=True);safe(path).write_text(safe(HERE/'producer.pyfrag').read_text(encoding='utf-8'),encoding='utf-8',newline='\n')
s=importlib.util.spec_from_file_location('frosted_audio_prepare',path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
manifest=json.loads(m.read(REPO/'desktop/upstream-sources.json'));current={r['path']:r for r in manifest['sources']};inv=m.inventory(REPO);append=[r for r in inv if r['path'] not in current];manifest['sources']+=append
shadow=HERE/'source-shadow';gitdir=subprocess.check_output(['git','rev-parse','--absolute-git-dir'],cwd=REPO,text=True).strip();m.write(shadow/'.git','gitdir: '+gitdir.replace('\\','/')+'\n')
for p in m.SOURCES:m.write(shadow/p,m.read(REPO/p))
m.write(shadow/'desktop/upstream-sources.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
m.write(shadow/'desktop/tools/sync-upstream.py',m.read(REPO/'desktop/tools/sync-upstream.py'))
records=m.generate(shadow,HERE/'generated');print('Generated',len(records),'original source consumers')
m.write(HERE/'registry-recipe.json',json.dumps(dict(commit=m.PIN,appendRows=append,featureMerge=[dict(path=r['path'],sha256=r['sha256'],preserveMode=current[r['path']]['mode'],appendFeatures=[f for f in r['features'] if f not in current[r['path']].get('features',[])]) for r in inv if r['path'] in current]),ensure_ascii=False,indent=2)+'\n')
old=HERE/'generated/com/android/purebilibili/feature/home/components/DesktopOriginalBottomVisualTuning.kt'
if safe(old).exists():safe(old).unlink()

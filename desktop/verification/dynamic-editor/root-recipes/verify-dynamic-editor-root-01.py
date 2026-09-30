from pathlib import Path
import hashlib,importlib.util,json,subprocess,tempfile
REPO=Path(__file__).resolve().parents[2]
def safe(p):
 v=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(v if v.startswith(prefix) else prefix+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,REPO/path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
editor=module('editor_root_contract','desktop/tools/extract-upstream-dynamic-editor.py')
host=module('editor_root_contract_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(REPO);parser=media.parser_for(REPO)
with tempfile.TemporaryDirectory(prefix='editor-original-production-') as tmp:
 out=Path(tmp);editor.generate(REPO,out,standalone=False)
 files=sorted(out.rglob('*.kt'));assert len(files)==8
 generated=[dict(path=p.relative_to(out).as_posix(),sha256Bytes=sha(p)) for p in files]
 for row in generated:assert sha(REPO/'desktop/build/generated/dynamic-editor'/row['path'])==row['sha256Bytes']
original=editor.read(REPO,'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicIncrementalRefreshPolicy.kt')
actual=(REPO/'desktop/build/generated/dynamic-settings/com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicIncrementalPolicy.kt').read_text(encoding='utf-8')
for name in ['shouldStartDynamicRefresh','resolveDynamicRefreshUserId']:
 assert media.function(original,name,parser)==media.function(actual,name,parser)
base=json.loads((REPO/'desktop/.local/dynamic-editor-detail-parity/final-production-safe/source-inventory.json').read_text(encoding='utf-8'))
paths=list(dict.fromkeys([r['path'] for r in base]+['app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt','app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicIncrementalRefreshPolicy.kt']))
identities=[]
for p in paths:
 current=safe(REPO/p).read_bytes().replace(b'\r\n',b'\n')
 upstream=subprocess.check_output(['git','show','fcf84853b287662e8a9129ea0d38576c36522a34:'+p],cwd=REPO).replace(b'\r\n',b'\n')
 assert current==upstream,p
 identities.append(dict(path=p,sha256Lf=hashlib.sha256(current).hexdigest(),actualOriginalTagVerified=True))
assert len(identities)==24
value=dict(passed=True,productionGeneratedFiles=generated,soleExistingSegmentedControl=True,originalRefreshHelpersUnchanged=True,originalSourceIdentities=identities,sourceCountNotFeatureProgress=True)
p=REPO/'desktop/.local/dynamic-editor-root-conformance-01.json';p.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
print('PASS: 8 production-selected outputs match actual Main; 2 exact original refresh helpers; 24 original tag identities.')
from pathlib import Path
import importlib.util,hashlib,json,sys
sys.dont_write_bytecode=True;HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];PRIMARY=REPO.parent/'BiliPai'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def mod(p,n):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
root=HERE/'existing-producer-proof';assert not root.exists();root.mkdir()
shared=mod(HERE/'prepared-existing-producer-review/desktop/tools/extract-upstream-shared-liquid-tabs.py','visibility_production_proof')
shared.generate(REPO,root/'shared')
changed=read(root/'shared/generated/com/android/purebilibili/feature/home/components/BottomBar.kt')
expected=read(HERE/'declared-prospective-existing-producer/BottomBar.kt')
assert changed==expected
linked=mod(HERE/'prepared-existing-producer-review/desktop/tools/extract-upstream-linked-dock.py','dedup_production_proof')
shadow=HERE/'source-shadow';manifest=json.loads(read(shadow/'desktop/upstream-sources.json'));current={r['path']:r for r in manifest['sources']}
for row in linked.inventory(REPO):
 if row['path'] in current:
  assert current[row['path']]['sha256']==row['sha256'];current[row['path']]['features']=list(dict.fromkeys(current[row['path']].get('features',[])+row['features']))
 else:manifest['sources'].append(row)
for p in linked.SOURCES+[f'app/src/main/res/drawable/{a}.xml' for a in linked.ASSETS]:write(shadow/p,read(REPO/p))
for name in ['extract-upstream-dynamic-reply-protocol.py','extract-appearance-platform.py']:write(shadow/'desktop/tools'/name,read(REPO/'desktop/tools'/name))
write(shadow/'desktop/upstream-sources.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
linked.generate(shadow,root/'linked')
generated=list(safe(root/'linked/com').rglob('*.kt'))
assert not any(p.name in ['HomeScrollOffsetPolicy.kt','DesktopOriginalLinkedDockScrollLocals.kt'] for p in generated)
original=REPO/'desktop/.local/stable-linked-dock-parity/generated';records=[]
for p in generated:
 rel=p.relative_to(safe(root/'linked'));assert read(p)==read(original/rel);records.append(dict(path=rel.as_posix(),sha256LF=sha(read(p))))
result=dict(status='PASS',originalCommit=linked.PIN,shared32ExistingProducerVisibilityOutputSHA256LF=sha(changed),shared32AllBodiesUnchangedExceptVisibility=True,linkedGeneratedKt=len(generated),exactLinkedUnchangedSources=records,reusedActual17=['HomeScrollOffsetPolicyKt','DesktopFavoriteScrollLocalsKt'],newStore=False,newGlobals=False,noRootSourceMutation=True,noGradle=True,noHTTP=True,noHWND=True)
write(HERE/'existing-producer-source-proof.json',json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('PASS exact existing producer visibility and LinkedDock skip: ',len(generated),'unchanged Kt')

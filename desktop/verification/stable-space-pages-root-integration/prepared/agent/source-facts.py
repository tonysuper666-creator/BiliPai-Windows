from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023'
PIN='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
NEW='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
HEAD='5f5f29a00609ec215b06553b59b3d3669ef0335b'
FEATURE='stable-original-space-pages-root-parity'
def raw(ref,path):return subprocess.check_output(['git','-C',str(C),'show',ref+':'+path])
def sha(b):return hashlib.sha256(b).hexdigest()
spec=importlib.util.spec_from_file_location('space',P/'prepared/desktop/tools/extract-upstream-space-pages.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
manifest=json.loads(raw(HEAD,'desktop/upstream-sources.json').decode())
assert manifest['upstreamCommit']==PIN and len(manifest['sources'])==1225
existing={row['path']:row for row in manifest['sources']}
rows=[]
paths=g.PATHS+['app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt']
for path in paths:
    data=raw(PIN,path).replace(b'\r\n',b'\n')
    prior=existing.get(path)
    if prior:assert prior['sha256']==sha(data),path
    rows.append(dict(path=path,sha256=sha(data),mode=prior['mode'] if prior else 'policy-extract',
        features=[FEATURE],operation='feature-union' if prior else 'append',
        priorFeatures=prior.get('features',[]) if prior else []))
(P/'source-inventory.json').write_text(json.dumps(dict(upstream=PIN,candidate=HEAD,
    baselineSourceCount=1225,appendCount=sum(r['operation']=='append'for r in rows),
    unionCount=sum(r['operation']=='feature-union'for r in rows),sources=rows),ensure_ascii=False,indent=2),encoding='utf8')
delta=[]
for name in ['SpaceScreen','SpaceViewModel','SeasonSeriesDetailViewModel']:
    path='app/src/main/java/com/android/purebilibili/feature/space/'+name+'.kt'
    diff=subprocess.check_output(['git','-C',str(C),'diff',PIN,NEW,'--',path])
    out=P/'v025-delta'/f'{name}.diff';out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(diff)
    delta.append(dict(path=path,v023Sha256LF=sha(raw(PIN,path).replace(b'\r\n',b'\n')),
        v025Sha256LF=sha(raw(NEW,path).replace(b'\r\n',b'\n')),diff=str(out.relative_to(P)),diffSha256=sha(diff)))
(P/'v025-delta/summary.json').write_text(json.dumps(dict(fromCommit=PIN,toCommit=NEW,
    productionBaselineChanged=False,v025Compiled=False,files=delta,changes=[
    'SpaceScreen: PlaybackProgressManager moves to core.player; AppHingeSafeContent covers SpaceContentBox; liveRoom stable smart cast; nullable cheese badge/status and banner title handling.',
    'SpaceViewModel: require stable response.data locals in dynamic and cheese requests. No endpoint/parameter change in these three files.',
    'SeasonSeriesDetailViewModel: require stable checked archive response.data. Existing full favorite producer owns this VM; this packet does not duplicate it.'
    ],upgradeObligations=[
    'Rebase the full original producer and reverse receipts only after Root chooses a unified v025 source inventory.',
    'Map hinge-safe content to actual Windows adaptive window, preserving explicit physical-fold differences.',
    'Use the relocated sole progress owner; do not add a second progress cache or assert v023 proof accepts v025.'
    ]),ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(dict(sources=len(rows),appendCount=sum(r['operation']=='append'for r in rows),unionCount=sum(r['operation']=='feature-union'for r in rows))))

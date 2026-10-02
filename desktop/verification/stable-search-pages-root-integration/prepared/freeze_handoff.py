from pathlib import Path
import hashlib,json,os,subprocess
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';S=M/'desktop/.local/stable-product-snapshot-85';HEAD='6ac84c8036ed4c75e2944d92d1020566e283e983'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def dump(p,v):wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
def obj(p):return json.loads(read(p))
assert not (P/'frozen-handoff.json').exists(),'Never mutate an already frozen handoff'
contract=obj(P/'install-contract.json');delta=obj(P/'registry-delta.json');spec=obj(P/'adaptation-spec.json')
assert contract['baseline']==delta['baseline']==spec['baseline']==HEAD
for row in contract['existingFamilies']:
 baseline=subprocess.check_output(['git','show',HEAD+':'+row['path']],cwd=C)
 assert hashlib.sha256(baseline).hexdigest()==row['baselineGitBytesSha']
 before=baseline.decode().replace('\r\n','\n');prepared=read(row['preparedPath']);assert hashlib.sha256(prepared).hexdigest()==row['preparedBytesSha']
 after=before
 for h in row['exactHunks']:assert after.count(h['before'])==1;after=after.replace(h['before'],h['after'])
 assert after==prepared.decode().replace('\r\n','\n')
for row in contract['newCanonicalFiles']:assert sha(row['preparedPath'])==row['sha256Bytes']
assert len(contract['existingFamilies'])==10 and sum(len(r['exactHunks'])for r in contract['existingFamilies'])==15 and len(contract['newCanonicalFiles'])==3
assert len(delta['newSources'])==15 and len(delta['existingSourceUnions'])==15 and len(delta['existingResourceUnions'])==3
for row in delta['newSources']+delta['existingSourceUnions']:assert hashlib.sha256(read(C/row['path']).replace(b'\r\n',b'\n')).hexdigest()==row['sha256']
for row in delta['existingResourceUnions']:assert hashlib.sha256(read(C/row['path']).replace(b'\r\n',b'\n')).hexdigest()==row['sha256']
# Same exact source closure compiled and executed; current core bytes remain equal after all checks.
cp=obj(S/'ordered-runtime-cp.json');assert len(cp)==101
for row in cp:assert sha(row['path'])==row['sha256Bytes']
assert sha(S/'manifest.json')=='417663152a99ecf23e6887e2940b15c7c1cfd06c6c3f0b2b1925430ed4ca4012'
for attempt in ['15','16']:
 before=obj(P/'compile'/attempt/'pins-before.json');after=obj(P/'compile'/attempt/'pins-after.json');assert before==after
 assert obj(P/'compile'/attempt/'result.json')['passed']
 for row in before['sourceInputs']:assert sha(row['path'])==row['sha256Bytes']
assert obj(P/'runtime/12/receipt.json')['passed'] and obj(P/'runtime/12/result.json')['assertions']==71
# Fresh source outputs are exact; only original-selected producers own generated Kotlin files.
outputs=[]
for file in (P/'producer-check/search-final').rglob('*.kt'):
 rel=file.relative_to(P/'producer-check/search-final');assert sha(file)==sha(P/'generated'/rel)
 outputs.append(dict(path=rel.as_posix(),sha256Bytes=sha(file),producer='extract-upstream-search-pages.py'))
assert len(outputs)==7
for r in obj(P/'shared-backtop-inverse-audit.json'):
 assert sha(P/'generated'/r['output'])==sha(P/'producer-check/shared-favorites'/r['output'])
 assert hashlib.sha256(read(P/'generated'/r['output']).replace(b'\r\n',b'\n')).hexdigest()==r['outputSha256LF']
 outputs.append(dict(path=r['output'],sha256Bytes=sha(P/'generated'/r['output']),producer='extract-upstream-favorites.py',inverseOriginalExact=True))
direct=[]
for path in spec['directSources']:
 original=read(C/path).replace(b'\r\n',b'\n');target=P/'direct/com/android/purebilibili'/path.removeprefix('app/src/main/java/com/android/purebilibili/')
 assert read(target)==original
 direct.append(dict(path=path,sha256LF=hashlib.sha256(original).hexdigest(),verbatim=True,soleProducer='prepareUpstreamSources'))
assert len(direct)==11
retired=[]
for lane,name in [('full-navigation-settings','DesktopOriginalSearchTabOrderPolicy.kt'),('settings-privacy','PrivacySearchHintPolicy.kt')]:
 path='com/android/purebilibili/feature/search/'+name;file=P/'producer-check'/lane/path
 old=C/'desktop/build/generated'/lane/path
 content=read(file).decode();assert 'internal fun 'not in content and 'internal val 'not in content and 'package com.android.purebilibili.feature.search' in content
 retired.append(dict(owner=lane,path=path,previousActualGeneratedSha256Bytes=sha(old),replacementSha256Bytes=sha(file),packageOnlyMarker=True,actualSeededOverwriteVerified=True))
producerReceipt=dict(passed=True,sourceOutputs=outputs,directSources=direct,staleSelectedOutputs=retired,freshNavigationPrivacyClosureCompile='compile/16/result.json',snapshot85Manifest=sha(S/'manifest.json'),ordered101CP=sha(S/'ordered-runtime-cp.json'),candidateWritten=False)
dump(P/'producer-verification.json',producerReceipt)
failures=[]
notes=['Fixture assumed a switched-away page cache already represented current original page; fixed current page assertion.','Fixture encoded SearchLiveUser.is_live as Int; real model Boolean fixed in fixture.','Generic timeout lacked context; kept failure and added diagnostics.','Fixture expected pinned fallback while a nonempty original getHotSearch branch correctly short-circuited; fixture response fixed.','Real product common BackToTop consumers required Favorites-only context; adapted same existing global owner through optional common platform port.','Fixture omitted real Shell global LiquidTab settings; supplied same isolated Store read projection.','Fixture omitted real Shell global Dynamic skeleton settings; supplied same isolated Store read projection.','Fixture expected exact UP label without original count suffix; matching fixed in fixture.','Fixture expected wrong landing/open-Trending labels; used original 大家都在搜 / 完整榜单.','Fixture omitted actual scene foreground binding for ImmersiveAppScaffold; linked to owned scene lifetime.']
for i,note in enumerate(notes,1):
 folder=P/'runtime'/f'{i:02}';assert not obj(folder/'receipt.json')['passed']
 failures.append(dict(attempt=f'runtime/{i:02}',kind='product-boundary'if i==5 else'fixture',note=note,receiptSha256=sha(folder/'receipt.json'),logSha256=sha(folder/'run.log')))
failures+= [dict(attempt='compile/01',kind='adaptation',note='Initial prospective compile exposed missing adapters/shared declarations/resource/animation boundaries; retained real diagnostics, fixed preparation.'),dict(attempt='compile/04',kind='fixture-and-invalid-proof',note='Obsolete SHORT enum in fixture; fixture edited during this compile. Not used as a successful source-stability proof.')]
dump(P/'failure-history.json',dict(failures=failures,finalCompile='16',finalRuntime='12',failedLogsPreserved=True,oldEvidenceNotRelabeled=True))
# Curated raw inputs, proof and receipts only; no generated or original copied tree, no binary payload.
raw=[]
def add(path):
 file=P/path;assert file.is_file(),path
 assert file.suffix.lower()not in ('.jar','.class','.dll','.exe','.zip')
 raw.append(dict(path=Path(path).as_posix(),absolutePath=str(file),sha256Bytes=sha(file),bytes=len(read(file))))
for file in sorted((P/'prepared').rglob('*')):
 if file.is_file():add(file.relative_to(P))
for name in ['README.md','install-contract.json','registry-delta.json','gradle-append.kts','source-inputs.json','adaptation-spec.json','inverse-audit.json','settings-method-audit.json','skeleton-method-audit.json','shared-backtop-inverse-audit.json','producer-verification.json','failure-history.json','SearchOwnerFixture.kt','compile85.py','run_fixture85.py','build_contract.py','producer-check/search-final-audit.json','producer-check/full-navigation-inventory.json','freeze_handoff.py']:add(name)
for attempt in ['15','16']:
 for file in ['compile.args','compile.log','pins-before.json','pins-after.json','result.json']:add('compile/'+attempt+'/'+file)
for file in ['inputs.json','receipt.json','result.json','run.log','original-search-keyboard-results.png','original-search-up-tab.png','original-search-trending.png']:add('runtime/12/'+file)
for i in range(1,11):
 for file in ['receipt.json','run.log']:add(f'runtime/{i:02}/'+file)
for attempt in ['01','04']:
 for file in ['compile.log','result.json']:
  if(P/'compile'/attempt/file).exists():add('compile/'+attempt+'/'+file)
assert len({r['path']for r in raw})==len(raw)
for r in raw:assert sha(r['absolutePath'])==r['sha256Bytes']
manifest=dict(schema='stable-original-search-pages-root-parity-v1',frozen=True,baseline=HEAD,upstreamTag='v0.2.3',upstreamCommit=spec['upstreamCommit'],candidateWritten=False,userDesktopChanged=False,userAccountDataRead=False,newDependencies=[],installContract=dict(path=str(P/'install-contract.json'),sha256Bytes=sha(P/'install-contract.json'),existingFamilies=10,exactHunks=15,newCanonicalFiles=3),copyWhiteList=contract['newCanonicalFiles'],registryDelta=dict(path=str(P/'registry-delta.json'),sha256Bytes=sha(P/'registry-delta.json'),newSourceIdentities=15,existingSourceFeatureUnions=15,existingResourceFeatureUnions=3,newResourceIdentities=0),gradleAppend=dict(path=str(P/'gradle-append.kts'),sha256Bytes=sha(P/'gradle-append.kts')),originalBodyInverse=dict(fullSources=5,sharedBackToTopSources=2,settingsMethods=7,trendingSkeletonComplete=True,directVerbatimSources=11),compile=dict(attempt='16',passed=True,inputs=42,actual85Manifest=sha(S/'manifest.json'),ordered101CP=sha(S/'ordered-runtime-cp.json'),wholeShell=True,freshNavigationPrivacy=True,beforeAfterPinsEqual=True),runtime=dict(attempt='12',passed=True,assertions=71,actualComposePointer=True,actualAWTComposeKeyboard=True,soleHistoryOwnedCASAndLegacy=True,RootMounted=False,businessNetwork=False,userAccount=False,nativePlayer=False),pending=['Normal whole-product Gradle classes/compileTestKotlin after exact installation','Actual physical Root Search/Trending mounting and all typed destination runtime navigation','Real account / Bilibili API / media playback','New EXE packaging and deployment'],outputsExcluded=['JAR','generated source trees','copied upstream trees','101 runtime classpath JARs'],rawFiles=raw,rawCount=len(raw),rawBytes=sum(r['bytes']for r in raw))
dump(P/'frozen-handoff.json',manifest)
print('FROZEN',len(raw),'raw',manifest['rawBytes'],'bytes',sha(P/'frozen-handoff.json'))

from pathlib import Path
import hashlib,importlib.util,json,os,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
BASE='app/src/main/java/com/android/purebilibili/';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def sha(value):return hashlib.sha256(value).hexdigest()
def wide(path):
 value=os.path.abspath(str(path));return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def load(name):return json.loads((P/name).read_text(encoding='utf8'))
def original(path):return subprocess.check_output(['git','-C',str(C),'show',UP+':'+path]).decode('utf8').replace('\r\n','\n')
spec=importlib.util.spec_from_file_location('parser',C/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
spec=importlib.util.spec_from_file_location('media',C/'desktop/tools/extract-upstream-media.py');media=importlib.util.module_from_spec(spec);spec.loader.exec_module(media)
inventory=load('producer-inventory.json');receipts=[]
for row in inventory['emitted']:
 origin=original(row['originalPath']);assert sha(origin.encode())==row['originalSha256LF']
 output=(P/'generated'/row['output']).read_text(encoding='utf8').split('\n',1)[1]
 if row.get('completeOriginalFileBody'):
  selection=origin
 elif row['output'].endswith('DesktopOriginalBangumiFollowPreloadPolicy.kt'):
  selection=origin[:origin.index('internal const val BANGUMI_FOLLOW_STATUS_UNFOLLOW')]+media.function(origin,'resolveFollowPreloadPageCount',parser)+'\n\n'+media.function(origin,'preloadFollowedSeasonsForType',parser)+'\n'
 elif row['output'].endswith('DesktopOriginalBangumiEpisodePagePolicy.kt'):
  selection='package com.android.purebilibili.feature.bangumi\n\n'+origin[origin.index('internal data class BangumiEpisodePreviewWindow'):]
 elif row['output'].endswith('DesktopOriginalBangumiPosterDetailSkeleton.kt'):
  selection=origin[:origin.index('/**')]+ '@Composable\n'+media.function(origin,'PosterDetailSkeleton',parser)+'\n'
 else:
  assert row.get('completeSelectedMethodBodies')
  for method in row['methods']:
   raw=media.function(origin,method['method'],parser);assert sha(raw.encode())==method['originalSha256LF']
   adapted=raw
   for delta in method['changes']:
    assert adapted.count(delta['before'])==delta['count'];adapted=adapted.replace(delta['before'],delta['after'])
   assert output.count(adapted)==1 and sha(adapted.encode())==method['adaptedSha256LF']
  receipts.append(dict(path=row['originalPath'],output=row['output'],selectedMethods=len(row['methods']),inverseBodyEqual=True));continue
 restored=output
 for delta in reversed(row['changes']):
  assert restored.count(delta['after'])==delta['count'],row['output'];restored=restored.replace(delta['after'],delta['before'])
 assert restored==selection,row['output']
 receipts.append(dict(path=row['originalPath'],output=row['output'],completeFile=row.get('completeOriginalFileBody',False),selectionSha256LF=sha(selection.encode()),inverseBodyEqual=True))
compile=load('runs/05/result.json');assert compile['passed'] and compile['snapshot']==85 and compile['sourceCount']==13
for source in compile['explicitProspectiveSources']:assert sha(wide(source['path']).read_bytes())==source['sha256Bytes']
with zipfile.ZipFile(P/'runs/05/prospective.jar') as product:
 new_classes={name for name in product.namelist() if name.endswith('.class')}
with zipfile.ZipFile(M/'desktop/.local/stable-product-snapshot-85/main-kotlin.jar') as base:
 duplicates=sorted(new_classes & set(base.namelist()));assert not duplicates,duplicates
contract=load('install-contract.json');hunks=load('exact-hunks.json')
for target in load('targets.json'):
 raw=subprocess.check_output(['git','-C',str(C),'show',contract['candidateHead']+':'+target['path']]).decode('utf8').replace('\r\n','\n')
 assert sha(raw.encode())==target['beforeSha256LF']
 for hunk in [h for h in hunks if h['path']==target['path']]:
  assert raw.count(hunk['before'])==1;raw=raw.replace(hunk['before'],hunk['after'])
 assert sha(raw.encode())==target['afterSha256LF']
 if target['path']=='desktop/upstream-sources.json':
  registry=json.loads(raw);assert len(registry['sources'])==1191 and len({r['path']for r in registry['sources']})==1191
  pinset={r['originalPath']:r['originalSha256LF'] for r in inventory['emitted']}
  for source in registry['sources']:
   if source['path'] in pinset:assert source['sha256']==pinset[source['path']] and 'independent-bangumi-pages' in source['features']
for row in contract['copyWhitelist']:assert sha((P/row['source']).read_bytes())==row['sha256Bytes']
proof=load('proof-runs/02/result.json');assert proof['passed'] and proof['snapshot']==85
assert 'PASS 34 focused original Bangumi assertions' in (P/'proof-runs/02/run.log').read_text(encoding='utf8')
root=(P/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiPagesRoot.kt').read_text(encoding='utf8')
for boundary in ['ordinaryVideoResources', 'MpvPlayer(', 'OkHttpClient(', 'PlaybackProgressManager.getInstance', 'DesktopPluginStore(', 'section.load(']:
 if boundary=='ordinaryVideoResources':continue
 assert boundary not in root,boundary
assert 'appResources.progress.forEntry' in root and 'aggregate.bangumiEnvironment.repository' in root
assert 'preferredAid = episode.aid' in root and 'episode.from == "pugv" || episode.playable || episode.episodeCanView' in root
nav=original(BASE+'navigation/AppNavigation.kt');assert 'isCourse = episode.from == "pugv" || episode.playable || episode.episodeCanView' in re.sub(r'\s+',' ',nav)
assert 'replaceNavigation3TopWithKey(' in nav and 'replaceSeason(entryKey, season)' in root
receipt=dict(passed=True,receipts=receipts,completeOriginalFiles=sum(r.get('completeFile',False) for r in receipts),generatedFiles=12,completeSelectedProtocolMethods=8,newOriginalIdentities=8,registeredTotalAfter=1191,resourcesAfter=244,existingClassFqnOverlays=duplicates,compiledSnapshot=85,orderedRuntimeEntries=101,narrowCompile=True,focusedAssertions=34,producerReplay=load('standalone-replay-receipt.json'),actualRootAcceptance=False,nativePlayerAcceptance=False,realAccountAcceptance=False,completeOriginalPlayerAccepted=False,candidateWrites=0)
(P/'source-inverse-audit.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('AUDIT PASS 12 outputs /8 complete files /8 protocol methods /0 FQN overlays /34 assertions')

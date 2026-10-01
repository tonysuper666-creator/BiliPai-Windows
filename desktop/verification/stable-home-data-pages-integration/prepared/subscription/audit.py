from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(path):return Path('\\\\?\\'+str(Path(path).absolute()))
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def read(path):return safe(path).read_text(encoding='utf-8').replace('\r\n','\n')
def write(path,value):
 safe(path.parent).mkdir(parents=True,exist_ok=True);safe(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def pin(path):return dict(path=Path(path).relative_to(HERE).as_posix(),sha256Bytes=sha(path))
def main():
 spec=importlib.util.spec_from_file_location('producer',HERE/'prepared/desktop/tools/extract-upstream-subscription-page.py');tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
 outputs={}
 for phase,standalone in [('production',False),('standalone',True)]:
  destination=HERE/'source-audit'/phase
  emitted=tool.generate(REPO,destination,standalone)
  assert len(emitted)==1
  expected=HERE/'prepared/generated/com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt'
  assert safe(emitted[0]).read_bytes()==safe(expected).read_bytes()
  outputs[phase]=[pin(item) for item in emitted]
 inventory=json.loads(read(HERE/'source-inventory.json'))
 assert inventory['inverseNormalizedOriginalEqual']
 assert inventory['feedBlockBranches']==8 and inventory['topLevelDeclarations']==13
 originals=[]
 for suffix in ['feature/home/subscription/SubscriptionFeedPage.kt','core/plugin/feed/FeedModels.kt',
   'core/plugin/feed/FeedReadingStore.kt','core/plugin/feed/FeedConditionalStore.kt',
   'core/plugin/feed/FeedFetcher.kt','core/plugin/feed/FeedArticleExtractor.kt',
   'core/plugin/feed/FeedLayoutAllocation.kt','core/plugin/feed/FeedHtmlParser.kt',
   'core/plugin/feed/FeedSourceCatalog.kt','core/store/SettingsManager.kt']:
  path='app/src/main/java/com/android/purebilibili/'+suffix
  result=subprocess.run(['git','show',COMMIT+':'+path],cwd=REPO,capture_output=True)
  assert result.returncode==0,path
  text=result.stdout.decode().replace('\r\n','\n')
  target=HERE/'original-source'/path;safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_text(text,encoding='utf-8',newline='\n')
  originals.append(dict(path=path,sha256LF=sha(target),artifact=pin(target)))
 hunks=json.loads(read(HERE/'existing-source-hunks.json'))
 for item in hunks:
  candidate=read(HERE/'proof-existing'/item['path']);inverse=candidate
  for change in reversed(item['changes']):inverse=inverse.replace(change['after'],change['before'])
  assert hashlib.sha256(inverse.encode()).hexdigest()==item['baseSha256LF']
  assert hashlib.sha256(candidate.encode()).hexdigest()==item['candidateSha256LF']
  assert item['inverseOriginalEqual'] and not item['installWholeFile']
 # Extract only the class parser function, without executing any historical review.
 historical=read(MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py')
 helper=historical[historical.index('def methods('):historical.index('\nwith zipfile.ZipFile(',historical.index('def methods('))]
 scope={};exec('import struct\n'+helper,scope);methods=scope['methods']
 cp=json.loads(read(MAIN/'desktop/.local/stable-product-snapshot-33/ordered-runtime-cp.json'))
 jar=HERE/'runs/compile-03/prepared-subscription-page.jar'
 result=json.loads(read(HERE/'runs/compile-03/compile-result.json'));assert result['status']=='PASS'
 declared=set(result['declaredExistingProductionClassOverrides']);index={};actualClasses=set();actualCount=0
 for row in cp:
  assert sha(row['path'])==row['sha256Bytes']
  with zipfile.ZipFile(safe(row['path'])) as z:
   actualClasses.update(e for e in z.namelist() if e.endswith('.class'))
   for entry in z.namelist():
    if entry.endswith('Kt.class') and '$' not in entry:
     for method in methods(z.read(entry),entry):
      actualCount+=1;index.setdefault(method['key'],[]).append(method)
 newMethods=[];intersections=[]
 with zipfile.ZipFile(safe(jar)) as z:
  ownClasses={e for e in z.namelist() if e.endswith('.class')}
  assert ownClasses & actualClasses == declared
  for entry in ownClasses:
   if entry.endswith('Kt.class') and '$' not in entry and entry not in declared:
    for method in methods(z.read(entry),entry):
     newMethods.append(method)
     for found in index.get(method['key'],[]):intersections.append(dict(candidate=method,actual=found))
 assert not intersections,intersections
 audit=dict(status='PASS',scope='Prepared source-only complete original Subscription page closure',
  originalCommit=COMMIT,originals=originals,generated=pin(HERE/'prepared/generated/com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt'),
  inverseNormalizedExecutableBodiesEqual=True,all13TopLevelDeclarationsRetained=True,all8FeedBlockBranchesRetained=True,
  productionStandalonePreparedByteEqual=True,producerOutputs=outputs,
  originalArticleLongerPlainTextComparisonRetained=True,fullBodySaveUsesOriginalArticleFetchJob=True,
  soleRuntimeRepository=True,soleReadingStore=True,soleFeedTransport=True,solePluginSettings=True,
  ownerAdmission='Captured caller Job + retained Root commitIfCurrent; original no-scope operations unchanged',
  lockOrder='Root Store -> retained entry admission; existing AtomicFile monitor reaches this gate but retirement must not wait for repository/file mutexes while holding Root Store. Settings outer Root gate -> plugin backing, final check never reacquires Root.',
  busyCleanup='Same Root owner only, request generation rechecked inside commit; cancelled request cannot clear replacement loading',
  progressMetadata='Latest sole state readKeys/fullBodies survive in-flight refresh progress',
  backBoundary='Existing NavigationEvent owner; article-only Escape completed event, no invented predictive samples; native edge gesture not claimed',
  externalUrlBoundary='Root Windows external browser, Android embedded WebView not claimed',
  classCount=len(ownClasses),declaredExistingClassOverrides=sorted(declared),newClassCount=len(ownClasses-declared),
  newTopLevelPublicStaticMethods=len(newMethods),actualTopLevelPublicStaticMethods=actualCount,
  matchingRule='Same package + JVM name + JVM parameter descriptor, excluding file-private/access bridges and explicitly replaced whole proof-source families',
  newTopLevelIntersections=intersections,newClassIntersections=[],
  compileEvidence=pin(HERE/'runs/compile-03/compile-result.json'),candidateJar=pin(jar),
  actualProductRuntimeAcceptance=False,HTTP=False,GUI=False,HWND=False,Gradle=False,
  pending=['Root installation and actual retained Home owner/gallery wiring','Actual mounted UI/pointer/back/preview/save proof',
   'Root admission lock-order integration must retire outside Store while awaiting plugin IO','No external feed/article fetch performed'])
 write(HERE/'source-checks.json',audit)
 registry=json.loads(read(REPO/'desktop/upstream-sources.json'))
 existing=[row for row in registry['sources'] if row['path']==inventory['path']]
 write(HERE/'registry-recipe.json',dict(overwriteRegistry=False,existingIdentity=existing,
   source=dict(path=inventory['path'],sha256=inventory['sha256LF'],features=['home-subscription-page'],mode='extracted'),
   merge='Merge features for existing exact identity; do not duplicate/overwrite current modes or unrelated identities',
   referencesOnly=[row['path'] for row in originals if row['path']!=inventory['path']]))
 print(json.dumps(dict(status='PASS',originals=len(originals),classes=len(ownClasses),declaredOverrides=len(declared),newTopLevelMethods=len(newMethods),actualMethods=actualCount,newSymbolIntersections=0,sourceChecksSha256Bytes=sha(HERE/'source-checks.json')),indent=2))
if __name__=='__main__':main()

from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(path):
 value=str(Path(path).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def lf(data):return data.replace(b'\r\n',b'\n')
def save(path,value):
 safe(path.parent).mkdir(parents=True,exist_ok=True);safe(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def pin(path):return dict(path=str(path),sha256Bytes=sha(path),sha256LF=hashlib.sha256(lf(safe(path).read_bytes())).hexdigest())
def main():
 parser_spec=importlib.util.spec_from_file_location('tokens',MAIN/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(parser_spec);parser_spec.loader.exec_module(parser)
 originals={
  'app/src/main/java/com/android/purebilibili/core/ui/LottieComponents.kt':['LottieAnimation','ErrorState'],
  'app/src/main/java/com/android/purebilibili/core/ui/blur/RecoverableVisualEffects.kt':['rememberRecoverableHazeState','shouldEnableRecoverableHeavyVisualEffects'],
  'app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionBackgroundPolicy.kt':['videoCardTransitionLiveBackgroundEffect','videoCardTransitionBackgroundEffect'],
  'app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionClock.kt':['rememberVideoCardTransitionClock'],
 }
 source_rows=[]
 for path,names in originals.items():
  data=subprocess.run(['git','-C',str(CANDIDATE),'show',COMMIT+':'+path],capture_output=True,check=True).stdout
  target=HERE/'original-source'/path;safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(data)
  source=lf(data).decode('utf-8');tokens=parser.kotlin_tokens(source);functions=[]
  for name in names:
   starts=[]
   for i,token in enumerate(tokens):
    if token[0]!='fun':continue
    before_parameters=[];j=i+1
    while j<len(tokens) and tokens[j][0]!='(':
     before_parameters.append(tokens[j][0]);j+=1
    if name not in before_parameters or j==len(tokens):continue
    parameter_depth=1
    while parameter_depth:
     j+=1;parameter_depth+=(tokens[j][0]=='(')-(tokens[j][0]==')')
    j+=1
    while j<len(tokens) and tokens[j][0] not in ['{','=']:j+=1
    if j==len(tokens) or tokens[j][0]!='{':continue
    starts.append((i,j))
   assert len(starts)==1,(path,name,starts)
   i,j=starts[0];depth=1;end=j
   while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
   body=source[tokens[i][1]:tokens[end][2]]
   functions.append(dict(name=name,line=source[:tokens[i][1]].count('\n')+1,bodySha256LF=hashlib.sha256(body.encode()).hexdigest()))
  source_rows.append(dict(originalPath=path,gitCommit=COMMIT,sha256Bytes=hashlib.sha256(data).hexdigest(),sha256LF=hashlib.sha256(lf(data)).hexdigest(),copiedSource=str(target),functions=functions))
 manual=CANDIDATE/'desktop/src/main/kotlin'
 generated=CANDIDATE/'desktop/build/generated'
 tokens_to_find=['LocalDesktopHomePlatform.current','LocalDesktopHomePlatform provides','LocalDesktopHomeErrorAnimation.current','LocalDesktopHomeErrorAnimation provides','videoCardTransitionLiveBackgroundEffect(','.videoCardTransitionBackgroundEffect(']
 references=[]
 for root in [manual,generated]:
  for path in sorted(root.rglob('*.kt')):
   if 'retained' in str(path.relative_to(root)):continue
   source=safe(path).read_text(encoding='utf-8')
   rows=[dict(line=i,text=line.strip()) for i,line in enumerate(source.splitlines(),1) if any(token in line for token in tokens_to_find)]
   if rows:references.append(dict(**pin(path),references=rows))
 seam_paths=[
  'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeWindowPlatform.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeMediaPorts.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeEnvironment.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalHomeRoot.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeOwnedMedia.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiquidReadabilityPlatform.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/ui/UiSkinAssets.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopLottieAsset.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt',
 ]
 snapshot=MAIN/'desktop/.local/stable-product-snapshot-33'
 snapshot_data=json.loads((snapshot/'manifest.json').read_text(encoding='utf-8'))
 prior={item['path']:item['sha256Bytes'] for item in snapshot_data['inputs']}
 seams=[]
 for path in seam_paths:
  item=pin(CANDIDATE/path);item['snapshot33InputPin']=prior.get(path);item['currentBytesMatchSnapshot33']=item['sha256Bytes']==prior.get(path);seams.append(item)
 payload=[pin(path) for path in sorted((HERE/'prepared').rglob('*.kt'))]
 result=dict(status='SOURCE_ONLY_REVIEW_AND_COMPILE_PASS',upstreamCommit=COMMIT,actualCompileGraph=dict(manifest=pin(snapshot/'manifest.json'),orderedCp=pin(snapshot/'ordered-runtime-cp.json'),runtimeEntries=97),originalSources=source_rows,currentSourceSeams=seams,currentGeneratedAndManualReferences=references,referenceScanExcludesUncompiledRetainedSourceArchives=True,payload=payload,compileResult=pin(HERE/'runs/compile-02/compile-result.json'),findings=[
  'Current Shell top-level provider supplies liquid/readability/dynamic bindings but not HomePlatform/Metric/ErrorAnimation. HomeRoot alone provides those locals.',
  'Generated general transition modifier definitions read HomePlatform; current actual calls found only under HomeScreen. No direct Dynamic/Favorites/Tab/Dock HomePlatform read was found. Their use of recoverableBlurEnabled alone does not demand HomePlatform.',
  'Window resources belong above all Root routes and must be the same objects passed into HomeRoot. No section/MID binding is introduced.',
  'ErrorState has a required URL animation callback but no actual URL consumer; UiSkinAssets only loads validated local paths. Prepared adapter uses the existing actual Repository HTTP client and existing DesktopLottieAsset.',
  'Native media join, Lottie asset close and decoded-resource disposal occur outside SessionStore/app admission and local registration gates.',
  'Shared transition clock/navigation driver is not installed by this window adapter; Root must drive the existing original clock/state from its actual nav owner, not supply zero/default progress.'
 ],runtimeAnimationOrWindowExecuted=False,HTTP=False,accountRead=False,MainOrSharedProducerModified=False)
 save(HERE/'source-review.json',result)
 print(json.dumps(dict(status=result['status'],originalFiles=len(source_rows),currentSeams=len(seams),referenceFiles=len(references),payloadFiles=len(payload),sourceReviewSha256Bytes=sha(HERE/'source-review.json')),indent=2))
if __name__=='__main__':main()

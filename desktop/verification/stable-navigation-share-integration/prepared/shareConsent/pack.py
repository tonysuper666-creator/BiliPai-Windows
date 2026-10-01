from pathlib import Path
import hashlib,json,importlib.util,subprocess,difflib
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def js(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2))
pins=json.loads(read(LANE/'pins.json'));records=json.loads(read(LANE/'generation-records.json'))
bodies={r['output']:read(LANE/'prepared/generated'/r['output']) for r in records}
producer='''"""Original complete stable video share UI/services and explicit Windows effects.
Payload/build stay in the sole Home producer. Android Activity resolution remains
source-retained only; Windows exposes its real system chooser/copy/save capabilities.
"""
from pathlib import Path
import argparse,hashlib,subprocess
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
SOURCE_PINS=PIN_VALUE
BODIES=BODY_VALUE
def read(p):return Path(p).read_text(encoding='utf-8').replace('\\r\\n','\\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def inventory(repo):
 return [dict(path=p,mode='policy-extract',features=['video-share-original-windows'],sha256=h) for p,h in SOURCE_PINS.items() if any(p.endswith('/'+name+'.kt') for name in ['VideoShareSheet','VideoSharePolicy','VideoShareSheetMotion','VideoShareToFollowingDialog','VideoShareMoreTargetsSheet','VideoShareCoverService','VideoShareCardService','CrashTrackingConsentDialog','MessageRepository'])]
def generate(repo,out,standalone=False):
 repo=Path(repo);out=Path(out)
 for path,digest in SOURCE_PINS.items():
  assert sha(read(repo/path))==digest,path
  original=subprocess.run(['git','show',COMMIT+':'+path],cwd=repo,capture_output=True,check=True).stdout.decode().replace('\\r\\n','\\n')
  assert sha(original)==digest,path
 files=[]
 for target,body in BODIES.items():
  path=out/target;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8',newline='\\n');files.append(path)
 return files
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--source-repo',required=True);p.add_argument('--output-dir',required=True);a=p.parse_args();generate(a.source_repo,a.output_dir)
'''.replace('PIN_VALUE',repr(pins)).replace('BODY_VALUE',repr(bodies))
write(LANE/'prepared/tools/extract-upstream-video-share-consent.py',producer)
sp=importlib.util.spec_from_file_location('share_replay',LANE/'prepared/tools/extract-upstream-video-share-consent.py');m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
m.generate(REPO,LANE/'replay-production')
checks=[]
for path,digest in pins.items():
 assert sha(read(REPO/path))==digest
 checks.append('source Git/LF '+path)
for row in records:
 original=read(LANE/'original-stable'/row['source']);prepared=read(LANE/'prepared/generated'/row['output'])
 assert prepared==read(LANE/'replay-production'/row['output']);checks.append('producer replay '+row['output'])
 changes=[];a=original.splitlines(keepends=True);b=prepared.splitlines(keepends=True)
 for tag,i1,i2,j1,j2 in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
  if tag=='equal':continue
  changes.append({'tag':tag,'originalLine':i1+1,'preparedLine':j1+1,'before':''.join(a[i1:i2]),'after':''.join(b[j1:j2]),'originalRange':[i1,i2],'preparedRange':[j1,j2]})
 reverse=b[:]
 for h in reversed(changes):reverse[h['preparedRange'][0]:h['preparedRange'][1]]=a[h['originalRange'][0]:h['originalRange'][1]]
 assert ''.join(reverse)==original;checks.append('exact reverse '+row['source'])
 name=Path(row['source']).stem
 js(LANE/'adaptation-diffs'/(name+'.json'),changes)
 js(LANE/'reverse-adapters'/(name+'.json'),{'source':row['source'],'output':row['output'],'originalSha256LF':sha(original),'preparedSha256LF':sha(prepared),'changes':changes})
for token in ['loadRemainingPages','do {','distinctBy(FollowingUser::mid)','delay(300)','val failed = mutableSetOf<Long>()','selectedIds = failed','nextPage++','recipients.forEachIndexed']:
 source=bodies['com/android/purebilibili/feature/video/share/VideoShareToFollowingDialog.kt'];assert token in source,token;checks.append('original friend '+token)
for token in ['BottomBarLiquidSegmentedControl','AppNativeSegmentedControl','showFollowingPicker','showMoreTargets','prepareVideoShareMedia','hideVideoShareSheet','sharingTarget = null']:
 assert token in bodies['com/android/purebilibili/feature/video/share/VideoShareSheet.kt'];checks.append('complete sheet '+token)
for token in ['21007','21015','21020','21026','21046','21047','25003','25005','MessageSendPayloadFactory.buildTextContent','if (e is CancellationException) throw e']:
 assert token in bodies['com/android/purebilibili/data/repository/DesktopOriginalVideoShareMessages.kt'];checks.append('original message '+token)
card=bodies['com/android/purebilibili/feature/video/share/VideoShareCardService.kt']
for line in read(LANE/'original-stable/app/src/main/java/com/android/purebilibili/feature/video/share/VideoShareCardService.kt').splitlines():
 if line.startswith('private const val CARD_'):assert line in card;checks.append('original card scalar '+line)
assert 'VideoSharePayload(' not in bodies['com/android/purebilibili/feature/video/share/DesktopOriginalVideoSharePolicy.kt'];checks.append('sole Home payload reused')
for output,body in bodies.items():
 assert 'import android.' not in body and 'HttpURLConnection' not in body and 'FileProvider' not in body,output
 checks.append('physical Windows seam '+output)
native=json.loads(read(LANE/'native-local-hunks.json'))
for target in native['targets']:
 s=read(REPO/target['target']);assert sha(s)==target['baseSha256LF']
 for row in native['rows']:
  if row['target']==target['target']:assert s.count(row['before'])==1;s=s.replace(row['before'],row['after'],1);checks.append('exact local hunk '+row['target'])
 assert sha(s)==target['candidateSha256LF'];assert s==read(LANE/'review-only-native'/target['target'])
js(LANE/'source-audit.json',{'passed':True,'checks':len(checks),'items':checks,'fullOriginalFilesReverseRecovered':len(records),'newDependencies':0,'nativeDLLBuilt':False})
js(LANE/'source-inventory.json',{'pinnedCommit':m.COMMIT,'sourcePins':pins,'generatedSources':records,'soleHomePayloadReuse':True,'windowsPhysicalEffects':['original friend private text protocol','actual system native text/image chooser','copy link','Root-window save complete card','same serial local diagnostic actor'],'androidActivityResolver':'complete source retained review-only; no fake package/candidate enumeration'})
print('source/replay/reverse checks',len(checks))

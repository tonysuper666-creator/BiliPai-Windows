from pathlib import Path
import hashlib,importlib.util,json,os
P=Path(__file__).resolve().parent;M=P.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def w(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX)else PREFIX+s)
def sha(p):return hashlib.sha256(w(p).read_bytes()).hexdigest()
producer=P/'prepared/existing/desktop/tools/extract-upstream-video-full-owner.py'
spec=importlib.util.spec_from_file_location('producer',producer)
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
test=P/'replayed';row=p.generate_original_video_progress(M.parent/'BiliPai-v023',test)
assert row['generated'] and row['mode']=='policy-extract'
prepared=P/'generated/com/android/purebilibili/feature/video/controller/PlaybackProgressManager.kt'
assert w(test/row['path']).read_bytes()==w(prepared).read_bytes()
result=json.loads((P/'proof-actual74/result.json').read_text());assert result['passed'] and result['productOverrides']==[]
whitelist=[dict(kind='new',source='prepared/manual/com/bilipai/desktop/ui/DesktopOriginalPlaybackProgressStorage.kt',
 destination='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlaybackProgressStorage.kt',
 sha256Bytes=sha(P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalPlaybackProgressStorage.kt')),
 dict(kind='twoExactHunks',source='producer-hunks.json',destination='desktop/tools/extract-upstream-video-full-owner.py'),
 dict(kind='singleIdentityUnion',source='registry-delta.json',destination='desktop/upstream-sources.json')]
(P/'install-whitelist.json').write_text(json.dumps(whitelist,indent=2)+'\n',encoding='utf8')
(P/'source-review.json').write_text(json.dumps(dict(producerReplayExact=True,originalFullBodyInverseExact=True,
 originalTarget=p.COMMIT,productionNewFiles=1,producerExactEdits=2,newOriginalIdentities=1,gradleDelta=False,
 rootScope='app/globalStore',originalManagerMemoryReadAfterApply=True,actual74Assertions=22,
 originalAndroidSharedPreferencesSubset=True,fullRootMounted=False),indent=2)+'\n',encoding='utf8')
raw=[];excluded=[]
for f in sorted(P.rglob('*')):
 if not f.is_file() or f.name=='frozen-handoff.json' or '__pycache__' in f.parts:continue
 rec=dict(path=f.relative_to(P).as_posix(),sha256Bytes=sha(f),bytes=w(f).stat().st_size)
 if f.suffix in ['.jar','.class','.pyc']:excluded.append(rec)
 else:raw.append(rec)
manifest=dict(schemaVersion=1,task='stable-original-progress-storage-parity',artifacts=raw,
 excludedRuntimeArtifacts=excluded,installWhitelist=whitelist,wholeRootAccepted=False)
(P/'frozen-handoff.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf8')
print(len(raw),'raw',sha(P/'frozen-handoff.json'))

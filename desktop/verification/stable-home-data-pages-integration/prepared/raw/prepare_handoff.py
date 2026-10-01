from pathlib import Path
import json,hashlib,importlib.util,difflib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
registry=json.loads(read(REPO/'desktop/upstream-sources.json'))
records=[]
pins=json.loads(read(HERE/'selected-declaration-identities.json'))['sourcePins']
# Original request bodies + platform semantics references; no replacement registry is produced.
for path in [*pins,'app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt','app/src/main/java/com/android/purebilibili/core/util/AnalyticsHelper.kt','app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt']:
 body=read(REPO/path);existing=next((r for r in registry['sources'] if r['path']==path),None)
 feature='home-protocols' if path in pins else 'home-protocol-platform-reference'
 records.append({'path':path,'sha256':sha(body),'sha256Bytes':hashlib.sha256(safe(REPO/path).read_bytes()).hexdigest(),
  'mode':existing['mode'] if existing else 'direct' if path.endswith('WatchLaterRefreshBus.kt') else 'policy-extract',
  'features':[feature],'existingIdentity':existing is not None,'existingMode':existing['mode'] if existing else None,
  'referenceOnly':path not in pins})
write(HERE/'source-inventory.json',json.dumps({'pinnedCommit':registry['upstreamCommit'],'observedRegistryCount':len(registry['sources']),'records':records,'mergeOnly':True},indent=2)+'\n')

rel='desktop/tools/extract-upstream-home-page.py';base=read(REPO/rel)
anchor='def generate(repo, output, standalone=False):'
helper='''def _bind_preview_subject(text):
    # Android can keep AnimatedVisibility composed while the subject changes. The native
    # preview owns a source/coroutine, so preserve the original caller but scope its remembered
    # URL/player effect to the actual immutable BVID/CID subject. Epoch is Root's outer key.
    opening="            if (item != null) {\\n                com.android.purebilibili.feature.home.components.VideoPreviewDialog("
    closing="                hazeState = hazeState\\n            )\\n            }\\n        }\\n        } // Unified header/feed depth snapshot"
    assert text.count(opening)==1 and text.count(closing)==1
    text=text.replace(opening,"            if (item != null) {\\n                key(item.bvid, item.cid) {\\n                com.android.purebilibili.feature.home.components.VideoPreviewDialog(")
    return text.replace(closing,"                hazeState = hazeState\\n            )\\n                } // Native preview subject lifetime\\n            }\\n        }\\n        } // Unified header/feed depth snapshot")

'''
assert base.count(anchor)==1;desired=base.replace(anchor,helper+anchor)
old='            scope["write"](target,scope["read"](source));written.append(target)'
new='''            body=scope["read"](source)
            if row and row["path"]=="app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt":body=_bind_preview_subject(body)
            scope["write"](target,body);written.append(target)'''
assert desired.count(old)==1;desired=desired.replace(old,new)
write(HERE/'prepared/sole-producer-delta/extract-upstream-home-page.py',desired)
write(HERE/'home-preview-subject.patch',''.join(difflib.unified_diff(base.splitlines(True),desired.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel)))
write(HERE/'home-preview-subject-base.json',json.dumps({'path':rel,'baseSha256LF':sha(base),'baseSha256Bytes':hashlib.sha256(safe(REPO/rel).read_bytes()).hexdigest(),'desiredSha256LF':sha(desired),'rawSourceUnchanged':True,'originalCallerUnchangedInsideKey':True},indent=2)+'\n')
spec=importlib.util.spec_from_file_location('preview',(HERE/'prepared/sole-producer-delta/extract-upstream-home-page.py'));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
oldHome=MAIN/'desktop/.local/stable-home-page-parity/prepared/generated/com/android/purebilibili/feature/home/DesktopOriginalHomeScreen.kt'
newHome=mod._bind_preview_subject(read(oldHome))
write(HERE/'prepared/preview-delta/com/android/purebilibili/feature/home/DesktopOriginalHomeScreen.kt',newHome)

spec=importlib.util.spec_from_file_location('protocols',HERE/'prepared/tools/extract-upstream-home-protocols.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.generate(REPO,HERE/'producer-replay/default')
m.generate(REPO,HERE/'producer-replay/standalone',True)
checks=[]
for file in (HERE/'prepared/generated').rglob('*.kt'):
 rel=file.relative_to(HERE/'prepared/generated');candidate=HERE/'producer-replay/standalone'/rel
 assert read(file)==read(candidate),str(rel)
 if file.name!='WatchLaterRefreshBus.kt':assert read(HERE/'producer-replay/default'/rel)==read(file),str(rel)
 checks.append({'path':rel.as_posix(),'sha256LF':sha(read(file)),'standaloneMatchesCompiledPayload':True})
assert not (HERE/'producer-replay/default/com/android/purebilibili/core/refresh/WatchLaterRefreshBus.kt').exists()
write(HERE/'producer-replay-proof.json',json.dumps({'productionOutputs':6,'standaloneOutputs':7,'compiledPayloadIdentical':checks,'directBusProductionSkipped':True},indent=2)+'\n')
print('inventory, preview sole-producer delta and exact protocol replay prepared')

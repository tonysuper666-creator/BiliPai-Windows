from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];LOCAL=REPO/'desktop/.local'
DEST=REPO/'desktop/verification/image-saving';SNAP=HERE/'main-product-snapshot-04'
records={};excluded=[];cohorts={}
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def save(path,value):
    ext(path).parent.mkdir(parents=True,exist_ok=True)
    ext(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def copy(source,relative,expected=None):
    raw=ext(source).read_bytes();digest=hashlib.sha256(raw).hexdigest()
    assert expected is None or digest==expected
    target=DEST/relative;ext(target).parent.mkdir(parents=True,exist_ok=True);ext(target).write_bytes(raw)
    path=target.relative_to(REPO).as_posix();row=dict(path=path,sha256Bytes=digest,bytes=len(raw))
    if path in records:assert records[path]==row
    records[path]=row
def frozen(lane,label,name,pin):
    source=LOCAL/lane;manifest=source/name;assert sha(manifest)==pin
    data=json.loads(ext(manifest).read_bytes());rows=data.get('artifacts',data.get('files',data.get('formalRaw')));assert rows
    accepted=0
    for row in rows:
        path=Path(row['path']);relative=path.relative_to(source).as_posix() if path.is_absolute() else row['path'].replace('\\','/')
        actual=Path(row['rawPath']) if 'rawPath' in row else source/relative
        assert actual.is_relative_to(source),str(actual)
        assert sha(actual)==row['sha256Bytes'],relative
        suffix=Path(relative).suffix.lower()
        private=any(part in {'private-native-home','task-native-temp','private-native-temp','task-store','native-cache','private-home'} for part in Path(relative).parts)
        if suffix in {'.jar','.class','.kotlin_module','.dll','.exe','.zip','.tar','.gz','.lock','.dat','.x'} or private:
            excluded.append(dict(cohort=label,path=relative,sha256Bytes=row['sha256Bytes'],reason='Locally verified compiled/runtime artifact; not committed.'))
            continue
        copy(actual,label+'/'+relative,row['sha256Bytes']);accepted+=1
    for row in data.get('evidenceOnlyBinaries',[])+data.get('evidenceOnlyRuntimeArtifacts',[])+data.get('runtimeReferencesOnly',[]):
        assert sha(source/row['path'])==row['sha256Bytes']
        excluded.append(dict(cohort=label,path=row['path'],sha256Bytes=row['sha256Bytes'],reason=row.get('reason',row.get('scope','Evidence-only runtime artifact.'))))
    copy(manifest,label+'/'+name,pin)
    return dict(declared=len(rows),archived=accepted,excluded=len(rows)-accepted,additionalEvidenceOnlyRuntime=len(data.get('evidenceOnlyBinaries',[]))+len(data.get('evidenceOnlyRuntimeArtifacts',[]))+len(data.get('runtimeReferencesOnly',[])))
install=json.loads(ext(HERE/'source-install-receipt.json').read_bytes())
for lane,name,pin in install['pins']:
    label={'dynamic-detail-reply-parity/detail-container-next/static-save-location-next':'location-prepared',
      'native-known-folder-save-parity':'known-folder-prepared',
      'dynamic-detail-reply-parity/detail-container-next/static-save-codec-next':'codec-prepared',
      'settings-image-save-path-parity':'settings-prepared','dynamic-batch-save-parity':'batch-prepared'}[lane]
    cohorts[label]=frozen(lane,label,name,pin)
for entry in json.loads(ext(HERE/'actual-main-handoffs.json').read_bytes()):
    cohorts[entry['label']]=frozen(entry['lane'],entry['label'],entry['manifest'],entry['sha256Bytes'])
review=json.loads(ext(HERE/'root-source-review.json').read_bytes())
acceptance=json.loads(ext(HERE/'root-runtime-review.json').read_bytes());assert acceptance['passed']
for name in ['install.py','source-install-receipt.json','snapshot-main.py','review-main.py','review-runtime.py','record-runtime.init.gradle','actual-main-runtime-paths.json','root-source-review.json','root-runtime-review.json','actual-main-handoffs.json','gradle-main-01.log','gradle-main-02.log','freeze-and-stage.py']:
    copy(HERE/name,'root-integration/'+name)
for name in ['manifest.json','ordered-runtime-cp.json']:
    copy(SNAP/name,'root-integration/main-product-snapshot-04/'+name)
save(DEST/'excluded-runtime-artifacts.json',dict(locallyVerified=True,binariesCommitted=False,artifacts=excluded))
copy(DEST/'excluded-runtime-artifacts.json','excluded-runtime-artifacts.json')
report=dict(baseCommit=install['base'],upstreamTag='v0.2.3-alpha.9',upstreamCommit='fcf84853b287662e8a9129ea0d38576c36522a34',
 sourceWindowsVersion='0.2.406.9',deployedWindowsVersion='0.2.406.5',registryBefore=622,registryAfter=622,cohorts=cohorts,
 sourceReview=review,runtimeReview=acceptance,
 changes=['One original image_save_tree_uri preference on the existing global settings backing.',
 'Original settings row and complete choose/reset dialog mounted in Storage and original search target.',
 'Actual Windows KnownFolder Pictures/BiliPai default, custom failure fallback with fresh staged input.',
 'Original URL classifiers and names; raw GIF/WebP, Windows native PNG/JPEG95 encoding.',
 'Batch attempts all ordinary failures, propagates cancellation immediately.',
 'Actual card/static/Motion Photo and existing comment PNG consumers share the same locations and existing account commit guard.'],
 scope=dict(mainCompiled=True,packaged=False,wholeRootShellAccepted=False,actualWindowsChooserAccepted=False,hwndAccepted=False,
  avatarSaveMounted=False,dynamicBitmapSaveMounted=False,defaultVideoMoviesDirectoryMapped=False,
  AndroidDecoderByteEquivalence=False,WindowsPhotosIndexingAccepted=False,nativeLivePhotoPlaybackAccepted=False,systemShareAccepted=False,
  fullApplicationParity=False))
save(DEST/'integration-report.json',report);copy(DEST/'integration-report.json','integration-report.json')
save(DEST/'artifact-manifest.json',dict(artifactCount=len(records),artifacts=sorted(records.values(),key=lambda r:r['path']),binariesCommitted=False,
 scope='Prepared original/platform sources and actual Main04 narrow consumer/UI proofs; desktop EXE not updated.'))
paths=list(records)+[(DEST/'artifact-manifest.json').relative_to(REPO).as_posix()]
for arguments in [['diff','--name-only','-z'],['diff','--cached','--name-only','-z'],['ls-files','--others','--exclude-standard','-z']]:
    paths.extend(path for path in subprocess.check_output(['git','-c','core.longpaths=true']+arguments,cwd=REPO).decode().split(chr(0)) if path)
paths=sorted(set(paths))
assert all(not path.startswith('desktop/.local/') and 'private-native-home' not in Path(path).parts for path in paths)
assert all(Path(path).suffix.lower() not in {'.jar','.class','.kotlin_module','.dll','.exe','.zip','.tar','.gz','.lock','.dat','.x'} for path in paths)
ext(HERE/'staging-paths.txt').write_bytes(chr(0).join(paths).encode()+b'\x00')
print(json.dumps(dict(rawArtifacts=len(records),stagePaths=len(paths),manifestSha256Bytes=sha(DEST/'artifact-manifest.json'),reportSha256Bytes=sha(DEST/'integration-report.json'))))

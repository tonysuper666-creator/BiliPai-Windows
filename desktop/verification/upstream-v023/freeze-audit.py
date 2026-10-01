"""Archive exact source evidence without binaries or candidate runtime stores."""
from pathlib import Path
import hashlib,json,os,re,subprocess
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];CANDIDATE=BASE.parent/'BiliPai-v023'
OUT=BASE/'desktop/verification/upstream-v023'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def read(path):return ext(path).read_bytes()
def save(path,value):ext(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert not (OUT/'artifact-manifest.json').exists();ext(OUT).mkdir(parents=True,exist_ok=True)
rows=[];excluded=[]
def copy(source,relative):
    raw=read(source);target=OUT/relative;ext(target.parent).mkdir(parents=True,exist_ok=True);ext(target).write_bytes(raw)
    rows.append(dict(path=relative,bytes=len(raw),sha256Bytes=sha(raw)))
def archive_lane(source,manifest_name,pin,label):
    path=source/manifest_name;raw=read(path);assert sha(raw)==pin
    manifest=json.loads(raw);copy(path,label+'/'+manifest_name)
    for row in manifest['artifacts']:
        original=source/row['path'];raw=read(original)
        assert sha(raw)==row['sha256Bytes']
        expected_size=next((row[k] for k in ['sizeBytes','bytes','size','byteLength'] if k in row),None)
        if expected_size is not None:assert len(raw)==expected_size
        if original.suffix.lower() in {'.jar','.dll','.dat','.lock','.class','.zip'}:
            excluded.append(dict(source=str(original),sha256Bytes=sha(raw),bytes=len(raw),why='immutable runtime reference, not Git source evidence'))
        else:copy(original,label+'/'+row['path'])

for name in ['audit-release.py','prepare-candidate.py','rebase-simple-producers.py','install-protocol-producers.py',
             'install-preview-producer.py','freeze-audit.py','release-api.json','source-delta.json','summary.json',
             'upstream-numstat.txt','candidate-rebase.json','simple-producers-review.json','protocol-install.json','preview-install.json',
             'gradle-candidate-01.log','gradle-infrastructure-02.log','gradle-selected-producers-03.log','gradle-preview-producer-04.log']:
    copy(HERE/name,name)
for directory,dirs,names in os.walk(ext(HERE/'simple-producers-02')):
    for name in sorted(names):
        source=Path(directory)/name
        copy(source,'selected-producers/'+source.relative_to(ext(HERE/'simple-producers-02')).as_posix())
archive_lane(CANDIDATE/'desktop/.local/stable-dynamic-protocol-rebase','detail-reply-evidence-manifest.json',
             '0a08d27a090c6d0eb402fd58f42fbb9951895a76db62c0fb3992c9ac43976b46','protocol-prepared')
archive_lane(BASE/'desktop/.local/stable-image-preview-producer-parity','evidence-manifest.json',
             'aa2f0fded76142156e7470d40d999939e78fe9ea3a86300353ab8e1e805a92d9','preview-prepared')
archive_lane(BASE/'desktop/.local/stable-image-preview-source-review','evidence-manifest.json',
             'aad91096ddab1ff993737dab50caa3fdf4b9a46bde46da1b0fe0a738f395dc51','preview-source-review')
for relative in ['desktop/tools/extract-upstream-crash-prompt.py','desktop/tools/extract-upstream-dynamic-gallery-motion-photo.py',
                 'desktop/tools/extract-upstream-dynamic-reply-protocol.py','desktop/tools/extract-upstream-dynamic-detail-protocol.py',
                 'desktop/tools/extract-upstream-dynamic-card.py','desktop/tools/prepare-native-diagnostic-share.py','desktop/upstream-sources.json']:
    copy(CANDIDATE/relative,'candidate-source/'+relative)
logs={name:read(HERE/name).decode('utf-16' if read(HERE/name).startswith(b'\xff\xfe') else 'utf-8-sig')
      for name in ['gradle-candidate-01.log','gradle-infrastructure-02.log','gradle-selected-producers-03.log','gradle-preview-producer-04.log']}
initial=logs['gradle-candidate-01.log']
failed=re.findall(r'^> Task :([^\s]+) FAILED\s*$',initial,re.M)
assert len(failed)==9 and 'BUILD FAILED' in initial
assert all('BUILD SUCCESSFUL' in log for name,log in logs.items() if name!='gradle-candidate-01.log')
report=dict(schema='v023-candidate-source-rebase-report-v1',targetTag='v0.2.3',
    targetCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    candidateBranch='desktop/v023-parity',sourceAndResourcePinsVerified=832,
    originalProductionSourceFiles=1534,targetProductionSourceFiles=1547,
    changedUpstreamFiles=269,changedRegisteredSources=87,changedRegisteredResources=0,
    localApkMatchesOfficialPublishedAsset=True,initialClassTaskPassed=False,initialFailedTasks=failed,
    resolvedSourceAndBuildTasks=['verifyGoogleCastSources','prepareNativeDiagnosticShare','extractUpstreamCrashPrompt',
        'extractUpstreamDynamicGalleryMotionPhoto','extractUpstreamDynamicReplyProtocol','extractUpstreamDynamicDetailProtocol','extractUpstreamDynamicFullCard'],
    selectedDetailReplyVerifierPassed=True,
    remainingInitialFailures=['extractUpstreamDynamicEditor','verifyUpstreamDynamicEditorProtocol'],
    windowsCheckoutFixes=['Raw Cast notices remain byte-identical in new checkouts.','Native atomic stage writes use Windows extended paths.'],
    nativeDllSha256Bytes='2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514',
    acceptedMainSourceStillAlpha9=True,wholeStableKotlinCompiled=False,stableRuntimeAccepted=False,
    newFeaturesRuntimeAccepted=False,packaged=False,desktopFolderDeployed=False,autoPublicationAccepted=False,
    preparedOnlyBoundaries=['Preview narrow compile uses declared Main04 product-class overrides.',
        'No full stable renderer/caller/window E2E.', 'Stream upload and new feature consumers remain to align.'],
    excludedRuntimeReferences=excluded)
save(OUT/'integration-report.json',report)
raw=read(OUT/'integration-report.json');rows.append(dict(path='integration-report.json',bytes=len(raw),sha256Bytes=sha(raw)))
assert len({r['path'] for r in rows})==len(rows)
for row in rows:assert sha(read(OUT/row['path']))==row['sha256Bytes']
save(OUT/'artifact-manifest.json',dict(schema='v023-source-target-audit-artifacts-v1',frozen=True,
    targetCommit=report['targetCommit'],runtimeAccepted=False,rawArtifacts=sorted(rows,key=lambda r:r['path']),
    excludedRuntimeReferences=excluded))
print(json.dumps(dict(rawArtifacts=len(rows),manifestSha256Bytes=sha(read(OUT/'artifact-manifest.json')),
    initialFailures=len(failed),resolvedTasks=7,wholeProductAccepted=False)))

"""Archive source/raw evidence; keep binaries and private fixtures out of Git."""
from pathlib import Path
import hashlib,json,os,subprocess,sys,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
BASE=REPO/'desktop/.local/dynamic-detail-reply-parity/detail-container-next'
DEST=REPO/'desktop/verification/dynamic-detail-container'
SNAPSHOT=HERE/'main-product-snapshot-02'
def ext(p):
    value=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def save(p,obj):
    ext(p).parent.mkdir(parents=True,exist_ok=True)
    ext(p).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
records=[]
def copy(source,relative,expected=None):
    assert source.suffix not in {'.jar','.class','.kotlin_module','.dll','.exe','.zip'},source
    data=ext(source).read_bytes()
    digest=hashlib.sha256(data).hexdigest()
    if expected is not None:assert digest==expected,source
    target=DEST/relative;ext(target).parent.mkdir(parents=True,exist_ok=True);ext(target).write_bytes(data)
    records.append(dict(path=target.relative_to(REPO).as_posix(),sha256Bytes=digest,bytes=len(data)))
def frozen(source,label,manifest_name,expected):
    manifest=source/manifest_name;assert sha(manifest)==expected
    obj=json.loads(ext(manifest).read_bytes())
    rows=obj.get('artifacts',obj.get('files'))
    assert isinstance(rows,list)
    for row in rows:copy(source/row['path'],label+'/'+row['path'],row['sha256Bytes'])
    copy(manifest,label+'/'+manifest_name,expected)
registry=json.loads((REPO/'desktop/upstream-sources.json').read_bytes())
assert len(registry['sources'])==621 and len({r['path'] for r in registry['sources']})==621
main=json.loads((SNAPSHOT/'manifest.json').read_bytes())
for row in main['sourceFiles']:
    assert hashlib.sha256(ext(REPO/row['path']).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==row['sha256Lf'],row['path']
for row in json.loads((SNAPSHOT/'ordered-runtime-cp.json').read_bytes()):assert sha(row['path'])==row['sha256Bytes']
frozen(BASE/'frozen-layout-thread-02','layout-thread-prepared','frozen-handoff.json','fe377517de8ad362f6988c1c0e0e6bc9880354917e43fc277a07820af7b16758')
frozen(BASE/'liquid-ui-proof/post-admission-delta/frozen-final-01','post-admission-prepared','frozen-handoff.json','932c0baf122cf5611cef8e9ca55335a4c454857fa87e38fbc7eaf478597a9ab3')
frozen(BASE/'layout-source-provenance-addendum','source-provenance-correction','evidence-manifest.json','e3d28477d9a9144d712484ba4a7fee73a065b68b068614d4836755785d60e011')
frozen(BASE/'navigationevent-dependency-review','navigationevent-dependencies','evidence-manifest.json','c02aeaff2e619c99bb0fae6f6b08a4529e2aede5e0c276dc291b84ee8328ec8a')
frozen(REPO/'desktop/.local/dynamic-detail-root-mounted-proof/main-admission-normal-acceptance-01',
       'actual-main01-admission-composer','evidence-manifest.json','e92b8f3dc2d28ccc233b3360b9c025f4b0aef764ea6c9eb8a44e47a5c5a10ba0')
frozen(REPO/'desktop/.local/comment-png-owner-main-proof','actual-main02-png-commit','frozen-handoff.json',
       'e2bdc4fa02a58e18ef58fc94998e5d1fcd754706d598a52c949630f9cbdbb48b')
for name in ['installation-receipt.json','route-source-receipt.json','install-source.py','snapshot-main.py','freeze-and-stage.py','record-runtime.init.gradle','actual-main-runtime-paths.json',
             'gradle-main-01-final.log','gradle-main-02.log','gradle-main-03.log','gradle-main-04.log','gradle-main-05.log']:
    copy(HERE/name,'root-integration/'+name)
for number in ['01','02']:
    for name in ['manifest.json','ordered-runtime-cp.json']:
        copy(HERE/('main-product-snapshot-'+number)/name,'root-integration/main-product-snapshot-'+number+'/'+name)
tests=[]
for name in ['DesktopDynamicCardStateRegistryTest','DesktopTopicRoutingTest']:
    path=REPO/('desktop/build/test-results/test/TEST-com.bilipai.desktop.ui.'+name+'.xml')
    node=ET.fromstring(path.read_bytes());assert int(node.attrib['failures'])==int(node.attrib['errors'])==int(node.attrib['skipped'])==0
    tests.append(dict(className=name,tests=int(node.attrib['tests']),failures=0,errors=0,skipped=0))
    copy(path,'root-integration/tests/'+path.name)
report=dict(upstreamTag='v0.2.3-alpha.9',upstreamCommit='fcf84853b287662e8a9129ea0d38576c36522a34',
    sourceWindowsVersion='0.2.406.9',deployedWindowsVersion='0.2.406.5',packaged=False,
    previousMainCommit='ce9ac0aefca42c5e3058f82b3e55652a3a9da150',registryCount=621,previousRegistryCount=602,
    sourceAdditions=dict(layoutUniqueIdentities=23,layoutNew=18,layoutMerged=5,messagePolicyNew=1,messageClosureMerged=2,totalNew=19),
    mainSnapshotManifestSha256Bytes=sha(SNAPSHOT/'manifest.json'),actualRuntimeCpSha256Bytes=sha(SNAPSHOT/'ordered-runtime-cp.json'),runtimeEntries=89,
    tests=dict(actualMain01JUnit=tests,total=15,ownershipCaptureFixThenMain02CompilePassed=True),
    actualMain01Admission=dict(cases=6,productOverrides=0,normalComposerStrictRootParent='701/701',correctedMetadataAndFailedHistoryRetained=True),
    actualMain02PngCommit=dict(assertions=29,cases=9,productOverrides=0,rootGateAndDisposeBodyExtractedIntoFixture=True,
        actualRootScreenExecuted=False,nativePickerExecuted=False,
        existingTargetPreservedOnEpochRotationPageCloseAndStoreLockQueuedJobCancellation=True,
        windowsLongRouteFooterQrOverlapObserved=True,qrDecodingAccepted=False),
    whitespace=dict(frozenContainerProducerHasOneTrailingBlankLine=True,producerBytesRetainedForSourceProvenance=True),
    scope=dict(fullLayoutThreadSourceMounted=True,completeCommunityConsumerRuntimeAccepted=False,completeDesktopShellAccepted=False,
               systemShareAccepted=False,nativePickerAccepted=False,liquidGlassEnabled=False,fullDetailParity=False),
    changes=['Original detail layout and modal thread container consume one captured original reply session and existing raw Root item.',
             'Original message-link policy preserves dynamic root/target identifiers.',
             'Late detail reads retain comment counts confirmed during their request without changing unrelated fresh fields.',
             'Post admission captures the immutable original reply target before desktop coroutine dispatch.',
             'PNG final move can use the real session monitor then page export lock; captured epoch mismatches retire work without throwing during composition.'])
save(DEST/'integration-report.json',report)
records.append(dict(path=(DEST/'integration-report.json').relative_to(REPO).as_posix(),sha256Bytes=sha(DEST/'integration-report.json'),bytes=(DEST/'integration-report.json').stat().st_size))
save(DEST/'artifact-manifest.json',dict(artifacts=sorted(records,key=lambda r:r['path']),artifactCount=len(records),
    binariesCommitted=False,scope='Prepared closure and actual Main compile/admission/normal composer; full mounted Community consumer acceptance is still pending.'))
paths=[r['path'] for r in records]+[(DEST/'artifact-manifest.json').relative_to(REPO).as_posix()]
existing=subprocess.check_output(['git','-c','core.longpaths=true','diff','--name-only','-z'],cwd=REPO).decode().split(chr(0))
cached=subprocess.check_output(['git','-c','core.longpaths=true','diff','--cached','--name-only','-z'],cwd=REPO).decode().split(chr(0))
untracked=subprocess.check_output(['git','-c','core.longpaths=true','ls-files','--others','--exclude-standard','-z'],cwd=REPO).decode().split(chr(0))
paths+= [p for p in existing+cached+untracked if p and not p.startswith('desktop/verification/dynamic-detail-container/')]
assert all(not p.startswith('desktop/.local/') for p in paths)
pathspec=HERE/'staging-paths.txt';pathspec.write_bytes(chr(0).join(sorted(set(paths))).encode()+b'\x00')
print(json.dumps(dict(rawArtifacts=len(records),stagePaths=len(set(paths)),manifestSha256Bytes=sha(DEST/'artifact-manifest.json'),reportSha256Bytes=sha(DEST/'integration-report.json'))))

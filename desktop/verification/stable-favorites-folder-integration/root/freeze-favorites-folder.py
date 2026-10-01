from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-favorites-folder-integration'
assert not OUT.exists()
def wide(p):return Path('\\\\?\\'+str(p.absolute()))
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
rows=[];names=set();excluded=[]
def put(name,b):
    assert name not in names,name;names.add(name)
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
    rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
for prefix,name,d in [('full-favorites-prepared','stable-favorites-parity','9131efcc50ed7129653bb6d8e585eda86a4d8bb95584b31c0ac011822c8efcbe'),
    ('original-folder-prepared','stable-favorites-folder-sheet-parity','df54bc254658fd114a75ec96f5f01776629b02b0623aef7b80c39a44cb3db9d1')]:
    lane=MAIN/'desktop/.local'/name;raw=pin(lane/'frozen-handoff.json',d);m=json.loads(raw)
    for r in m['artifacts']:
        b=pin(lane/r['path'],r['sha256Bytes']);assert len(b)==r['bytes']
        if Path(r['path']).suffix in ('.jar','.class','.dll','.exe','.png'):
            excluded.append(dict(cohort=prefix,**r));continue
        put(prefix+'/'+r['path'],b)
    put(prefix+'/frozen-handoff.json',raw)
for name in ('install-favorites-sources.py','favorites-source-install.json'):
    put('root/'+name,read(HERE/name))
put('root/freeze-favorites-folder.py',read(Path(__file__)))
for name in ('classes-17.log','favorite-projection-tests-17.log','favorite-projection-tests-17b.log'):
    put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
for name in ('manifest.json','ordered-runtime-cp.json'):
    put('root/snapshot17/'+name,read(MAIN/'desktop/.local/stable-product-snapshot-17'/name))
put('root/direct-owner-audit.json',read(MAIN/'desktop/.local/stable-favorites-root-wiring/direct-owner-audit.json'))
tests=[]
for name in ('TEST-com.bilipai.desktop.audio.DesktopMusicRootIntegrationTest.xml','TEST-com.bilipai.desktop.DesktopPlaybackControllerTest.xml'):
    raw=read(REPO/'desktop/build/test-results/test'/name);root=ET.fromstring(raw)
    assert root.attrib['failures']=='0' and root.attrib['errors']=='0' and root.attrib['skipped']=='0'
    tests.append(dict(suite=root.attrib['name'],tests=int(root.attrib['tests']),failures=0,errors=0,skipped=0))
    put('root/junit/'+name,raw)
assert sum(t['tests'] for t in tests)==3
for name in ('DesktopPlaybackControllerTest.kt','audio/DesktopMusicRootIntegrationTest.kt'):
    put('root/final-test-source/'+name,read(REPO/'desktop/src/test/kotlin/com/bilipai/desktop'/name))
for name in ('tools/extract-upstream-favorite-folder-sheet.py','src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoFavoriteRoot.kt'):
    put('root/installed/'+name,read(REPO/'desktop'/name))
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));assert len(registry['sources'])==741
for entries in (registry['sources'],registry['resources']):
    assert len(entries)==len({r['path'] for r in entries})
    for r in entries:
        b=read(REPO/r['path']);b=b if r.get('hashNormalization','lf')=='raw' else b.decode().replace('\r\n','\n').encode()
        assert sha(b)==r['sha256'],r['path']
report=dict(upstreamTag=registry['upstreamTag'],upstreamCommit=registry['upstreamCommit'],
    sourceIdentities=741,resources=210,all951SourceAndResourcePinsVerified=True,wholeClasses17Passed=True,
    fullOriginalFavoritesSourcesCompiled=True,fullOriginalFavoritesRootMounted=False,
    originalFolderSheetRootSourceWired=True,originalFolderSingleMembershipDelta=True,
    sameGlobalQuickPreference=True,originalLongPressEntryPolicy=True,
    sameRootCloudRelationAndRawFavoriteCount=True,sessionEpochAndAidAndCallerScopeGuarded=True,
    actualControllerProjectionTests=2,originalMusicRouteLifecycleCases=18,junitMethods=3,junitSuites=tests,
    firstTestCompileFailed='Existing alpha.9 BGM Native route tests no longer compile against original stable Detail routing',
    testRepair='Retain exact original stable Detail ID/CID routing; separate unchanged Listen audio source rules; final 3 methods pass',
    manualFavoriteButtonIsWindowsBinding=True,fullOriginalVideoActionRowAccepted=False,
    actualRootFavoriteButtonAccepted=False,actualRootFolderWindowAccepted=False,realAccountOperationsAccepted=False,
    desktopExeReplaced=False,fullStableRuntimeAccepted=False,publicReleaseAccepted=False,
    childEvidenceScope='Memory transport and isolated original VM/store; no socket/real account/native window',
    countTestScope='Actual current controller and unattached Mpv source actor; fixture metadata/transport, no HWND/codec/online account',
    productionCliAdaptation='Only installed drawer __main accepts --repo/--output/--standalone; generate default4selected unchanged',
    pending=['Full Favorites Root retained navigation and video/Listen queue continuation',
        'Actual original page/category/drawer interactions in integrated Main',
        'Full Home/music/danmaku/settings/player/space source closure','Full Root/PiP/DPI/account/runtime and EXE deployment'],
    excludedTaskBinaries=excluded)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(raw)
print(json.dumps(dict(artifacts=len(rows),manifestSha256Bytes=sha(raw),junitMethods=3,excluded=len(excluded))))

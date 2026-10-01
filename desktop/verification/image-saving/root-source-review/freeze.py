"""Read-only Main source receipt. Writes only this new review directory."""
from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'.git').exists())
def safe(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,value):
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(value,encoding='utf-8',newline='\n')
def save(name,value):write(HERE/name,json.dumps(value,ensure_ascii=False,indent=2)+'\n')
assert not safe(HERE/'evidence-manifest.json').exists(),'already frozen'
paths=[
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopImageSaveLocations.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDynamicCardHost.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSaveLocationPreferences.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSavePathSettings.kt',
]
rows=[];texts={}
for path in paths:
    raw=safe(ROOT/path).read_bytes();text=raw.decode('utf-8').replace('\r\n','\n');texts[path]=text
    copy='reviewed-source/'+path;write(HERE/copy,text)
    rows.append(dict(path=path,sha256Bytes=sha(raw),sha256Lf=sha(text.encode()),lineCount=len(text.splitlines()),reviewedCopy=copy))
location=texts[paths[0]];assets=texts[paths[1]];shell=texts[paths[2]]
assert 'return write(DesktopDynamicSaveTarget(checkNotNull(selected), false))' in location
assert 'if (attempt(Path.of(uri), false)) return@withContext true' in location
assert 'remember(pluginStore) { DesktopImageSaveLifetime' in shell
assert 'imageSaveLifetime.close()\n                dynamicCache.shutdownForRestore()' in shell
assert 'DesktopImageSaveLocationPreferences(pluginStore, imageSaveLifetime::withCommit)' in shell
assert 'private val imageSaveLocations: DesktopImageSaveLocations? = null' in assets
save('source-review.json',dict(
    verdict='No remaining blocker found in this narrow corrected source review.',sourceCount=7,sourceRows=rows,
    reviewScope='Main Locations/Assets + actual Root global preferences/settings/card/comment wiring. Source only; no tests, Gradle, HTTP, chooser, HWND, EXE or accounts operated.',
    rootReportedActualMain04CompilationPassed=True,reviewerVerifiedCompilation=False,reviewerExecutedRuntime=False,
    resolvedIssuesHistory=[
      dict(originalLocationsSha256Lf='b34772c18b91e8fe89d641222c64a636f46da9d758ee630a0620a2e0519fdfae',line=97,
        issue='attempt():Boolean block body had a bare write call without return.',repair='return write(...); source verified before any reviewer fixture.'),
      dict(originalLocationsSha256Lf='b34772c18b91e8fe89d641222c64a636f46da9d758ee630a0620a2e0519fdfae',line=103,
        issue='Custom attempt false returned directly and skipped original default fallback.',repair='Only return true when custom attempt true; false continues to default. Cancellation still rethrows.'),
    ],
    originalIdentity=dict(tag='v0.2.3-alpha.9',commit='fcf84853b287662e8a9129ea0d38576c36522a34',
       imagePreviewPath='app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt',
       imagePreviewSha256Lf='8ab6d642e5085483ffa5fbe684cb962468c6b93daec46c1eb768e98f8b3fe0b0'),
    originalAnchors=[
       dict(lines='2038-2043,2067-2077,2142-2145',contract='URL substring GIF priority/WebP raw bytes; PNG decoded PNG; remaining decoded JPEG95. Name BiliPai_<milliseconds>.<extension>.'),
       dict(lines='2079-2086,2108,2147-2159,2177',contract='Custom failure -> default; raw fallback opens a fresh tempFile input, bitmap fallback recompresses bitmap.'),
       dict(lines='505-513',contract='urls.map saves every ordinary item before all aggregation; Desktop cancellation must propagate rather than trigger fallback or later requests.'),
       dict(lines='2414,2417-2426,2441-2446,2472-2489,2497-2501',contract='Motion Photo BiliPai_Live_<milliseconds>.jpg; custom combined stream first, fresh JPEG/video input fallback; Android DCIM/BiliPai -> Pictures/BiliPai -> DCIM -> Pictures -> no relative path.'),
       dict(lines='2564-2623,2581-2588,2603-2605',contract='Standalone MP4 BiliPai_Live_<milliseconds>.mp4, video/mp4, Movies/BiliPai,64KiB stream; no image_save_tree_uri read/custom directory route.'),
       dict(path='app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt',lines='6683,6737-6755',contract='Original nullable string image_save_tree_uri; null removes primary key. Windows sync+flow use same global settings backing.'),
       dict(path='app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt',lines='292-306,610-621',contract='Cancel/null leaves preference untouched; close dialog -> chooser launch; asynchronous reset-to-null -> dismiss.'),
    ],
    staticPureNames=['desktopOriginalStaticGalleryKeepBytes','desktopOriginalStaticGalleryExtension','desktopOriginalStaticGalleryMimeType','desktopOriginalStaticGalleryFileName'],
    findings=[
       dict(anchor='DesktopDynamicImageAssets.kt90-104,143-160; DesktopImageSaveLocations.kt99-118',result='One anonymous downloaded source; every custom/default attempt reopens/encodes it and creates its own destination stage. No second HTTP download for ordinary write fallback; source/stage finally cleanup retained.'),
       dict(anchor='DesktopDynamicImageAssets.kt112-135; original accepted DesktopDynamicMotionPhotoFiles.kt51,76,83-94',result='Only one image+video download pair; each destination attempt reopens both sources. Motion final context.ensureActive remains inside gate; scratch/source finally cleanup retained.'),
       dict(anchor='DesktopDynamicImageAssets.kt75-88; DesktopImageSaveLocations.kt104-118',result='Ordinary failure continues batch and aggregates false; custom Boolean false/ordinary exception falls back; CancellationException immediately propagates and never falls back.'),
       dict(anchor='DesktopSessionStore.kt46-49 -> DesktopDynamicImageAssets.kt53-58 -> DesktopImageSaveLifetime.kt25-28 (same Locations file) -> MotionPhotoFiles.kt84',result='SessionStore monitor -> Assets lock -> app lifetime gate -> Motion file gate. close lifetime marks closed under app gate then releases it; Root beforeStoreFreeze closes lifetime before subsequent shutdown calls. No app-gate-held Assets.close inversion.'),
       dict(anchor='DesktopShell.kt225-232,249-253,895,1136-1142; prefs.kt26-44',result='One pluginStore-key global lifetime/preferences/locations, same original settings key. No MID/sessionEpoch/popup key in global preference authority. Real Root scope/window chooser supplied; popup close does not retire chooser request; app close/restore retire lifetime.'),
       dict(anchor='DesktopOriginalDynamicCardHost.kt59,68-72; CommunityDynamicScreens.kt92,123-139',result='Both dynamic Assets and comment PNG use the same Root Local locations instance. Comment commit order is SessionStore -> export owner lock -> app gate; actual writer invokes final owned/cancellation check inside commit before move.'),
    ],
    explicitlyPending=['MP4 default Movies/Videos mapping (explicit SaveAs retained)','Space avatar preview/save entry','Windows Photos/MediaStore indexing equivalence','Real chooser acceptance','HWND/shell/receiver behaviour','Desktop EXE acceptance','Android Bitmap/ICC full equivalence'],
    boundary='Root-reported Main04 compile PASS is attributed, not this reviewer proof. The current mutable source pins identify reviewed bytes; actual immutable compiled/runtime verification is separate Root evidence.',
    MainEdited=False,oldFrozenEdited=False,sharedGradle=False,testsExecuted=False,HTTP=False,HWND=False,
))
artifacts=[]
for path in sorted(safe(HERE).rglob('*'),key=str):
    if path.is_file() and path.name!='evidence-manifest.json':
        relative=str(path)[len(str(safe(HERE)))+1:].replace('\\','/')
        artifacts.append(dict(path=relative,sizeBytes=path.stat().st_size,sha256Bytes=sha(path.read_bytes())))
save('evidence-manifest.json',dict(schema='read-only-image-save-source-review-v1',frozen=True,sourceCount=7,sourceRows=rows,
    artifactCount=len(artifacts),artifacts=artifacts,sourceReviewSha256Bytes=sha(safe(HERE/'source-review.json').read_bytes()),
    MainEdited=False,sharedGradle=False,oldFrozenEdited=False,testsExecuted=False,HTTP=False,HWND=False,
    rootReportedMain04CompilePASS=True,reviewerScope='Source only; compilation/runtime proof remains Root-owned.',
    pending=['MP4 Movies default','avatar save entry','Photos indexing','real chooser','HWND','EXE']))
print(json.dumps(dict(manifest=str(HERE/'evidence-manifest.json'),sha256Bytes=sha(safe(HERE/'evidence-manifest.json').read_bytes()),rows=7,artifacts=len(artifacts))))

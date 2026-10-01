"""Reuse frozen behavior fixtures, adding actual new Main source assertions only."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_text(v,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def replace_once(s,a,b):assert s.count(a)==1,a[:100];return s.replace(a,b,1)
definitions=[
 ('download','dynamic-motion-photo-download-owner-parity','frozen-handoff.json','bd97df3e2aa1ea4529fa49f9196b8f95db7e0100dfcc20aff79d0b091a14a503','files','runs/05/frozen-sources/DownloadFixture.kt'),
 ('gallery','dynamic-gallery-motion-photo-parity','frozen-handoff.json','08da9ef3efd871724d1cc58482dc5945e18c9312229e4749f75e0dc405826c18','files','gallery-runs/01/frozen-sources/GalleryFixture.kt'),
 ('qr','comment-qr-windows-clip-parity','frozen-handoff.json','382b64f3b20ab5b0d330a78bcedc6a74a7a2d38a8be32f946dfa4905f25b5f57','files','runs/01/QrClipFixture.kt'),
 ('commitcancel','dynamic-detail-reply-parity/detail-container-next/motion-photo-commit-cancel-delta','evidence-manifest.json','af0187e4604340a71bc5bd3c7ece5065836e83ea1f2e2014eb28e8fad22d41a3','artifacts','compile-01/sources/CommitCancelFixture.kt'),
]
sources={};receipt=[]
for name,lane,manifest,expected,key,path in definitions:
    base=REPO/'desktop/.local'/lane;assert sha(base/manifest)==expected
    pins={r['path']:r['sha256Bytes'] for r in json.loads((base/manifest).read_bytes())[key]}
    assert sha(base/path)==pins[path]
    text=(base/path).read_text(encoding='utf-8');sources[name]=text
    write(HERE/'original-fixtures'/Path(path).name,text)
    receipt.append({'name':name,'lane':lane,'manifestSha256Bytes':expected,'fixturePath':path,'fixtureSha256Bytes':pins[path]})

s=sources['download']
s=replace_once(s,'package com.bilipai.desktop.ui.downloadproof','package com.bilipai.desktop.ui.assetsintegrationproof.download')
s=replace_once(s,'DesktopDynamicCardOperations::class.java,DesktopDynamicSaveTarget::class.java)',
    'DesktopDynamicCardOperations::class.java,DesktopDynamicSaveTarget::class.java,\n        DesktopDynamicImageAssets::class.java,DesktopDynamicMotionPhotoFiles::class.java,DesktopDynamicMotionPhotoExif::class.java,\n        Class.forName("com.android.purebilibili.feature.dynamic.components.DesktopOriginalMotionPhotoPackingKt"))')
s=s.replace('"candidateAssetsOverrideDeclared":true','"candidateAssetsOverrideDeclared":false,"productionOverrides":false,"actualMainProductClasses":true')
s=s.replace('actual Main owner + loopback HTTP -> actual EXIF/packing; declared Assets candidate override only','actual new Main Assets/Files/Exif/packing/owner + loopback HTTP; zero product overrides')
write(HERE/'fixtures/DownloadFixture.kt',s)

s=sources['commitcancel']
s=replace_once(s,'package com.bilipai.desktop.ui.commitcancelproof','package com.bilipai.desktop.ui.assetsintegrationproof.commitcancel')
s=replace_once(s,'    val frozenAssetsJar = Path.of(args[4]).toRealPath()\n    val filesJar = Path.of(args[5]).toRealPath()\n    val mode = args[6]; check(mode == "candidate" || mode == "baseline")',
    '    val mode = "actual-main"')
s=s.replace('checkSource(DesktopDynamicImageAssets::class.java, frozenAssetsJar)','checkSource(DesktopDynamicImageAssets::class.java, mainJar)')
s=s.replace('checkSource(DesktopDynamicMotionPhotoFiles::class.java, filesJar)','checkSource(DesktopDynamicMotionPhotoFiles::class.java, mainJar)\n    checkSource(DesktopDynamicMotionPhotoExif::class.java, mainJar)')
s=s.replace('), filesJar)', '), mainJar)').replace('), frozenAssetsJar)', '), mainJar)')
s=replace_once(s,'prove(preserved == (mode == "candidate"), "candidate must retain target; baseline must reproduce canceled replacement")',
    'prove(preserved, "actual new Main must retain target on save Job cancel while Store final gate waits")')
s=s.replace('"MainInstalled":false','"MainCompiledProductIntegration":true,"MainShellUIExecuted":false,"productionOverrides":false')
s=s.replace('unchanged actual Assets','new actual Main Assets').replace('unchanged Main Store','actual Main Store')
write(HERE/'fixtures/CommitCancelFixture.kt',s)

s=sources['gallery']
s=replace_once(s,'package com.bilipai.desktop.ui.gallerymotionphotoproof','package com.bilipai.desktop.ui.assetsintegrationproof.gallery')
s=replace_once(s,'import com.bilipai.desktop.ui.*','import com.bilipai.desktop.ui.*\nimport com.bilipai.desktop.data.*')
s=replace_once(s,'    var epoch = 1L\n    val expectedEpoch = epoch\n    val owned = { epoch == expectedEpoch }',
    '''    val mainJar=Path.of(args[2]).toRealPath()
    for(clazz in listOf(DesktopDynamicEditorSelectedImages::class.java,DesktopDynamicGallerySelection::class.java,
        DesktopRepository::class.java,DesktopSessionStore::class.java,
        Class.forName("com.android.purebilibili.core.util.DesktopOriginalGalleryResultPolicyKt"))) {
        prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath()==mainJar,"actual Main gallery/handles/owner/policy codeSource ${clazz.name}")
    }
    val sessions=DesktopSessionStore.temporary()
    val account=AccountSummary(321L,"Declared gallery fixture","")
    sessions.saveAccount(mapOf("SESSDATA" to "declared-gallery-task-01"),account)
    val repository=DesktopRepository(sessions)
    val operations=DesktopDynamicCardOperations(repository)
    val owned=operations::isOwned
    prove(repository.dynamicCacheSessionGuard===sessions,"same actual Main gallery epoch guard")''')
s=replace_once(s,'        epoch = 2L // Same account MID, different existing session epoch.',
    '        sessions.saveAccount(mapOf("SESSDATA" to "declared-gallery-task-02"),account) // Actual same MID/new existing Store epoch.')
s=replace_once(s,'    val flip = AtomicBoolean(false)\n    val guard = { !flip.get() }','    val currentOperations=DesktopDynamicCardOperations(repository)\n    val guard=currentOperations::isOwned')
s=s.replace('"MainIntegration":false','"MainCompiledProductIntegration":true,"MainShellUIExecuted":false,"productionOverrides":false')
s=s.replace('frozen Main','new Main').replace('candidate adapter','actual Main adapter')
s=replace_once(s,'    println("PASS $assertions assertions / 4 cases: original gallery decisions and actual new Main handle owner; no chooser/window")',
    '    repository.httpClient.dispatcher.executorService.shutdown();repository.httpClient.connectionPool.evictAll()\n    println("PASS $assertions assertions / 4 cases: original gallery decisions and actual new Main handle owner; no chooser/window")')
write(HERE/'fixtures/GalleryFixture.kt',s)

s=sources['qr']
s=replace_once(s,'package com.bilipai.desktop.ui.qrclipproof','package com.bilipai.desktop.ui.assetsintegrationproof.qr')
s=replace_once(s,'    val mode=args[0];val output=Path.of(args[1]);Files.createDirectories(output)',
    '    val mode="actual-main";val output=Path.of(args[0]);Files.createDirectories(output)')
s=replace_once(s,'    val main=Path.of(args[2]).toAbsolutePath().normalize();val candidate=Path.of(args[3]).toAbsolutePath().normalize()\n    val required=if(mode=="candidate")candidate else main',
    '    val main=Path.of(args[1]).toAbsolutePath().normalize();val required=main')
s=s.replace('if(mode=="candidate")','if(mode=="actual-main")')
s=s.replace('"candidateOverridesDeclared",mode=="candidate"','"candidateOverridesDeclared",false')
s=s.replace('"printedFooterTextCanBeClipped",mode=="candidate"','"printedFooterTextCanBeClipped",true')
s=s.replace('put("MainInstalled",false)','put("MainCompiledProductIntegration",true);put("MainShellUIExecuted",false);put("productionOverrides",false)')
s=s.replace('candidate saved QR','actual Main saved QR').replace('candidate all QR','actual Main all QR')
s=s.replace('"declared Canvas codeSource"','"actual Main Canvas codeSource"').replace('"declared renderer codeSource"','"actual Main renderer codeSource"')
write(HERE/'fixtures/QrClipFixture.kt',s)
save(HERE/'fixture-adaptation.json',{
    'inputFrozenManifestsAndFixturePins':receipt,
    'outputFixtures':[{'path':str(p.relative_to(HERE)).replace('\\','/'),'sha256Bytes':sha(p)} for p in sorted((HERE/'fixtures').glob('*.kt'))],
    'behavioral51_11DownloadCasesRetained':True,'extraActualMainProductCodeSources':True,
    'commitConcernUsesActualMainAssetsFilesNoCandidateJar':True,'GalleryRotationUsesActualStoreInsteadOfSyntheticEpoch':True,
    'qrCandidateModeRemovedAllClassesMustMain':True,'productionSourcesCompiledOrOverridden':False,
    'actualMainSnapshotRunPending':True,'MainWrites':False,
})
print('PASS four fixture-only adaptations from frozen inputs; waiting for new immutable actual Main pins')

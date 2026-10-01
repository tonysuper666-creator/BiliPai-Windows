from pathlib import Path
import hashlib,importlib.util,json,sys,subprocess,zipfile,struct,difflib
sys.dont_write_bytecode=True
import compile as c
HERE=c.HERE;REPO=c.MAIN.parent/'BiliPai-v023'
tool=HERE/'prepared/desktop/tools/extract-upstream-danmaku-list-menu.py'
spec=importlib.util.spec_from_file_location('sole_danmaku_ui',tool);g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):g.save(p,v)
def check(name,value):assert value,name;checks.append(dict(name=name,passed=True))
checks=[]
inventory=json.loads((HERE/'generated/source-inventory.json').read_text(encoding='utf-8'))
production=g.generate(REPO,HERE/'prepared/production-generated',False)
check('standalone10-production9-direct1-omitted',len(inventory['emitted'])==10 and len(production['emitted'])==9 and not any(x['mode']=='direct' for x in production['emitted']))
for row in production['emitted']:
    check('default-standalone-byte-equal:'+row['path'],(HERE/'prepared/production-generated'/row['path']).read_bytes()==(HERE/'generated'/row['path']).read_bytes())
for p in (HERE/'platform').rglob('*.kt'):
    dest=HERE/'prepared/desktop/src/main/kotlin'/p.relative_to(HERE/'platform');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(p.read_bytes())
for p in (HERE/'selected-files').rglob('*.kt'):
    dest=HERE/'prepared'/p.relative_to(HERE/'selected-files');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(p.read_bytes())
source={}
for row in inventory['sources']:
    raw=subprocess.check_output(['git','show',g.COMMIT+':'+row['path']],cwd=REPO).decode('utf-8').replace('\r\n','\n')
    check('stable-pinned-source:'+row['path'],g.sha(raw)==row['sha256LF'])
    source[row['path']]=raw;g.write(HERE/'original-inputs'/row['path'],raw)
compiled=json.loads((HERE/'compile-03/compile-result.json').read_text(encoding='utf-8'))
accepted=json.loads((HERE/'proof-02/accepted-result.json').read_text(encoding='utf-8'))
check('compile14-and-focused3case37assertions-accepted',compiled['status']==accepted['status']=='PASS' and accepted['assertions']==37)
payload=list((HERE/'generated').rglob('*.kt'))+list((HERE/'platform').rglob('*.kt'))+list((HERE/'selected-files').rglob('*.kt'))
check('14-exact-compiled-payload-inputs',len(payload)==14 and sorted(digest(p) for p in payload)==sorted(x['sha256LF'] for x in compiled['sourceInputs']))
for row in inventory['emitted']:
    if row.get('reverseNormalizedOriginalByteEqual'):
        reverse=(HERE/'generated'/row['path']).read_text(encoding='utf-8')
        for patch in reversed(row['adaptations']):reverse=reverse.replace(patch['after'],patch['before'])
        original=source[row['origin']]
        if row['mode']=='selected-complete-schema':original=original[:original.index('\ndata class DanmakuWindow(')]
        check('full-renderer-or-schema-reverse-equal:'+row['path'],reverse==original)
        g.write(HERE/'source-diffs'/(Path(row['path']).name+'.patch'),'\n'.join(difflib.unified_diff(original.splitlines(),(HERE/'generated'/row['path']).read_text(encoding='utf-8').splitlines(),fromfile='stable/'+row['origin'],tofile='selected/'+row['path']))+'\n')
parser=(HERE/'generated/com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuItemParser.kt').read_text(encoding='utf-8')
for name in ('createTextDataFromProto','formatDanmakuTextWithCount','createTextData','mapLayerType'):
    original,_,_=g.function(source[g.PATHS[3]],name)
    current,_,_=g.function(parser,name)
    reverse=current.replace('    fun createTextDataFromProto','    private fun createTextDataFromProto').replace('    fun createTextData(','    private fun createTextData(')
    check('complete-original-standard-factory:'+name,reverse==original)
protocol=(HERE/'generated/com/android/purebilibili/data/repository/DesktopOriginalDanmakuProtocol.kt').read_text(encoding='utf-8')
for name in ('getDanmakuThumbupState','recallDanmaku','likeDanmaku','reportDanmaku'):
    original,_,_=g.function(source[g.PATHS[7]],name);current,_,_=g.function(protocol,name)
    reverse=current.replace('        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n','')
    reverse='\n'.join(line for line in reverse.split('\n') if line.strip() not in ('assertOwned()','kotlinx.coroutines.currentCoroutineContext().ensureActive()'))
    reverse=reverse.replace('readCsrf()','com.android.purebilibili.core.store.TokenManager.csrfCache')
    without_logs=g.drop_logs(original,[])
    check('original-protocol-reverse-minus-platform-logs:'+name,reverse==without_logs)
    g.write(HERE/'source-diffs'/('protocol-'+name+'.patch'),'\n'.join(difflib.unified_diff(original.splitlines(),current.splitlines(),fromfile='stable/'+name,tofile='owned-protocol/'+name))+'\n')
session=(HERE/'generated/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalDanmakuSession.kt').read_text(encoding='utf-8')
original_vm=source[g.PATHS[2]]
state=original_vm[original_vm.index('    data class DanmakuMenuState('):original_vm.index('    private val _danmakuMenuState')].rstrip()
check('full-original-menu-state-schema-exact',state in session)
for name in ('refreshDanmakuThumbupState','recallDanmaku','likeDanmaku','reportDanmaku'):
    occurrence=1 if name in ('likeDanmaku','reportDanmaku') else 0
    original,_,_=g.function(original_vm,name,occurrence=occurrence);current,_,_=g.function(session,name,occurrence=occurrence)
    for hook in ('.onSuccess {','.onFailure {'):
        for text,label in ((original,'original'),(current,'current')):
            at=text.index(hook);begin=text.index('{',at);end=g.balanced(g.masked(text),begin,'{','}')
            if label=='original':receipt=text[at:end]
            else:
                reverse=text[at:end].replace('menuRequest!=menuGeneration || ','')
                check('original-state-receipt-exact:'+name+hook,reverse==receipt)
    g.write(HERE/'source-diffs'/('session-'+name+'.patch'),'\n'.join(difflib.unified_diff(original.splitlines(),current.splitlines(),fromfile='stable/'+name,tofile='owned-session/'+name))+'\n')

# Classfile parsing checks Kotlin top-level declarations independently of wrapper class names.
def methods(data,entry):
    p=8
    def u2():
        nonlocal p
        v=struct.unpack_from('>H',data,p)[0];p+=2;return v
    def u4():
        nonlocal p
        v=struct.unpack_from('>I',data,p)[0];p+=4;return v
    def attrs():
        nonlocal p
        for _ in range(u2()):u2();length=u4();p+=length
    n=u2();pool=[None]*n;i=1
    while i<n:
        tag=data[p];p+=1
        if tag==1:length=u2();pool[i]=data[p:p+length].decode('utf-8',errors='replace');p+=length
        elif tag in (3,4):p+=4
        elif tag in (5,6):p+=8;i+=1
        elif tag in (7,8,16,19,20):p+=2
        elif tag in (9,10,11,12,17,18):p+=4
        elif tag==15:p+=3
        else:raise ValueError(tag)
        i+=1
    p+=6;interfaces=u2();p+=interfaces*2
    for _ in range(u2()):p+=6;attrs()
    rows=[];package=entry.rsplit('/',1)[0]
    for _ in range(u2()):
        flags=u2();name=pool[u2()];desc=pool[u2()];attrs()
        if flags&1 and flags&8 and not name.startswith('access$'):rows.append((package,name,desc[:desc.index(')')+1],entry))
    return rows
candidate=HERE/'compile-03/original-danmaku-list-menu.jar';actual=c.SNAP/'main-kotlin.jar'
with zipfile.ZipFile(candidate) as own,zipfile.ZipFile(actual) as main:
    own_names={x for x in own.namelist() if x.endswith('.class')};main_names={x for x in main.namelist() if x.endswith('.class')}
    own_methods=[m for e in own_names if e.endswith('Kt.class') and '$'not in e for m in methods(own.read(e),e)]
    main_methods=[m for e in main_names if e.endswith('Kt.class') and '$'not in e for m in methods(main.read(e),e)]
    own_keys={x[:3] for x in own_methods};intersections=[x for x in main_methods if x[:3] in own_keys]
    check('class-intersection-is-only-25-declared-platform-family-classes',sorted(own_names&main_names)==compiled['declaredActualClassOverlap'])
    check('public-top-level-package-JVM-name-parameter-descriptor-intersection-zero',not intersections)
save(HERE/'symbol-audit.json',dict(status='PASS',candidateJarSha256Bytes=digest(candidate),actualMain16JarSha256Bytes=digest(actual),candidateClasses=len(own_names),actualClasses=len(main_names),declaredClassIntersections=compiled['declaredActualClassOverlap'],unexpectedClassIntersections=[],candidateTopLevelPublicStaticMethods=len(own_methods),actualTopLevelPublicStaticMethods=len(main_methods),topLevelIntersections=[],matchingRule='same package/JVM name/parameter descriptor; return ignored; private and access$ helpers excluded'))
save(HERE/'source-audit.json',dict(status='PASS',checkCount=len(checks),checks=checks,originalBodiesKept=True,preparedOnly=True,platformAdaptations=['Two Android clipboard/Toast ports; required original text-selection delegate','Only upstream ignored onLongClick clickable->combinedClickable fix','Original complete standard model AWT bitmap/font carrier aliases only','Shared Root API/CSRF and owned cancellation/checkpoints','Per-DMID stats requests and menu-generation/same-like-request finally guards','One global settings namespace with original portrait/landscape/legacy keys and normalization','Sole Overlay rawDocument derived CID/sourceVersion/revision getter; original API fields retained in existing comments'],noMainOrGradle=True,noGuiOrNetwork=True))
save(HERE/'install-contract.json',dict(preparedOnly=True,upstreamCommit=g.COMMIT,actualCompileSnapshot16ManifestSha256Bytes=digest(c.SNAP/'manifest.json'),actualCp92Sha256Bytes=digest(c.SNAP/'ordered-runtime-cp.json'),
 producer='prepared/desktop/tools/extract-upstream-danmaku-list-menu.py',entry='generate(repo,output,standalone=False)',productionSelectedCount=9,directOnceReferences=production['directReferences'],manualSourceCount=2,selectedCurrentPlatformSourceCount=2,totalCompileSources=14,
 originalSources=inventory['sources'],sourceOnlyRecipe=['Copy sole new producer; run default into exactly one generated source directory. Do not compile task generated plus production-generated together.','Register direct WeightedTextData.kt once, LF pinned. Merge list-menu feature into already-existing source identities; never overwrite Root modes/features.','Install two manual platform sources from prepared/desktop/src/main/kotlin and the two selected Parser/Overlay files using base/candidate LF pins in platform-delta-contract.json.','Append fragments/DesktopDynamicCardOperations.danmaku.ktfrag only. Keep Root stream/grade/fraud/reply/editor members and existing sole API/Store/client untouched.','In DesktopPlaybackController.load move existing danmaku launch after native.loadVersioned and current=Current(...version...) assignment; validate same generation/accountEpoch/source ownership and pass expectedSourceVersion=version. Offline default callers deliberately produce no pool source snapshot.','Recompile the whole product: primary Comment source-payload tail fields change constructor/copy ABI. Existing precompiled Snapshot16 WindowLoader/plugin callers were not runtime exercised with this override.'],
 requiredOwnedEnvironment='DesktopDanmakuSessionEnvironment(cid,sourceVersion,expectedEpoch,callerScope,actions,stillOwned,currentMid,feedback,seekFromUser). stillOwned checks page alive AND repository.sessionEpoch==capturedEpoch AND same native sourceVersion AND current controller CID. Use Root DesktopPlaybackController.seekTo(ms/1000.0) to retain actual user-seek/sponsor behavior. currentMid is a late guarded Root read, not a cached or invented hash.',
 RootActionsBinding='Implement DesktopDanmakuActions with same existing owned Operations methods; do not construct Retrofit/another Repository. Internal public schema is the original selected DanmakuThumbupState.',
 sessionRecipe='remember per immutable page/CID/sourceVersion/accountEpoch, one DesktopOriginalDanmakuSession; close it on disposal (only its child scope). All action callbacks run on Root Compose UI dispatcher. Pool and menu consume same likedDanmakuIds. showOriginalDanmakuItem(item,currentMid) is the exact original manager isSelf/hash rule for a future actual overlay hit target.',
 host='DesktopOriginalDanmakuHost(overlay,environment,session,platform,blockPreferences,settingsScope,showPool,currentPositionMs,onDismissPool). Root owns the entry Boolean and mounts the complete original UI; getter only projects sole raw loaded standard comments, not full server catalog.',
 platform='Required DesktopDanmakuPlatform.isOwned/feedback/copyText/textSelection. Delegate textSelection to existing sole original TextSelectionBottomSheet under Root same owned LocalDesktopDynamicCardBindings provider (e.g. same source Video comment root common binding). No default fake card platform or new renderer.',
 settings='DesktopDanmakuBlockPreferences(existingGlobalPluginStore,withOwnedAdmission). Root supplies its actual SessionStore owner admission and live page/source gate. One settings namespace, exact danmaku_portrait_block_rules/danmaku_landscape_block_rules plus legacy danmaku_block_rules fallback. Keys are never MID-bound. Existing block/filter policies reused; full settings/cloud/import UI is pending.',
 lockOrder='Root current SessionStore owner admission -> PluginStore backing monitor/updateFromSnapshot. No HTTP/await inside either. Overlay getter requestLock -> existing player sourceVersion monitor; Root must not call getter while holding player source lock. Getter does not cross GUI/native work.',
 proof=dict(compilePhase='compile-03',groupedCases=3,assertions=37,actualCodeSources=6,memoryRetrofitNoSocket=True,declaredProductOverrides='Parser/Overlay only',unexpectedClassOrTopLevelOverlap=0,actualOriginalUiMounted=False,actualRootPlaybackCallbacksConsumed=False,actualOverlayHitTesting=False,OpsFragmentCompiledByRootStillRequired=True),
 pending=inventory['pending'],noMainOrSharedGradleEdits=True,noNewDependency=True,noAccountReadsOrExternalRequests=True,noHWND=True))
save(HERE/'history.json',dict(compile01='Failed because original report convenience overload was selected without full content overload; preserved raw log.',compile02='Whole fourteen source compile passed before per-DMID stats job refinement; not final acceptance.',compile03='Final full source compile passed.',proof01='Fixture compile failed due wildcard ambiguity of the existing app DanmakuParser vs desktop DanmakuParser; no runtime happened.',proof02='Explicit desktop parser import; final 3 grouped/37 assertions passed. No GUI/Root consumer claim.'))
rows=[]
for p in sorted(HERE.rglob('*')):
    if p.is_file() and p.name!='frozen-handoff.json' and '__pycache__'not in p.parts:rows.append(dict(path=str(p.relative_to(HERE)).replace('\\','/'),sha256Bytes=digest(p),bytes=p.stat().st_size))
save(HERE/'frozen-handoff.json',dict(frozen=True,preparedOnly=True,upstreamCommit=g.COMMIT,phase='danmaku-list-menu-complete-original-renderers-source-backed-schema-owned-protocol',artifactCount=len(rows),artifacts=rows,sourceAuditSha256Bytes=digest(HERE/'source-audit.json'),symbolAuditSha256Bytes=digest(HERE/'symbol-audit.json'),installContractSha256Bytes=digest(HERE/'install-contract.json'),compile03ResultSha256Bytes=digest(HERE/'compile-03/compile-result.json'),proof02AcceptedSha256Bytes=digest(HERE/'proof-02/accepted-result.json'),actualOriginalUiOrRootNativePointerAcceptance=False,pending=inventory['pending']))
print(json.dumps(dict(status='PASS',checks=len(checks),artifacts=len(rows),manifestSha256Bytes=digest(HERE/'frozen-handoff.json'),contractSha256Bytes=digest(HERE/'install-contract.json'))))

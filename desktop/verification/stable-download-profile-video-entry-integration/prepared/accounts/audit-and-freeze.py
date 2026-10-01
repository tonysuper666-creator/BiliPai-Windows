from pathlib import Path
import hashlib,json,re,subprocess,zipfile
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def digest(b):return hashlib.sha256(b).hexdigest()
def save(name,value):
    p=LANE/name;wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def body(text,name):
    m=re.search(r'(?m)^\s*(?:(?:private|internal|public|override|suspend)\s+)*fun\s+'+re.escape(name)+r'\s*\(',text);assert m,name
    start=m.start();start=text.index('fun',start);brace=text.index('{',m.end());i=brace;depth=0;state='code'
    while i<len(text):
        c=text[i];n=text[i:i+2]
        if state=='line':
            if c=='\n':state='code'
        elif state=='comment':
            if n=='*/':state='code';i+=1
        elif state in ('string','char'):
            if c=='\\':i+=1
            elif c==('"'if state=='string'else"'"):state='code'
        else:
            if n=='//':state='line';i+=1
            elif n=='/*':state='comment';i+=1
            elif c=='"':state='string'
            elif c=="'":state='char'
            elif c=='{':depth+=1
            elif c=='}':
                depth-=1
                if depth==0:return dict(startLine=text.count('\n',0,start)+1,endLine=text.count('\n',0,i)+1,sha256Lf=digest(text[start:i+1].encode()),body=text[start:i+1])
        i+=1
    raise AssertionError(name)
selection={
 'app/src/main/java/com/android/purebilibili/core/store/AccountSessionStore.kt':['getAccounts','getActiveAccountMid','getPlaybackAccountMid','getPlaybackAccount','setPlaybackAccountMid','clearActiveAccount','removeAccount','upsertCurrentAccount','activateAccount'],
 'app/src/main/java/com/android/purebilibili/core/store/TokenManager.kt':['saveMid','saveVipStatus','clear','applyStoredSession'],
 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileViewModel.kt':['persistProfileSession','clearInvalidProfileSession','logout','switchAccount','removeStoredAccount'],
}
origins=[]
for path,names in selection.items():
    blob=subprocess.run(['git','show',COMMIT+':'+path],cwd=CANDIDATE,capture_output=True,check=True).stdout
    target=LANE/'original-stable'/path;wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(blob)
    text=blob.decode('utf-8').replace('\r\n','\n');rows=[]
    for name in names:
        row=body(text,name);row.pop('body');row['name']=name;rows.append(row)
    origins.append(dict(path=path,sha256Bytes=digest(blob),sha256Lf=digest(text.encode()),selectedMethods=rows))
changes=json.loads(read(LANE/'local-hunks.json'));assert len(changes['changes'])==2
inverse=[]
for row in changes['changes']:
    old=read(LANE/'source-baselines'/row['path']);new=read(LANE/'proof-only'/row['path']);text=new.decode('utf-8')
    assert digest(old)==row['baseSha256Bytes'] and digest(new)==row['candidateSha256Lf']
    for h in reversed(row['hunks']):assert text.count(h['after'])==1;text=text.replace(h['after'],h['before'],1)
    assert text.encode()==old.replace(b'\r\n',b'\n')
    inverse.append(dict(path=row['path'],hunks=len(row['hunks']),reverseNormalizedByteEqual=True,baseSha256Lf=row['baseSha256Lf'],candidateSha256Lf=row['candidateSha256Lf']))
bindingsPath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopProfileBindings.kt'
raw=read(CANDIDATE/bindingsPath);assert digest(raw)==next(r['sha256Bytes']for r in json.loads(read(MAIN/'desktop/.local/stable-product-snapshot-45/manifest.json'))['inputs']if r['path']==bindingsPath)
wide(LANE/'source-baselines'/bindingsPath).parent.mkdir(parents=True,exist_ok=True);wide(LANE/'source-baselines'/bindingsPath).write_bytes(raw)
interface=raw.decode('utf-8').split('internal interface DesktopProfileAccountPort {',1)[1].split('\n}',1)[0]
methods=re.findall(r'\bfun (\w+)\(',interface);assert len(methods)==14
adapterPath='prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopProfileAccountsBinding.kt'
adapter=read(LANE/adapterPath).decode('utf-8');assert set(re.findall(r'override (?:suspend )?fun (\w+)\(',adapter))==set(methods)
originalUpsert=body(read(LANE/'original-stable'/selection.keys().__iter__().__next__()).decode('utf-8'),'upsertCurrentAccount')['body']
candidateUpsert=body(read(LANE/'proof-only'/changes['changes'][0]['path']).decode('utf-8'),'upsertProfileCurrentAccount')['body']
fieldMap={
 'name':('navData?.uname?.ifBlank { previous?.name.orEmpty() } ?: previous?.name.orEmpty()','nav?.uname?.ifBlank { previous?.session?.name.orEmpty() } ?: previous?.session?.name.orEmpty()'),
 'face':('navData?.face?.ifBlank { previous?.face.orEmpty() } ?: previous?.face.orEmpty()','nav?.face?.ifBlank { previous?.session?.face.orEmpty() } ?: previous?.session?.face.orEmpty()'),
 'isVip':('navData?.vip?.status == 1 || TokenManager.isVipCache','nav?.vip?.status == 1 || account.isVip'),
 'vipLabel':('navData?.vip?.label?.text.orEmpty().ifBlank { previous?.vipLabel.orEmpty() }','nav?.vip?.label?.text.orEmpty().ifBlank { previous?.session?.vipLabel.orEmpty() }'),
}
for pair in fieldMap.values():assert pair[0]in originalUpsert and pair[1]in candidateUpsert
result=json.loads(read(LANE/'run-03/result.json'));assert result['passed']and result['assertions']==30 and result['cpToolsSourcesPrePostByteEqual']
contract=json.loads(read(LANE/'source-contract.json'));contract['originalAnchors']={r['name']:[r['startLine'],r['endLine']]for r in origins[0]['selectedMethods']};save('source-contract.json',contract)
save('source-audit.json',dict(passed=True,originalCommit=COMMIT,originalSources=origins,interfacePath=bindingsPath,interfaceSha256Bytes=digest(raw),interfaceMethods=methods,complete14Methods=True,inverseHunks=inverse,navFieldExpressionMappings={k:dict(original=v[0],candidate=v[1],sameSourceExpressionWithStorageAlias=True)for k,v in fieldMap.items()},scope='Source-preserving original field expressions with declared single-store/owner adapters; not whole-body identity or actual mounted Profile UI proof'))
save('installation-recipe.json',dict(baseActualSnapshot=45,newManualSource=dict(source=adapterPath,destination=adapterPath.removeprefix('prepared/'),sha256Lf=digest(adapter.encode())),existingExactHunks='local-hunks.json',sharedFamilies=2,hunks=4,wholeProofOnlyFilesMustNotBeInstalled=True,sourceRegistryNewUpstreamIdentity=False,newSourceRegistryEntryNotNeededForHandwrittenAdapter=True,rootConstructor='DesktopProfileAccountsBinding(repository, capturedEpoch, capturedMid, entryJob, isCurrent, commitIfCurrent)',requiredRootContract=['Capture primary MID and epoch under the SAME SessionStore; capturedMid is not playback MID','Reuse the existing Profile navigation entry Job and Store -> entry admission. Root commit must run the supplied action synchronously at most once','Key the entry by primary epoch/MID; metadata name/face/VIP changes do not create another account owner','Account switch/logout invalidates old entries; construct replacement only after old ownership is retired. Authentication reset is outside Store/entry monitors','Original Profile VM guards removal of the active account. Preserve that existing UI/VM guard','Do not transfer an old adapter to the replacement epoch. Only exact same-caller logout terminal may validate clearActive completion; subsequent old reads and writes are cancelled','No HTTP/client creation, native close/join, or blocking callback may be added inside commitIfCurrent'],existingKeySchemaUnchanged=True,prospectiveProof=result,actualWholeProductAndRootMountPending=True))
save('result.json',dict(passed=True,phase='prepared-profile-account-port-on-actual45-runtime',originalCommit=COMMIT,groups=3,assertions=30,existingProductionFamiliesOverridden=2,actualProductAcceptance=False,RootMounted=False,HTTP=False,GUI=False,sourceAudit='source-audit.json',acceptedRun='run-03',preservedFailureRuns=['run-01','run-02'],failures='Both production compiles passed; fixture compilation failed on identical missing whitespace around Kotlin infix to. A shell edit failed before run-02, so run-02 retains the same failure. Run-03 fixes fixture whitespace only; production inputs unchanged.',cpPrePostByteEqual=True))
assert not (LANE/'frozen-handoff.json').exists()
rows=[];excluded=[]
for p in sorted(wide(LANE).rglob('*')):
    if not p.is_file():continue
    rel=p.relative_to(wide(LANE)).as_posix()
    if p.suffix in ('.jar','.class','.pyc')or '/private-synthetic-store/'in '/'+rel or '__pycache__'in rel:
        excluded.append(dict(path=rel,reason='private temporary synthetic store or binary',sha256Bytes=digest(p.read_bytes())));continue
    rows.append(dict(path=rel,bytes=p.stat().st_size,sha256Bytes=digest(p.read_bytes())))
save('frozen-handoff.json',dict(frozen=True,phase='source-only-complete-profile-account-port',baseActualSnapshot=45,artifacts=rows,excludedPrivateAndBinary=excluded,installation='installation-recipe.json',proof=dict(groups=3,assertions=30,productionOverrides=2,fixtureClassIntersections=0,actual45RuntimeEntries=97),limitations=['prospective Store/Repository overrides; actual whole-product installation not accepted here','no Root Profile mounted UI, account requests, network/socket, native window, chooser, or user file operations','proof-only whole source copies are evidence only; installation uses exact local-hunks'],oldFrozenPacketsUnchanged=['Playback84','FinalPublication428','actual44','actual45 proof86']))
print(json.dumps(dict(frozenHandoff=str(LANE/'frozen-handoff.json'),sha256Bytes=digest(read(LANE/'frozen-handoff.json')),artifacts=len(rows),excluded=len(excluded),manualSourceSha256Lf=digest(adapter.encode()),localHunksSha256Bytes=digest(read(LANE/'local-hunks.json')),installationRecipeSha256Bytes=digest(read(LANE/'installation-recipe.json')))))

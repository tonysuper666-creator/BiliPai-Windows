from pathlib import Path
import hashlib,json,subprocess
root=Path(__file__).resolve().parent;main=root.parents[2];candidate=main.parent/'BiliPai-v023'
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git','-c','core.longpaths=true',*a],cwd=candidate)
assert not (root/'installation.json').exists() and not git('status','--porcelain','-z').strip()
head=git('rev-parse','HEAD').decode().strip();assert head=='7637ac94555968f419e78ce496451523f9e194bb'
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalSpacePagesRoot.kt'
before=(candidate/path).read_bytes();s=before.decode().replace('\r\n','\n')
a='    fun prune() {\n';b='''    /** Navigation animations may recompose an outgoing physical entry after
     * it has been removed. Such a view cannot create/revive a retained owner. */
    fun entryIfRetained(key:BiliPaiNavKey):DesktopOriginalSpacePageEntry? = synchronized(lock) {
        if(!owns()||!routes.containsEntry(key))return@synchronized null
        try { entry(key) } catch(cancelled:CancellationException) {
            if(!owns()||!routes.containsEntry(key))null else throw cancelled
        }
    }
    fun prune() {
'''
assert s.count(a)==1;s=s.replace(a,b,1)
a='    val entry=pages.entry(key)\n';b='    val entry=pages.entryIfRetained(key)?:return\n'
assert s.count(a)==1;s=s.replace(a,b,1)
p=root/'before'/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(before)
(candidate/path).write_text(s,encoding='utf8',newline='\n')
d=dict(baseCommit=head,sourceCount=1234,resourceCount=244,sourceTargets=[dict(path=path,beforeSha256Bytes=sha(before),afterSha256Bytes=sha(s.encode()))],
 priorActualWindow='actual91-01',priorWindowAsserted=17,actualWindowFailure='Outgoing removed Space entry recomposed during navigation animation',
 retiredUiCannotRecreateOwner=True,strictRequestEntryAdmissionPreserved=True,normalCompile=False,rootRuntimeAccepted=False)
(root/'installation.json').write_text(json.dumps(d,indent=2)+'\n',encoding='utf8');print(json.dumps(d))

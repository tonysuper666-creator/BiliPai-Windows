from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-67'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n').decode('utf8')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def save(p,v):put(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'
base=read(REPO/path);assert sha(base)=='81711670c1808f81291e191bc68b4e4516c81d0dfa86ae5a2a286d41c40cd536'
snapshot=json.loads(read(SNAP/'manifest.json'));assert next(r for r in snapshot['inputs']if r['path']==path)['sha256Bytes']==sha(base)
t=base;hunks=[]
def change(a,b,label):
 global t
 assert t.count(a)==1,(label,t.count(a));at=t.index(a);t=t[:at]+b+t[at+len(a):];hunks.append(dict(before=a,after=b,at=at,label=label))
change('    private val primaryApi = repository.ownedHomeService','    private val capturedPrimaryApi = repository.ownedHomeService','Backing identifier only; admitted getter exposes this immutable request API')
change('        api = primaryApi,','        api = capturedPrimaryApi,','Original primary service remains the exact raw protocol transport')
change('    private val preferenceSnapshot = preferences.normalized()', '''    // Identity scalar only, captured under the SAME receipt/Store -> entry gate.
    private val capturedPrimaryMid = read { repository.activeAccountMid() }
    private val metadataPlaybackCalls = repository.ownedPlaybackCallFactory(authorization, ::current)
    private val preferenceSnapshot = preferences.normalized()''','Same request metadata transport and primary identity; no client/cache/store')
change('    val rawRepository: DesktopOriginalVideoLoadRepository get() = protocol\n', '''    val rawRepository: DesktopOriginalVideoLoadRepository get() = protocol

    /** Metadata facet of THIS captured request, not a latest-credential service. */
    val primaryApi: BilibiliApi get() = read { capturedPrimaryApi }
    val playbackCalls: okhttp3.Call.Factory get() = read { metadataPlaybackCalls }
    fun hasPrimarySession(): Boolean = read {
        !repository.ownedHomeCookie("SESSDATA", receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    fun hasPrimaryCsrf(): Boolean = read {
        !repository.ownedHomeCookie("bili_jct", receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    fun hasPrimaryBuvid(): Boolean = read {
        !repository.ownedHomeCookie("buvid3", receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    fun hasPrimaryAccessToken(): Boolean = read {
        !repository.ownedHomeAccessToken(receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    /** Required query reads Repository's EXISTING visitor generation/flag. It is
     * supplied only by metadata assembly; the pre-existing capture ABI is intact. */
    fun isBuvidInitialized(
        query: (DesktopPlaybackAuthorizationReceipt, () -> Boolean) -> Boolean,
    ): Boolean = read { query(receipt, ::entryCurrent) }

    fun updatePrimaryVip(isVip: Boolean): Unit = read {
        if (capturedPrimaryMid == null) throw CancellationException("Primary VIP session absent")
        repository.withProfileAccountAdmission(receipt.accountEpoch, capturedPrimaryMid,
            ::entryCurrent, commitIfEntryCurrent) { saveProfileVipStatus(isVip) }
        // The existing Store may advance authorization revision for a changed VIP.
        // This view is NEVER retagged; subsequent use must reject that old receipt.
    }
''','Eight admitted original metadata views; primary VIP writes the actual encrypted Store')
back=t
for h in reversed(hunks):
 assert back[h['at']:h['at']+len(h['after'])]==h['after'];back=back[:h['at']]+h['before']+back[h['at']+len(h['after']):]
assert back==base and t[t.index('    companion object {'):]==base[base.index('    companion object {'):]
put(P/'baseline'/path,base);put(P/'prepared'/path,t)
save(P/'local-hunks/binding.json',dict(path=path,baseSHA256LF=sha(base),candidateSHA256LF=sha(t),hunks=hunks,exactIndexedInverse=True,captureAndConstructorAbiUnchanged=True,installWholeFile=False))
repoPath='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt';repo=read(REPO/repoPath)
assert next(r for r in snapshot['inputs']if r['path']==repoPath)['sha256Bytes']==sha(repo)
before='    internal fun ownedHomeAccessToken(expectedEpoch: Long, stillOwned: () -> Boolean): String? ='
after='''    /** Existing visitor SPI/bootstrap completion only, not Android activation proof. */
    internal fun ownedHomeVisitorInitialized(expectedEpoch: Long, stillOwned: () -> Boolean): Boolean =
        sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) {
            visitorInitialized && visitorGeneration == expectedEpoch
        }

'''+before
assert repo.count(before)==1
put(P/'baseline'/repoPath,repo)
save(P/'local-hunks/repository-visitor.json',dict(path=repoPath,baseSHA256LF=sha(repo),candidateSHA256LF=sha(repo.replace(before,after,1)),hunks=[dict(before=before,after=after)],sourceOnlyVisibilityAccessor=True,compiledOverride=False,installWholeFile=False,noVisitorFlagOrCacheProduced=True))
save(P/'source-contract.json',dict(actualBase=67,baseRuntimeEntries=101,bindings=8,store='Existing SessionStore',primaryApi='Existing ownedHomeService; getter read admission and transport captures original request receipt/Job',playbackCalls='Existing ownedPlaybackCallFactory(authorization, ::current), no new Call.Factory/client authority',visitorQuery='Required per-metadata query of actual Repository-owned visitorInitialized AND visitorGeneration; no constructor/capture ABI change',vipCommit='Existing withProfileAccountAdmission + saveProfileVipStatus under outer receipt Store -> entry gate; primary MID captured; no credential or MID replacement',getWbiKeysVisibility='REFERENCE ONLY: parent sole frozen metadata/core visibility hunk; not reproduced in this packet',vipRevisionBoundary='Changed VIP is in existing playback authorization identity. Actual Store can retire this receipt; original full load must retire/re-capture rather than silently retag. Store policy is unchanged.',noHttpOrAccountOrGuiOrNativeRun=True))
print('Prepared minimal Binding facet',sha(t),'4 exact hunks; existing capture ABI retained')

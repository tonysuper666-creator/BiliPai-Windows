from pathlib import Path
import hashlib,json,subprocess
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-45'
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def save(name,value):write(LANE/name,(json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode())
assert sha((SNAP/'manifest.json').read_bytes())=='61b8233a766542366bf6a72868434e9effbf2f0c6458ae0f77326168a50f3fec'
manifest=json.loads((SNAP/'manifest.json').read_bytes());inputs={r['path']:r for r in manifest['inputs']}
STORE='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSessionStore.kt';REPO='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt'
store_fragment='''
    /** Profile TokenManager writes share the canonical primary identity and encrypted file.
     * Called only inside Repository's captured-epoch Store -> entry admission. */
    internal fun saveProfileMid(mid: Long) = synchronized(lock) {
        val account = saved.account ?: throw CancellationException("Profile primary session absent")
        if (mid <= 0L || account.mid != mid) throw CancellationException("Profile nav MID does not match captured primary")
        val next = saved.copy(account = account.copy(mid = mid)).let { it.copy(accounts = accountRecords(it)) }
        persist(next); saved = next
        mutableAccount.value = next.account
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }

    internal fun saveProfileVipStatus(vip: Boolean) = synchronized(lock) {
        val account = saved.account ?: throw CancellationException("Profile primary session absent")
        val next = saved.copy(account = account.copy(isVip = vip)).let { it.copy(accounts = accountRecords(it)) }
        persist(next); saved = next; refreshPlaybackAuthorizationLocked()
        mutableAccount.value = next.account
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }

    /** Original AccountSessionStore.upsertCurrentAccount values, using this Store's credentials.
     * No cookie/token/selected-playback identity is obtained from Profile's NavData. */
    internal fun upsertProfileCurrentAccount(nav: com.android.purebilibili.data.model.response.NavData?) = synchronized(lock) {
        val account = saved.account ?: return@synchronized
        val mid = nav?.mid?.takeIf { it > 0L } ?: account.mid
        val sessData = saved.cookies["SESSDATA"]?.takeIf { it.isNotBlank() } ?: return@synchronized
        if (mid != account.mid) throw CancellationException("Profile upsert MID does not match captured primary")
        val existing = accountRecords(saved).associateBy { it.session.mid }
        val previous = existing[mid]
        val timestamp = System.currentTimeMillis()
        val updated = StoredAccountSession(
            mid = mid,
            name = nav?.uname?.ifBlank { previous?.session?.name.orEmpty() } ?: previous?.session?.name.orEmpty(),
            face = nav?.face?.ifBlank { previous?.session?.face.orEmpty() } ?: previous?.session?.face.orEmpty(),
            sessData = sessData,
            csrf = saved.cookies["bili_jct"].orEmpty(),
            accessToken = saved.accessToken.orEmpty(),
            refreshToken = saved.refreshToken,
            accessTokenPlatform = saved.accessTokenPlatform,
            buvid3 = saved.cookies["buvid3"].orEmpty().ifBlank { saved.spiCookies["buvid3"].orEmpty() },
            isVip = nav?.vip?.status == 1 || account.isVip,
            vipLabel = nav?.vip?.label?.text.orEmpty().ifBlank { previous?.session?.vipLabel.orEmpty() },
            lastUsedAt = timestamp
        )
        val primary = account.copy(name = updated.name, avatar = updated.face, isVip = updated.isVip)
        val nextPrimary = saved.copy(account = primary, lastUsedAt = timestamp)
        val record = recordCurrent(nextPrimary).copy(session = updated)
        val merged = existing.values.filterNot { it.session.mid == mid }.plus(record).sortedByDescending { it.session.lastUsedAt }
        val next = nextPrimary.copy(accounts = merged)
        persist(next); saved = next; refreshPlaybackAuthorizationLocked()
        mutableAccount.value = primary
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }
'''
repo_fragment='''
    /** All Profile reads/writes are admitted by THIS same Store, then Root's entry gate.
     * The callback must only commit short synchronous state/file actions, never HTTP/native join. */
    internal fun <T> withProfileAccountAdmission(expectedEpoch: Long, expectedMid: Long?,
        stillOwned: () -> Boolean, commitIfCurrent: ((() -> Unit) -> Boolean),
        action: DesktopSessionStore.() -> T): T {
        try {
            return sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) {
                if (sessions.activeAccountMid() != expectedMid) throw CancellationException("Profile primary MID changed")
                var applied = false
                var value: Any? = null
                val admitted = commitIfCurrent {
                    if (sessions.generation != expectedEpoch || sessions.activeAccountMid() != expectedMid || !stillOwned())
                        throw CancellationException("Profile account entry retired")
                    value = action(sessions); applied = true
                }
                if (!admitted || !applied) throw CancellationException("Profile account entry rejected")
                @Suppress("UNCHECKED_CAST")
                value as T
            }
        } catch (error: BiliApiException) {
            throw CancellationException("Profile account epoch retired").also { it.initCause(error) }
        }
    }

    /** Root's existing authentication/cache reset, outside Store/entry monitors. */
    internal fun profileAuthenticationChanged() = resetAuthentication()

    /** Completion of this exact logout only: no mutation or admission under the new epoch. */
    internal fun verifyProfileLogoutTerminal(expectedEpoch: Long, acceptedEpoch: Long): Boolean = try {
        sessions.withHomeRequestAdmission(acceptedEpoch, { true }) {
            acceptedEpoch == expectedEpoch + 1L && sessions.activeAccountMid() == null && sessions.currentCookies()["SESSDATA"].isNullOrBlank()
        }
    } catch (_: BiliApiException) { false }
'''
changes=[]
for rel,fragment,anchor in [(STORE,store_fragment,'    internal fun accessTokenCredentials()'),(REPO,repo_fragment,'    internal val httpClient:')]:
    raw=(CANDIDATE/rel).read_bytes();assert sha(raw)==inputs[rel]['sha256Bytes'],(rel,sha(raw),inputs[rel]['sha256Bytes'])
    write(LANE/'source-baselines'/rel,raw)
    text=raw.decode('utf-8').replace('\r\n','\n')
    additions=[]
    if rel==STORE and 'import kotlinx.coroutines.CancellationException'not in text:
        before='package com.bilipai.desktop.data';after=before+'\n\nimport kotlinx.coroutines.CancellationException'
        assert text.count(before)==1;text=text.replace(before,after,1);additions.append(dict(before=before,after=after))
    line=next(s for s in text.splitlines()if s.startswith(anchor));after=fragment+'\n'+line
    assert text.count(line)==1;text=text.replace(line,after,1);additions.append(dict(before=line,after=after))
    if rel==STORE:
        before='session.cookies["buvid3"].orEmpty().ifBlank { session.spiCookies["buvid3"].orEmpty() }, account.isVip, lastUsedAt = session.lastUsedAt)'
        after='session.cookies["buvid3"].orEmpty().ifBlank { session.spiCookies["buvid3"].orEmpty() }, account.isVip,\n            vipLabel = session.accounts.firstOrNull { it.session.mid == account.mid }?.session?.vipLabel.orEmpty(),\n            lastUsedAt = session.lastUsedAt)'
        assert text.count(before)==1;text=text.replace(before,after,1);additions.append(dict(before=before,after=after))
    inverse=text
    for h in reversed(additions):assert inverse.count(h['after'])==1;inverse=inverse.replace(h['after'],h['before'],1)
    assert inverse==raw.decode('utf-8').replace('\r\n','\n')
    changes.append(dict(path=rel,baseSha256Bytes=sha(raw),baseSha256Lf=sha(inverse.encode()),candidateSha256Lf=sha(text.encode()),hunks=additions))
    write(LANE/'proof-only'/rel,text.encode())
save('local-hunks.json',dict(baseActualSnapshot=45,existingFamilies=2,wholeFileInstallationForbidden=True,changes=changes))
original='app/src/main/java/com/android/purebilibili/core/store/AccountSessionStore.kt'
blob=subprocess.run(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+original],cwd=CANDIDATE,capture_output=True,check=True).stdout
write(LANE/'original-stable'/original,blob)
save('source-contract.json',dict(originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',originalPath=original,originalSha256Bytes=sha(blob),
    originalAnchors=dict(upsert=[124,155],clearActive=[97,103],remove=[105,121],activate=[158,201]),
    sameStore=True,newClient=False,newSchema=False,newPersistentKeys=False,originalUpsertFields=['mid','name','face','sessData','csrf','accessToken','refreshToken','accessTokenPlatform','buvid3','isVip','vipLabel','lastUsedAt'],
    declaredAdapters=['Primary MID save rejects mismatched Nav MID instead of relabeling live credentials','Existing Store coalesces logout session+active selector in one epoch publication; subsequent clearActive only validates exact terminal receipt and never opens a new-epoch write grant','Synchronous removeAccount uses real entry Job and one immediate atomic click admission; suspend mutations additionally capture their caller Job','Existing recordCurrent retains previously persisted original vipLabel rather than dropping it while projecting same active record']))
print(json.dumps(dict(prepared=True,hunks=sum(len(c['hunks'])for c in changes),sharedFamilies=2,baseActualSnapshot=45)))

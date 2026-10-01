from pathlib import Path
import hashlib,json,subprocess,difflib
P=Path(__file__).resolve().parent;MAIN=P.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,b):
 wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b if isinstance(b,bytes)else b.encode())
def save(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def original(path):
 b=subprocess.run(['git','show',COMMIT+':'+path],cwd=CANDIDATE,capture_output=True,check=True).stdout
 write(P/'original-stable'/path,b);return dict(path=path,sha256Bytes=sha(b),lines=len(b.splitlines()))
identities=[original(p)for p in ['app/src/main/java/com/android/purebilibili/core/cache/PlayUrlCache.kt','app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt','app/src/main/java/com/android/purebilibili/core/network/TokenRefreshHelper.kt','app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt']]
save(P/'original-source-inventory.json',dict(commit=COMMIT,identities=identities))
rel='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt';base=read(CANDIDATE/rel).replace(b'\r\n',b'\n').decode();text=base;hunks=[]
def hunk(name,before,after):
 global text
 assert text.count(before)==1,(name,text.count(before))
 text=text.replace(before,after,1);hunks.append(dict(name=name,before=before,after=after))
hunk('single-protocol-monitor-and-original-timing',
'    private val wbiMutex = Mutex()\n    private var wbiKeys: Pair<String, String>? = null\n    @Volatile private var wbiExpiresAt = 0L\n    private var wbiGeneration = -1L\n    private val playbackCache = DesktopPlaybackCache()\n',
'''    private val wbiMutex = Mutex()
    // Store -> entry admission -> this short monitor. Never suspend or perform IO here.
    private val playbackProtocolMonitor = Any()
    private var wbiKeys: Pair<String, String>? = null
    private var wbiExpiresAt = 0L
    private var wbiGeneration = -1L
    private var appApiCooldownUntilMs = 0L
    private var lastPlayback412Time = 0L
    private val playbackCache = DesktopPlaybackCache()
    private val originalVideoWbiCacheDurationMs = TimeUnit.MINUTES.toMillis(30)

    private fun resetVideoProtocolGenerationLocked(generation: Long) {
        if (wbiGeneration != generation) {
            wbiKeys = null
            wbiExpiresAt = 0L
            appApiCooldownUntilMs = 0L
            lastPlayback412Time = 0L
            wbiGeneration = generation
        }
    }

    /** Views only: the existing playbackCache and WBI/diagnostics fields remain sole owners.
     * commitIfCurrent must take the same entry gate AFTER this Store admission.
     * Only short state/cache actions run in this gate; no Call/ACK/native wait or join. */
    internal fun originalVideoProtocolViews(
        authorization: DesktopPlaybackAuthorization,
        stillOwned: () -> Boolean,
        commitIfCurrent: ((() -> Unit) -> Boolean),
        cacheKey: (String, Long, Int) -> DesktopPlaybackCache.Key,
    ): Pair<com.bilipai.desktop.ui.DesktopOriginalVideoRawCache, com.bilipai.desktop.ui.DesktopOriginalVideoProtocolState> {
        fun <T> admitted(block: () -> T): T = sessions.withPlaybackAuthorizationAdmission(authorization.receipt, stillOwned) {
            var result: Result<T>? = null
            if (!commitIfCurrent {
                if (!stillOwned()) throw CancellationException("Original video request retired")
                result = runCatching(block)
            }) throw CancellationException("Original video entry retired")
            requireNotNull(result).getOrThrow()
        }
        fun <T> state(block: () -> T): T = admitted { synchronized(playbackProtocolMonitor) {
            resetVideoProtocolGenerationLocked(authorization.receipt.accountEpoch)
            block()
        } }
        fun key(bvid: String, cid: Long, quality: Int): DesktopPlaybackCache.Key = cacheKey(bvid, cid, quality).also {
            require(it.accountEpoch == authorization.receipt.accountEpoch && it.authorizationRevision == authorization.receipt.revision)
        }
        val cache = object : com.bilipai.desktop.ui.DesktopOriginalVideoRawCache {
            override fun get(bvid: String, cid: Long, requestedQuality: Int): PlayUrlData? = admitted {
                playbackCache.get(key(bvid, cid, requestedQuality))?.data
            }
            override fun put(bvid: String, cid: Long, data: PlayUrlData, quality: Int) { admitted {
                playbackCache.put(key(bvid, cid, quality), data, quality)
            } }
        }
        val protocolState = object : com.bilipai.desktop.ui.DesktopOriginalVideoProtocolState {
            override var appApiCooldownUntilMs: Long
                get() = state { this@DesktopRepository.appApiCooldownUntilMs }
                set(value) { state { this@DesktopRepository.appApiCooldownUntilMs = value } }
            override var wbiKeys: Pair<String, String>?
                get() = state { this@DesktopRepository.wbiKeys }
                set(value) { state { this@DesktopRepository.wbiKeys = value } }
            override var wbiKeysTimestamp: Long
                get() = state { if (wbiExpiresAt == 0L) 0L else wbiExpiresAt - originalVideoWbiCacheDurationMs }
                set(value) { state { wbiExpiresAt = if (value == 0L) 0L else value + originalVideoWbiCacheDurationMs } }
            override var last412Time: Long
                get() = state { lastPlayback412Time }
                set(value) { state { lastPlayback412Time = value } }
        }
        return cache to protocolState
    }

    internal fun originalVideoCacheKey(authorization: DesktopPlaybackAuthorization, bvid: String, cid: Long,
        quality: Int, preferences: PlayerPreferences, codecOverride: String?, blockedCodecs: Set<String>,
        av1Supported: Boolean): DesktopPlaybackCache.Key {
        val codec = normalizeCodecFamilyKey(codecOverride)
        require(codec == null || codec in setOf("avc1", "hev1", "av01")) { "不支持的视频编码" }
        return DesktopPlaybackCache.Key(authorization.receipt.accountEpoch, bvid, cid, quality, codec,
            firstCodec = resolveEffectiveVideoCodecPreference(codec, preferences.videoCodecPreference, blockedCodecs),
            secondCodec = resolveEffectiveVideoSecondCodecPreference(codec, preferences.videoSecondCodecPreference),
            audioQuality = resolveSpeedCompatibleAudioQualityPreference(resolveRequestedAudioQuality(
                preferences.defaultAudioQuality, preferences.lastSelectedAudioQuality), preferences.speed.toFloat()),
            av1Supported = resolveEffectiveAv1Support(av1Supported, blockedCodecs), authorizationRevision = authorization.receipt.revision)
    }
''')
hunk('home-wbi-reads-same-monitor',
'''            val keys = wbiMutex.withLock {
                if (expectedEpoch != sessions.generation || wbiGeneration != expectedEpoch || !stillOwned())
                    throw kotlinx.coroutines.CancellationException("Home WBI epoch retired")
                requireNotNull(wbiKeys)
            }
''',
'''            val keys = wbiMutex.withLock {
                sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) { synchronized(playbackProtocolMonitor) {
                    resetVideoProtocolGenerationLocked(expectedEpoch)
                    requireNotNull(wbiKeys)
                } }
            }
''')
hunk('reset-same-protocol-state-no-io-under-monitor',
'    private fun resetAuthentication() { client.dispatcher.cancelAll(); visitorInitialized = false; wbiExpiresAt = 0; playbackCache.clear() }',
'''    private fun resetAuthentication() {
        client.dispatcher.cancelAll()
        visitorInitialized = false
        synchronized(playbackProtocolMonitor) {
            wbiKeys = null; wbiExpiresAt = 0L; wbiGeneration = -1L
            appApiCooldownUntilMs = 0L; lastPlayback412Time = 0L
        }
        playbackCache.clear()
    }''')
before='''        val cacheKey = DesktopPlaybackCache.Key(epoch, details.bvid, part.cid, quality, codec,
            firstCodec = resolveEffectiveVideoCodecPreference(codec, preferenceSnapshot.videoCodecPreference, blockedSnapshot),
            secondCodec = resolveEffectiveVideoSecondCodecPreference(codec, preferenceSnapshot.videoSecondCodecPreference),
            audioQuality = resolveSpeedCompatibleAudioQualityPreference(resolveRequestedAudioQuality(
                preferenceSnapshot.defaultAudioQuality, preferenceSnapshot.lastSelectedAudioQuality), preferenceSnapshot.speed.toFloat()),
            av1Supported = resolveEffectiveAv1Support(true, blockedSnapshot), authorizationRevision = authorization.receipt.revision)
'''
hunk('one-existing-cache-key-formula',before,
'''        val cacheKey = originalVideoCacheKey(authorization, details.bvid, part.cid, quality,
            preferenceSnapshot, codec, blockedSnapshot, av1Supported = true)
''')
before=text[text.index('    private suspend fun sign(params:'):text.index('    internal fun PlayUrlData.toPlaybackSource')]
after='''    private suspend fun sign(params: Map<String, String>, includeRiskFingerprint: Boolean = false,
        forceRefresh: Boolean = false, requestApi: BilibiliApi = api, expectedEpoch: Long? = null,
        stillOwned: () -> Boolean = { true }): Map<String, String> {
        val keys = wbiMutex.withLock {
            val generation = expectedEpoch ?: sessions.generation
            val now = System.currentTimeMillis()
            val cached = sessions.withHomeRequestAdmission(generation, stillOwned) { synchronized(playbackProtocolMonitor) {
                resetVideoProtocolGenerationLocked(generation)
                wbiKeys?.takeIf { !forceRefresh && now < wbiExpiresAt }
            } }
            cached ?: run {
                // Nav fetch remains outside Store/entry/protocol monitor; anonymous -101 can carry keys.
                val img = requestApi.getNavInfo().data?.wbi_img ?: throw BiliApiException(-1, "无法获取接口签名信息")
                val imageKey = img.img_url.substringAfterLast('/').substringBefore('.')
                val subKey = img.sub_url.substringAfterLast('/').substringBefore('.')
                require(imageKey.length == 32 && subKey.length == 32) { "接口签名信息格式异常" }
                sessions.withHomeRequestAdmission(generation, stillOwned) { synchronized(playbackProtocolMonitor) {
                    resetVideoProtocolGenerationLocked(generation)
                    wbiKeys = imageKey to subKey
                    wbiExpiresAt = System.currentTimeMillis() + originalVideoWbiCacheDurationMs
                    requireNotNull(wbiKeys)
                } }
            }
        }
        return WbiUtils.sign(params, keys.first, keys.second, includeRiskFingerprint = includeRiskFingerprint)
    }

'''
hunk('same-wbi-sign-cache-original-30min',before,after)
write(P/'baseline'/rel,base);write(P/'proof-only'/rel,text)
save(P/'repository-local-hunks.json',dict(path=rel,baseLFsha256=sha(base.encode()),candidateLFsha256=sha(text.encode()),hunks=hunks))
write(P/'repository-local.patch',''.join(difflib.unified_diff(base.splitlines(True),text.splitlines(True),fromfile=rel,tofile=rel)))
rel='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopPlaybackCache.kt';base=read(CANDIDATE/rel).replace(b'\r\n',b'\n').decode();text=base;hunks=[]
hunk('original-capacity-80', 'LinkedHashMap<Key, Entry>(8, .75f, true)','LinkedHashMap<Key, Entry>(80, .75f, true)')
hunk('original-expiry-10min-strict-greater-preserve-clock-rollback-guard',
'now - entry.storedAt >= TimeUnit.MINUTES.toMillis(5)','now - entry.storedAt > TimeUnit.MINUTES.toMillis(10)')
hunk('original-lru-capacity-80','while (entries.size > 8)','while (entries.size > 80)')
write(P/'baseline'/rel,base);write(P/'proof-only'/rel,text)
save(P/'cache-local-hunks.json',dict(path=rel,baseLFsha256=sha(base.encode()),candidateLFsha256=sha(text.encode()),hunks=hunks))
write(P/'cache-local.patch',''.join(difflib.unified_diff(base.splitlines(True),text.splitlines(True),fromfile=rel,tofile=rel)))
for packet in ['repository','cache']:
 d=json.loads(read(P/(packet+'-local-hunks.json')));t=read(P/'proof-only'/d['path']).decode()
 for h in reversed(d['hunks']):
  assert t.count(h['after'])==1;t=t.replace(h['after'],h['before'],1)
 assert sha(t.encode())==d['baseLFsha256']
save(P/'source-inverse-result.json',dict(passed=True,repositoryHunks=5,cacheHunks=3,scope='exact selected candidate hunks only; no live mutation or policy/model duplication'))
print('prepared exact Repository/cache hunks; original source identities and inverse PASS')

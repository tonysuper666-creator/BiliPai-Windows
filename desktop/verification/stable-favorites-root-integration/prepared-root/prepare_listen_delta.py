from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
rel=Path('desktop/src/main/kotlin/com/bilipai/desktop/audio/ListenAudioSession.kt')
base=(REPO/rel).read_text(encoding='utf-8');base=base.replace('\r\n','\n');s=base
def replace(old,new):
 global s
 assert s.count(old)==1,old[:100]
 s=s.replace(old,new,1)
replace('    private var playGeneration = 0L\n','''    private var playGeneration = 0L
    /** Navigation-entry lease only; queue/items/native state remain the original session's. */
    private data class QueueOwnership(val owner: Any, val generation: Long, val nativeBaseline: Long)
    private var queueOwnership: QueueOwnership? = null
''')
replace('''    internal fun playStartingAt(items: List<PlaylistItem>, index: Int = 0, positionSeconds: Double = 0.0) {
        if (!sessionIsCurrent()) return
''','''    internal fun playStartingAt(items: List<PlaylistItem>, index: Int = 0, positionSeconds: Double = 0.0) =
        startQueue(items, index, positionSeconds, null)

    /** Same native actor as ordinary Listen playback; no fabricated parallel session. */
    internal fun playQueueForOwner(owner: Any, items: List<PlaylistItem>, index: Int = 0): Boolean {
        startQueue(items, index, 0.0, owner)
        return ownsQueue(owner)
    }

    private fun startQueue(items: List<PlaylistItem>, index: Int, positionSeconds: Double, owner: Any?) {
        if (!sessionIsCurrent()) return
''')
replace('''        shuffle = ShuffleProgress(history = listOf(selected), historyIndex = 0, cyclePlayed = setOf(selected))
''','''        queueOwnership = owner?.let { QueueOwnership(it, playGeneration, player.currentSourceVersion) }
        shuffle = ShuffleProgress(history = listOf(selected), historyIndex = 0, cyclePlayed = setOf(selected))
''')
replace('''        val pendingSourceVersion = player.currentSourceVersion
        ownedSourceVersion = null
''','''        val pendingSourceVersion = player.currentSourceVersion
        queueOwnership = queueOwnership?.copy(generation = generation, nativeBaseline = pendingSourceVersion)
        ownedSourceVersion = null
''')
replace('''        playJob?.cancel(); playGeneration++
        mutableState.update { it.copy(active = false, loading = false) }
''','''        val loadedOwner = queueOwnership?.takeIf { ownedPlaybackSourceVersion != null }
        playJob?.cancel(); playGeneration++
        queueOwnership = loadedOwner?.copy(generation = playGeneration)
        mutableState.update { it.copy(active = false, loading = false) }
''')
replace('''    fun enqueue(items: List<PlaylistItem>) { if (sessionIsCurrent()) changeQueue(normalizeListenQueue(mutableState.value.queue + items)) }
''','''    internal fun ownsQueue(owner: Any): Boolean {
        val lease = queueOwnership ?: return false
        if (lease.owner !== owner || lease.generation != playGeneration || !sessionIsCurrent()) return false
        if (ownedSourceVersion != null) return ownedPlaybackSourceVersion != null
        val current = mutableState.value
        return (current.loading || current.error != null) && player.currentSourceVersion == lease.nativeBaseline &&
            player.currentSourceSnapshot() == null
    }

    /** Original PlaylistManager addAllToCurrentPlaylist BVID append policy. */
    internal fun appendQueueForOwner(owner: Any, items: List<PlaylistItem>): Boolean {
        if (!ownsQueue(owner)) return false
        val existing = mutableState.value.queue.mapTo(mutableSetOf()) { it.bvid }
        val appended = items.filter { it.bvid !in existing }
        if (appended.isNotEmpty()) changeQueue(normalizeListenQueue(mutableState.value.queue + appended))
        return ownsQueue(owner)
    }

    internal fun stopQueueForOwner(owner: Any): Boolean {
        if (!ownsQueue(owner)) return false
        playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
        stopOwnedSource()
        mutableState.update { it.copy(active = false, loading = false, lyricsLoading = false) }
        persist()
        return true
    }

    fun enqueue(items: List<PlaylistItem>) { if (sessionIsCurrent()) changeQueue(normalizeListenQueue(mutableState.value.queue + items)) }
''')
replace('''    private fun stopOwnedSource() {
        ownedSourceVersion?.let(player::stopIfSourceVersion)
''','''    private fun stopOwnedSource() {
        queueOwnership = null
        ownedSourceVersion?.let(player::stopIfSourceVersion)
''')
out=HERE/'prepared'/rel;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(s,encoding='utf-8',newline='\n')
patch=''.join(difflib.unified_diff(base.splitlines(True),s.splitlines(True),fromfile='a/'+str(rel).replace('\\','/'),tofile='b/'+str(rel).replace('\\','/')))
(HERE/'listen-owned-queue.patch').write_text(patch,encoding='utf-8',newline='\n')
evidence=dict(file=str(rel).replace('\\','/'),baseLF=hashlib.sha256(base.encode()).hexdigest(),desiredLF=hashlib.sha256(s.encode()).hexdigest(),preparedOnly=True,sharedMainChanged=False,semantics='Real Listen session epoch/native-source lease; normal start retires, same queue next/pause-loaded retains, original BVID append no reload')
(HERE/'listen-delta-baseline.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
print(json.dumps(evidence,indent=2))

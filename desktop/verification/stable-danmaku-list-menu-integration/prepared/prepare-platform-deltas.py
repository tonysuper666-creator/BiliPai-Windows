from pathlib import Path
import hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-16'
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def main():
    manifest=json.loads((SNAP/'manifest.json').read_text());pins={p['path']:p['sha256Bytes'] for p in manifest['inputs']};rows=[]
    for name in ('DanmakuParser.kt','DanmakuOverlay.kt'):
        relative='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/'+name
        raw=(REPO/relative).read_bytes();assert sha(raw)==pins[relative],relative
        original=raw.decode('utf-8').replace('\r\n','\n');s=original;patch=[]
        def adapt(before,after):
            nonlocal s
            assert s.count(before)==1,(name,before,s.count(before));patch.append(dict(before=before,after=after));s=s.replace(before,after)
        if name=='DanmakuParser.kt':
            adapt('    val isVipGradualColor: Boolean = false,\n','''    val isVipGradualColor: Boolean = false,
    // Original source payload for the original list factories; display/plugin processing remains separate.
    val originalElement: DanmakuProto.DanmakuElem? = null,
    val originalXmlAttributes: String? = null,
    val originalXmlContent: String? = null,
''')
            adapt('            val text = StringBuilder()','            val text = StringBuilder()\n            val sourceText = StringBuilder()')
            adapt('                text.clear()','                text.clear(); sourceText.clear()')
            adapt('serverId = fields.getOrNull(7)?.toLongOrNull() ?: 0L, userHash = fields.getOrNull(6).orEmpty().take(200))','serverId = fields.getOrNull(7)?.toLongOrNull() ?: 0L, userHash = fields.getOrNull(6).orEmpty().take(200),\n                    originalXmlAttributes = attributes.getValue("p"))')
            adapt('                val limit = if ((pending?.mode ?: 0) >= 7) 16_384 else 300','''                if (pending != null && pending?.mode in 1..6) sourceText.append(characters, start, length)
                val limit = if ((pending?.mode ?: 0) >= 7) 16_384 else 300''')
            adapt('                        } else comments += it.copy(text = content)','                        } else comments += it.copy(text = content, originalXmlContent = sourceText.toString())')
            adapt('item.colorful == DanmakuProto.DmColorfulTypeVipGradualColor)','item.colorful == DanmakuProto.DmColorfulTypeVipGradualColor, originalElement = item)')
        else:
            adapt('    private var commandCid: Long? = null','''    private var commandCid: Long? = null
    private var poolSourceVersion: Long? = null
    private val mutablePoolSourceRevision = MutableStateFlow(0L)
    val poolSourceRevision: StateFlow<Long> = mutablePoolSourceRevision.asStateFlow()
    /** Derived view of the sole raw document, with no second item/cache authority. */
    fun poolSourceFor(cid: Long, sourceVersion: Long): DanmakuPoolSourceSnapshot? = synchronized(requestLock) {
        if (closed.get() || liveMode || cid <= 0L || commandCid != cid || poolSourceVersion != sourceVersion ||
            !player.ownsSourceVersion(sourceVersion)) null
        else DanmakuPoolSourceSnapshot(cid, sourceVersion, documentRevision.get(), rawDocument.comments)
    }''')
            adapt('    suspend fun load(cid: Long, aid: Long = 0L, durationSeconds: Double = 0.0)', '    suspend fun load(cid: Long, aid: Long = 0L, durationSeconds: Double = 0.0, expectedSourceVersion: Long? = null)')
            adapt('        loadSource(source, cid, aid, durationSeconds)','        loadSource(source, cid, aid, durationSeconds, expectedSourceVersion)')
            adapt('        loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds)','        loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds, null)')
            adapt('    private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double)', '    private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double, expectedSourceVersion: Long?)')
            adapt('                commandCid = cid; mutableCommands.value = emptyList()','''                // Clear the old document before publishing a new CID/version identity.
                rawDocument = DanmakuDocument()
                poolSourceVersion = expectedSourceVersion
                commandCid = cid; mutableCommands.value = emptyList()''')
            adapt('            documentRevision.incrementAndGet() to pluginProcessor','            documentRevision.incrementAndGet().also { mutablePoolSourceRevision.value = it } to pluginProcessor')
            adapt('    private interface User32 : StdCallLibrary {','    private interface User32 : StdCallLibrary {') if False else None
            s+='''
/** Immutable projection metadata; comments is the sole raw-document list reference. */
data class DanmakuPoolSourceSnapshot(val cid:Long,val sourceVersion:Long,val revision:Long,val comments:List<DanmakuComment>)
'''
        write(HERE/'base-inputs'/relative,original);write(HERE/'selected-files'/relative,s)
        reverse=s
        if name=='DanmakuOverlay.kt':reverse=reverse[:reverse.index('\n/** Immutable projection metadata;')]
        for p in reversed(patch):assert reverse.count(p['after'])==1;reverse=reverse.replace(p['after'],p['before'])
        assert reverse==original
        rows.append(dict(path=relative,actualMain16Sha256Bytes=pins[relative],baseSha256LF=sha(original.encode()),candidateSha256LF=sha(s.encode()),adaptations=patch,reverseOriginalLfEqual=True))
    # A member hunk only. Root owns the complete Operations file and all its existing stream/grade/reply members.
    fragment='''
    // Desktop original danmaku list/menu actions. Existing sole API/epoch/read/mutate authority.
    private val originalDanmakuActions = com.android.purebilibili.data.repository.DesktopOriginalDanmakuProtocol(
        api, { assertOwned(); repository.requireCsrf() }, ::assertOwned)
    internal suspend fun getDanmakuThumbupState(cid:Long,dmid:Long) =
        result { read { originalDanmakuActions.getDanmakuThumbupState(cid,dmid).getOrThrow() } }
    internal suspend fun recallDanmaku(cid:Long,dmid:Long) =
        result { mutate { originalDanmakuActions.recallDanmaku(cid,dmid).getOrThrow() } }
    internal suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean=true) =
        result { mutate { originalDanmakuActions.likeDanmaku(cid,dmid,like).getOrThrow() } }
    internal suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String="") =
        result { mutate { originalDanmakuActions.reportDanmaku(cid,dmid,reason,content).getOrThrow() } }
'''
    write(HERE/'fragments/DesktopDynamicCardOperations.danmaku.ktfrag',fragment)
    save(HERE/'platform-delta-contract.json',dict(snapshot16ManifestSha256Bytes=sha((SNAP/'manifest.json').read_bytes()),selectedFiles=rows,declaredProductOverrides=['DanmakuParser family','DanmakuOverlay family'],rootPlaybackRecipe='Move existing load() danmaku launch from before native.loadVersioned to after current = Current(...version...) assignment. Capture expected/accountEpoch/version, guard context still owned, pass expectedSourceVersion=version. Old/default/offline callers expose no list snapshot.',opsRecipe='Append member fragment only; never replace whole Ops. Add generated helper/source identities once to sole original producer/registry.',noMainOrGradle=True))
    print('prepared two exact source-backed platform deltas and Operations member fragment')
if __name__=='__main__':main()

from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2];SOURCE=MAIN.parent/'BiliPai-v023'
PIN='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
PATH='app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt'
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def span(s,start):
 at=s.index(start);opening=s.index('{',at);depth=1;i=opening+1;quote=None;escape=False
 while depth:
  c=s[i]
  if quote:
   if escape:escape=False
   elif c=='\\':escape=True
   elif c==quote:quote=None
  elif c in ['"',"'"]:quote=c
  elif c=='{':depth+=1
  elif c=='}':depth-=1
  i+=1
 return at,i
def generate(repo, output, standalone=False):
 outputRoot=Path(output)
 original=wide(_desktop_canonical_source(repo, PATH)).read_bytes().replace(b'\r\n',b'\n').decode()
 assert sha(original)=='a6a884fffc8d045609da0ac745d7376ec68c5237a2b4dae64e7a6c578240bd80'
 start,end=span(original,'object PlaylistManager {');body=original[start:end]
 edits=[]
 def change(before,after,label):
  nonlocal body
  assert body.count(before)==1,label
  body=body.replace(before,after);edits.append({'before':before,'after':after,'label':label})
 change('object PlaylistManager {','internal class DesktopOriginalPlaylistManager(\n    private val readSnapshot: () -> String?,\n    private val enqueueSnapshot: (String) -> Unit,\n    private val reportPersistenceFailure: (Throwable) -> Unit,\n) {','original singleton lifetime supplied by sole retained Root instance')
 change('    private var appContext: Context? = null\n    private val json = Json { ignoreUnknownKeys = true }\n','    private val json = Json { ignoreUnknownKeys = true }\n', 'Android context storage replaced by same global Store ports')
 a,b=span(body,'    fun init(context: Context) {')
 change(body[a:b],'    fun init() { restoreState() }','read original snapshot once from actual global Store')
 a,b=span(body,'    private fun persistState() {');before=body[a:b]
 after=before.replace('        val context = appContext ?: return\n','')
 pref='''            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SNAPSHOT, raw)
                .apply()'''
 assert after.count(pref)==1
 after=after.replace(pref,'            enqueueSnapshot(raw) // short queue admission only; persistence IO is outside Root gates')
 after=after.replace('''        }.onFailure { e ->
            Logger.e(TAG, "⚠️ Failed to persist playlist state", e)''','''        }.onFailure { e ->
            reportPersistenceFailure(e) // queues Root diagnostics outside admission
            Logger.e(TAG, "⚠️ Failed to persist playlist state", e)''')
 change(before,after,'original snapshot schema/serialization preserved; actual same Store enqueues outside-gate IO')
 a,b=span(body,'    private fun restoreState() {');before=body[a:b]
 after=before.replace('''        val context = appContext ?: return
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null)
            .orEmpty()''','        val raw = readSnapshot().orEmpty()')
 after=after.replace('''            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SNAPSHOT)
                .apply()''','            enqueueSnapshot("") // original invalid snapshot removal')
 assert before!=after
 change(before,after,'original decode/restore/invalid snapshot policy retained over sole settings document')
 inverse=body
 for edit in reversed(edits):
  assert inverse.count(edit['after'])==1
  inverse=inverse.replace(edit['after'],edit['before'])
 assert inverse==original[start:end]
 extra='''
    /** Read-only use of the ORIGINAL playlist session token for Root clients. */
    fun isSessionCurrent(session: PlaylistSession): Boolean = session == activePlaylistSession
    internal fun captureDesktopSession(): PlaylistSession = activePlaylistSession

    /** Same original session/state, with the existing Windows CID identity policy.
     * The reconciler's opaque keys never reach persisted items/API/native playback. */
    fun replaceQueueIfCurrent(items: List<PlaylistItem>, index: Int, session: PlaylistSession): Boolean {
        if (session != activePlaylistSession || index !in items.indices) return false
        val selected = _playlist.value.getOrNull(_currentIndex.value) ?: return false
        if (selected.bvid != items[index].bvid || selected.cid != items[index].cid) return false
        fun identity(item: PlaylistItem) = item.copy(bvid = "${item.bvid}:${item.cid}")
        val progress = reconcileShuffleProgressForPlaylistUpdate(
            _playlist.value.map(::identity), items.map(::identity), index, snapshotShuffleProgress())
        _playlist.value = items
        _currentIndex.value = index
        applyShuffleProgress(progress)
        persistState()
        return true
    }

    /** Metadata resolved the actual CID of THIS queued item. Index, original
     * session and shuffle history remain the same; no media command is issued. */
    fun resolveSelectedCidIfCurrent(session: PlaylistSession, bvid: String,
        expectedCid: Long, resolvedCid: Long): Boolean {
        if (session != activePlaylistSession || resolvedCid <= 0L) return false
        val index = _currentIndex.value
        val selected = _playlist.value.getOrNull(index) ?: return false
        if (selected.bvid != bvid || selected.cid != expectedCid) return false
        _playlist.value = _playlist.value.toMutableList().apply {
            this[index] = selected.copy(cid = resolvedCid)
        }
        persistState()
        return true
    }

    /** Actual old queue/shuffle handoff, without starting media or adding an authority. */
    fun adoptQueue(items: List<PlaylistItem>, index: Int, source: ExternalPlaylistSource,
        external: Boolean, progress: ShuffleProgress): PlaylistSession {
        val session = if (external) setExternalPlaylist(items, index, source) else {
            setPlaylist(items, index); activePlaylistSession
        }
        applyShuffleProgress(progress)
        persistState()
        return session
    }
'''
 body=body[:-1]+extra+'}\n'
 output='''// Selected complete original PlaylistManager object; platform persistence/lifetime adapters declared in inventory.
package com.android.purebilibili.feature.video.player

import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val TAG = "PlaylistManager"

'''+body+'\n'
 target=outputRoot/'com/android/purebilibili/feature/video/player/DesktopOriginalPlaylistManager.kt'
 wide(target.parent).mkdir(parents=True,exist_ok=True);wide(target).write_text(output,encoding='utf-8',newline='\n')
 inventory={'commit':PIN,'originalPath':PATH,'originalSha256LF':sha(original),'originalObjectBodySha256LF':sha(original[start:end]),'output':str(target),'outputSha256LF':sha(output),'edits':edits,'platformAdditions':extra,'platformAdditionsSha256LF':sha(extra),'existingCanonicalReference':'Actual DesktopPlaylistPolicies entire model/policies/session/shuffle helpers; no duplicate declarations','bodyInverseExact':True,'inverseScope':'Complete original object excluding separately declared platformAdditions; all adaptations reversed byte-exact'}
 return [inventory]
if __name__=='__main__':
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True);parser.add_argument('--standalone',action='store_true')
 args=parser.parse_args();rows=generate(args.repo,args.output,args.standalone);print('Original playlist source outputs:',len(rows))

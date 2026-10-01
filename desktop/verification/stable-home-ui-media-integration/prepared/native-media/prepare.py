from pathlib import Path
import difflib, hashlib, json, urllib.request

HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='69e63f425a531f814431fba12750bdb3721357f2'
PREFIX='desktop/src/main/kotlin/com/bilipai/desktop/player/'
CHANGES=[]
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(p):return p.read_bytes().replace(b'\r\n',b'\n')
def write(p,text):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8',newline='\n')
def save(p,obj):write(p,json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
def replace(s,before,after):
 assert s.count(before)==1,(s.count(before),before[:130]);CHANGES.append(dict(before=before,after=after));return s.replace(before,after,1)
def main():
 rows=[]
 for name in ['MpvNative.kt','MpvPlayer.kt']:
  CHANGES.clear()
  source=CANDIDATE/(PREFIX+name); original=lf(source).decode('utf-8'); s=original
  if name=='MpvNative.kt':
   s=replace(s,'import com.sun.jna.Pointer\n','import com.sun.jna.Pointer\nimport com.sun.jna.ptr.PointerByReference\n')
   s=replace(s,'    fun mpv_free(data: Pointer)\n','''    fun mpv_free(data: Pointer)

    // Optional same-core render transport, pinned render.h. Existing HWND/client ABI is unchanged.
    fun mpv_render_context_create(result: PointerByReference, handle: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(context: Pointer, callback: MpvRenderUpdateCallback, data: Pointer?)
    fun mpv_render_context_update(context: Pointer): Long
    fun mpv_render_context_render(context: Pointer, params: Pointer): Int
    fun mpv_render_context_free(context: Pointer)
''')
  else:
   s=replace(s,'class MpvPlayer internal constructor(private val useNullAudioOutput: Boolean = false) : AutoCloseable {',
    '''class MpvPlayer internal constructor(private val useNullAudioOutput: Boolean = false) : AutoCloseable {
    private var softwareTarget: MpvSoftwareTarget? = null
    internal constructor(softwareTarget: MpvSoftwareTarget, useNullAudioOutput: Boolean = false) : this(useNullAudioOutput) {
        this.softwareTarget = softwareTarget
    }''')
   s=replace(s,'            val revision = ++playbackRevision\n','            val revision = ++playbackRevision\n            softwareTarget?.beginSource(sourceVersion, revision)\n')
   s=replace(s,'            requestedSource = null\n            softwareDecodingRequested = false\n','            requestedSource = null\n            softwareTarget?.clear()\n            softwareDecodingRequested = false\n')
   s=replace(s,'    private fun attach(windowId: Long) {','''    /** Headless Compose texture transport; owns the SAME normal session/event loop and mpv instance. */
    internal fun startSoftwareTransport() = synchronized(lock) {
        check(softwareTarget != null) { "A software render target is required" }
        check(!closed.get()) { "Player is closed" }
        if (session == null) startSession(0L)
    }

    private fun attach(windowId: Long) {''')
   s=replace(s,'            if (closed.get() || session != null) return\n','            if (closed.get() || session != null || softwareTarget != null) return\n')
   s=replace(s,'        if (closed.compareAndSet(false, true)) {\n','        if (closed.compareAndSet(false, true)) {\n            softwareTarget?.close()\n')
   s=replace(s,'            var fatalFailure: Throwable? = null\n','            var fatalFailure: Throwable? = null\n            var softwareRenderer: MpvSoftwareRenderer? = null\n')
   s=replace(s,'                ) + if (useNullAudioOutput) mapOf("ao" to "null") else emptyMap()\n','''                ).let { original ->
                    if (softwareTarget == null) original else original.filterKeys { it != "wid" && it != "gpu-api" } +
                        mapOf("vo" to "libmpv", "hwdec" to "no")
                } + if (useNullAudioOutput) mapOf("ao" to "null") else emptyMap()
''')
   s=replace(s,'                checkResult(native, native.mpv_initialize(handle), "initialize")\n','''                checkResult(native, native.mpv_initialize(handle), "initialize")
                softwareTarget?.let { target ->
                    softwareRenderer = MpvSoftwareRenderer(native, handle, target).also { it.start() }
                }
''')
   s=replace(s,'                while (!closing.get()) {\n','                while (!closing.get()) {\n                    softwareRenderer?.throwIfFailed()\n')
   s=replace(s,'                if (native != null && handle != null) native.mpv_terminate_destroy(handle)\n','''                // The render context/thread MUST terminate before its core. Never call render APIs from this client worker.
                try { softwareRenderer?.close() }
                finally { if (native != null && handle != null) native.mpv_terminate_destroy(handle) }
''')
   s=replace(s,'                        activeRevision = action.revision\n','                        activeRevision = action.revision\n                        softwareTarget?.beginSource(action.version, action.revision)\n')
   s=replace(s,'resolveMpvHardwareDecoding(hardwareDecodeEnabled, action.softwareDecoding)',
    'if (softwareTarget == null) resolveMpvHardwareDecoding(hardwareDecodeEnabled, action.softwareDecoding) else "no"')
   # Other user hwdec setter uses the same optional transport capability; preserve default native output.
   s=replace(s,'                            resolveMpvHardwareDecoding(hardwareDecodeEnabled, softwareDecodingRequested)), "hwdec")',
    '                            if (softwareTarget == null) resolveMpvHardwareDecoding(hardwareDecodeEnabled, softwareDecodingRequested) else "no"), "hwdec")')
   s=replace(s,'                        publishState { it.copy(firstVideoFrameReady = true) }\n','''                        publishState {
                            softwareTarget?.enableSource(activeSourceVersion, activeRevision)
                            it.copy(firstVideoFrameReady = true)
                        }
''')
  dest=HERE/'prepared'/PREFIX/name;write(dest,s)
  write(HERE/'hunks'/f'{name}.patch',''.join(difflib.unified_diff(original.splitlines(True),s.splitlines(True),fromfile='a/'+PREFIX+name,tofile='b/'+PREFIX+name)))
  write(HERE/'base'/name,original)
  reverse=s
  for change in reversed(CHANGES):
   assert reverse.count(change['after'])==1;reverse=reverse.replace(change['after'],change['before'],1)
  assert reverse==original
  save(HERE/'hunks'/f'{name}.adaptations.json',dict(path=PREFIX+name,changes=list(CHANGES),inverseNormalizedSha256LF=sha(reverse.encode()),originalSha256LF=sha(original.encode()),inverseByteIdentical=True))
  rows.append(dict(path=PREFIX+name,candidateBase=str(source),baseSha256LF=sha(original.encode()),candidateSha256LF=sha(s.encode()),localPatch=str(HERE/'hunks'/f'{name}.patch')))
 save(HERE/'patch-baselines.json',rows)
 official=HERE/'official-mpv-headers';official.mkdir(exist_ok=True)
 for name in ['render.h','client.h']:
  p=official/name;url=f'https://raw.githubusercontent.com/mpv-player/mpv/{COMMIT}/include/mpv/{name}'
  if not p.exists():p.write_bytes(urllib.request.urlopen(url,timeout=30).read())
 save(HERE/'official-mpv-headers.json',[dict(path=str(official/n),sha256Bytes=sha((official/n).read_bytes()),url=f'https://raw.githubusercontent.com/mpv-player/mpv/{COMMIT}/include/mpv/{n}',commit=COMMIT) for n in ['render.h','client.h']])
 print(json.dumps(rows,indent=2))
if __name__=='__main__':main()

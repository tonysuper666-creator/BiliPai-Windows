from pathlib import Path
import hashlib,json
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def sha(b):return hashlib.sha256(b).hexdigest()
edits=[]
def edit(name,old,new):edits.append(dict(path=name,before=old,after=new,beforeSnippetSha256=sha(old.encode()),afterSnippetSha256=sha(new.encode())))
popup='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCommandPopupWindow.kt'
port='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoWindowsWindowPort.kt'
edit(popup,'    private val anchorComponent: Component,\n    private val onWindowAvailability: (Any, Boolean) -> Unit,',
 '    private val anchorComponent: Component,\n    private val onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,\n    private val onWindowAvailability: (Any, Boolean) -> Unit,')
edit(popup,'            windowReady = ready\n            onWindowAvailability(windowIdentity, ready)',
 '            windowReady = ready\n            popup?.let { onNativeWindowAvailability?.invoke(it, ready) }\n            onWindowAvailability(windowIdentity, ready)')
old='''    onWindowAvailability: ((Any, Boolean) -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return'''
new='''    onWindowAvailability: ((Any, Boolean) -> Unit)?,
    content: @Composable () -> Unit,
) {
    DesktopShapedVideoCommandPopup(surfaceSize, anchorComponent, onWindowAvailability, null, content)
}

@Composable
internal fun DesktopShapedVideoCommandPopup(
    surfaceSize: IntSize,
    anchorComponent: Component,
    onWindowAvailability: ((Any, Boolean) -> Unit)?,
    onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return'''
edit(popup,old,new)
edit(popup,'    val latestWindowAvailability by rememberUpdatedState(onWindowAvailability)\n',
 '    val latestWindowAvailability by rememberUpdatedState(onWindowAvailability)\n    val latestNativeWindowAvailability by rememberUpdatedState(onNativeWindowAvailability)\n')
edit(popup,'        DesktopCommandPopupWindow(owner, anchorComponent) { identity, ready -> latestWindowAvailability?.invoke(identity, ready) }',
 '        DesktopCommandPopupWindow(owner, anchorComponent, { window, ready -> latestNativeWindowAvailability?.invoke(window, ready) }) { identity, ready -> latestWindowAvailability?.invoke(identity, ready) }')
edit(popup,'    private var requested = IntSize.Zero\n',
 '    private var requested = IntSize.Zero\n    private var presented = false\n')
edit(popup,'    fun update(context: CompositionLocalContext, size: IntSize) {',
 '    fun update(context: CompositionLocalContext, size: IntSize, presented: Boolean) {')
edit(popup,'        requested = size\n        updateGeometry()',
 '        requested = size\n        this.presented = presented\n        updateGeometry()')
edit(popup,'        val showing = owner.isShowing && (owner !is Frame',
 '        val showing = presented && owner.isShowing && (owner !is Frame')
edit(popup,'''    onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return''',
 '''    onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,
    content: @Composable () -> Unit,
) {
    DesktopShapedVideoCommandPopup(surfaceSize, anchorComponent, onWindowAvailability,
        onNativeWindowAvailability, true, content)
}

/** PiP hides this actual popup while retaining its original Section composition. */
@Composable
internal fun DesktopShapedVideoCommandPopup(
    surfaceSize: IntSize,
    anchorComponent: Component,
    onWindowAvailability: ((Any, Boolean) -> Unit)?,
    onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,
    presented: Boolean,
    content: @Composable () -> Unit,
) {
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return''')
edit(popup,'    SideEffect { host.update(context, surfaceSize) }',
 '    SideEffect { host.update(context, surfaceSize, presented) }')
edit(port,'    private val chrome: DesktopWindowsProfileChrome,\n',
 '    private val chrome: DesktopWindowsProfileChrome,\n    private val captureProtection: DesktopWindowsVideoCaptureOwner,\n')
edit(port,'                if (!subject.owns()) return@onEdt\n                this@DesktopOriginalVideoWindowsWindowPort.requestedOrientation = requestedOrientation',
 '                if (!subject.owns()) return@onEdt\n                if (captureProtection.orientationLocked && requestedOrientation != this@DesktopOriginalVideoWindowsWindowPort.requestedOrientation) {\n                    diagnostic("Original fullscreen lock keeps the current Windows presentation")\n                    return@onEdt\n                }\n                this@DesktopOriginalVideoWindowsWindowPort.requestedOrientation = requestedOrientation')
edit(port,'    /** Same Window fullscreen lease, also used by the fullscreen platform view. */',
 '''    /** The original fullscreen/screen-lock intent belongs to this UI entry.
     * Changing part/quality or approved same-entry recovery retains protection.
     * Deferred exit and playback operations keep their stricter exact snapshot.
     */
    fun acquireScreenshotAndOrientationLock(locked: Boolean): AutoCloseable {
        if (!locked) { captureProtection.refresh(); return AutoCloseable {} }
        val stillOwned = { !closed.get() && isRootCurrent() && owner.owns() }
        val lease = captureProtection.acquire(stillOwned)
        val token = sequence.incrementAndGet()
        captureLeases[token] = lease
        // close may retire/enumerate before this map registration becomes visible.
        if (!stillOwned()) captureLeases.remove(token)?.close()
        return AutoCloseable { captureLeases.remove(token)?.close() }
    }

    /** Same Window fullscreen lease, also used by the fullscreen platform view. */''')
edit(port,'    private val wake = ConcurrentHashMap<Long, AutoCloseable>()\n',
 '    private val wake = ConcurrentHashMap<Long, AutoCloseable>()\n    private val captureLeases = ConcurrentHashMap<Long, AutoCloseable>()\n')
edit(port,'            releaseEntryKeepAwake()\n            restoreEntryWindowChrome()',
 '            releaseEntryKeepAwake()\n            captureLeases.keys.toList().forEach { captureLeases.remove(it)?.close() }\n            restoreEntryWindowChrome()')
families=[]
for name in [popup,port]:
 b=(REPO/name).read_bytes();lf=b.replace(b'\r\n',b'\n');value=lf;positions=[]
 for r in [r for r in edits if r['path']==name]:
  old=r['before'].encode();new=r['after'].encode();assert value.count(old)==1,(name,r['before'])
  at=value.index(old);positions.append((at,old,new));value=value[:at]+new+value[at+len(old):]
 inverse=value
 for at,old,new in reversed(positions):
  assert inverse[at:at+len(new)]==new;inverse=inverse[:at]+old+inverse[at+len(new):]
 assert inverse==lf
 for sub,data in [('baseline',b),('prepared/existing',value)]:
  p=H/sub/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
 families.append(dict(path=name,beforeSha256Bytes=sha(b),beforeSha256LF=sha(lf),afterSha256LF=sha(value),indexedInverse=True))
(H/'exact-hunks.json').write_text(json.dumps(edits,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(H/'baseline-families.json').write_text(json.dumps(families,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(existingFamilies=2,exactHunks=len(edits),manual=1)))

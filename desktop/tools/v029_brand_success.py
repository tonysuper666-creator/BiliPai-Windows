"""Complete fixed-v029 BrandSuccess events/host and bounded confirmed-hook deltas.
Unknown raw bytes fail closed. Existing sole producers own every generated output.
"""
from pathlib import Path
import hashlib,json,os
COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
ROOT = Path(__file__).resolve().parents[1]/"upstream-slices/v029-brand-success"
MANIFEST_SHA = "62be11142e9a2fa7396cb47ad2db104709ec1c8a5b3b47e7f7366966be7f0918"
BASE = "app/src/main/java/com/android/purebilibili/"
def wide(p):
 s=os.path.abspath(p);return Path(s if os.name!='nt' or s.startswith("\\\\?\\") else "\\\\?\\"+s)
def checked():
 raw=wide(ROOT/'manifest.json').read_bytes()
 if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed brand-success manifest mismatch')
 m=json.loads(raw)
 if m['fixedUpstreamCommit']!=COMMIT or m['hashNormalization']!='raw' or len(m['files'])!=6:raise ValueError('Unknown brand-success manifest')
 result={}
 for r in m['files']:
  p=r['path']
  if p in result or not p.startswith(BASE) or '..' in Path(p).parts or '\\' in p:raise ValueError('Invalid fixed path')
  b=wide(ROOT/p).read_bytes()
  blob=hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
  if r['commit']!=COMMIT or len(b)!=r['bytes'] or hashlib.sha256(b).hexdigest()!=r['rawSha256'] or blob!=r['gitBlob']:raise ValueError('Fixed original bytes mismatch: '+p)
  result[p]=b
 return result
def replace(s,b,a,label,edits):
 if s.count(b)!=1:raise ValueError((label,s.count(b)))
 edits.append(dict(label=label,count=1,before=b,after=a));return s.replace(b,a,1)
def reverse(s,edits):
 for r in reversed(edits):
  if s.count(r['after'])!=r['count']:raise ValueError('Inverse mismatch '+r['label'])
  s=s.replace(r['after'],r['before'],r['count'])
 return s
def proof(original,final,edits):
 if reverse(final,edits)!=original:raise ValueError('Whole source inverse mismatch')
 return dict(fixedUpstreamCommit=COMMIT,beforeSha256=hashlib.sha256(original.encode()).hexdigest(),afterSha256=hashlib.sha256(final.encode()).hexdigest(),wholeInverseExact=True,edits=edits)
def runtime_outputs():
 originals=checked();outputs={};proofs=[]
 path=BASE+'core/events/BrandSuccessEvents.kt';original=originals[path].decode();s=original;edits=[]
 s=replace(s,'    val detail: String? = null\n','    val detail: String? = null,\n    val origin: com.bilipai.desktop.ui.DesktopBrandSuccessOrigin,\n', 'immutable original-owner origin',edits)
 s=replace(s,'object BrandSuccessEvents {','class BrandSuccessEvents(private val rootOwned: () -> Boolean) : AutoCloseable {\n    @Volatile private var closed = false\n    override fun close() { closed = true }\n    fun isCurrent(event: BrandSuccessFeedback): Boolean = !closed && rootOwned() && event.origin.isCurrent()\n    private fun publish(origin: com.bilipai.desktop.ui.DesktopBrandSuccessOrigin, action: () -> Unit) {\n        origin.publish { if (!closed && rootOwned()) action() }\n    }', 'Root instance, no global singleton',edits)
 s=replace(s,'import kotlinx.coroutines.flow.asSharedFlow','import kotlinx.coroutines.flow.asSharedFlow\nimport kotlinx.coroutines.flow.collect', 'same original flow public collector',edits)
 s=replace(s,'    val events = mutableEvents.asSharedFlow()','    val events: kotlinx.coroutines.flow.Flow<BrandSuccessFeedback> = kotlinx.coroutines.flow.flow {\n        mutableEvents.collect { event -> if (isCurrent(event)) emit(event) }\n    }', 'final consume admission, original no replay',edits)
 s=replace(s,'    fun favoriteSaved() {\n        mutableEvents.tryEmit(BrandSuccessFeedback(sequence.incrementAndGet(), BrandSuccessKind.FAVORITE))\n    }','    fun favoriteSaved(origin: com.bilipai.desktop.ui.DesktopBrandSuccessOrigin) = publish(origin) {\n        mutableEvents.tryEmit(BrandSuccessFeedback(sequence.incrementAndGet(), BrandSuccessKind.FAVORITE, origin = origin))\n    }', 'confirmed favorite with actual caller',edits)
 s=replace(s,'    fun followChanged(following: Boolean, detail: String? = null) {','    fun followChanged(origin: com.bilipai.desktop.ui.DesktopBrandSuccessOrigin, following: Boolean, detail: String? = null) = publish(origin) {', 'confirmed follow with actual caller',edits)
 s=replace(s,'            detail\n','            detail, origin\n', 'follow origin travels with queued event',edits)
 s=replace(s,'    fun downloadCompleted(taskId: String, createdAt: Long, title: String) {','    fun downloadCompleted(origin: com.bilipai.desktop.ui.DesktopBrandSuccessOrigin, taskId: String, createdAt: Long, title: String) = publish(origin) {', 'actual completed task origin',edits)
 s=replace(s,'BrandSuccessFeedback(sequence.incrementAndGet(), BrandSuccessKind.DOWNLOAD, title)','BrandSuccessFeedback(sequence.incrementAndGet(), BrandSuccessKind.DOWNLOAD, title, origin)', 'download origin travels with dedup event',edits)
 outputs['kotlin/com/android/purebilibili/core/events/BrandSuccessEvents.kt']=s.encode();proofs.append(dict(originalPath=path,**proof(original,s,edits)))
 path=BASE+'core/ui/BrandSuccessFeedbackHost.kt';original=originals[path].decode();s=original;edits=[]
 s=replace(s,'    enabled: Boolean = true,','    events: BrandSuccessEvents,\n    enabled: Boolean = true,','required same Root events',edits)
 s=replace(s,'    LaunchedEffect(lifecycleOwner, enabled) {','    LaunchedEffect(lifecycleOwner, enabled, events) {','Root instance lifetime key',edits)
 s=replace(s,'BrandSuccessEvents.events.collectLatest','events.events.collectLatest','actual instance collection',edits)
 start=s.index('    val anchorBounds = remember(current.id) {');end=s.index('    val density =',start)
 s=replace(s,s[start:end],'    val anchorBounds: androidx.compose.ui.geometry.Rect? = remember(current.id) { null }\n', 'Root fallback corner, no Android global anchors',edits)
 s=replace(s,'    val configuration = androidx.compose.ui.platform.LocalConfiguration.current\n    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }\n    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }','    val screenWidthPx = with(density) { window.widthDp.toPx() }\n    val screenHeightPx = with(density) { window.heightDp.toPx() }','actual existing Root viewport',edits)
 s=replace(s,'    Column(\n','    com.bilipai.desktop.ui.DesktopBrandSuccessFrame(current, { if (feedback?.id == current.id) feedback = null }) {\n    Column(\n','existing decorative native carrier',edits)
 s=replace(s,'onFinished = { if (feedback?.id == current.id) feedback = null }','onFinished = { if (events.isCurrent(current) && feedback?.id == current.id) feedback = null }','only current original finish',edits)
 s=replace(s,'\n    }\n}\n','\n    }\n    }\n}\n','carrier content closes once',edits)
 outputs['kotlin/com/android/purebilibili/core/ui/BrandSuccessFeedbackHost.kt']=s.encode();proofs.append(dict(originalPath=path,**proof(original,s,edits)))
 outputs['brand-success-source-proof.json']=(json.dumps(dict(schema=1,proofs=proofs,originals=list(originals)),ensure_ascii=False,indent=2)+'\n').encode()
 return outputs
def favorites_delta(s):
 checked();original=s;edits=[]
 s=replace(s,'suspend fun favoriteVideo(aid: Long, favorite: Boolean, folderId: Long? = null): Result<Boolean> {','suspend fun favoriteVideo(aid: Long, favorite: Boolean, folderId: Long? = null, showSuccessFeedback: Boolean = true): Result<Boolean> {\n        val brandOrigin = environment.captureBrandFeedback()', 'original v029 favorite intent and origin before IO',edits)
 s=replace(s,'if (response.code == 0) {\n                    Result.success(favorite)','if (response.code == 0) {\n                    if (favorite && showSuccessFeedback) environment.confirmBrandFavorite(brandOrigin)\n                    Result.success(favorite)', 'actual nonempty quick favorite success only',edits)
 s=replace(s,'suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {','suspend fun followUser(mid: Long, follow: Boolean, emitBrandFeedback: Boolean = true): Result<Boolean> {\n        val brandOrigin = environment.captureBrandFeedback()', 'original v029 batch flag and before-IO caller',edits)
 s=replace(s,'environment.confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))','environment.confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))\n                    if (emitBrandFeedback) environment.confirmBrandFollow(brandOrigin, follow)', 'keep original state event, independently publish brand',edits)
 return s,proof(original,s,edits)
def following_delta(s):
 checked();original=s;edits=[]
 s=replace(s,'        _isBatchUnfollowing.value = true\n','        val brandOrigin = environment.favorites.captureBrandFeedback()\n        _isBatchUnfollowing.value = true\n','same actual batch caller',edits)
 s=replace(s,'                applyRemovedUsers(successMids)\n','                applyRemovedUsers(successMids)\n                environment.favorites.confirmBrandFollow(brandOrigin, false,\n                    if (failedCount == 0) "已取关 ${successMids.size} 位 UP 主"\n                    else "已取关 ${successMids.size} 位，${failedCount} 位失败")\n','complete original v029 aggregate success detail',edits)
 s=replace(s,'environment.actions.followUser(mid, follow = false)','environment.actions.followUser(mid, follow = false, emitBrandFeedback = false)','batch per-user original state event retained; brand suppressed',edits)
 return s,proof(original,s,edits)

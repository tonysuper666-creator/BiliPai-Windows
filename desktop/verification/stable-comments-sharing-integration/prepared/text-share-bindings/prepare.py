from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());TARGET=MAIN.parent/'BiliPai-v023'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def main():
 rows=[]
 def patch(name,hunks,install=True):
  path='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name;source=TARGET/path
  raw=safe(source).read_bytes();before=raw.decode().replace('\r\n','\n');after=before
  for old,new in hunks:
   assert after.count(old)==1,(name,old,after.count(old));after=after.replace(old,new,1)
  base=HERE/'base-inputs'/name;write(base,before);dest=HERE/('prepared' if install else 'review-only')/path;write(dest,after)
  rows.append(dict(path=path,install=install,baseCurrentCandidateSha256Bytes=hashlib.sha256(raw).hexdigest(),baseSha256LF=sha(base),candidateSha256LF=sha(dest),baseCopy=str(base),candidate=str(dest),hunks=[dict(before=a,after=b) for a,b in hunks]))
 patch('DesktopOriginalDynamicCardHost.kt',[
  ('    val saveParent=LocalDesktopDynamicSaveParent.current','    val textShare=LocalDesktopTextShareBindings.current\n    val saveParent=LocalDesktopDynamicSaveParent.current'),
  ('    val platform=remember(operations,assets,preferences.context){object:DesktopDynamicCardPlatform{','    val platform=remember(operations,assets,preferences.context,textShare){object:DesktopDynamicCardPlatform{'),
  ('        override fun shareText(text:String){guarded{show("Windows 系统分享面板尚未接入；可使用复制或分享至消息")}}','        override fun shareText(text:String){requestDesktopTextShare(textShare,scope,"BiliPai 分享",text,::owned,::show)}')])
 patch('DesktopDynamicCommentPlatform.kt',[
  ('import kotlinx.coroutines.CancellationException','import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.CancellationException'),
  ('    private val pickImages: (Int, (List<String>) -> Unit) -> Unit,','    private val pickImages: (Int, (List<String>) -> Unit) -> Unit,\n    private val textShare: DesktopTextShareBindings? = null,\n    private val shareScope: CoroutineScope? = null,'),
  ('        if (isOwned()) showFeedback("Windows 系统文字分享尚未接入，可使用复制")','        requestDesktopTextShare(textShare, shareScope, title, text, ::isOwned, ::showFeedback)')])
 patch('CommunityDynamicScreens.kt',[
  ('    val clipboard=LocalDesktopTextClipboard.current\n    val platform=remember(alive,clipboard,imageSaveLocations){','    val clipboard=LocalDesktopTextClipboard.current\n    val textShare=LocalDesktopTextShareBindings.current\n    val platform=remember(alive,clipboard,imageSaveLocations,textShare){'),
  ('        },pickImages=commentPickers::pickImages)}','        },pickImages=commentPickers::pickImages,textShare=textShare,shareScope=pageScope)}')])
 patch('DesktopSpaceImagePreviews.kt',[
  ('import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences','import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.SupervisorJob\nimport kotlinx.coroutines.cancel'),
  ('    val clipboard = LocalDesktopTextClipboard.current','    val shareParentScope = rememberCoroutineScope()\n    val shareScope = remember(alive) { CoroutineScope(shareParentScope.coroutineContext + SupervisorJob(shareParentScope.coroutineContext[Job])) }\n    val textShare = LocalDesktopTextShareBindings.current\n    val clipboard = LocalDesktopTextClipboard.current'),
  ('    val platform = remember(alive, clipboard, uriHandler) {','    val platform = remember(alive, clipboard, uriHandler, textShare) {'),
  ('            clipboard, feedback = { latestFeedback(it) }, openExternalLink = uriHandler::openUri)','            clipboard, feedback = { latestFeedback(it) }, openExternalLink = uriHandler::openUri,\n            textShare = textShare, shareScope = shareScope)'),
  ('        onDispose { alive.set(false); assets.close() }','        onDispose { alive.set(false); shareScope.cancel(); assets.close() }'),
  ('    private val openExternalLink: (String) -> Unit,','    private val openExternalLink: (String) -> Unit,\n    private val textShare: DesktopTextShareBindings? = null,\n    private val shareScope: CoroutineScope? = null,'),
  ('    override fun shareText(text: String) { showFeedback("Windows 系统文字分享尚未接入，可使用复制") }','    override fun shareText(text: String) { requestDesktopTextShare(textShare, shareScope, "BiliPai 分享", text, ::isOwned, ::showFeedback) }')])
 patch('DesktopBgmDetailRoot.kt',[
  ('    val clipboard = LocalDesktopTextClipboard.current','    val textShare = LocalDesktopTextShareBindings.current\n    val clipboard = LocalDesktopTextClipboard.current'),
  ('        val gallery = remember(operations, assets, clipboard, uriHandler) {','        val gallery = remember(operations, assets, clipboard, uriHandler, textShare) {'),
  ('                ::owned, clipboard, ::feedback, uriHandler::openUri)','                ::owned, clipboard, ::feedback, uriHandler::openUri, textShare = textShare, shareScope = scope)'),
  ('        val platform = remember(operations, clipboard, images, locations) {','        val platform = remember(operations, clipboard, images, locations, textShare) {'),
  ('                }, pickImages = pickers::pickImages)','                }, pickImages = pickers::pickImages, textShare = textShare, shareScope = scope)')],install=False)
 save(HERE/'patch-plan.json',dict(scope='prepared platform/caller hunks only; Root applies against current candidate, no whole Shell overwrite',baseline='actual immutable Snapshot15 for compiler/runtime; mutable sibling sources are explicitly byte-pinned below',files=rows))
 actor=TARGET/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeTextShare.kt'
 write(HERE/'reference-existing-native-actor.kt.txt',safe(actor).read_text(encoding='utf-8').replace('\r\n','\n'))
 save(HERE/'source-inventory.json',dict(payloadCount=5,existingNativeActor=dict(path=str(actor),sha256Bytes=sha(actor),sha256LF=sha(HERE/'reference-existing-native-actor.kt.txt'),emitted=False),newPlatformSource=dict(path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopTextShareBindings.kt',sha256LF=sha(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopTextShareBindings.kt')),manualDeltas=rows,bgmCallersOwnedByVideoCommonAdapter=True,noOriginalRendererGenerated=True,noNewActorOrStore=True))
 print(json.dumps(dict(payloads=5,sourceInventorySha256=sha(HERE/'source-inventory.json'),patchPlanSha256=sha(HERE/'patch-plan.json'))))
if __name__=='__main__':main()

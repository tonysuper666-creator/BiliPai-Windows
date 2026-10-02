from pathlib import Path
import subprocess,hashlib,json,sys
P=Path(__file__).resolve().parent; M=P.parents[2]; C=M.parent/'BiliPai-v023'; HEAD='6fd5bbd804f272650942f5a445385ba5428ffa9b'
U='desktop/src/main/kotlin/com/bilipai/desktop/ui/'
paths=[U+n+'.kt' for n in ['DesktopDynamicImageAssets','DesktopVideoShareFiles','DesktopHomeGalleryBindings','DesktopOriginalHomeEmbeddedAggregate','DesktopHomeRootFactory','DesktopReadyHomeFactoryBinding','DesktopReadyOriginalRootMount','DesktopOriginalRootStack','DesktopOriginalDynamicCardHost','DesktopSpaceImagePreviews','DesktopOriginalCommentRootBindings','DesktopOriginalArticleRootHost']]+['desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeTextShare.kt','desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp','desktop/native/diagnostic-share/approved-development-build.json','desktop/tools/compile-native-diagnostic-share.py','desktop/tools/prepare-native-diagnostic-share.py']
def sha(b):return hashlib.sha256(b).hexdigest()
def put(p,b):p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b.encode() if isinstance(b,str)else b)
def replace(path,before,after):
 p=P/'prepared'/path;s=p.read_text(encoding='utf8');assert s.count(before)==1,(path,before[:80],s.count(before));put(p,s.replace(before,after))
pins=[]
for path in paths:
 b=subprocess.check_output(['git','-C',str(C),'show',HEAD+':'+path]);put(P/'baseline'/path,b);put(P/'prepared'/path,b);pins.append(dict(path=path,sha256Bytes=sha(b),bytes=len(b)))
put(P/'baseline-pins.json',json.dumps(dict(head=HEAD,files=pins),indent=2)+'\n')
replace('desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp','extension!=L".png"&&extension!=L".webp"','extension!=L".png"&&extension!=L".webp"&&extension!=L".gif"')
replace('desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeTextShare.kt','setOf("jpg","jpeg","png","webp")','setOf("jpg","jpeg","png","webp","gif")')
replace(U+'DesktopDynamicImageAssets.kt','    suspend fun saveImage(rawUrl: String): Boolean = saveMutex.withLock {','''    /** Original preview share bytes, using this SAME anonymous owned download actor; never decoded. */
    internal suspend fun readOriginalShareBytes(rawUrl: String): ByteArray = withContext(Dispatchers.IO) {
        checkpoint()
        val url = normalizeImageUrl(rawUrl)
        require(url.isNotBlank()) { "图片地址为空" }
        val source = downloadTo(url, scratchDirectory(), MAX_IMAGE_BYTES)
        try {
            checkpoint(); require(Files.size(source) in 1..MAX_IMAGE_BYTES)
            Files.readAllBytes(source).also { checkpoint(); require(it.size.toLong() in 1..MAX_IMAGE_BYTES) }
        } finally { Files.deleteIfExists(source) }
    }

    suspend fun saveImage(rawUrl: String): Boolean = saveMutex.withLock {''')
replace(U+'DesktopVideoShareFiles.kt','    suspend fun publish(name:String,mime:String,write:(Path)->Unit)','    suspend fun publish(name:String,mime:String,write:(Path)->Unit):VideoShareCoverFile = publish(name,mime,null,write)\n    suspend fun publish(name:String,mime:String,beforeCommit:(()->Unit)?,write:(Path)->Unit)')
replace(U+'DesktopVideoShareFiles.kt','    suspend fun bytes(url:String):ByteArray','    internal suspend fun assertAdmission() = checkpoint()\n    suspend fun bytes(url:String):ByteArray')
replace(U+'DesktopVideoShareFiles.kt','if(!commit {callerJob?.ensureActive();checkOwner();Files.move(temporary,target)})','if(!commit {callerJob?.ensureActive();checkOwner();beforeCommit?.invoke();Files.move(temporary,target)})')
replace(U+'DesktopHomeGalleryBindings.kt','    private val textShare: DesktopTextShareBindings,','    internal val imageShare: DesktopImagePreviewShareBindings,\n    private val textShare: DesktopTextShareBindings,')
replace(U+'DesktopHomeGalleryBindings.kt','        error("Windows 系统图片分享面板尚未接入") // Exact existing CardHost platform capability boundary.','        imageShare.shareImage(url, ::isOwned)')
replace(U+'DesktopHomeGalleryBindings.kt','            textShare: DesktopTextShareBindings,','            shareFiles: DesktopVideoShareFiles,\n            mediaShare: DesktopImagePreviewMediaShare,\n            textShare: DesktopTextShareBindings,')
replace(U+'DesktopHomeGalleryBindings.kt','            return DesktopHomeGalleryBindings(context, lifetime, sameCardSession, operations, assets,\n                textShare, clipboard, externalLink, feedback)','''            val imageShare = DesktopImagePreviewShareBindings(assets::readOriginalShareBytes, shareFiles,
                { lifetime.owns() && sameCardSession.isOwned() }, mediaShare)
            return DesktopHomeGalleryBindings(context, lifetime, sameCardSession, operations, assets,
                imageShare, textShare, clipboard, externalLink, feedback)''')
replace(U+'DesktopOriginalHomeEmbeddedAggregate.kt','            textShare: DesktopTextShareBindings,','            shareFiles: DesktopVideoShareFiles,\n            mediaShare: DesktopImagePreviewMediaShare,\n            textShare: DesktopTextShareBindings,')
replace(U+'DesktopOriginalHomeEmbeddedAggregate.kt','sameCardSession, actualImageLocations, actualRootWindow, textShare, clipboard, externalLink, feedback)','sameCardSession, actualImageLocations, actualRootWindow, shareFiles, mediaShare, textShare, clipboard, externalLink, feedback)')
replace(U+'DesktopHomeRootFactory.kt','    val overlays: (DesktopHomeRetainedGate, DesktopHomeRootRequestBinding) -> DesktopHomeOverlayPorts,','''    val shareFiles: (DesktopHomeRetainedGate) -> DesktopVideoShareFiles,
    val mediaShare: DesktopImagePreviewMediaShare,
    val overlays: (DesktopHomeRetainedGate, DesktopHomeRootRequestBinding, DesktopVideoShareFiles) -> DesktopHomeOverlayPorts,''')
replace(U+'DesktopHomeRootFactory.kt','        var original: DesktopHomeRetainedEntry? = null','        var sharedFiles: DesktopVideoShareFiles? = null\n        var original: DesktopHomeRetainedEntry? = null')
replace(U+'DesktopHomeRootFactory.kt','''                { gate, requests -> DesktopOriginalHomeEmbeddedAggregate.create(repository, gate,
                    requests, runtime, window.settings, cardSession, window.imageLocations, window.window,
                    window.textShare, window.clipboard, window.externalLink, window.feedback,
                    DesktopHomeEmbeddedRootUi(window.onMatchClick, window.liveScrollToTop, window.globalHaze)) })''','''                { gate, requests ->
                    // Pure path/lease construction. Publication admission denies I/O until Retainer installs this gate.
                    val files = window.shareFiles(gate).also { sharedFiles = it }
                    DesktopOriginalHomeEmbeddedAggregate.create(repository, gate,
                        requests, runtime, window.settings, cardSession, window.imageLocations, window.window,
                        files, window.mediaShare, window.textShare, window.clipboard, window.externalLink, window.feedback,
                        DesktopHomeEmbeddedRootUi(window.onMatchClick, window.liveScrollToTop, window.globalHaze)) })''')
replace(U+'DesktopHomeRootFactory.kt','window.overlays(gate, entry.requests))','window.overlays(gate, entry.requests, requireNotNull(sharedFiles)))')
replace(U+'DesktopReadyHomeFactoryBinding.kt','    private val invalidateAuthentication: (Long, Long) -> Unit,','    private val invalidateAuthentication: (Long, Long) -> Unit,\n    private val rootPublished: (DesktopHomeRetainedGate) -> Boolean,')
replace(U+'DesktopReadyHomeFactoryBinding.kt','liveScrollToTop = liveScroll, globalHaze = { haze }, overlays = ::overlays)','''liveScrollToTop = liveScroll, globalHaze = { haze }, shareFiles = ::shareFiles,
        mediaShare = { file, title, text, owned, retired -> nativeShare.shareMedia(file, title, text, owned, retired) },
        overlays = ::overlays)''')
replace(U+'DesktopReadyHomeFactoryBinding.kt','    private fun overlays(gate: DesktopHomeRetainedGate, requests: DesktopHomeRootRequestBinding): DesktopHomeOverlayPorts {','''    private fun shareFiles(gate: DesktopHomeRetainedGate): DesktopVideoShareFiles {
        fun owned() = gate.owns() && rootPublished(gate)
        return DesktopVideoShareFiles(runtime.store.root.resolve("cache"), { url ->
            if (!owned()) throw CancellationException("Share Root has not been published")
            readShareBytes(repository.ownedHomeCallFactory(gate.epoch, ::owned), url, gate)
        }, ::owned, { block -> gate.commit {
            if (!owned()) throw CancellationException("Share Root publication retired")
            block()
        } })
    }

    private fun overlays(gate: DesktopHomeRetainedGate, requests: DesktopHomeRootRequestBinding,
        files: DesktopVideoShareFiles): DesktopHomeOverlayPorts {''')
replace(U+'DesktopReadyHomeFactoryBinding.kt','''        val files = DesktopVideoShareFiles(runtime.store.root.resolve("cache"), { url ->
            readShareBytes(repository.ownedHomeCallFactory(gate.epoch, gate::owns), url, gate)
        }, gate::owns, gate::commit)
''','')
replace(U+'DesktopReadyOriginalRootMount.kt','services.feedback, services.openExternalLink, services.authenticationInvalidated) }','''services.feedback, services.openExternalLink, services.authenticationInvalidated,
            rootPublished = { gate -> handle.retainer.current()?.entry?.gate === gate }) }''')
replace(U+'DesktopOriginalRootStack.kt','            LocalDesktopDynamicCardBindings provides aggregate.gallery,','            LocalDesktopDynamicCardBindings provides aggregate.gallery,\n            LocalDesktopImagePreviewShareBindings provides aggregate.gallery.imageShare,')
replace(U+'DesktopOriginalDynamicCardHost.kt','    val textShare=LocalDesktopTextShareBindings.current','    val textShare=LocalDesktopTextShareBindings.current\n    val imageShare=LocalDesktopImagePreviewShareBindings.current')
replace(U+'DesktopOriginalDynamicCardHost.kt','val platform=remember(operations,assets,preferences.context,textShare)','val platform=remember(operations,assets,preferences.context,textShare,imageShare)')
replace(U+'DesktopOriginalDynamicCardHost.kt','override suspend fun shareImage(url:String):Boolean=error("Windows 系统图片分享面板尚未接入")','override suspend fun shareImage(url:String):Boolean=imageShare.shareImage(url,::owned)')
replace(U+'DesktopSpaceImagePreviews.kt','    val textShare = LocalDesktopTextShareBindings.current','    val textShare = LocalDesktopTextShareBindings.current\n    val imageShare = LocalDesktopImagePreviewShareBindings.current')
replace(U+'DesktopSpaceImagePreviews.kt','val platform = remember(alive, clipboard, uriHandler, textShare)','val platform = remember(alive, clipboard, uriHandler, textShare, imageShare)')
replace(U+'DesktopSpaceImagePreviews.kt','textShare = textShare, shareScope = shareScope)','imageShare = imageShare, textShare = textShare, shareScope = shareScope)')
replace(U+'DesktopSpaceImagePreviews.kt','    private val textShare: DesktopTextShareBindings? = null,','    private val imageShare: DesktopImagePreviewShareBindings,\n    private val textShare: DesktopTextShareBindings? = null,')
replace(U+'DesktopSpaceImagePreviews.kt','override suspend fun shareImage(url: String): Boolean = error("Windows 系统图片分享面板尚未接入")','override suspend fun shareImage(url: String): Boolean = imageShare.shareImage(url, ::isOwned)')
replace(U+'DesktopOriginalCommentRootBindings.kt','    val textShare = LocalDesktopTextShareBindings.current','    val textShare = LocalDesktopTextShareBindings.current\n    val imageShare = LocalDesktopImagePreviewShareBindings.current')
replace(U+'DesktopOriginalCommentRootBindings.kt','val gallery = remember(operations, assets, clipboard, uriHandler, textShare)','val gallery = remember(operations, assets, clipboard, uriHandler, textShare, imageShare)')
replace(U+'DesktopOriginalCommentRootBindings.kt','uriHandler::openUri, textShare = textShare, shareScope = scope)','uriHandler::openUri, imageShare = imageShare, textShare = textShare, shareScope = scope)')
replace(U+'DesktopOriginalArticleRootHost.kt','    textShare: DesktopTextShareBindings?,','    textShare: DesktopTextShareBindings?,\n    imageShare: DesktopImagePreviewShareBindings,')
replace(U+'DesktopOriginalArticleRootHost.kt','override suspend fun shareImage(url: String) = leaf.call { gallery.shareImage(url) }','override suspend fun shareImage(url: String) = leaf.call { imageShare.shareImage(url, ::isOwned) }')
replace(U+'DesktopOriginalArticleRootHost.kt','            val textShare = LocalDesktopTextShareBindings.current','            val textShare = LocalDesktopTextShareBindings.current\n            val imageShare = LocalDesktopImagePreviewShareBindings.current')
replace(U+'DesktopOriginalArticleRootHost.kt','val gallery = remember(leaf, actualGallery, textShare) { articleGalleryPlatform(leaf, actualGallery, textShare) }','val gallery = remember(leaf, actualGallery, textShare, imageShare) { articleGalleryPlatform(leaf, actualGallery, textShare, imageShare) }')
print('Prepared baseline-pinned narrow edits.')

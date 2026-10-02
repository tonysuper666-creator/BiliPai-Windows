from pathlib import Path
P=Path(__file__).resolve().parent
old=P.parent/'stable-full-video-root-media-actual-proof82'
s=(old/'RootMediaFixture.kt').read_text(encoding='utf8').split('    private suspend fun exercise() {')[0]
s=s.replace('package com.bilipai.desktop.rootmediafixture','package com.bilipai.desktop.rootexternalfixture')
s=s.replace('RootMediaFixture(private val root: Path)','RootExternalFixture(private val root: Path, clip: Path)')
s=s.replace('actual guest media lifecycle','actual authorized external media lifecycle')
s=s.replace('import androidx.compose.ui.unit.dp','import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.semantics.*\nimport com.android.purebilibili.core.plugin.PluginCapability\nimport com.bilipai.desktop.plugins.js.DesktopJsPluginHost\nimport com.sun.net.httpserver.HttpServer\nimport java.net.InetSocketAddress\nimport java.util.concurrent.Executors\nimport java.util.concurrent.atomic.AtomicInteger')
s=s.replace('    private val closing = AtomicBoolean(false)','    private val origin = FixtureMediaOrigin(clip)\n    private val closing = AtomicBoolean(false)')
s=s.replace('    private val checks = mutableListOf<JsonObject>()','    private val checks = mutableListOf<JsonObject>()\n    private val ownershipFrames = mutableListOf<JsonObject>()')
extra=r'''
private class FixtureMediaOrigin(clip: Path):AutoCloseable {
    private val bytes=Files.readAllBytes(clip)
    private val executor=Executors.newCachedThreadPool()
    private val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val requests=AtomicInteger()
    val url get()="http://127.0.0.1:${server.address.port}/local.mp4"
    init {
        server.executor=executor
        server.createContext("/local.mp4") { e ->
            requests.incrementAndGet()
            try {
                val range=e.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                val start=range?.groupValues?.get(1)?.toInt() ?: 0
                val end=(range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                if(start<0||start>bytes.lastIndex||end<start) { e.sendResponseHeaders(416,-1); return@createContext }
                e.responseHeaders.add("Content-Type","video/mp4")
                e.responseHeaders.add("Accept-Ranges","bytes")
                if(range!=null)e.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}")
                if(e.requestMethod=="HEAD") { e.responseHeaders.add("Content-Length",(end-start+1).toString()); e.sendResponseHeaders(if(range!=null)206 else 200,-1) }
                else { e.sendResponseHeaders(if(range!=null)206 else 200,(end-start+1).toLong()); e.responseBody.use { it.write(bytes,start,end-start+1) } }
            } finally { e.close() }
        }
        server.start()
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
}
private fun semanticTree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::semanticTree)
'''
s=s.replace('private class RootExternalFixture',extra+'\nprivate class RootExternalFixture')
s+=r'''
    private fun ownedWindows():List<java.awt.Window> {
        fun flatten(w:java.awt.Window):List<java.awt.Window> = listOf(w)+w.ownedWindows.flatMap(::flatten)
        return flatten(checkNotNull(windowRef.get()))
    }
    private fun ownedCanvasCount():Int {
        val canvas=checkNotNull(findCanvas(player.surface))
        return ownedWindows().sumOf { countCanvas(it,canvas) }
    }
    private fun observeOwnership(label:String,shell:DesktopOriginalVideoShellOwner,initial:DesktopOriginalVideoOwnerAssembly,
        environment:DesktopOriginalVideoRootWindowEnvironment,media:DesktopRetainedMedia) {
        check(SwingUtilities.isEventDispatchThread())
        val actual=shell.slot.currentAssembly()
        val canvas=checkNotNull(findCanvas(player.surface))
        val parent=SwingUtilities.getWindowAncestor(canvas)
        ownershipFrames+=buildJsonObject {
            put("label",label);put("originalOrdinaryOwns",initial.owns());put("sameOrdinaryAssembly",actual===initial)
            put("initialOrdinaryIdentity",System.identityHashCode(initial));put("currentOrdinaryIdentity",actual?.let { System.identityHashCode(it) } ?: 0)
            put("sameMpvInstance",media.player===player);put("nativeSourceVersion",player.currentSourceVersion)
            put("externalVersion",media.external.sourceVersion ?: 0L);put("externalOwns",media.external.ownsNativeSource)
            put("actualRoute",environment.currentKey()::class.java.simpleName)
            put("mainCanvasCount",countCanvas(checkNotNull(windowRef.get()),canvas));put("ownedCanvasCount",ownedCanvasCount())
            put("canvasParentWindowClass",parent?.javaClass?.name.orEmpty());put("canvasParentWindowIsMain",parent===windowRef.get())
            put("canvasParentWindowOwned",parent in ownedWindows());put("canvasShowing",canvas.isShowing)
        }
    }
    private suspend fun invokeOriginalButton(label:String) = withTimeout(20_000) {
        while(true) {
            val invoked=withContext(Dispatchers.Main) {
                val w=checkNotNull(windowRef.get())
                val nodes=w.semanticsOwners.flatMap { semanticTree(it.unmergedRootSemanticsNode) }
                val node=nodes.firstOrNull { n ->
                    semanticTree(n).any { child -> child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text==label } } &&
                        n.config.getOrNull(SemanticsActions.OnClick)?.action!=null &&
                        !n.config.contains(SemanticsProperties.Disabled)
                }
                node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
            }
            if(invoked)return@withTimeout
            delay(50)
        }
    }
    private suspend fun exercise() {
        var error:Throwable?=null
        try {
            val shell=awaitReference("actual Shell") { refs.find<DesktopOriginalVideoShellOwner>() }
            awaitCondition("actual factory") { shell.navigationReady() }
            val owner=withContext(Dispatchers.Main) { shell.slot.requireAssembly() }
            val platforms=awaitReference("actual Windows platforms") { runCatching { shell.requireWindows() }.getOrNull() }
            val environment=awaitReference("actual Root environment") { refs.find<DesktopOriginalVideoRootWindowEnvironment>() }
            val resources=awaitReference("actual resources") { refs.find<DesktopOriginalVideoRootShellResources>() }
            val media=awaitReference("same actual retained media") { refs.find<DesktopRetainedMedia>() }
            withTimeout(20_000) { platforms.awaitNativeInitialization() }
            withContext(Dispatchers.Main) { checkThat("Actual idle ordinary Assembly, Section, MPV and Overlay agree",owner.owns()&&owner.section.nativePlayer===player&&
                (readField(platforms.holder.section,"resources") as DesktopOriginalVideoSectionWindowsResources).overlay===resources.overlay) }
            val plugins=environment.runtime.jsPlugins
            plugins.accountChanged(repository.sessionEpoch)
            plugins.load()
            val id="fixture.loopback.media"
            val streamUrl=JsonPrimitive(origin.url).toString()
            val script="""window.BiliPaiPlugin={id:'fixture.loopback.media',title:'Loopback Fixture',permissions:['EXTERNAL_MEDIA_PLAYBACK'],modules:[{id:'items',title:'Items',functionName:'load'}],load:function(params){return [{id:'a',title:'Media A',streams:[{id:'a',title:'fixture-loopback-mp4-a',url:$streamUrl,contentType:'video/mp4',headers:{}}]},{id:'b',title:'Media B',streams:[{id:'b',title:'fixture-loopback-mp4-b',url:$streamUrl,contentType:'video/mp4',headers:{}}]}];}};"""
            val preview=plugins.previewScript(script)
            val installed=plugins.install(preview,setOf(PluginCapability.EXTERNAL_MEDIA_PLAYBACK))
            plugins.setEnabled(id,true)
            checkThat("Isolated plugin traverses actual preview, install approval and enable",installed.manifest.id==id&&
                plugins.state.value.plugins.single { it.installed.manifest.id==id }.let { it.authorizationMatches&&it.installed.enabled }&&repository.account.value==null)
            withContext(Dispatchers.Main) { checkThat("Actual typed plugin content route admitted",environment.commands.push(BiliPaiNavKey.JsPluginContent(id))) }
            awaitCondition("actual plugin content") { environment.currentKey()==BiliPaiNavKey.JsPluginContent(id) }
            suspend fun open(index:Int):Long {
                invokeOriginalButton("fixture-loopback-mp4-${if(index==0)"a" else "b"}")
                awaitCondition("actual original stream callback enters ExternalMedia") { environment.currentKey() is BiliPaiNavKey.ExternalMedia&&media.external.request?.title=="Media ${if(index==0)"A" else "B"}" }
                withTimeout(25_000) { player.state.first { st ->
                    if(st.error!=null)error("Actual external native error: "+st.error)
                    st.firstVideoFrameReady&&st.videoCodec!=null&&!st.loading&&st.durationSeconds>0
                } }
                invokeOriginalButton("暂停")
                withTimeout(10_000) { player.state.first { it.paused&&!it.loading } }
                return withContext(Dispatchers.Main) {
                    val version=checkNotNull(media.external.sourceVersion)
                    checkThat("External $index original menu callback, authority and real first frame agree",media.external.authorizationCurrent&&media.external.ownsNativeSource&&media.external.loaded&&player.ownsSourceVersion(version)&&origin.requests.get()>0)
                    observeOwnership("external-$index-first-frame",shell,owner,environment,media)
                    checkThat("External $index uses the same native MPV and one Root-owned Canvas",media.player===player&&ownedCanvasCount()==1&&SwingUtilities.getWindowAncestor(checkNotNull(findCanvas(player.surface))) in ownedWindows())
                    checkThat("External $index acquisition follows original ordinary stop retirement",!owner.owns()&&shell.slot.currentAssembly()!==owner)
                    capture("actual-external-$index")
                    version
                }
            }
            val first=open(0)
            withContext(Dispatchers.Main) { checkThat("Actual Settings cover admitted",environment.commands.push(BiliPaiNavKey.Settings)) }
            awaitCondition("actual Settings") { environment.currentKey()==BiliPaiNavKey.Settings }
            delay(500)
            withContext(Dispatchers.Main) {
                observeOwnership("external-settings-covered",shell,owner,environment,media)
                checkThat("Covered external source retains exact native version",media.external.sourceVersion==first&&player.ownsSourceVersion(first)&&media.external.authorizationCurrent)
                checkThat("Actual back to external admitted",environment.commands.back())
            }
            awaitCondition("actual external return") { environment.currentKey() is BiliPaiNavKey.ExternalMedia }
            withContext(Dispatchers.Main) { checkThat("Actual back to plugin content admitted",environment.commands.back()) }
            awaitCondition("actual content return") { environment.currentKey()==BiliPaiNavKey.JsPluginContent(id) }
            val second=open(1)
            checkThat("Second original external menu launch advances same native actor",second>first&&media.external.sourceVersion==second&&player.ownsSourceVersion(second))
            invokeOriginalButton("浮窗")
            awaitCondition("actual PiP attached") { resources.pip.active.value&&SwingUtilities.getWindowAncestor(player.surface)!==windowRef.get() }
            withContext(Dispatchers.Main) {
                val pipWindow=checkNotNull(SwingUtilities.getWindowAncestor(player.surface))
                observeOwnership("external-PiP",shell,owner,environment,media)
                checkThat("External PiP owns same Canvas and source",countCanvas(pipWindow,checkNotNull(findCanvas(player.surface)))==1&&player.ownsSourceVersion(second)&&media.player===player)
                resources.pip.restore()
            }
            awaitCondition("actual PiP restore") { !resources.pip.active.value&&SwingUtilities.getWindowAncestor(player.surface)===windowRef.get() }
            withContext(Dispatchers.Main) { checkThat("External PiP restore retains same version and one Canvas",player.ownsSourceVersion(second)&&countCanvas(checkNotNull(windowRef.get()),checkNotNull(findCanvas(player.surface)))==1) }
            closeProduct()
            withContext(Dispatchers.Main) { checkThat("Actual external Root shutdown joins owner and closes native session",shell.slot.assemblies.value==null&&!owner.owns()&&player.decoderCapabilities.value==null) }
        } catch(failure:Throwable) { error=failure;failure.printStackTrace() }
        finally {
            try { closeProduct() } catch(failure:Throwable) { if(error==null)error=failure else error.addSuppressed(failure) }
            origin.close()
            withContext(Dispatchers.Main) { refs.close() }
            val origins=listOf(DesktopOriginalVideoShellOwner::class.java,DesktopOriginalVideoRootAssembler::class.java,
                DesktopOriginalVideoOwnerAssembly::class.java,DesktopOriginalVideoSectionWindowsPlatform::class.java,
                DesktopOriginalVideoRootWindowPlatformsImpl::class.java,MpvPlayer::class.java,
                DesktopExternalPageMemory::class.java,DesktopJsPluginHost::class.java)
            val result=buildJsonObject {
                put("status",if(error==null)"PASS_ACTUAL_AUTHORIZED_EXTERNAL_ROOT" else "FAIL")
                put("assertions",checks.size);put("checks",JsonArray(checks));put("ownershipFrames",JsonArray(ownershipFrames));put("error",error?.javaClass?.name.orEmpty());put("errorMessage",sanitizeDesktopDiagnosticText(error?.message.orEmpty()))
                put("classOrigins",buildJsonObject { origins.forEach { clazz -> put(clazz.name,buildJsonObject {
                    put("origin",clazz.protectionDomain.codeSource.location.toString())
                    val bytes=checkNotNull(clazz.getResourceAsStream("/"+clazz.name.replace('.','/')+".class")).use { it.readBytes() }
                    put("sha256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }) } })
                put("productionOverrides",0);put("seededSuccess",false);put("realAccount",false);put("globalInput",false);put("semanticCallbackOnly",true)
                put("unchangedMainEntry",false);put("videoDetailSuccessAccepted",false);put("physicalVideoToVideoCarrierAccepted",false)
                put("externalFirstFrameAccepted",checks.any { it["name"]?.jsonPrimitive?.content=="External 0 original menu callback, authority and real first frame agree"&&it["passed"]?.jsonPrimitive?.boolean==true })
                put("externalTwoSourceAccepted",checks.any { it["name"]?.jsonPrimitive?.content=="Second original external menu launch advances same native actor"&&it["passed"]?.jsonPrimitive?.boolean==true })
                put("PiPTransferAccepted",checks.any { it["name"]?.jsonPrimitive?.content=="External PiP restore retains same version and one Canvas"&&it["passed"]?.jsonPrimitive?.boolean==true });put("SMTCButtonAccepted",false)
            }
            Files.writeString(root.resolve("root-external-proof.json"),result.toString())
            withContext(Dispatchers.Main) { exit.get()?.invoke() }
            fixtureJob.cancel()
        }
    }
}
fun main(args:Array<String>) {
    require(args.size==2)
    val root=Path.of(args[0]).toAbsolutePath().normalize()
    val clip=Path.of(args[1]).toAbsolutePath().normalize()
    require(Files.isDirectory(root)&&Files.isRegularFile(clip))
    RootExternalFixture(root,clip).mount()
}
'''
(P/'RootExternalFixture.kt').write_text(s,encoding='utf8',newline='\n')
r=(old/'run.py').read_text(encoding='utf8').replace('RootMediaFixture.kt','RootExternalFixture.kt').replace('root-media-fixture','root-external-fixture').replace('root_media_actual_fixture','root_external_actual_fixture').replace('com.bilipai.desktop.rootmediafixture.RootMediaFixtureKt','com.bilipai.desktop.rootexternalfixture.RootExternalFixtureKt').replace('root-media-proof.json','root-external-proof.json').replace('PASS_ACTUAL_GUEST_MEDIA_ROOT','PASS_ACTUAL_AUTHORIZED_EXTERNAL_ROOT')
r=r.replace("parser.add_argument('--snapshot'","parser.add_argument('--clip',required=True,type=Path)\nparser.add_argument('--clip-sha',required=True)\nparser.add_argument('--snapshot'")
r=r.replace('    assert sha(native) == args.mpv_sha','    assert sha(args.clip) == args.clip_sha\n    assert sha(native) == args.mpv_sha')
r=r.replace("diagnosticSHA=sha(diagnostic),","diagnosticSHA=sha(diagnostic),clipSHA=sha(args.clip),")
r=r.replace('str(output.resolve())]','str(output.resolve()), str(args.clip.resolve())]')
r=r.replace('detailSuccessAccepted=proof["videoDetailSuccessAccepted"],','externalFirstFrameAccepted=proof["externalFirstFrameAccepted"], externalTwoSourceAccepted=proof["externalTwoSourceAccepted"],\n        semanticCallbackOnly=True,detailSuccessAccepted=proof["videoDetailSuccessAccepted"],')
(P/'run.py').write_text(r,encoding='utf8',newline='\n')
print('Prepared fixture-only external branch; not compiled or run.')

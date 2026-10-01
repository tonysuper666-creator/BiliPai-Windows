from pathlib import Path
import hashlib,importlib.util,json,subprocess,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-41';OUT=HERE/'profile-media-actual41';assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
def verifyPins():
 for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes']
verifyPins();assert len(cp)==97
NATIVE=MAIN/'desktop/native/windows-x64';nativePins={'libmpv-2.dll':'673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4','ffmpeg.exe':'9c60da6c0b083110d59084ea39f60ae149aa3e031c3b4bb4f573fafa1c1e7cea'}
for name,pin in nativePins.items():assert sha((NATIVE/name).read_bytes())==pin
original=MAIN/'desktop/.local/stable-home-native-media-proof/NativeHomeMediaFixture.kt';text=original.read_text(encoding='utf-8')
def replace(before,after):
 global text
 assert text.count(before)==1,before[:100];text=text.replace(before,after)
replace('    init { registry.currentState = Lifecycle.State.RESUMED }','    init { registry.currentState = Lifecycle.State.RESUMED }\n    fun moveTo(state: Lifecycle.State) { registry.currentState = state }')
replace('            val file = Path.of(java.net.URI(uri)); check(file == blue)','            val file = Path.of(java.net.URI(uri)); check(file == blue || file == moving)')
replace('        val lifetime = requireNotNull(media)', '''        val lifetime = requireNotNull(media)
        val once = requireNotNull(lifetime.open(blue.toUri().toString(), true, false))
        try {
            until("once mode real terminal EOF") { once.player.state.value.ended }
            verify("private once lease really stops at EOF without repeating", !once.player.state.value.looping && once.player.state.value.positionSeconds >= 3.7)
        } finally { once.close() }
        val repeated = requireNotNull(lifetime.open(blue.toUri().toString(), true, true))
        try {
            until("repeat native source ready") { !repeated.player.state.value.loading && repeated.player.state.value.videoCodec != null }
            val loopSeek = requireNotNull(repeated.player.seekToTracked(3.7))
            until("repeat seek delivered") { repeated.player.state.value.seekCompletedId == loopSeek }
            until("native repeat actually wraps") { repeated.player.state.value.positionSeconds < 1.0 && !repeated.player.state.value.ended }
            verify("private repeat lease wraps actual native timeline", repeated.player.state.value.looping && repeated.player.state.value.nativePaused == false)
        } finally { repeated.close() }
        verify("original sole skin repeat policy keeps once and default distinction",
            com.android.purebilibili.feature.profile.resolveProfileSkinVideoRepeatMode(" Once ") == 0 &&
            com.android.purebilibili.feature.profile.resolveProfileSkinVideoRepeatMode(null) == 1)
        val profileMedia = DesktopOriginalProfileMedia(lifetime) { error("Profile media failed: $it") }
        val profileLifecycle = LocalMediaLifecycle().apply { moveTo(Lifecycle.State.STARTED) }
        val skinScene = ImageComposeScene(width = 160, height = 90, coroutineContext = coroutineContext)
        try {
            skinScene.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides profileLifecycle) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        profileMedia.skinVideo(moving.toUri().toString(), "once", true, Modifier.fillMaxSize())
                    }
                }
            }
            fun skinPixels(): ByteArray = skinScene.render(System.nanoTime()).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            var skinHash = hash(skinPixels())
            withTimeout(12_000L) { while (hash(skinPixels()) == skinHash) { delay(20L) } }
            repeat(10) { skinPixels(); delay(20L) }
            skinHash = hash(skinPixels())
            withTimeout(3_000L) { while (hash(skinPixels()) == skinHash) { delay(20L) } }
            verify("original skin STARTED lifecycle produces changing native pixels", true)
            profileLifecycle.moveTo(Lifecycle.State.CREATED)
            repeat(20) { skinPixels(); delay(20L) }
            skinHash = hash(skinPixels()); delay(350L)
            verify("skin stops below STARTED with copied pixels held", skinHash == hash(skinPixels()))
        } finally { skinScene.close() }
        val gif = moving.parent.resolve("alignment.gif")
        for ((label, alignment, expected) in listOf(
            Triple("top", androidx.compose.ui.Alignment.TopCenter, java.awt.Color.RED),
            Triple("center", androidx.compose.ui.Alignment.Center, java.awt.Color.GREEN),
            Triple("bottom", androidx.compose.ui.Alignment.BottomCenter, java.awt.Color.BLUE))) {
            val gifScene = ImageComposeScene(width = 100, height = 100, coroutineContext = coroutineContext)
            val gifLifecycle = LocalMediaLifecycle()
            try {
                gifScene.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides gifLifecycle) {
                        profileMedia.wallpaper(gif.toUri().toString(), gif.toFile(), alignment, false, false, Modifier.fillMaxSize())
                    }
                }
                suspend fun gifPixels(): java.awt.image.BufferedImage = gifScene.render(System.nanoTime()).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(ByteArrayInputStream(data.bytes)) } }
                var matched: java.awt.image.BufferedImage? = null
                withTimeout(12_000L) {
                    while (matched == null) {
                        val image = gifPixels()
                        if (image.getRGB(50,50) == expected.rgb) matched = image else delay(20L)
                    }
                }
                verify("actual owned GIF $label alignment selects correct original crop pixels", matched!!.getRGB(50,50) == expected.rgb)
                ImageIO.write(matched, "png", output.resolve("profile-gif-$label.png").toFile())
            } finally { gifScene.close() }
        }
''')
replace('"com.bilipai.desktop.data.DesktopSessionStore").map { name ->','"com.bilipai.desktop.data.DesktopSessionStore", "com.bilipai.desktop.ui.DesktopOriginalProfileMedia", "com.bilipai.desktop.ui.DesktopHomeWallpaperImagesKt", "com.bilipai.desktop.plugins.DesktopAnimatedSkinImage").map { name ->')
source=OUT/'NativeHomeMediaFixture.kt';source.write_text(text,encoding='utf-8',newline='\n')
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
jar=OUT/'fixture.jar';classpath=';'.join(r['path']for r in cp)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','profile_media_fixture','-d',str(jar),str(source)]
argfile=OUT/'compiler.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=180)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'Native fixture compile failed; inspect log'
with zipfile.ZipFile(jar)as z,zipfile.ZipFile(SNAP/'main-kotlin.jar')as prod:assert not {n for n in z.namelist()if n.endswith('.class')}.intersection(prod.namelist())
media=OUT/'local-media';media.mkdir()
for name,graph in [('moving','testsrc2=size=160x90:rate=15'),('blue','color=c=blue:s=160x90:r=15')]:
 cmd=[str(NATIVE/'ffmpeg.exe'),'-nostdin','-hide_banner','-y','-f','lavfi','-i',graph,'-t','4','-c:v','mpeg4','-q:v','2','-pix_fmt','yuv420p','-an',str(media/(name+'.mp4'))]
 r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',timeout=45);(media/(name+'-generator.log')).write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0
from PIL import Image,ImageDraw
frames=[]
for color in [(0,255,0),(255,255,0)]:
 img=Image.new('RGB',(100,300),'red');draw=ImageDraw.Draw(img);draw.rectangle((0,100,99,199),fill=color);draw.rectangle((0,200,99,299),fill='blue');frames.append(img)
frames[0].save(media/'alignment.gif',save_all=True,append_images=frames[1:],duration=[1000,1000],loop=0,optimize=False)
command=[str(c.JAVA),'-Xmx2g','-Djava.awt.headless=true','-Djava.security.manager=allow','-Dbilipai.mpv.path='+str(NATIVE/'libmpv-2.dll'),'-cp',str(jar)+';'+classpath,'com.bilipai.desktop.ui.NativeHomeMediaFixtureKt',str(media/'moving.mp4'),str(media/'blue.mp4'),str(OUT/'proof')]
(OUT/'java-command.json').write_text(json.dumps(command,indent=2)+'\n',encoding='utf-8')
r=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=100);(OUT/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'Native profile proof failed; inspect runtime.log'
result=json.loads((OUT/'proof/result.json').read_text(encoding='utf-8'));assert result['status']=='PASS'and result['productionClassOverrides']==0 and result['assertions']==27
assert len(result['actualCodeSources'])==12
for row in result['actualCodeSources']:
 assert (SNAP/'main-kotlin.jar').as_uri()in row['codeSource'].replace('file:/C:','file:///C:')
verifyPins()
receipt=dict(passed=True,actualPhase=41,actualRuntimeEntries=97,productionOverrides=0,actualCodeOrigins=12,assertions=result['assertions'],originalNativeFixtureSha256Bytes=sha(original.read_bytes()),expandedFixtureSha256Bytes=sha(source.read_bytes()),nativePins=nativePins,
 originalVideoAlignmentSemantics='WallpaperMedia applies alignment only to image/GIF; video retains centered ZOOM',
 snapshotManifestSha256Bytes=sha((SNAP/'manifest.json').read_bytes()),orderedClasspathSha256Bytes=sha((SNAP/'ordered-runtime-cp.json').read_bytes()),
 rootProfileMounted=False,physicalWindowTested=False,realAccountRead=False,nativeNetworkSources=0,scope='Actual local libmpv decode/EOF/repeat and isolated Compose skin lifecycle/GIF pixels')
(OUT/'accepted.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,assertions=27,actualOrigins=12,productionOverrides=0)))

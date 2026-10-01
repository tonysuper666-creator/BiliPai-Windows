from pathlib import Path
import hashlib, importlib.util, json, subprocess, zipfile, sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
SNAP = MAIN / 'desktop/.local/stable-product-snapshot-31'
OUT = MAIN / 'desktop/.local/stable-danmaku-render-main31-proof'
assert not OUT.exists()

def wide(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)

def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def pin(p, h):
    b = read(p)
    assert sha(b) == h, p
    return b
def save(p, data):
    wide(p).write_text(json.dumps(data, indent=2, ensure_ascii=False)+'\n', encoding='utf-8', newline='\n')

meta_raw = pin(SNAP/'manifest.json', '999bc989c995f877761b22c5c00a6a0d99819100d8cef591a25f94b5d56a3a5d')
meta = json.loads(meta_raw)
assert meta['wholeCandidateClassesPassed'] and meta['sourceRegistryCount'] == 773
cp_raw = pin(SNAP/'ordered-runtime-cp.json', '0da93f5423ef53986a1939d97b37114a97e4a8e64b646052d34eaeaa916a6dee')
cp = json.loads(cp_raw)
assert len(cp) == 92
for r in cp: pin(r['path'], r['sha256Bytes'])
main = next(r['path'] for r in cp if r.get('source') == 'desktop/build/classes/kotlin/main')
assert sha(read(main)) == '4ffee7ee8c833b472d9c34f569de3b292aad79251bfa8d78c5a9188f3689c0e3'

advanced = REPO/'desktop/.local/stable-danmaku-render-config-consumers-parity'
monitor = REPO/'desktop/.local/stable-danmaku-monitor-passive-delta'
manifests = []
for lane, expected in ((advanced, '6d221c053309abd218b55a129634726bf5f99f28d4458a374069351468b69103'),
                       (monitor, 'ab13142a81130473f2fa4aaf2dee90816a5cccf24ba175cc499cd480f51f59a5')):
    m = json.loads(pin(lane/'frozen-handoff.json', expected))
    manifests.append(dict(path=str(lane/'frozen-handoff.json'), sha256Bytes=expected))
    # The handoff schema is pinned; inspect its exact rows before selecting the fixture below.
    rows = m['evidence']
    assert rows is not None
    for row in rows:
        rel = row['relative']
        target = Path(row['path']) if Path(row['path']).is_absolute() else lane/rel
        pin(target, row['sha256Bytes'])

raw_fixture = read(advanced/'proof/RenderConfigProof.kt')
fixture = raw_fixture.decode('utf-8')
edits = [
    ('    override fun systemChromeInsetPx()=16\n', '    override fun systemChromeInsetPx()=16\n    override fun maximumDisplayShortSidePx()=360f // Explicit fixture monitor, no product fallback.\n'),
    ('DesktopDanmakuPaintGeometry.from(720,480,AffineTransform.getScaleInstance(1.5,1.5))', 'DesktopDanmakuPaintGeometry.from(720,480,AffineTransform.getScaleInstance(1.5,1.5),360f)'),
    ('DesktopDanmakuPaintGeometry.from(0,480,AffineTransform())', 'DesktopDanmakuPaintGeometry.from(0,480,AffineTransform(),360f)'),
]
fixture = fixture.replace('\r\n', '\n')
for old, new in edits:
    assert fixture.count(old) == 1, old
    fixture = fixture.replace(old, new, 1)
monitor_fixture = read(monitor/'proof/MonitorViewportProof.kt')
native_fixture = '''package com.bilipai.desktop.danmaku

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.EventQueue
import java.io.File
import javax.swing.UIManager

fun main(args:Array<String>) {
    check(!GraphicsEnvironment.isHeadless())
    val rows=mutableListOf<String>()
    var assertions=0
    EventQueue.invokeAndWait {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEachIndexed {index,device ->
            val window=Frame(device.defaultConfiguration)
            try {
                window.setSize(640,480)
                window.addNotify() // Real private hidden test HWND, not a mounted application Root.
                check(window.isDisplayable && !window.isVisible);assertions++
                val actualDevice=requireNotNull(window.graphicsConfiguration).device
                check(actualDevice===device);assertions++
                val platform=DesktopWindowsDanmakuRenderPlatform {window}
                val mode=actualDevice.displayMode
                val short=minOf(mode.width,mode.height).toFloat()
                check(platform.maximumDisplayShortSidePx()==short && short>0f);assertions++
                val geometry=requireNotNull(DesktopDanmakuPaintGeometry.from(640,480,
                    window.graphicsConfiguration.defaultTransform,platform.maximumDisplayShortSidePx()))
                check(geometry.viewport.widthPx>0 && geometry.viewport.heightPx>0);assertions++
                check(geometry.viewport.scale>0f && geometry.viewport.scale<=1f);assertions++
                val font=platform.resolveTypeface(4)
                check(font.family==(window.font ?: requireNotNull(UIManager.getFont("Label.font"))).family);assertions++
                check(platform.systemChromeInsetPx()>=0);assertions++
                rows += "{\\"index\\":$index,\\"physicalWidth\\":${mode.width},\\"physicalHeight\\":${mode.height},\\"shortSide\\":$short,\\"viewportScale\\":${geometry.viewport.scale}}"
            } finally {window.dispose()}
        }
    }
    check(rows.isNotEmpty());assertions++
    val result="{\\"status\\":\\"PASS\\",\\"assertions\\":$assertions,\\"actualHiddenAWTWindow\\":true,\\"actualRootWindowAccepted\\":false,\\"accountOrSocket\\":false,\\"monitors\\":[${rows.joinToString()}]}"
    File(args.single(),"proof-result.json").writeText(result+"\\n")
    println(result)
}
'''.encode('utf-8')

spec = importlib.util.spec_from_file_location('compiler', MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
OUT.mkdir()
save(OUT/'fixture-adaptation.json', dict(preparedFixtureSHA256=sha(raw_fixture), adaptedFixtureSHA256=sha(fixture.encode()),
    edits=[dict(old=old,new=new) for old,new in edits], onlyNewRequiredFixtureMonitorPort=True,
    productionSourceOrHistoricalEvidenceModified=False, monitorFixtureByteIdentical=True, preparedHandoffs=manifests))
results=[]
loaded_names=[
    'com.android.purebilibili.feature.video.danmaku.DanmakuConfig',
    'com.android.purebilibili.danmaku.engine.DanmakuRenderConfig',
    'com.bilipai.desktop.danmaku.DanmakuScheduler',
    'com.bilipai.desktop.danmaku.DanmakuOverlay',
    'com.bilipai.desktop.danmaku.LiveDanmakuRenderer',
    'com.bilipai.desktop.danmaku.DesktopWindowsDanmakuRenderPlatform',
    'com.bilipai.desktop.danmaku.DanmakuSettings',
    'com.bilipai.desktop.danmaku.DesktopOriginalDanmakuRenderPlatformKt',
]
for name, source, entry, headless in (
    ('advanced', fixture.encode(), 'com.bilipai.desktop.danmaku.RenderConfigProofKt', True),
    ('monitor', monitor_fixture, 'com.bilipai.desktop.danmaku.MonitorViewportProofKt', True),
    ('actual-hidden-window', native_fixture, 'com.bilipai.desktop.danmaku.ActualMonitorWindowProofKt', False)):
    run=OUT/name;run.mkdir();scratch=run/'scratch';scratch.mkdir()
    file_name=entry.rsplit('.',1)[1].removesuffix('Kt')+'.kt'
    (run/file_name).write_bytes(source)
    # Real code-source and loaded class-byte receipt, from the same JVM that executes the fixture.
    receipt='''package actual31.receipt
import java.io.File
import java.security.MessageDigest
fun main(args:Array<String>) {
  val fixture=Class.forName(args[0])
  fixture.getDeclaredMethod("main",Array<String>::class.java).invoke(null,arrayOf(args[1]))
  val names=args.drop(2)
  val rows=names.map {name ->
    val c=Class.forName(name)
    val bytes=requireNotNull(c.getResourceAsStream("/"+name.replace('.','/')+".class")).use {it.readBytes()}
    val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    "{\\"class\\":\\"$name\\",\\"path\\":\\"${File(c.protectionDomain.codeSource.location.toURI()).absolutePath.replace("\\\\","/")}\\",\\"classSha256Bytes\\":\\"$hash\\"}"
  }
  File(args[1],"loaded-code-sources.json").writeText("["+rows.joinToString()+"]\\n")
}
'''
    (run/'LoadedCodeSources.kt').write_text(receipt,encoding='utf-8',newline='\n')
    target=run/'fixture.jar'
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(c.PLUGIN),'-module-name','actual31_'+name.replace('-','_'),
          '-Xfriend-paths='+main,'-cp',';'.join(r['path'] for r in cp),'-d',str(target),str(run/file_name),str(run/'LoadedCodeSources.kt')]
    (run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
    p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
    (run/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n')
    if p.returncode:print(p.stdout+p.stderr)
    p.check_returncode()
    with zipfile.ZipFile(target) as z: compiled=set(z.namelist())
    overlaps=[]
    for r in cp:
        with zipfile.ZipFile(wide(r['path'])) as z:
            overlaps += [dict(className=n,artifact=r['path']) for n in compiled.intersection(z.namelist()) if n.endswith('.class')]
    assert not overlaps, overlaps
    p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless='+str(headless).lower(),'-cp',str(target)+';'+';'.join(r['path'] for r in cp),
        'actual31.receipt.LoadedCodeSourcesKt',entry,str(scratch),*loaded_names],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    (run/'run.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n')
    print(p.stdout+p.stderr);p.check_returncode()
    raw=json.loads(read(scratch/'proof-result.json'));assert raw['status']=='PASS'
    sources=json.loads(read(scratch/'loaded-code-sources.json'));assert len(sources)==len(loaded_names)
    for row in sources:
        assert Path(row['path']).absolute()==Path(main).absolute(),row
        with zipfile.ZipFile(wide(main)) as z:assert sha(z.read(row['class'].replace('.','/')+'.class'))==row['classSha256Bytes']
    result=dict(name=name,passed=True,assertions=raw['assertions'],productionClassOverrides=0,fixtureClassOverlaps=overlaps,
        actualCodeSources=sources,fixtureSHA256=sha(source),fixtureJarSHA256=sha(read(target)),headless=headless,
        actualRootPlaybackOrFullRootAccepted=False,fixtureResult=raw)
    save(run/'accepted.json',result);results.append(result)
for r in cp: pin(r['path'],r['sha256Bytes'])
save(OUT/'result.json',dict(passed=True,actualProductPhase=31,actualManifestSHA256=sha(meta_raw),actualCP_SHA256=sha(cp_raw),
    sourceRegistryCount=773,productionOverrides=0,all92RuntimeEntriesPinnedBeforeAndAfter=True,cases=results,
    assertions=sum(r['assertions'] for r in results),actualWindowsDisplayGetterAccepted=True,
    actualRootOrFullByteDanceRendererParityAccepted=False,desktopExeReplaced=False))
print('ACTUAL31_RENDER_PROOF_PASS',sha(read(OUT/'result.json')))

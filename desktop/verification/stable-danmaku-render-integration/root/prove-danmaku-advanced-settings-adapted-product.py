from pathlib import Path
import hashlib,json,subprocess,importlib.util,zipfile,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';phase=int(sys.argv[1])
SNAP=MAIN/f'desktop/.local/stable-product-snapshot-{phase}';OUT=MAIN/f'desktop/.local/stable-danmaku-main{phase}-advanced-settings-proof'
assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
manifest=read(SNAP/'manifest.json');metadata=json.loads(manifest);assert metadata['wholeCandidateClassesPassed'] and metadata['sourceRegistryCount']==773
cp_raw=pin(SNAP/'ordered-runtime-cp.json',metadata['orderedRuntimeClasspathSha256Bytes']);cp=json.loads(cp_raw);assert len(cp)==92
for r in cp:pin(r['path'],r['sha256Bytes'])
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
settings=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity'
m=json.loads(pin(settings/'frozen-handoff.json','51acaae885d7cf690f2b17aaf4ea856b02aae81e8b25c0086a5ffb01dddeb6a8'))
row=next(r for r in m['artifacts'] if r['path']=='SettingsFixture.kt')
settings_fixture=pin(settings/'SettingsFixture.kt',row['sha256Bytes'])
original_settings_fixture=settings_fixture
fixture_text=settings_fixture.decode('utf-8').replace('\r\n','\n')
fixture_edits=[
 ('DanmakuScheduler(listOf(comment("unique-rule-320")),projected)', 'DanmakuScheduler(listOf(comment("unique-rule-320")),projected,liveAdmission=false)'),
 ('DanmakuScheduler(listOf(comment("safe","def456")),scalar)', 'DanmakuScheduler(listOf(comment("safe","def456")),scalar,liveAdmission=false)'),
 ('DanmakuScheduler(listOf(comment("safe").copy(mode=5)),scalar)', 'DanmakuScheduler(listOf(comment("safe").copy(mode=5)),scalar,liveAdmission=false)'),
 ('scrolling.frame(25.0,100,100,20){10}', 'scrolling.frame(10.0,100,360,20){10}'),
 ('100.0-110.0*25.0/150.0', '100.0-110.0*10.0/20.0'),
 ('current Scheduler frame uses projected scroll50 times speed3', 'current Scheduler resolves saved scroll50 and speed3 through original20s renderer clamp'),
 ('fixed.frame(49.0,100,100,20){10}.size==1&&fixed.frame(50.0,100,100,20){10}.isEmpty()', 'fixed.frame(14.0,100,360,20){10}.size==1&&fixed.frame(15.0,100,360,20){10}.isEmpty()'),
 ('current Scheduler static50 survives old hidden20 cap', 'current Scheduler resolves saved static50 through original15s renderer clamp'),
]
for before,after in fixture_edits:
    assert fixture_text.count(before)==1,before
    fixture_text=fixture_text.replace(before,after,1)
fixture_text += '''

/** Explicit legacy collision fixture dimensions; current production requires its original config. */
private fun DanmakuScheduler.frame(time:Double,width:Int,height:Int,rowHeight:Int,measure:(DanmakuComment)->Int):List<PositionedDanmaku> {
    val font=requireNotNull(javax.swing.UIManager.getFont("Label.font"))
    val platform=object:DesktopOriginalDanmakuRenderPlatform {
        override fun resolveTypeface(fontWeight:Int)=font
        override fun systemChromeInsetPx()=0
        override fun maximumDisplayShortSidePx()=360f // Fixture monitor, never a product fallback.
    }
    val config=currentSettings.originalConfig(platform).resolveRenderConfig(
        com.android.purebilibili.feature.video.danmaku.DanmakuViewport(width,height,1f,1f)
    ).copy(lineHeightPx=rowHeight.toFloat(),lineMarginPx=0f,
        lineCount=(height*currentSettings.displayAreaRatio/rowHeight).toInt().coerceAtLeast(0))
    return frame(time,width,height,config) {DesktopDanmakuTextMetrics(measure(it),rowHeight-6.0)}
}
'''
settings_fixture=fixture_text.encode('utf-8')

consumer=REPO/'desktop/.local/stable-danmaku-root-consumers-parity'
pin(consumer/'frozen-handoff.json','51551c0d5070f6980e7511c052816fee4e5b0a1cb517e82f4f60208dce5eb01f')
consumer_fixture=read(consumer/'proof/RootDanmakuConsumerProof.kt')
OUT.mkdir();results=[]
(OUT/'fixture-adaptation.json').write_text(json.dumps(dict(originalSettingsFixtureSHA256=sha(original_settings_fixture),adaptedFixtureSHA256=sha(settings_fixture),edits=[dict(old=a,new=b) for a,b in fixture_edits],fixtureOnlyRequiredVideoAdmissionAndConfigPort=True,storedOriginalUpperBoundsRemainAsserted=True,rendererDurationAssertionsNowFollowOriginal20sScroll15sPinnedPolicies=True,rootConsumerFixtureByteIdentical=True,productionOrHistoricalSourceModified=False),indent=2)+'\n',encoding='utf-8')

for name,filename,source,entry in (
    ('settings','SettingsFixture.kt',settings_fixture,'com.bilipai.desktop.ui.SettingsFixtureKt'),
    ('consumers','RootDanmakuConsumerProof.kt',consumer_fixture,'com.bilipai.desktop.ui.RootDanmakuConsumerProofKt')):
    run=OUT/name;run.mkdir();(run/filename).write_bytes(source)
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(c.PLUGIN),'-module-name','actual_danmaku_product_proof',
          '-Xfriend-paths='+main,'-cp',';'.join(r['path'] for r in cp),'-d',str(run/'classes'),str(run/filename)]
    (run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
    p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
    (run/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8');p.check_returncode()
    compiled={p.relative_to(run/'classes').as_posix() for p in (run/'classes').rglob('*.class')};overlaps=[]
    for r in cp:
        with zipfile.ZipFile(wide(r['path'])) as z:overlaps.extend(dict(className=n,artifact=r['path']) for n in compiled.intersection(z.namelist()))
    assert not overlaps
    scratch=run/'scratch';scratch.mkdir()
    p=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(run/'classes')+';'+';'.join(r['path'] for r in cp),entry,str(scratch)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    (run/'run.log').write_text(p.stdout+p.stderr,encoding='utf-8');p.check_returncode()
    if name=='settings':
        raw=json.loads(read(scratch/'result.json'));assert raw['status']=='PASS' and raw['assertions']==34
        assert 'SETTINGS_PROOF_PASS groups=3 assertions=34' in p.stdout
        sources=raw['actualCodeSources'];assert len(sources)==11
        for r in sources:
            assert Path(r['path']).absolute()==Path(main).absolute()
            with zipfile.ZipFile(wide(main)) as z:assert sha(z.read(r['class'].replace('.','/')+'.class'))==r['classSha256Bytes']
        assertions=34
    else:
        assert 'ROOT_DANMAKU_CONSUMER_PROOF PASS assertions=27 groups=3 nativeWindowAndChooserNotExercised=true' in p.stdout,p.stdout
        sources=[];assertions=27
    result=dict(passed=True,name=name,groups=3,assertions=assertions,productionClassOverrides=0,classOverlaps=overlaps,
                fixtureSHA256=sha(source),actualProductPhase=phase,actualMainJar=main,actualCodeSources=sources,
                actualNativeRendererOrPanelPointerAccepted=False,systemWindowOrChooserUsed=False,accountOrSocketUsed=False)
    (run/'accepted.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');results.append(result)
    print(json.dumps(dict(name=name,passed=True,assertions=assertions)))
for r in cp:pin(r['path'],r['sha256Bytes'])
result=dict(passed=True,phase=phase,actualManifestSHA256=sha(manifest),actualCP_SHA256=sha(cp_raw),productionOverrides=0,groups=6,assertions=61,cases=results,
            fullPanelRootSourceWired=True,actualRootPanelPointerAccepted=False,actualNativeRenderAllFieldsAccepted=False,desktopExeReplaced=False)
(OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,groups=6,assertions=61)))

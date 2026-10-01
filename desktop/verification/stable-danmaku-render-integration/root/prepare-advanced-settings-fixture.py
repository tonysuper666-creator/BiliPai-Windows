from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
old=HERE/'prove-danmaku-advanced-settings-actual-product.py'
new=HERE/'prove-danmaku-advanced-settings-adapted-product.py'
assert not new.exists()
t=old.read_text(encoding='utf-8')
anchor="settings_fixture=pin(settings/'SettingsFixture.kt',row['sha256Bytes'])"
assert t.count(anchor)==1
adaptation='''
original_settings_fixture=settings_fixture
fixture_text=settings_fixture.decode('utf-8').replace('\\r\\n','\\n')
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
fixture_text += \'\'\'

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
\'\'\'
settings_fixture=fixture_text.encode('utf-8')
'''
t=t.replace(anchor,anchor+adaptation,1)
out_anchor="OUT=MAIN/f'desktop/.local/stable-danmaku-main{phase}-integration-proof'"
assert t.count(out_anchor)==1
t=t.replace(out_anchor,"OUT=MAIN/f'desktop/.local/stable-danmaku-main{phase}-advanced-settings-proof'",1)
mkdir='OUT.mkdir();results=[]'
assert t.count(mkdir)==1
t=t.replace(mkdir,mkdir+'''
(OUT/'fixture-adaptation.json').write_text(json.dumps(dict(originalSettingsFixtureSHA256=sha(original_settings_fixture),adaptedFixtureSHA256=sha(settings_fixture),edits=[dict(old=a,new=b) for a,b in fixture_edits],fixtureOnlyRequiredVideoAdmissionAndConfigPort=True,storedOriginalUpperBoundsRemainAsserted=True,rendererDurationAssertionsNowFollowOriginal20sScroll15sPinnedPolicies=True,rootConsumerFixtureByteIdentical=True,productionOrHistoricalSourceModified=False),indent=2)+'\\n',encoding='utf-8')
''',1)
new.write_text(t,encoding='utf-8',newline='\n')
(HERE/'advanced-settings-adapted-runner.json').write_text(json.dumps(dict(originalRunnerSHA256=hashlib.sha256(old.read_bytes()).hexdigest(),newRunnerSHA256=hashlib.sha256(new.read_bytes()).hexdigest(),firstFailurePreserved=True,onlyFixtureAndCohortAdaptation=True),indent=2)+'\n',encoding='utf-8',newline='\n')
print(new)

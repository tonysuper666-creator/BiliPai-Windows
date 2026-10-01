from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):return Path('\\\\?\\'+str(Path(p).absolute()))
lane=MAIN/'desktop/.local/stable-shared-dock-visibility-supplement'
raw=(lane/'frozen-handoff.json').read_bytes();assert sha(raw)=='8e9d75a953d931b779c83c482422c21df12a8d6e784c556b0f380470bbe5f5be'
for r in json.loads(raw)['artifacts']:assert sha(wide(lane/r['path']).read_bytes())==r['sha256Bytes']
p=REPO/'desktop/tools/extract-upstream-shared-liquid-tabs.py'
before=p.read_text(encoding='utf-8').replace('\r\n','\n');assert sha(before.encode())=='89c93a5b78a057373ab4c01ba5efcd585c98143e83a895e5c597e4952e808604'
subprocess.run(['git','apply','-p0','--check',str(lane/'producer.patch')],cwd=REPO,check=True)
subprocess.run(['git','apply','-p0',str(lane/'producer.patch')],cwd=REPO,check=True)
after=p.read_text(encoding='utf-8').replace('\r\n','\n');assert sha(after.encode())=='434523b12b0a218a0f1f0383dbfff64b782092b00d0c25d987844416adad0f89'
files=['desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopControllerNativeSmoke.kt',
       'desktop/src/test/kotlin/com/bilipai/desktop/DesktopPlaybackControllerTest.kt',
       'desktop/src/test/kotlin/com/bilipai/desktop/DesktopCheckpointFailureTest.kt',
       'desktop/src/test/kotlin/com/bilipai/desktop/DesktopPlaybackQueueOwnershipTest.kt',
       'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopStoryInitialFailureTest.kt',
       'desktop/src/test/kotlin/com/bilipai/desktop/ui/PlaybackSettingsDraftMemoryTest.kt',
       'desktop/src/test/kotlin/com/bilipai/desktop/player/DesktopPremiumAudioControllerTest.kt',
       'desktop/src/test/kotlin/com/bilipai/desktop/player/PlaybackSettingsIntegrationFixtures.kt']
changes=[]
for name in files:
    p=REPO/name;b=p.read_bytes();text=b.decode().replace('\r\n','\n')
    count=text.count('DesktopPlaybackController(');assert count in (1,2)
    assert text.count('dataSource = source')==count and 'currentDanmakuSettings =' not in text
    # These existing actors explicitly supply null for their overlay. Fail clearly
    # if a future fixture inadvertently invokes the required renderer capability.
    updated=text.replace('dataSource = source','currentDanmakuSettings = { error("This controller harness has no danmaku overlay") }, dataSource = source')
    baseline=HERE/'danmaku-harness-install-baseline'/name;baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(b)
    p.write_text(updated,encoding='utf-8',newline='\n')
    changes.append(dict(path=name,beforeLF=sha(text.encode()),afterLF=sha(updated.encode()),callSites=count))
report=dict(sharedSupplementFrozenSHA256=sha(raw),sharedBeforeLF=sha(before.encode()),sharedAfterLF=sha(after.encode()),
            completeOriginalVisibilityOverloads=2,soleProducerExtendedOnly=True,harnessChanges=changes,
            requiredRendererPortRetained=True,onlyOverlayAbsentHarnessesChanged=True,productionRootUsesActualProjection=True)
(HERE/'danmaku-harness-and-dock-supplement-install.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(installed=True,originalVisibilityOverloads=2,overlayAbsentHarnessCallSites=sum(r['callSites'] for r in changes))))

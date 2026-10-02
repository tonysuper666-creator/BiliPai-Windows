from pathlib import Path
import hashlib,json,subprocess
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023'
BASE='464f5573331dfc7f7c2f1e47a5bf6bbac8cfd201'
rows=[
 ('desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',[(399,399),(520,522),(1537,1540),(1808,1809)]),
 ('desktop/src/main/kotlin/com/bilipai/desktop/ui/MediaScreens.kt',[(276,312)]),
 ('desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopMediaRepository.kt',[(419,435)]),
 ('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopUnifiedPlaybackFacade.kt',[(264,271)]),
 ('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt',[(153,156),(193,195)]),
 ('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.kt',[(64,67)]),
 ('desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt',[(116,126),(187,204)]),
 ('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopRetainedMedia.kt',[(130,134),(140,159)])]
evidence=[]
for path,ranges in rows:
 raw=subprocess.check_output(['git','-C',str(C),'show',BASE+':'+path]).decode('utf8').replace('\r\n','\n');lines=raw.splitlines()
 evidence.append(dict(path=path,sha256LF=hashlib.sha256(raw.encode()).hexdigest(),ranges=[dict(start=i,end=j,body='\n'.join(lines[i-1:j]))for i,j in ranges]))
out=dict(candidateBase=BASE,scope='Read-only exact current fallback consumer trace before bridge installation',
 fallbackUsesEpisodeReferer=True,fallbackPublishesViaSameNativeOwner=False,fallbackSetsPgcPresenter=False,
 guardReadsAcceptedPluginDispatch=True,guardReadsBareMpvRequestedSource=False,
 priorAssemblyRetiredByAcquire=True,foreignSourceVersionInvalidatesNativeOwnerAcceptedIdentity=True,
 existingFallbackQualityErrorAndAutoNextOwner='DesktopBangumiPageMemory/MediaScreens/RetainedMediaEffects',
 regressionConclusion='Existing fallback cannot activate the new ep/cheese accepted-dispatch guard: it retires the assembly before loading and bypasses native.publish; foreign Mpv source invalidates any prior accepted identity. No known early listener/quality/heartbeat suppression regression in this consumer chain.',
 runtimeTested=False,normalCompile=False,productionWrites=0,evidence=evidence)
(P/'current-fallback-consumer-trace.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('Saved read-only concrete fallback consumer trace')

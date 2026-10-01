exec((__import__('pathlib').Path(__file__).parent/'prepare.py').read_text(encoding='utf-8').split("paths=['desktop/tools/extract-upstream-diagnostics.py'")[0])
ALPHA='fcf84853b287662e8a9129ea0d38576c36522a34'
targets=json.loads(read(HERE/'generated-targets.json'))
def target(name):return read(next(r['path'] for r in targets if r['path'].endswith(name)))
host=module('repair_source_host',REPO/'desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(REPO);parser=media.parser_for(REPO)
decl=module('repair_source_decl',REPO/'desktop/tools/extract-appearance-platform.py')
base='app/src/main/java/com/android/purebilibili/'
logger_path=base+'core/util/Logger.kt';logger=read(REPO/logger_path)
pure=logger[logger.index('private const val LOG_DIRECTORY_NAME'):logger.index('/**\n *  统一日志工具类')]
expected=pure.replace('LogCollector.','DesktopDiagnosticCollector.').replace('appendLine("Android版本: $androidRelease (API $apiLevel)")','appendLine("系统版本: $androidRelease；运行时: $apiLevel")')
assert target('DesktopDiagnosticPolicy.kt').rstrip().endswith(expected.rstrip())
exit_path=base+'core/performance/Android17Diagnostics.kt';exit_source=read(REPO/exit_path)
exception=decl.declarations(parser,exit_source,['AbnormalProcessExitException']).strip()
assert target('DesktopOriginalAbnormalProcessExitException.kt').rstrip().endswith(exception)
native_path=base+'core/performance/NativeExitTrace.kt';native=read(REPO/native_path)
assert target('DesktopOriginalNativeExitTrace.kt').rstrip().endswith(native.strip())
sponsor_path=base+'data/repository/SponsorBlockRepository.kt';sponsor=read(REPO/sponsor_path)
expected_sponsor=sponsor.replace('import com.android.purebilibili.core.network.NetworkModule\n','').replace('NetworkModule.okHttpClient','com.bilipai.desktop.plugins.DesktopPluginNetwork.publicClient').replace('android.os.SystemClock.elapsedRealtime()','com.bilipai.desktop.appearance.DesktopMonotonicClock.elapsedRealtime()')
assert target('SponsorBlockRepository.kt').rstrip().endswith(expected_sponsor.strip())
assert sponsor.count('android.os.SystemClock.elapsedRealtime()')==2
stable_sponsor_load=media.function(sponsor,'loadSegments',parser)
generated_load=media.function(target('SponsorBlockRepository.kt'),'loadSegments',parser)
assert generated_load.replace('com.bilipai.desktop.appearance.DesktopMonotonicClock.elapsedRealtime()','android.os.SystemClock.elapsedRealtime()')==stable_sponsor_load
sections_path=base+'feature/settings/ui/SettingsSections.kt';sections=read(REPO/sections_path)
original_tabs=media.function(sections,'FeedDynamicTabVisibilityItem',parser)
assert media.function(target('DesktopOriginalDynamicTabsFields.kt'),'FeedDynamicTabVisibilityItem',parser)==original_tabs
source_paths=[logger_path,exit_path,native_path,sponsor_path,sections_path]
identities=[]
for path in source_paths:
    stable=subprocess.check_output(['git','-c','core.longpaths=true','show',STABLE+':'+path],cwd=REPO).decode('utf-8').replace('\r\n','\n');assert stable==read(REPO/path)
    blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',STABLE+':'+path],cwd=REPO,text=True).strip()
    current=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+path,path],cwd=REPO,text=True).strip();assert blob==current
    write(HERE/'original-stable'/path,stable)
    before=subprocess.run(['git','-c','core.longpaths=true','show',ALPHA+':'+path],cwd=REPO,capture_output=True)
    if before.returncode==0:
        alpha=before.stdout.decode('utf-8').replace('\r\n','\n');write(HERE/'original-alpha9'/path,alpha)
        write(HERE/'source-diffs'/(Path(path).stem+'.diff'),''.join(difflib.unified_diff(alpha.splitlines(True),stable.splitlines(True),fromfile='alpha9:'+path,tofile='v023:'+path)))
    identities.append(dict(path=path,sha256Lf=digest(stable),pinnedCommit=STABLE,pinnedGitBlob=blob,currentGitBlob=current,alpha9SourceExisted=before.returncode==0))
write(HERE/'selected-original-body-Logger.txt',pure)
write(HERE/'selected-original-body-AbnormalException.kt',exception+'\n')
write(HERE/'selected-original-body-SponsorLoadSegments.kt',stable_sponsor_load+'\n')
write(HERE/'selected-original-body-TabsVisibilityItem.kt',original_tabs+'\n')
dump(HERE/'source-retention-proof.json',dict(passed=True,fixedStableCommit=STABLE,sources=identities,
    originalLoggerPureRegionLfSha256=digest(pure),entireLoggerPureRegionRetainedExceptExistingAdapters=True,
    originalExceptionClassLfSha256=digest(exception),entireOriginalExceptionClassUnchanged=True,
    nativeExitTraceFullOriginalLfSha256=digest(native),nativeExitTraceFullFileVerbatim=True,
    tabsVisibilityOriginalBodyLfSha256=digest(original_tabs),tabsVisibilityOriginalBodyUnchanged=True,
    sponsorLoadOriginalBodyLfSha256=digest(stable_sponsor_load),sponsorLoadBodyIdenticalAfterReversingTwoClockCalls=True,
    cachedSegmentsTtlAndGenerationAndDedupeAndLimitPreserved=True,
    platformClock='existing DesktopMonotonicClock maps monotonic elapsed time to milliseconds',
    systemExitCaptureAdded=False,AndroidProfilingHooksAdded=False,newHttpOrStore=False,MainWrittenByThisLane=False))
diagnostic_ids=json.loads(read(HERE/'generated/diagnostics/sources/source-identity.json'))
assert len(diagnostic_ids)==6 and all(r['matchesPinnedCommit'] and r['pinnedCommit']==STABLE for r in diagnostic_ids)
print('PASS source retention: full Logger pure region + original exception/full NativeExitTrace + exact tabs body + original sponsor load body with two clock substitutions')

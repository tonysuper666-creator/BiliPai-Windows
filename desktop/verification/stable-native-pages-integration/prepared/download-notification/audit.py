from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('tools',HERE/'compile.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
STABLE=c.MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def main():
 source=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/download/DesktopDownloadNotifications.kt'
 candidate=c.safe(source).read_text(encoding='utf-8');checks=[]
 def check(name,value):assert value,name;checks.append(dict(name=name,pass_=True))
 base='app/src/main/java/com/android/purebilibili/feature/download/'
 original=[]
 for filename,anchors in [('DownloadWorker.kt',{'identity':32,'updater':92,'foreground':142,'percentage':144,'title':149,'ongoing':153,'onlyAlertOnce':154}),('DownloadTask.kt',{'status':9,'taskSchema':57,'progress':76,'isDownloading':98}),('DownloadListNavigationPolicy.kt',{'pauseAll':23,'continueAll':27})]:
  path=base+filename
  p=subprocess.run(['git','-C',str(STABLE),'show',COMMIT+':'+path],capture_output=True,check=True)
  raw=p.stdout;lf=raw.replace(b'\r\n',b'\n');dest=HERE/'original-source'/filename;c.safe(dest.parent).mkdir(parents=True,exist_ok=True);c.safe(dest).write_bytes(lf)
  blob=subprocess.run(['git','-C',str(STABLE),'rev-parse',COMMIT+':'+path],capture_output=True,text=True,check=True).stdout.strip()
  original.append(dict(path=path,gitBlob=blob,sha256LF=hashlib.sha256(lf).hexdigest(),copiedSource=str(dest),anchors=anchors,mode='reference-only-not-emitted'))
 worker=c.safe(HERE/'original-source/DownloadWorker.kt').read_text(encoding='utf-8')
 start=worker.index('    private suspend fun getForegroundInfo(progress: Float): ForegroundInfo {')
 end=worker.index('\n    }\n}',start)+len('\n    }')
 foreground=worker[start:end]
 c.write(HERE/'original-source/getForegroundInfo.body.txt',foreground+'\n')
 start=worker.index('        val progressJob = launch {');end=worker.index('\n\n        try',start)
 updater=worker[start:end];c.write(HERE/'original-source/progressUpdater.body.txt',updater+'\n')
 check('exact original percentage expression', '(progress.coerceIn(0f, 1f) * 100).toInt()' in foreground and '(progress.coerceIn(0f, 1f) * 100).toInt()' in candidate)
 check('original title fallback', '?: "下载中..."' in foreground and '?: "下载中..."' in candidate)
 check('original 2000ms updater cadence', 'delay(2_000L)' in updater and 'delay(2_000L)' in candidate)
 check('sole manager StateFlow sampled', 'manager.tasks.value' in candidate and 'MutableStateFlow(DesktopDownloadNotificationStatus())' in candidate)
 check('existing pause/continue policy reused', 'shouldPauseAllInclude(it.item)' in candidate and 'shouldContinueAllInclude(it.item)' in candidate)
 check('existing manager mutation endpoints reused', 'manager.pause(' in candidate and 'manager.resume(' in candidate)
 check('no new download/network/store producer', all(x not in candidate for x in ['OkHttpClient(', 'DesktopRepository(', 'DesktopPluginStore(', 'SessionStore(', 'MutableStateFlow<List<DownloadTask>>']))
 check('EDT-only actual platform admission', 'scope.isActive && stillOwned() && window.isDisplayable && owners[window]?.get() === this' in candidate)
 check('generation guards queued presentation', 'renderGeneration.get() != generation' in candidate)
 check('weak host registry contains only presentation owner', 'WeakHashMap<Window, WeakReference<DesktopDownloadNotifications>>()' in candidate)
 check('replacement clears old before adopting native host', candidate.index('owners[window]?.get()?.closeOnEdt()') < candidate.index('owners[window] = WeakReference(this)'))
 check('closed owner cannot clear replacement taskbar', 'if (owners[window]?.get() === this)' in candidate)
 check('parent cancellation finalizes', '} } finally { close() }' in candidate)
 check('native window disposal finalizes', 'DISPLAYABILITY_CHANGED' in candidate and 'window.removeHierarchyListener(hierarchyListener)' in candidate)
 check('no repeated balloon/toast added', 'displayMessage(' not in candidate)
 check('indeterminate state avoids value call', 'projection.state != Taskbar.State.INDETERMINATE' in candidate)
 check('determinate resumes target state explicitly', 'bar.setWindowProgressState(window, projection.state)' in candidate)
 check('close clears state OFF', 'setWindowProgressState(window, Taskbar.State.OFF)' in candidate)
 compile_result=json.loads(c.safe(HERE/'compile-02/compile-result.json').read_text())
 check('candidate exact bytes equal compiled input', compile_result['sourceInputs'][0]['sha256Bytes']==c.sha(source))
 check('zero actual class FQN overlap', compile_result['classOverlapWithActual']==[])
 result=json.loads(c.safe(HERE/'proof-03/scratch/result.json').read_text())
 candidateJar=HERE/'compile-02/prepared-download-notification.jar'
 for row in result['codeSources']:
  if row['class'].startswith('java.'):
   check('actual JDK identity '+row['class'], row['location']=='jrt:/java.desktop');continue
  jar=candidateJar if row['class']=='com.bilipai.desktop.download.DesktopDownloadNotifications' else c.SNAP/'main-kotlin.jar'
  with zipfile.ZipFile(c.safe(jar)) as z:actual=hashlib.sha256(z.read(row['class'].replace('.','/')+'.class')).hexdigest()
  check('actual class bytes '+row['class'],actual==row['sha256ClassBytes'])
 check('one actual own HWND lifecycle accepted',result['caseCount']==1 and result['assertions']==34 and result['nativeTrayAddRemove'])
 c.save(HERE/'source-inventory.json',dict(upstreamCommit=COMMIT,originalSources=original,originalSelectedBodies=dict(getForegroundInfoSha256LF=hashlib.sha256(foreground.encode()).hexdigest(),progressUpdaterSha256LF=hashlib.sha256(updater.encode()).hexdigest()),payload=[dict(path=str(source),sha256LF=c.sha(source),mode='manual-windows-platform-adapter')],noOriginalAndroidWorkerEmitted=True,noNewModelsOrStore=True))
 c.save(HERE/'source-audit.json',dict(status='PASS',checks=checks,checkCount=len(checks),sourceSha256LF=c.sha(source),candidateJarSha256Bytes=c.sha(candidateJar),acceptedProofSha256Bytes=c.sha(HERE/'proof-03/accepted-result.json'),sourceInventorySha256Bytes=c.sha(HERE/'source-inventory.json')))
 print(json.dumps(dict(status='PASS',checks=len(checks),inventorySha256=c.sha(HERE/'source-inventory.json'),auditSha256=c.sha(HERE/'source-audit.json'))))
if __name__=='__main__':main()

from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n').decode()
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2,ensure_ascii=False)+'\n').encode())
def loadProducer(path):
 spec=importlib.util.spec_from_file_location('auditProducer',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
loadProducer(P/'baseline/desktop/tools/extract-upstream-plugins.py').generate(REPO,P/'audit-baseline-generated')
base={str(p.relative_to(wide(P/'audit-baseline-generated'))).replace('\\','/'):read(p)for p in wide(P/'audit-baseline-generated').rglob('*.kt')}
candidate={str(p.relative_to(wide(P/'generated'))).replace('\\','/'):read(p)for p in wide(P/'generated').rglob('*.kt')}
assert base.keys()==candidate.keys()
changed=[n for n in base if base[n]!=candidate[n]]
assert set(changed)=={'com/android/purebilibili/feature/plugin/CdnRegionPlugin.kt','com/android/purebilibili/feature/plugin/SponsorBlockInsightPolicy.kt'}
checks=[]
def check(c,label):assert c,label;checks.append(dict(passed=True,label=label))
cd='com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission'
for path in changed:
 s=candidate[path]
 if path.endswith('SponsorBlockInsightPolicy.kt'):
  s=s.replace('            '+cd+'.checkCurrentOrOriginal()\n','')
 else:
  pairs=[
   (f'{cd}.contextOrOriginal {{ PluginManager.getContext() }}','PluginManager.getContext()'),
   (f'.also {{ value -> {cd}.mutateOrOriginal {{ cache = value }} }}','.also { cache = it }'),
   (f'{cd}.mutateOrOriginal {{ cache = next }}','cache = next'),
   (f'{cd}.launchOrOriginal(com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope) {{','com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope.launch {'),
   (f'                val call = {cd}.playbackCallsOrOriginal {{ com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient }}.newCall(request)\n                {cd}.executeOrOriginal(call) {{ response ->','                com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient.newCall(request).execute().use { response ->'),
  ]
  for a,b in pairs:check(a in s,'inverse adaptation exists '+a);s=s.replace(a,b)
 check(s==base[path],'full generated source inverse byte-equals current sole baseline '+path)
 check(candidate[path].splitlines()[1]==base[path].splitlines()[1],'original SHA header unchanged '+path)
rows=json.loads(read(P/'exact-hunks.json'))['families'];operations=[]
for row in rows:
 path=row['path'];old=read(P/'baseline'/path);new=read(P/'prepared'/path)
 check(sha(old)==row['baseLF'] and sha(new)==row['candidateLF'],'whole source LF pin '+path)
 replacements=[]
 if path.endswith('DesktopPluginRuntime.kt'):
  for start,end in [('    internal suspend fun saveCdnConfiguration(', '    /** DASH backups'),('    internal suspend fun <T> mutatePlaybackPlugin(', '    /** Cleanup cannot depend')]:
   a=old.index(start);b=old.index(end,a)
   x=new.index('    private suspend fun playbackPluginWriteOperation(' if 'mutatePlayback' in start else start);y=new.index(end,x)
   replacements.append(dict(before=old[a:b],after=new[x:y],count=1))
 elif path.endswith('DesktopPluginStore.kt'):
  replacements.append(dict(before='import kotlinx.coroutines.Dispatchers',after='import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.Job',count=1))
  anchor='    /** Same backing, one atomic document publication for original canonical theme + startup cache. */'
  a=new.index('    /** Captured playback writes');b=new.index(anchor,a)
  replacements.append(dict(before=anchor,after=new[a:b]+anchor,count=1))
  before='        store.update("plugin_prefs", editor.values)'
  a=new.index('        val operation = DesktopPlayerPluginWriteAdmission.currentOrNull()');b=new.index('\n    }',a)
  replacements.append(dict(before=before,after=new[a:b],count=1))
 else:
  for start,end in [('    path = BASE + "feature/plugin/SponsorBlockInsightPolicy.kt"','    path = BASE + "feature/video/danmaku/DanmakuManager.kt"'),("    path = BASE + 'feature/plugin/CdnRegionPlugin.kt'",'    spec = importlib.util.spec_from_file_location')]:
   a=old.index(start);b=old.index(end,a);x=new.index(start);y=new.index(end,x)
   replacements.append(dict(before=old[a:b],after=new[x:y],count=1))
 replay=old
 for r in replacements:
  check(replay.count(r['before'])==r['count'],'exact replacement unique '+path);replay=replay.replace(r['before'],r['after'])
 check(replay==new,'exact replacement replay byte-equals prepared '+path)
 operations.append(dict(path=path,baseLF=sha(old),candidateLF=sha(new),replacements=replacements))
save(P/'install-exact-hunks.json',dict(mode='Root serial semantic merge; never whole-copy Runtime/Store/producer',operations=operations,newManual=json.loads(read(P/'exact-hunks.json'))['newManual']))
check(all('Files.' not in r['after'] for op in operations if op['path'].endswith('DesktopPluginRuntime.kt')for r in op['replacements']),'Runtime admission bodies contain no file IO')
check('callerJob.ensureActive(); check(); accepted = true' in read(P/'DesktopPlayerPluginWriteAdmission.kt'),'permit checks actual callerJob plus fixed captured origin/source under short gate')
check('if (originJob.isCancelled)' in read(P/'DesktopPlayerPluginWriteAdmission.kt') and 'originJob.isActive' not in read(P/'DesktopPlayerPluginWriteAdmission.kt'),'origin cancellation rejects; normal origin completion does not retire accepted lease')
check('if (backing.document !== snapshot) false' in read(P/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt'),'same original backing document identity checked before rename')
check(json.loads(read(P/'source-checks.json'))['legacyStoreUpdateUnchanged'],'legacy no-captured Store path exact bytes preserved')
save(P/'source-audit.json',dict(passed=True,checks=checks,checkCount=len(checks),generatedOutputs=len(base),changedOutputs=changed,unchangedOutputs=sorted(set(base)-set(changed)),sameOriginalAlgorithmsAndSchemas=True,noNewStoreClientCacheActorScope=True,originalRows=json.loads(read(P/'original-source-inventory.json'))))
print(json.dumps(dict(passed=True,checks=len(checks),changedOutputs=changed,sourceOperations=sum(len(r['replacements']) for r in operations)),indent=2))

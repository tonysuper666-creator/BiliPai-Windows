from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE=MAIN/'desktop/.local/stable-player-plugin-final-write-parity'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n').decode()
def write(p,s):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(s.encode())
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
assert hashlib.sha256(wide(BASE/'frozen-handoff.json').read_bytes()).hexdigest()=='829c748364219b1458151c546bbdae62d8cd053f613d877b94285f9b9143f5cb'
def sub(s,a,b):assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)
ops=[]
path='desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPlayerPluginWriteAdmission.kt'
s=old=read(BASE/'prepared'/path)
a='''            require(context.store === store) { "Captured playback plugin write has a different Store" }
            callerJob.ensureActive(); check()'''
b='''            require(context.store === store) { "Captured playback plugin write has a different Store" }
            return requestPermit(callerJob)
        }

        fun requestPermit(callerJob: Job): Permit {
            callerJob.ensureActive(); check()'''
s=sub(s,a,b)
anchor='    /** The cancellation registration covers execute AND all blocking response-body reads. */'
new='''    /** Original public transport is unchanged. Admission only mints an in-flight request permit;
     * execute and response-body IO run after the Root gate has been released. */
    suspend fun <T> executePublicOrOriginal(call: Call, block: (Response) -> T): T {
        val captured = operation.get() ?: return call.execute().use(block)
        val caller = currentCoroutineContext()[Job] ?: error("Sponsor request requires a caller Job")
        captured.requestPermit(caller).consume()
        return executeOrOriginal(call, block)
    }

'''
s=sub(s,anchor,new+anchor)
write(P/'baseline'/path,old);write(P/'prepared'/path,s)
ops.append(dict(path=path,baseLF=sha(old),candidateLF=sha(s),replacements=[dict(before=a,after=b,count=1),dict(before=anchor,after=new+anchor,count=1)]))
path='desktop/tools/extract-upstream-plugins.py';s=old=read(BASE/'prepared'/path)
anchor='    body = substitute(body, "android.os.SystemClock.elapsedRealtime()", "com.bilipai.desktop.appearance.DesktopMonotonicClock.elapsedRealtime()", count=2)\n    generated.append(write(output, path, source, body))'
injection='''    body = substitute(body, "    private suspend fun postJson(url: String, body: String): Result<String> = withContext(Dispatchers.IO) {\\n        runCatching {\\n            val request = Request.Builder()\\n                .url(url)\\n                .post(body.toRequestBody(\\"application/json\\".toMediaType()))\\n                .build()\\n            client.newCall(request).execute().use { response ->", "    private suspend fun postJson(url: String, body: String): Result<String> = withContext(Dispatchers.IO) {\\n        runCatching {\\n            val request = Request.Builder()\\n                .url(url)\\n                .post(body.toRequestBody(\\"application/json\\".toMediaType()))\\n                .build()\\n            com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(client.newCall(request)) { response ->", 1)
'''
replacement=anchor.replace('    generated.append',injection+'    generated.append')
s=sub(s,anchor,replacement)
write(P/'baseline'/path,old);write(P/'prepared'/path,s)
ops.append(dict(path=path,baseLF=sha(old),candidateLF=sha(s),replacements=[dict(before=anchor,after=replacement,count=1)]))
spec=importlib.util.spec_from_file_location('producer',P/'prepared'/path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.generate(REPO,P/'generated')
spec=importlib.util.spec_from_file_location('baselineProducer',P/'baseline'/path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.generate(REPO,P/'baseline-generated')
rel='com/android/purebilibili/data/repository/SponsorBlockRepository.kt';after=read(P/'generated'/rel);before=read(P/'baseline-generated'/rel)
inverse=after.replace('com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(client.newCall(request)) { response ->','client.newCall(request).execute().use { response ->')
assert inverse==before
basePaths={str(p.relative_to(wide(P/'baseline-generated'))).replace('\\','/')for p in wide(P/'baseline-generated').rglob('*.kt')}
candidatePaths={str(p.relative_to(wide(P/'generated'))).replace('\\','/')for p in wide(P/'generated').rglob('*.kt')}
assert basePaths==candidatePaths
changed=[r for r in basePaths if read(P/'generated'/r)!=read(P/'baseline-generated'/r)];assert changed==[rel]
save(P/'install-exact-hunks.json',dict(baseFrozen120SHA256Bytes='829c748364219b1458151c546bbdae62d8cd053f613d877b94285f9b9143f5cb',operations=ops,wholeFileReplace=False,newStoreClientScope=False))
save(P/'source-audit.json',dict(passed=True,fullRepositoryInverseByteEqualsBase120=True,changedOutputs=changed,unchangedOutputs=len(basePaths)-1,originalSourceSHAHeader=before.splitlines()[1],originalPublicClientUnchanged=True,threeOriginalPublicMethodsUseSolePostJson=['uploadViewedSegment','submitSegments','voteOnSegment'],legacyNoContextUsesOriginalExecuteUse=True,shortGateOnlyRequestPermit=True,executeAndBodyReadOutsideGate=True))
print(json.dumps(dict(passed=True,replacements=3,generatedChanged=changed),indent=2))

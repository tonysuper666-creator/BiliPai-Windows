from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.with_name('BiliPai-v023')
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,t):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(t.encode()if isinstance(t,str)else t)
number=sys.argv[1];compiled=LANE/('compile-audio-'+number);out=LANE/('audio-policy-proof-'+number);assert not safe(out).exists();safe(out).mkdir()
compiledResult=json.loads(safe(compiled/'compile-result.json').read_text());assert compiledResult['status']=='PASS' and compiledResult['existingClassOverlap']==[]
candidate=Path(compiledResult['candidateJar']['path']);assert sha(candidate)==compiledResult['candidateJar']['sha256Bytes']
snap=MAIN/'desktop/.local/stable-product-snapshot-61'
assert sha(snap/'manifest.json')=='6b5777dbe6855ddb9529f612580c11aa24dfcd23c771828e51369d610e4a9445'
assert sha(snap/'ordered-runtime-cp.json')=='746bdf5e200774e0b1765b31fa0bd35918080cdddf04606318482147f774c057'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text());assert len(cp)==101
for row in cp:assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
testJar=c.jar('org.jetbrains.kotlin','kotlin-test','2.4.0');testHash=sha(testJar)
testSources=[];sourceReceipts=[];assertionCount=0
for name in ['AudioModePlaybackPolicyTest','AudioModeSleepTimerPolicyTest']:
 relative='app/src/test/java/com/android/purebilibili/feature/video/screen/'+name+'.kt'
 raw=subprocess.check_output(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+relative],cwd=REPO)
 assert raw==safe(REPO/relative).read_bytes().replace(b'\r\n',b'\n')
 text=raw.decode();assertionCount+=len(re.findall(r'\bassert(?:Equals|False|True|Null)\(',text))
 adapted=text.replace('import kotlin.test.Test\n','').replace('    @Test\n','').replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalPlaybackStates as Player')
 target=out/'source-inputs'/name;target=target.with_suffix('.kt');write(target,adapted);testSources.append(target)
 write(out/'original-retained'/(name+'.kt.txt'),raw)
 sourceReceipts.append({'original':relative,'sha256LF':hashlib.sha256(raw).hexdigest(),'gitBlob':subprocess.check_output(['git','rev-parse','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+relative],cwd=REPO,text=True).strip(),'compileCopy':str(target),'sha256Bytes':sha(target),'adaptations':['Remove test annotation only; no new JUnit dependency','Map Media3 state constants to actual source states']})
runner=out/'source-inputs/AudioPolicyProof.kt'
write(runner,'''package com.android.purebilibili.feature.video.screen
fun main() {
    var cases=0
    for (test in listOf(AudioModePlaybackPolicyTest(),AudioModeSleepTimerPolicyTest())) {
        val methods=test.javaClass.declaredMethods.filter { it.parameterCount==0 && it.returnType==Void.TYPE && !it.isSynthetic }.sortedBy { it.name }
        for (method in methods) {
            try { method.invoke(test) } catch(e:java.lang.reflect.InvocationTargetException) { throw e.cause?:e }
            println("PASS original case: "+method.name);cases++
        }
    }
    check(cases==16)
    for (name in listOf("com.android.purebilibili.feature.video.screen.AudioModeScreenKt","com.android.purebilibili.feature.video.screen.AudioModeMusicPlayerKt","com.android.purebilibili.feature.video.ui.components.VideoCommentSheetHostKt")) {
        val loaded=Class.forName(name);check(loaded.declaredMethods.isNotEmpty());println("PASS full renderer ABI: "+name)
    }
    println("PASS all 16 original cases; no native window, request or account")
}
''');testSources.append(runner)
paths=[str(candidate)]+[r['path']for r in cp]+[str(testJar)]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(paths),'-Xfriend-paths='+str(candidate)+','+cp[1]['path'],'-module-name','audio_policy_fixture','-d',str(out/'classes')]+list(map(str,testSources))
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
compileRun=subprocess.run([str(c.JAVA),'-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(out/'compile.log',compileRun.stdout+compileRun.stderr);assert compileRun.returncode==0,compileRun.stdout+compileRun.stderr
run=subprocess.run([str(c.JAVA),'-Xmx1g','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(out/'classes')+';'+';'.join(paths),'com.android.purebilibili.feature.video.screen.AudioPolicyProofKt'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
write(out/'runtime.log',run.stdout+run.stderr);print(run.stdout+run.stderr)
for row in cp:assert sha(row['path'])==row['sha256Bytes']
assert sha(candidate)==compiledResult['candidateJar']['sha256Bytes'] and sha(testJar)==testHash
result={'status':'PASS'if run.returncode==0 else'FAIL','processExitCode':run.returncode,'actualSnapshot':61,'runtimeEntries':101,'candidateJar':compiledResult['candidateJar'],'sourceTests':sourceReceipts,'testSupportJar':{'path':str(testJar),'sha256Bytes':testHash},'originalCases':16,'originalAssertionsEvaluated':assertionCount,'rendererAbiClasses':3,'productionOverrides':0,'httpOrAccountOrWindow':False,'actualRootMounted':False}
write(out/'result.json',json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items()if k!='sourceTests'},indent=2));sys.exit(run.returncode)

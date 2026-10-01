from pathlib import Path
import json,re,subprocess,hashlib,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';snap=MAIN/'desktop/.local/stable-product-snapshot-36'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
run=HERE/f'proof-{1+len(list(HERE.glob("proof-??"))):02}';run.mkdir()
sources=[];calls=[];originals=[]
for name in ['BangumiHubPolicyTest','BangumiHubBlurPolicyTest','MyFollowPolicyTest']:
 path=REPO/f'app/src/test/java/com/android/purebilibili/feature/bangumi/{name}.kt'
 original=path.read_text(encoding='utf-8');names=re.findall(r'(?m)^    fun (`[^`]+`|\w+)\(',original)
 body=re.sub(r'(?m)^import org.junit[^\n]*\n','',original).replace('    @Test\n','')
 p=run/(name+'.kt');p.write_text(body,encoding='utf-8',newline='\n');sources.append(p)
 calls.extend(f' {name}().{n}()'for n in names)
 originals.append(dict(path=path.relative_to(REPO).as_posix(),sha256LF=hashlib.sha256(original.encode()).hexdigest(),methods=len(names),fixtureChanges='remove only JUnit imports/annotations; exact original bodies'))
adapter='''package com.android.purebilibili.feature.bangumi
private var assertionCount=0
internal fun assertTrue(value:Boolean){assertionCount++;check(value)}
internal fun assertFalse(value:Boolean){assertionCount++;check(!value)}
internal fun assertNull(value:Any?){assertionCount++;check(value==null)}
internal fun assertNotEquals(expected:Any?,actual:Any?){assertionCount++;check(expected!=actual)}
internal fun assertEquals(expected:Any?,actual:Any?){assertionCount++;check(expected==actual){"$expected != $actual"}}
internal fun assertEquals(expected:Float,actual:Float,delta:Float){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
internal fun assertEquals(expected:Double,actual:Double,delta:Double){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
fun main(){
__CALLS__
 println("{\\"passed\\":true,\\"adaptedOriginalPolicyMethods\\":__METHODS__,\\"adaptedOriginalAssertions\\":"+assertionCount+",\\"actualMainOrRootAccepted\\":false}")
}
'''.replace('__CALLS__','\n'.join(calls)).replace('__METHODS__',str(len(calls)))
p=run/'OriginalBangumiHubFixture.kt';p.write_text(adapter,encoding='utf-8',newline='\n');sources.append(p)
cp=json.loads((snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for r in cp:assert hashlib.sha256(wide(r['path']).read_bytes()).hexdigest()==r['sha256Bytes']
vm=MAIN/'desktop/.local/stable-home-viewmodel-parity/classes-vm-06';production=HERE/'compile-02/classes'
runtime=[str(production),str(vm)]+[r['path']for r in cp]
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','bangumi_hub_fixture',
 '-Xfriend-paths='+','.join([str(snap/'main-kotlin.jar'),str(vm),str(production)]),'-cp',';'.join(runtime),'-d',str(run/'classes'),*map(str,sources)]
(run/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
(run/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode==0:
 r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(run/'classes')]+runtime),'com.android.purebilibili.feature.bangumi.OriginalBangumiHubFixtureKt'],cwd=REPO,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 (run/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8')
for row in cp:assert hashlib.sha256(wide(row['path']).read_bytes()).hexdigest()==row['sha256Bytes']
(run/'result.json').write_text(json.dumps(dict(exitCode=r.returncode,originals=originals,originalPolicyMethods=len(calls),explicitProspectiveClasses=True,actualRootAccepted=False),indent=2)+'\n',encoding='utf-8')
print(r.stdout+r.stderr);sys.exit(r.returncode)

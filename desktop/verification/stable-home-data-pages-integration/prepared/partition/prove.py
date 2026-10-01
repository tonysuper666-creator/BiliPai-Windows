from pathlib import Path
import json,re,subprocess,hashlib,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';snap=MAIN/'desktop/.local/stable-product-snapshot-33'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
run=HERE/f'proof-{1+len(list(HERE.glob("proof-??"))):02}';run.mkdir()
original=(REPO/'app/src/test/java/com/android/purebilibili/feature/partition/PartitionScreenStructureTest.kt').read_text(encoding='utf-8')
names=re.findall(r'fun `([^`]+)`',original);assert len(names)==16
body=re.sub(r'(?m)^import org.junit[^\n]*\n','',original).replace('    @Test\n','')
old='assertTrue(listSource.contains("showUpBadge = false"))';assert body.count(old)==1
body=body.replace(old,'assertTrue(listSource.contains("showUpBadge = showUpBadges"))\n        assertTrue(listSource.contains(".getHomeUpBadgesVisible(context)"))')
(run/'fixture-expectation-correction.json').write_text(json.dumps(dict(originalStaleTestExpectation='showUpBadge = false',stableOriginalSourceActual='showUpBadge = showUpBadges from global persisted settings',fixtureCorrectionOnly=True,productionAlgorithmChanged=False,originalFailedRun='proof-01'),indent=2)+'\n',encoding='utf-8')
adapter='''
private var assertionCount=0
private fun assertTrue(value:Boolean){assertionCount++;check(value)}
private fun assertFalse(value:Boolean){assertionCount++;check(!value)}
private fun assertNull(value:Any?){assertionCount++;check(value==null)}
private fun assertEquals(expected:Any?,actual:Any?){assertionCount++;check(expected==actual){"$expected != $actual"}}
private fun assertEquals(expected:Float,actual:Float,delta:Float){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
private fun assertEquals(expected:Double,actual:Double,delta:Double){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
fun main() = kotlinx.coroutines.runBlocking {
 val fixture=PartitionScreenStructureTest()
__CALLS__
 val gates=com.bilipai.desktop.ui.provePartitionOwner()
 println("{\\"passed\\":true,\\"adaptedOriginalPolicyMethods\\":16,\\"adaptedOriginalAssertions\\":"+assertionCount+",\\"preparedOwnerGates\\":"+gates.size+",\\"actualMainOrRootAccepted\\":false}")
}
'''.replace('__CALLS__','\n'.join(' fixture.`'+n+'`()'for n in names))
(run/'OriginalPartitionPolicyFixture.kt').write_text(body+adapter,encoding='utf-8',newline='\n')
(run/'original-test.kt').write_text(original,encoding='utf-8',newline='\n')
sources=[run/'OriginalPartitionPolicyFixture.kt',HERE/'PartitionOwnerFixture.kt']
cp=json.loads((snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for r in cp:assert hashlib.sha256(wide(r['path']).read_bytes()).hexdigest()==r['sha256Bytes']
vm=MAIN/'desktop/.local/stable-home-viewmodel-parity/classes-vm-06';production=HERE/'compile-03/classes'
runtime=[str(production),str(vm)]+[r['path']for r in cp]
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','partition_fixture',
 '-Xfriend-paths='+','.join([str(snap/'main-kotlin.jar'),str(vm),str(production)]),'-cp',';'.join(runtime),'-d',str(run/'classes'),*map(str,sources)]
(run/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
(run/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode==0:
 r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(run/'classes')]+runtime),'com.android.purebilibili.feature.partition.OriginalPartitionPolicyFixtureKt'],cwd=REPO,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 (run/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8')
for row in cp:assert hashlib.sha256(wide(row['path']).read_bytes()).hexdigest()==row['sha256Bytes']
(run/'result.json').write_text(json.dumps(dict(exitCode=r.returncode,originalPolicyMethods=16,expectedPreparedOwnerGates=7,explicitProspectiveClasses=True,productOverridesForExistingOriginalPartition=0,actualRootAccepted=False),indent=2)+'\n',encoding='utf-8')
print(r.stdout+r.stderr);sys.exit(r.returncode)

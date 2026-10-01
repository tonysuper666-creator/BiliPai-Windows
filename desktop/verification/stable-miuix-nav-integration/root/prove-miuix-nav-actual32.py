from pathlib import Path
import ast,hashlib,json,importlib.util,subprocess,struct,zipfile,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-32'
OUT=MAIN/'desktop/.local/stable-miuix-nav-main32-proof'
assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,h):
    b=read(p);assert sha(b)==h,p;return b
def save(p,v):p.write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
meta=json.loads(pin(SNAP/'manifest.json','28118f431eb78418791426c914690143faedeeaef14cc11fb216ac648caad413'))
cp_raw=pin(SNAP/'ordered-runtime-cp.json','aba962efa0ce8cdd882f692e87a95d533e229766e091b4b5fa6f9d68f8b3101c');cp=json.loads(cp_raw)
assert len(cp)==97 and meta['wholeCandidateClassesPassed']
for r in cp:pin(r['path'],r['sha256Bytes'])
fork=next(r['path'] for r in cp if 'miuix5157-jvm-' in r['path'])
assert sha(read(fork))=='c3fe8751624fec7702756a3a25e8af745f3e6aad6d53abff80c52b419d137451'
parser=next(n for n in ast.parse(read(HERE/'audit-jvm-method-names.py').decode()).body if isinstance(n,ast.FunctionDef) and n.name=='parse')
ns={'struct':struct};exec(compile(ast.Module(body=[parser],type_ignores=[]),str(HERE/'audit-jvm-method-names.py'),'exec'),ns)
issues=[];class_count=0
with zipfile.ZipFile(wide(fork)) as z:
    for name in z.namelist():
        if name.endswith('.class'):
            class_count+=1;found=ns['parse'](z.read(name))
            if found:issues.append(dict(className=name,issues=found))
assert not issues,issues
OUT.mkdir();save(OUT/'library-static-method-name-audit.json',dict(staticOnly=True,classes=class_count,issues=issues,librarySha256=sha(read(fork))))
fixture=r'''package actual32.navReceipt

import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.nav.runtime.*
import top.yukonga.miuix.kmp.nav.state.NavEntryViewModelStores
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.io.File
import java.security.MessageDigest

@Serializable sealed interface ReceiptRoute:NavKey {
    @Serializable data object Home:ReceiptRoute
    @Serializable data class Video(val bvid:String,val cid:Long):ReceiptRoute
    @Serializable data class Space(val mid:Long):ReceiptRoute
}
private class CountedVM(private val cleared:()->Unit):ViewModel() {
    override fun onCleared()=cleared()
}
fun main(args:Array<String>) {
    var assertions=0
    fun expect(value:Boolean,label:String){check(value){label};assertions++}
    val home=ReceiptRoute.Home
    val first=ReceiptRoute.Video("BV-fixture-A",100)
    val otherPart=ReceiptRoute.Video("BV-fixture-A",101)
    val space=ReceiptRoute.Space(11)
    val stack=navBackStackOf(home)
    val nav=NavController(stack)
    expect(nav.backStack===stack,"same original list authority")
    expect(!nav.pop()&&stack==listOf(home),"root survives pop")
    nav.push(first);expect(stack==listOf(home,first),"original push")
    nav.push(otherPart);expect(stack==listOf(home,first,otherPart),"CID remains full route identity")
    nav.replace(space);expect(stack==listOf(home,first,space),"original replace")
    nav.popUntil {it==first};expect(stack==listOf(home,first),"original popUntil")
    expect(nav.pop()&&stack==listOf(home),"original pop")
    nav.popUntil {false};expect(stack==listOf(home),"unmatched predicate retains root")
    val empty=navBackStackOf();val emptyNav=NavController(empty)
    emptyNav.replace(first);expect(empty==listOf(first),"replace empty adds")
    expect(!emptyNav.pop(),"single root never pops")
    nav.push(first);nav.push(otherPart);nav.push(space)
    val saver=navBackStackSaver(serializer<List<ReceiptRoute>>())
    val scope=object:SaverScope {override fun canBeSaved(value:Any)=true}
    val encoded=requireNotNull(with(saver){scope.save(stack)})
    val restored=requireNotNull(saver.restore(encoded))
    expect(restored==stack,"original sealed route persistence")
    expect(restored!==stack,"restore owns restored snapshot list")
    expect(encoded.contains("BV-fixture-A")&&encoded.contains("101"),"identity payload retained")
    expect(runCatching{saver.restore("broken-json")}.isFailure,"malformed state rejected")
    val unknown=encoded.replace("ReceiptRoute.Video","UnregisteredRoute")
    expect(unknown!=encoded&&runCatching{saver.restore(unknown)}.isFailure,"unregistered route rejected")
    val cases=listOf(
        Triple(emptyList<Any>(),emptyList(),NavChange.None),
        Triple(emptyList(),listOf("a"),NavChange.Push),
        Triple(listOf("a"),listOf("a","b","c"),NavChange.MultiPush(2)),
        Triple(listOf("a","b"),listOf("a"),NavChange.Pop),
        Triple(listOf("a","b","c"),listOf("a"),NavChange.MultiPop(2)),
        Triple(listOf("a","b"),listOf("a","c"),NavChange.Replace),
        Triple(listOf("a"),listOf("b"),NavChange.Replace),
        Triple(listOf("a","b"),listOf("c","d"),NavChange.ReplaceAll))
    cases.forEach {(old,new,expected)->expect(navReconcile(old,new)==expected,"original reconcile $old -> $new")}
    expect(commonPrefixLength(listOf(first,space),listOf(first,otherPart))==1,"value-equal prefix identity")
    var clears=0
    val stores=NavEntryViewModelStores()
    fun vm(key:Any):CountedVM {
        val store=stores.storeFor(key)
        val owner=object:ViewModelStoreOwner {override val viewModelStore=store}
        return ViewModelProvider.create(owner,viewModelFactory {initializer {CountedVM {clears++}}})[CountedVM::class]
    }
    val homeVM=vm(home);val firstVM=vm(first)
    expect(vm(home)===homeVM&&vm(first)===firstVM,"culling/composition absence retains per-entry VMs")
    expect(homeVM!==firstVM,"entry stores isolated")
    stores.clearStore(first);expect(clears==1,"removed entry cleared once")
    stores.clearStore(first);expect(clears==1,"repeated removal idempotent")
    expect(vm(home)===homeVM&&clears==1,"other retained entry survives")
    expect(vm(first)!==firstVM,"reentered entry gets fresh VM")
    stores.clearAll();expect(clears==3,"display retirement clears remaining entries")
    stores.clearAll();expect(clears==3,"display retirement idempotent")
    val types=listOf(NavController::class.java,NavKey::class.java,NavEntryViewModelStores::class.java,
        Class.forName("top.yukonga.miuix.kmp.nav.core.NavBackStackKt"),
        Class.forName("top.yukonga.miuix.kmp.nav.core.NavDisplayKt"),
        Class.forName("top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandlerKt"),
        Class.forName("top.yukonga.miuix.kmp.nav.runtime.NavReconcilerKt"))
    val codeSources=types.map {type ->
        val bytes=requireNotNull(type.getResourceAsStream("/"+type.name.replace('.','/')+".class")).use {it.readBytes()}
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        "{\"class\":\"${type.name}\",\"path\":\"${File(type.protectionDomain.codeSource.location.toURI()).absolutePath.replace("\\","/")}\",\"classSha256Bytes\":\"$digest\"}"
    }
    val result="{\"status\":\"PASS\",\"assertions\":$assertions,\"productionOverrides\":0,\"actualRootOrDispatcherMounted\":false,\"codeSources\":[${codeSources.joinToString()}]}"
    File(args.single(),"result.json").writeText(result+"\n");println(result)
}
'''
(OUT/'NavReceipt.kt').write_text(fixture,encoding='utf-8',newline='\n')
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
serialization=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
target=OUT/'fixture.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(serialization),'-module-name','actual_miuix_nav_receipt',
    '-Xfriend-paths='+fork,'-cp',';'.join(r['path'] for r in cp),'-d',str(target),str(OUT/'NavReceipt.kt')]
(OUT/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
(OUT/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n');print(p.stdout+p.stderr);p.check_returncode()
with zipfile.ZipFile(target) as z:own={n for n in z.namelist() if n.endswith('.class')}
overlaps=[]
for r in cp:
    with zipfile.ZipFile(wide(r['path'])) as z:overlaps.extend(own.intersection(z.namelist()))
assert not overlaps,overlaps
run=OUT/'runtime';run.mkdir()
p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(target)+';'+';'.join(r['path'] for r in cp),
    'actual32.navReceipt.NavReceiptKt',str(run)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
(OUT/'run.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n');print(p.stdout+p.stderr);p.check_returncode()
result=json.loads(read(run/'result.json'));assert result['status']=='PASS'
for row in result['codeSources']:
    assert Path(row['path']).absolute()==Path(fork).absolute(),row
    with zipfile.ZipFile(wide(fork)) as z:assert sha(z.read(row['class'].replace('.','/')+'.class'))==row['classSha256Bytes']
for r in cp:pin(r['path'],r['sha256Bytes'])
save(OUT/'accepted.json',dict(passed=True,actualProductPhase=32,librarySHA256=sha(read(fork)),fixtureSHA256=sha(fixture.encode()),
    fixtureJarSHA256=sha(read(target)),assertions=result['assertions'],productionOverrides=0,all97RuntimeEntriesPinnedBeforeAndAfter=True,
    classFQNOverlaps=overlaps,loadedCodeSources=result['codeSources'],actualRootNavigationAndWindowDispatcherAccepted=False))
print('ACTUAL32_NAV_PASS',sha(read(OUT/'accepted.json')))

package actual32.navReceipt

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
    expect(!nav.pop()&&stack.toList()==listOf(home),"root survives pop")
    nav.push(first);expect(stack.toList()==listOf(home,first),"original push")
    nav.push(otherPart);expect(stack.toList()==listOf(home,first,otherPart),"CID remains full route identity")
    nav.replace(space);expect(stack.toList()==listOf(home,first,space),"original replace")
    nav.popUntil {it==first};expect(stack.toList()==listOf(home,first),"original popUntil")
    expect(nav.pop()&&stack.toList()==listOf(home),"original pop")
    nav.popUntil {false};expect(stack.toList()==listOf(home),"unmatched predicate retains root")
    val empty=navBackStackOf();val emptyNav=NavController(empty)
    emptyNav.replace(first);expect(empty.toList()==listOf(first),"replace empty adds")
    expect(!emptyNav.pop(),"single root never pops")
    nav.push(first);nav.push(otherPart);nav.push(space)
    val saver=navBackStackSaver(serializer<List<ReceiptRoute>>())
    val scope=object:SaverScope {override fun canBeSaved(value:Any)=true}
    val encoded=requireNotNull(with(saver){scope.save(stack)})
    val restored=requireNotNull(saver.restore(encoded))
    expect(restored.toList()==stack.toList(),"original sealed route persistence")
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

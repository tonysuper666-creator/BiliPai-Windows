package com.bilipai.desktop.diagnostics.ownerWatcherProof

import com.bilipai.desktop.diagnostics.DesktopNativeTextShare
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

fun main(args: Array<String>) = runBlocking {
    val out=Path.of(args[0]);Files.createDirectories(out)
    val candidate=Path.of(args[1]).toRealPath()
    var assertions=0
    fun verify(value:Boolean,text:String){check(value){text};assertions++}
    val actorType=DesktopNativeTextShare::class.java
    fun field(name:String)=actorType.getDeclaredField(name).apply{isAccessible=true}
    val apiType=Class.forName("com.bilipai.desktop.diagnostics.DesktopNativeTextShare\$Api")
    val watcherType=Class.forName("com.bilipai.desktop.diagnostics.DesktopNativeTextShare\$share\$2\$2")
    val watcherConstructor=watcherType.declaredConstructors.single().apply{isAccessible=true}
    val cases=mutableListOf<JsonObject>()
    for(mode in listOf("old-page-retired","live-then-epoch-retired","old-token-replaced")){
        val owned=AtomicBoolean(mode=="live-then-epoch-retired")
        val states=AtomicInteger();val retires=AtomicInteger();val nativeLoads=AtomicInteger();val retiredOnEdt=AtomicBoolean()
        val actor=DesktopNativeTextShare(nativeDll={nativeLoads.incrementAndGet();error("no DLL/native session allowed")},expectedSha256="fixture",window={null})
        val proxy=Proxy.newProxyInstance(apiType.classLoader,arrayOf(apiType)){_,method,values->
            when(method.name){
                "BilipaiShareState"->{states.incrementAndGet();(values!![1] as IntByReference).value=0;0}
                "BilipaiShareRetire"->{verify(values!![0]==77L,"only this actor active token retired");retires.incrementAndGet();retiredOnEdt.set(SwingUtilities.isEventDispatchThread());(values[1] as IntByReference).value=1;(values[2] as IntByReference).value=0;0}
                "toString"->"task-only native API spy"
                "hashCode"->77
                "equals"->false
                else->error("unexpected native method "+method.name)
            }
        }
        // Task-only ABI spy injection. No producer body is replaced; execute the actual
        // compiled watcher coroutine generated from this candidate's sole precise hunk.
        field("api\$delegate").set(actor,lazy{proxy})
        field("active").set(actor,77L)
        val actorScope=field("scope").get(actor) as CoroutineScope
        val token=if(mode=="old-token-replaced")66L else 77L
        @Suppress("UNCHECKED_CAST")
        val actualWatcher=watcherConstructor.newInstance(actor,token,{owned.get()},null) as suspend CoroutineScope.()->Unit
        val watcher=actorScope.launch(block=actualWatcher)
        field("watcher").set(actor,watcher)
        try{
            if(mode=="live-then-epoch-retired"){
                withTimeout(3500){while(states.get()==0)delay(10)}
                verify(field("active").get(actor)==77L&&retires.get()==0,"current owner preserves native token")
                owned.set(false)
            }
            withTimeout(3500){watcher.join()}
            if(mode=="old-token-replaced"){
                verify(field("active").get(actor)==77L,"old watcher does not retire replacement token")
                verify(states.get()==0&&retires.get()==0,"old watcher does not probe/copy current token")
            }else{
                verify(field("active").get(actor)==null&&retires.get()==1,"actual owner-failed watcher retires through original closeActive")
                verify(retiredOnEdt.get(),"existing closeActive uses original Swing dispatcher")
                verify(actor.error.value==null,"ownership retirement is not a UI failure")
            }
            verify(nativeLoads.get()==0,"no DLL load or real native API execution")
            cases+=buildJsonObject{put("mode",mode);put("stateCalls",states.get());put("retireCalls",retires.get());put("nativeDllLoads",nativeLoads.get())}
        }finally{actor.shutdown()}
    }
    val codeSources=mutableListOf<JsonObject>()
    for(type in listOf(actorType,watcherType)){
        val location=Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath();verify(location==candidate,"actual candidate actor/watcher code source")
        val bytes=type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}
        codeSources+=buildJsonObject{put("class",type.name);put("path",location.toString());put("sha256ClassBytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})}
    }
    val result=buildJsonObject{put("status","PASS");put("caseCount",cases.size);put("assertions",assertions);put("cases",JsonArray(cases));put("actualCodeSources",JsonArray(codeSources));put("actualCompiledWatcherExecuted",true);put("taskOnlyNativeApiSpy",true);put("nativePaneOpened",false);put("nativeCppDataRequestedInvoked",false);put("noHTTP",true);put("noHWND",true);put("noAccountReads",true);put("notSynchronousOwnerToNativeCallbackAdmissionProof",true)}
    Files.writeString(out.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result));println(result)
}

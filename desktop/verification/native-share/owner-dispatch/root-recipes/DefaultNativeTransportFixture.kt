@file:Suppress("DEPRECATION")
package com.bilipai.desktop.diagnostics.defaultnativeproof

import com.android.purebilibili.core.util.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

private val checks=mutableListOf<String>()
private fun verify(name:String,value:Boolean){check(value){name};checks+=name;println("PASS $name")}
private fun sha(path:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString(""){"%02x".format(it)}
private class Fence:SecurityManager(){val attempts=AtomicInteger();override fun checkPermission(p:Permission){}
    override fun checkConnect(h:String,p:Int){attempts.incrementAndGet();error("No network")}
    override fun checkListen(p:Int){attempts.incrementAndGet();error("No listen")}
    override fun checkMulticast(a:java.net.InetAddress){attempts.incrementAndGet();error("No multicast")}
    override fun checkExec(c:String){attempts.incrementAndGet();error("No process")}}
private fun cacheIsEmpty(root:Path):Boolean {
    val cache=root.resolve("cache/diagnostic-share")
    if(!Files.exists(cache))return true
    return try{Files.walk(cache).use{it.noneMatch(Files::isRegularFile)}}
    catch(failure:java.io.UncheckedIOException){if(failure.cause is NoSuchFileException)false else throw failure}
    catch(_:NoSuchFileException){false}
}

/** Actual default Main transport and compiled asset hash, without a window or ShareShow. */
fun main(args:Array<String>):Unit=runBlocking {
    val case=args[0];val out=Path.of(args[1]);Files.createDirectories(out)
    val root=Files.createTempDirectory("default-native-transport-")
    val actualResources=Path.of(args[2]);val originalDll=actualResources.resolve("native/windows-x64/bilipai-diagnostic-share.dll")
    val expected="2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514"
    verify("compiled Main generated hash is the reviewed newly rebuilt production DLL",DesktopNativeDiagnosticShareAssetHash.sha256==expected && sha(originalDll)==expected)
    val classSource=Path.of(DesktopNativeCrashShare::class.java.protectionDomain.codeSource.location.toURI())
    verify("default transport executes actual compiled Main Kotlin artifact",sha(classSource)=="bbe9ab8c995d866fa52fe6b4b0b42028a5a98fbd587b3bb8512d94023c28eff5")
    if(case=="hash-mismatch"){
        val resources=root.resolve("tampered-resources");val dll=resources.resolve("native/windows-x64/bilipai-diagnostic-share.dll")
        Files.createDirectories(dll.parent);val bytes=Files.readAllBytes(originalDll);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();Files.write(dll,bytes)
        check(sha(dll)!=expected);System.setProperty("compose.application.resources.dir",resources.toString())
    }else System.setProperty("compose.application.resources.dir",actualResources.toString())
    val store=DesktopPluginStore(root)
    val writer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,LinkedBlockingQueue<Runnable>()){r->Thread(r,"fixture-default-native-actor").apply{isDaemon=true}}
    val actor=DesktopDiagnostics(store,"fixture-default-native",writer=writer)
    val dllCalls=AtomicInteger();val windowCalls=AtomicInteger()
    val provider=DesktopNativeCrashShare(
        nativeDll={dllCalls.incrementAndGet();Path.of(requireNotNull(System.getProperty("compose.application.resources.dir")),"native","windows-x64","bilipai-diagnostic-share.dll")},
        expectedSha256=DesktopNativeDiagnosticShareAssetHash.sha256,
        window={windowCalls.incrementAndGet();null},diagnostics=actor)
    val lifecycle=DesktopDiagnosticLifecycle(actor,provider)
    val fence=Fence();System.setSecurityManager(fence)
    try{
        verify("Main provider construction keeps native initialization lazy",dllCalls.get()==0 && windowCalls.get()==0)
        check(actor.persistLocalCrash(IllegalStateException("synthetic-task-only-default-native-crash")));actor.flush()
        val snapshot=resolveCrashSnapshotFile(root.toFile()).toPath();val marker=resolveCrashSnapshotMarkerFile(root.toFile()).toPath()
        val original=Files.readAllBytes(snapshot)
        when(case){
            "missing-window"->{
                verify("actual WinRT prepare and state reach the Main window callback before safe fallback",!provider.shareSnapshot() && dllCalls.get()==1 && windowCalls.get()==1)
                actor.flush()
                verify("default native retirement removes the actor lease when no window granted data",cacheIsEmpty(root))
            }
            "repeat-no-window"->{
                repeat(40){index->
                    check(!provider.shareSnapshot());actor.flush()
                    check(windowCalls.get()==index+1){"prepare did not reach window callback at round $index"}
                    check(cacheIsEmpty(root)){"private actor lease remained at round $index"}
                }
                verify("40 actual default native prepare state and retire cycles exceed the prior native session cap",windowCalls.get()==40 && cacheIsEmpty(root))
                verify("the actual native DLL is loaded only once per provider",dllCalls.get()==1)
            }
            "hash-mismatch"->{
                verify("tampered asset fails before native prepare and Main window access",!provider.shareSnapshot() && dllCalls.get()==1 && windowCalls.get()==0 && provider.error.value!=null)
                actor.flush();verify("hash rejection retires the actor copy without touching crash evidence",cacheIsEmpty(root))
            }
            "retired-owner"->{
                provider.retire()
                verify("retired Main owner rejects work before native initialization or window access",!provider.shareSnapshot() && dllCalls.get()==0 && windowCalls.get()==0)
                verify("retired owner creates no actor share lease",cacheIsEmpty(root))
            }
            else->error("unknown case")
        }
        verify("default transport leaves original crash bytes and pending marker intact",Files.readAllBytes(snapshot).contentEquals(original) && Files.exists(marker))
        lifecycle.shutdownForRestore();lifecycle.shutdownForRestore()
        verify("default Main owner shutdown is idempotent and closes future admission",!provider.shareSnapshot() && cacheIsEmpty(root))
        verify("original staged Main DLL remains byte identical after acceptance",sha(originalDll)==expected)
        verify("no network listener multicast process or share receiver is used",fence.attempts.get()==0)
        out.resolve("result.json").toFile().writeText(buildJsonObject{
            put("passed",true);put("case",case);put("checks",JsonArray(checks.map(::JsonPrimitive)));put("taskRoot",root.toString())
            put("defaultMainTransport",true);put("actualMainKotlinJarSha256Bytes",sha(classSource));put("nativeDllSha256Bytes",sha(originalDll))
            put("dllPathCalls",dllCalls.get());put("windowCalls",windowCalls.get());put("networkAttempts",fence.attempts.get())
            put("HWND",false);put("ShareShow",false);put("ShareUI",false);put("DataRequested",false);put("SetStorageItems",false)
            put("realReceiver",false);put("MainShellExecuted",false);put("packageAcceptance",false)
        }.toString())
    }finally{lifecycle.shutdownForRestore();System.setSecurityManager(null)}
}

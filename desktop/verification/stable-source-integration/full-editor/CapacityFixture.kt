package com.bilipai.desktop.ui.editorFullUiProof

import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]).toRealPath()
    val candidate = Path.of(args[1]).toRealPath()
    val main = Path.of(args[2]).toRealPath()
    var assertions = 0
    fun prove(condition: Boolean, label: String) { check(condition) { label }; assertions++ }
    val store = DesktopSessionStore.temporary()
    store.saveAccount(mapOf("SESSDATA" to "declared-ui-capacity", "bili_jct" to "declared-capacity-csrf"), AccountSummary(42L,"Declared UI capacity",""))
    val repository = DesktopRepository(store)
    var requests = 0
    val client = repository.httpClient.newBuilder().addInterceptor { requests++; error("Any transport denied in UI capacity fixture") }.build()
    repository.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(repository, client)
    val operations = DesktopDynamicCardOperations(repository)
    val paths = (0 until 20).map { root.resolve("image-$it.png").also { file -> Files.write(file, byteArrayOf(7)) } }
    try {
        for ((type, expected) in listOf(DesktopDynamicGallerySelection::class.java to candidate,
            DesktopDynamicEditorSelectedImages::class.java to candidate, DesktopDynamicCardOperations::class.java to candidate,
            DesktopSessionStore::class.java to main, DesktopRepository::class.java to main)) {
            prove(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()==expected,"Declared candidate/Main04 class source ${type.name}")
        }
        prove(repository.dynamicCacheSessionGuard === store,"Sole actual SessionStore admission")
        DesktopDynamicEditorSelectedImages(operations::isOwned, operations::withOwnedEditorImageAdmission).use { selected ->
            val gallery=DesktopDynamicGallerySelection(selected,operations::isOwned)
            val published=gallery.acceptResult(true,paths.first(),paths,18)
            prove(published.size==18 && published==paths.take(18).map { it.toUri().toString() },"Original gallery ordered distinct/take accepts 18 for publishing")
            prove(selected.accept(paths).size==18,"Selected files adapter cap reaches 18")
            prove(gallery.acceptResult(true,paths.first(),paths,9)==published.take(9),"Same adapter retains caller's nine-image comment cap")
            prove(gallery.acceptResult(true,paths.first(),paths,1)==published.take(1),"Single gallery result remains first selected source")
            prove(gallery.acceptResult(false,paths.first(),paths,18).isEmpty(),"Cancelled picker adds no file handles")
            prove(runCatching { gallery.acceptResult(true,paths.first(),paths,19) }.exceptionOrNull() is IllegalArgumentException,"Adapter rejects cap above original 18")
            val body=selected.read(published.last()).third
            prove(body.contentLength()==1L && body.isOneShot(),"Eighteenth selected image reaches existing streaming provider")
            selected.close()
            prove(runCatching { gallery.acceptResult(true,paths.first(),paths,18) }.exceptionOrNull() is CancellationException,"Closed selected owner rejects further admission")
        }
        DesktopDynamicEditorSelectedImages(operations::isOwned, operations::withOwnedEditorImageAdmission).use { selected ->
            val gallery=DesktopDynamicGallerySelection(selected,operations::isOwned)
            store.saveAccount(mapOf("SESSDATA" to "declared-ui-capacity-replaced", "bili_jct" to "declared-capacity-csrf-new"),AccountSummary(42L,"Same MID replacement",""))
            prove(runCatching { gallery.acceptResult(true,paths.first(),paths,18) }.exceptionOrNull() is CancellationException,"Actual Store same-MID generation transition retires old gallery owner")
        }
        prove(requests==0,"UI capacity proof makes no transport calls")
        Files.writeString(root.resolve("result.json"),"""{"passed":true,"cases":3,"assertions":$assertions,"sourceCap":18,"commentCap":9,"sameSelectedAdapter":true,"actualMain04StoreClasses":true,"transportCalls":$requests,"credentialsSerialized":false,"realChooser":false,"HWND":false,"UIInteractionMeasured":false}""")
        println("PASS full UI capacity boundary: 18 publish / 9 comment / retired owner; $assertions assertions")
    } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}

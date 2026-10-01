package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args.single())
    Files.createDirectories(root)
    val store = DesktopPluginStore(root)
    val lock = Any(); var alive = true
    val preferences = DesktopImageSaveLocationPreferences(store) { block ->
        synchronized(lock) { if (!alive) false else { block(); true } }
    }
    val uri = Path.of("D:\\图片 保存\\B站").toUri().toString()
    store.update("settings", mapOf("unrelated" to JsonPrimitive("retained")))
    val cases = mutableListOf<String>()
    check(selectAndRememberDesktopImageSaveDirectory(preferences, { Path.of("D:\\图片 保存\\B站") }, { alive }))
    check(preferences.getImageSaveTreeUriSync() == uri && preferences.getImageSaveTreeUri().first() == uri)
    val reopened = DesktopImageSaveLocationPreferences(DesktopPluginStore(root)) { block -> block(); true }
    check(reopened.getImageSaveTreeUriSync() == uri)
    check(store.preferences("settings")["unrelated"] == JsonPrimitive("retained"))
    cases += "approved fake chooser stores file URI in same real global settings and flow/reopened facade agree"
    check(!selectAndRememberDesktopImageSaveDirectory(preferences, { null }, { alive }))
    check(preferences.getImageSaveTreeUriSync() == uri)
    cases += "chooser cancellation preserves prior directory"
    val started = CompletableDeferred<Unit>(); val selected = CompletableDeferred<Path?>()
    val pending = async {
        runCatching { selectAndRememberDesktopImageSaveDirectory(preferences,
            { started.complete(Unit); selected.await() }, { alive }) }
    }
    started.await(); synchronized(lock) { alive = false }
    selected.complete(Path.of("D:\\late"))
    check(pending.await().exceptionOrNull() is CancellationException)
    check(preferences.getImageSaveTreeUriSync() == uri)
    cases += "late chooser after settings intent close does not persist or replace prior value"
    synchronized(lock) { alive = true }
    resetDesktopImageSaveDirectory(preferences) { alive }
    check(preferences.getImageSaveTreeUriSync() == null && preferences.getImageSaveTreeUri().first() == null)
    check("image_save_tree_uri" !in store.preferences("settings"))
    check(store.preferences("settings")["unrelated"] == JsonPrimitive("retained"))
    cases += "restore default removes original key only without a second account store"
    store.freezeWrites()
    val blocked = runCatching { selectAndRememberDesktopImageSaveDirectory(preferences, { Path.of("D:\\after-restore") }, { alive }) }
    check(blocked.isFailure && preferences.getImageSaveTreeUriSync() == null)
    cases += "existing store restore freeze rejects a later chooser even with live UI flag"
    fun q(s: String) = "\""+s.replace("\\", "\\\\").replace("\"", "\\\"")+"\""
    println("{\"passed\":true,\"cases\":[${cases.joinToString(",") { q(it) }}],\"storeCodeSource\":${q(DesktopPluginStore::class.java.protectionDomain.codeSource.location.toString())},\"adapterCodeSource\":${q(Class.forName("com.bilipai.desktop.settings.DesktopImageSavePathSettingsKt").protectionDomain.codeSource.location.toString())},\"actualWindow\":false,\"mainIntegrated\":false}")
}

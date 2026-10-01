package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private var assertions = 0
private fun verify(value: Boolean, message: String) { check(value) { message }; assertions++ }
private fun backing(store: DesktopPluginStore): Any = DesktopPluginStore::class.java.getDeclaredField("backing").run {
    isAccessible = true; get(store)
}
private fun noTemps(root: Path): Boolean = Files.list(root).use { it.noneMatch { p -> p.fileName.toString().endsWith(".tmp") } }
private fun content(root: Path) = Files.readString(root.resolve("plugin-settings.json"))
private fun await(latch: CountDownLatch) = check(latch.await(5, TimeUnit.SECONDS)) { "Fixture latch timed out" }
private suspend fun awaitTemp(root: Path): Path = withTimeout(5_000) {
    var result: Path? = null
    while (result == null) {
        result = Files.list(root).use { it.filter { p -> p.fileName.toString().endsWith(".tmp") }.findFirst().orElse(null) }
        if (result == null) delay(10)
    }
    result
}

private class Owner(val store: DesktopPluginStore) {
    val gate = Any()
    val current = AtomicBoolean(true)
    val admissions = AtomicInteger()
    var afterAdmission: (() -> Unit)? = null
    val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), current::get, { block ->
        val accepted = synchronized(gate) {
            check(!Thread.holdsLock(backing(store))) { "Root admission was called under the settings backing monitor" }
            if (!current.get()) false else { admissions.incrementAndGet(); block(); true }
        }
        afterAdmission?.invoke()
        accepted
    })
}

private suspend fun runProof() = coroutineScope {
    val root = Files.createTempDirectory("bilipai-settings-io-proof-")
    val store = DesktopPluginStore(root)
    store.update("unrelated", mapOf("keep" to JsonPrimitive("retain")))
    store.update("settings", mapOf("count" to JsonPrimitive(0)))
    val count = playerIntPreferencesKey("count")
    val text = playerStringPreferencesKey("text")
    val owner = Owner(store)
    val observed = store.snapshot("settings")
    val result = owner.context.settingsDataStore.edit { it[text] = "original-key" }
    verify(result[text] == "original-key", "edit returns the actual committed values")
    verify(observed.value[text] == "original-key", "existing snapshot publishes after successful replacement")
    verify(store.preferences("unrelated")["keep"]?.jsonPrimitive?.content == "retain", "other namespace survives")
    verify(noTemps(root), "successful commit cleans owned staging file")

    // Competing edits deliberately take the same document, then meet at admission.
    val first = CountDownLatch(2)
    val release = CountDownLatch(1)
    val editCalls = AtomicInteger()
    suspend fun increment() = owner.context.settingsDataStore.edit {
        val n = editCalls.incrementAndGet()
        if (n <= 2) { first.countDown(); await(release) }
        it[count] = (it[count] ?: 0) + 1
    }
    val left = async(Dispatchers.IO) { increment() }
    val right = async(Dispatchers.IO) { increment() }
    withContext(Dispatchers.IO) { await(first) }; release.countDown()
    left.await(); right.await()
    verify(observed.value[count] == 2, "CAS retry prevents lost read-dependent history edits")
    verify(editCalls.get() == 3, "exactly one conflicting pure callback was recomputed")
    verify(noTemps(root), "conflict cleans its discarded staging file")

    // Hold Root admission before an edit starts. Real staging must still complete.
    val gateHeld = CountDownLatch(1); val openGate = CountDownLatch(1)
    val gateThread = Thread { synchronized(owner.gate) { gateHeld.countDown(); await(openGate) } }
    gateThread.start(); withContext(Dispatchers.IO) { await(gateHeld) }
    val staged = async(Dispatchers.IO) { owner.context.settingsDataStore.edit { it[text] = "staged-outside-root" } }
    val temporary = awaitTemp(root)
    withTimeout(5_000) { while (!Files.readString(temporary).contains("staged-outside-root")) delay(10) }
    verify(content(root).contains("original-key"), "disk replacement waits for Root admission")
    verify(!staged.isCompleted, "held Root gate blocks only final permit, staging already finished")
    openGate.countDown(); withContext(Dispatchers.IO) { gateThread.join(5_000) }; staged.await()
    verify(observed.value[text] == "staged-outside-root", "released permit completes existing Store write")

    // After minting, block the backing monitor. Root admission must remain available.
    val minted = CountDownLatch(1); val backingHeld = CountDownLatch(1); val openBacking = CountDownLatch(1)
    owner.afterAdmission = { minted.countDown(); await(backingHeld) }
    val backingThread = Thread {
        await(minted)
        synchronized(backing(store)) { backingHeld.countDown(); await(openBacking) }
    }
    backingThread.start()
    val waitingForBacking = async(Dispatchers.IO) { owner.context.settingsDataStore.edit { it[text] = "accepted-global-edit" } }
    withContext(Dispatchers.IO) { await(backingHeld) }
    val responsive = async(Dispatchers.Default) { synchronized(owner.gate) { true } }
    verify(withTimeout(1_000) { responsive.await() }, "backing contention cannot retain Root gate")
    verify(!waitingForBacking.isCompleted, "replacement is actually waiting for backing")
    owner.current.set(false)
    openBacking.countDown(); withContext(Dispatchers.IO) { backingThread.join(5_000) }
    waitingForBacking.await()
    verify(observed.value[text] == "accepted-global-edit", "already accepted global edit may finish after entry retires")
    verify(noTemps(root), "in-flight accepted edit cleans staging")
    owner.afterAdmission = null; owner.current.set(true)

    // Caller cancellation while waiting for permit must leave the existing document unchanged.
    val beforeCancel = content(root)
    val cancelHeld = CountDownLatch(1); val cancelOpen = CountDownLatch(1)
    val cancelThread = Thread { synchronized(owner.gate) { cancelHeld.countDown(); await(cancelOpen) } }
    cancelThread.start(); withContext(Dispatchers.IO) { await(cancelHeld) }
    val cancelled = async(Dispatchers.IO) { owner.context.settingsDataStore.edit { it[text] = "must-not-publish" } }
    awaitTemp(root); cancelled.cancel(); cancelOpen.countDown()
    withContext(Dispatchers.IO) { cancelThread.join(5_000) }; cancelled.join()
    verify(cancelled.isCancelled, "cancelled caller is rejected at permit")
    verify(content(root) == beforeCancel && observed.value[text] == "accepted-global-edit", "cancelled write changes neither disk nor flow")
    verify(noTemps(root), "cancelled write deletes its owned temporary file")

    owner.current.set(false)
    val retired = runCatching { owner.context.settingsDataStore.edit { it[text] = "retired" } }.exceptionOrNull()
    verify(retired is CancellationException && content(root) == beforeCancel, "retired owner cannot begin a write")
    owner.current.set(true)
    val rejectedContext = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { false })
    val rejected = runCatching { rejectedContext.settingsDataStore.edit { it[text] = "rejected" } }.exceptionOrNull()
    verify(rejected is CancellationException && content(root) == beforeCancel && noTemps(root), "Root refusal leaves no document or staging change")

    val mirror = owner.context.getSharedPreferences("player-mirror", DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
    mirror.edit().putStringSet("set", setOf("a", "b")).putInt("integer", 8).commit()
    verify(mirror.getStringSet("set", null) == setOf("a", "b") && mirror.getInt("integer", 0) == 8, "original mirror namespace values round-trip")
    mirror.edit().putString("set", null).apply()
    verify(!mirror.contains("set") && observed.value[count] == 2, "mirror removal preserves canonical settings namespace")

    val permit = owner.context.preferenceWritePermit {}
    permit.consume(store)
    verify(runCatching { permit.consume(store) }.isFailure, "permit is one-use")
    val foreignRoot = Files.createTempDirectory("bilipai-settings-foreign-")
    val foreignStore = DesktopPluginStore(foreignRoot)
    val foreignPermit = owner.context.preferenceWritePermit {}
    verify(runCatching { foreignPermit.consume(foreignStore) }.isFailure, "permit binds the actual Store identity")

    val beforeFreeze = content(root)
    val frozen = runCatching {
        store.updateOriginalFromSnapshot("settings", {}, { owner.context.preferenceWritePermit {}.also { store.freezeWrites() } }) {
            Unit to mapOf("text" to JsonPrimitive("frozen-write"))
        }
    }.exceptionOrNull()
    verify(frozen is IllegalStateException && content(root) == beforeFreeze && noTemps(root), "Store freeze still wins after permit but before replacement")
    val restored = DesktopPluginStore(root)
    Owner(restored).context.settingsDataStore.edit { it[text] = "restored-generation" }
    verify(content(root).contains("restored-generation") && observed.value[text] == "accepted-global-edit", "restored generation owns new writes while old flows stay frozen")
    verify(runCatching { owner.context.settingsDataStore.edit { it[text] = "old-generation" } }.isFailure, "old facade cannot revive frozen backing")

    val malformedRoot = Files.createTempDirectory("bilipai-settings-malformed-")
    Files.writeString(malformedRoot.resolve("plugin-settings.json"), "{\"settings\":123}")
    val malformed = DesktopPluginStore(malformedRoot)
    val malformedBefore = content(malformedRoot)
    verify(runCatching { Owner(malformed).context.settingsDataStore.edit { it[text] = "replace" } }.isFailure && content(malformedRoot) == malformedBefore,
        "invalid existing namespace is rejected without overwrite")
    verify(Files.isRegularFile(root.resolve("plugin-settings.json")) && noTemps(root), "final persistence is the existing real file with no leaked staging")
    println("SettingsIoProof PASS $assertions assertions; original settings Store, real disk, no HTTP or native windows")
    println("Fixture temporary roots: $root; $foreignRoot; $malformedRoot")
}

fun main() = runBlocking { withTimeout(30_000) { runProof() } }

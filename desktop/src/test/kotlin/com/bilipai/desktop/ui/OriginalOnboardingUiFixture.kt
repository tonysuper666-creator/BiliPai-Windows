package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.serialization.json.*
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One actual Main invocation per JVM. Cold reopen is a separate process using the same private LOCALAPPDATA. */
object OriginalOnboardingUiFixture {
    private data class Drawn(val serial: Long, val key: String, val beforeAck: Boolean, val stack: List<String>)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 4..5) { "Usage: <accept|reject|back|reopen|deep-link> <empty-report-dir> <health-file> <token> [public-BVID]" }
        val mode = args[0]
        require(mode in setOf("accept", "reject", "back", "reopen", "deep-link"))
        require((mode == "deep-link") == (args.size == 5))
        val bvid = args.getOrNull(4)
        if (bvid != null) require(Regex("BV[0-9A-Za-z]{10}").matches(bvid))
        val report = Path.of(args[1]).toRealPath()
        Files.list(report).use { require(it.findAny().isEmpty) { "Report directory must be fresh" } }
        val health = Path.of(args[2]).toAbsolutePath().normalize()
        require(!Files.exists(health)) { "Do not reuse a startup health receipt" }
        val token = args[3]
        val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
        val submitted = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val observations = CopyOnWriteArrayList<Drawn>()
        val actions = OriginalOnboardingUiActions(latest::get, report, token)
        fun savePreExitObservations(exitInput: String) {
            val drawn = observations.toList()
            check(drawn.isNotEmpty()) { "No actual Root frames were observed" }
            if (mode == "reopen") check(drawn.none { it.key == BiliPaiNavKey.Onboarding.toString() })
            else check(drawn.filter { it.beforeAck }.all {
                it.key == BiliPaiNavKey.Onboarding.toString() && it.stack == listOf(BiliPaiNavKey.Onboarding.toString())
            }) { "An original page escaped the mandatory agreement before the real ACK button" }
            actions.requireAcknowledged(mode in setOf("accept", "deep-link", "reopen"))
            val record = buildJsonObject {
                put("schema", 2)
                put("mode", mode)
                // Compose application normally terminates this JVM. Actual exit and final
                // disk state must be observed by the external runner after the process ends.
                put("actualMainReturned", false)
                put("observationPhase", "BEFORE_REQUESTED_ACTUAL_EXIT")
                put("allPreExitAssertionsPassed", true)
                put("externalExitAndFinalPersistenceRequired", true)
                put("plannedActualExitInput", exitInput)
                put("defaultRenderer", "DIRECT3D")
                put("input", if (mode == "back") "actual-window-navigation-dispatcher" else "original-accessible-actions")
                put("realAccountUsed", false)
                put("productionPreferencesWrittenByFixture", false)
                put("physicalStackWrittenByFixture", false)
                put("allRoutesAccepted", false)
                put("initialBvid", bvid?.let(::JsonPrimitive) ?: JsonNull)
                put("welcome", buildJsonObject { actions.welcomeFlags().forEach { (key, value) ->
                    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
                } })
                put("drawn", buildJsonArray { drawn.forEach { item -> add(buildJsonObject {
                    put("serial", item.serial); put("key", item.key); put("beforeAck", item.beforeAck)
                    put("stack", buildJsonArray { item.stack.forEach { add(JsonPrimitive(it)) } })
                }) } })
            }
            Files.writeString(report.resolve("observations.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), record), CREATE_NEW, WRITE)
        }
        actions.requireAcknowledged(mode == "reopen")
        if (mode != "reopen") actions.requireFreshGuestStartup()
        DesktopOriginalRootValidationTap.install { frame ->
            latest.set(frame)
            observations.add(Drawn(frame.serial, frame.key.toString(), !submitted.get(), frame.routes.stack.map { it.toString() }))
        }.use {
            val worker = Thread({
                try {
                    if (mode == "reopen") {
                        val home = actions.awaitHome()
                        actions.awaitActualHealth(health)
                        check(observations.none { it.key == BiliPaiNavKey.Onboarding.toString() })
                        actions.requireAcknowledged(true)
                        savePreExitObservations("actual-owned-window-close")
                        completed.set(true)
                        actions.closeOwnedWindow(home)
                    } else {
                        val initial = actions.awaitFreshAgreement()
                        actions.awaitActualHealth(health)
                        if (mode == "reject" || mode == "back") {
                            actions.disagree(initial, useActualWindowBack = mode == "back") {
                                savePreExitObservations(if (mode == "back") "actual-window-navigation-back" else "original-disagree-button")
                                completed.set(true)
                            }
                        } else {
                            if (mode == "deep-link") {
                                // Keep the actual startup argument pending while the untouched mandatory screen is drawn.
                                Thread.sleep(3000)
                                EventQueue.invokeAndWait {
                                    check(initial.routes.stack.toList() == listOf(BiliPaiNavKey.Onboarding))
                                    check(latest.get()?.key == BiliPaiNavKey.Onboarding)
                                }
                                actions.capture("005-link-waiting", requireNotNull(latest.get()))
                            }
                            actions.acknowledgeThroughOriginalUi(initial) { submitted.set(true) }
                            val final = if (mode == "deep-link") actions.awaitVideo(initial.serial, initial.handle, requireNotNull(bvid))
                                else actions.awaitHome(initial.serial, initial.handle)
                            check(final.handle === initial.handle && final.routes === initial.routes)
                            actions.requireAcknowledged(true)
                            savePreExitObservations("actual-owned-window-close")
                            completed.set(true)
                            actions.closeOwnedWindow(final)
                        }
                    }
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    kotlin.system.exitProcess(92)
                }
            }, "Original onboarding actual Root observer")
            worker.isDaemon = true
            worker.start()
            val mainArgs = mutableListOf("--update-health-file", health.toString(), "--update-health-token", token)
            if (bvid != null) mainArgs.add("--video=$bvid")
            com.bilipai.desktop.main(mainArgs.toTypedArray())
            check(completed.get()) { "Actual Main closed before onboarding observations completed" }
            worker.join(10_000)
            check(!worker.isAlive) { "Onboarding observer did not terminate after Main shutdown" }
        }
    }
}

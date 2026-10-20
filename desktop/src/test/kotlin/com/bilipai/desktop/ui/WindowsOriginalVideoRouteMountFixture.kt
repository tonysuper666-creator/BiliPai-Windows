package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.serialization.json.*
import java.awt.EventQueue
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.swing.JFrame

/**
 * Slice V12a (UX spec 4.13, 9.2 and 10.3): one actual Main window, one ordinary video, one measurement. Does the dormant original
 * VideoDetail branch of the shell mount, or is the page under a VideoDetail key the desktop-owned leaf alone?
 *
 * The fixture adds no input at all. It follows the start of WindowsVideoActualRootUiFixture, the live Windows video fixture: the actual
 * Main is started with the startup argument --video=BVID, the first drawn Root must not be the mobile agreement gate (Windows starts
 * directly in Home, DesktopOriginalOnboardingPreferences.isRequired() is false, so there is no agreement to accept and no
 * acknowledgement is ever written), the startup health marker must appear, and the drawn VideoDetail frame of the same Root is
 * awaited. It then samples the accessibility tree of the main window and of every showing window it owns (the video command carrier
 * and menus are owned windows). Every named node counts, whether or not it is on screen, as far as the accessibility bridge exposes it.
 * WindowsOriginalVideoRouteMount holds the two anchor lists and the truth table; the Python suite test_ux_original_route_fixture.py
 * re-derives from the call graph that each anchor is emitted by exactly one of the two pages.
 *
 * It never writes a route, a preference, a setting, an acknowledgement or a key, never moves the pointer and never presses a control.
 * It writes only files in the fresh report directory: the screenshots and accessibility dumps of OriginalOnboardingUiActions.capture
 * and v12a-receipt.json.
 *
 * Windows cloud runner only; never run on the user's machine (it plays the video unmuted). Unlike WindowsVideoActualRootUiFixture it
 * does not seed a silent player profile, because it writes no preference at all.
 *
 * Arguments: report-dir health-file token public-BVID [desktop-leaf | original | any]. The expectation defaults to desktop-leaf, the
 * shipped shell. The receipt is written first; only then does the verdict decide the exit status: 0 when it meets the expectation,
 * 93 when it does not (the receipt says which page mounted), 92 for any failure to measure (the code of OriginalOnboardingUiFixture;
 * most of the other fixtures exit 91 for a failed assertion).
 * Slice V12b, if decision D7 asks for the classic route, runs the same fixture with the expectation original.
 *
 * Wiring that this slice does not do. Registering a Gradle task, adding the class to the exported UI harness closure and running it in
 * the interim Windows cloud run are Root hunks (desktop/build.gradle.kts, the harness export and the workflow are Root-reserved). The
 * task would read as the following, modelled on windowsVideoActualRootUiSmoke:
 *
 *     tasks.register<JavaExec>("windowsOriginalVideoRouteMountSmoke") {
 *         group = "verification"
 *         dependsOn("classes", "testClasses", "prepareAppResources")
 *         classpath = sourceSets["test"].runtimeClasspath
 *         mainClass.set("com.bilipai.desktop.ui.WindowsOriginalVideoRouteMountFixture")
 *         systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
 *         systemProperty("file.encoding", "UTF-8")
 *         doFirst {
 *             systemProperty("bilipai.rootValidationToken", providers.gradleProperty("rootValidationToken").get())
 *             args(providers.gradleProperty("rootValidationReport").get(), providers.gradleProperty("rootValidationHealth").get(),
 *                 providers.gradleProperty("rootValidationToken").get(), providers.gradleProperty("rootValidationVideo").get())
 *         }
 *     }
 *
 * Not claimed. The fixture was written without a compiler and has not run: it is source only. The negative half of the verdict
 * (no original anchor seen) corroborates, it does not prove, that the original page is absent, because the run has no positive
 * control on the original page; the exclusivity of the shell branches is what carries the proof, and the Python suite pins it.
 */
object WindowsOriginalVideoRouteMountFixture {
    private const val MAIN_WINDOW_TITLE = "BiliPai Windows"
    private const val FIRST_ROOT_SECONDS = 45L
    private const val READY_SECONDS = 90L
    private const val OBSERVE_MILLIS = 8_000L
    private const val SAMPLE_MILLIS = 200L
    private const val RECEIPT_NAME = "v12a-receipt.json"
    private const val EXIT_MEASUREMENT_FAILED = 92
    private const val EXIT_EXPECTATION_MISSED = 93

    private class Observation(val names: Set<String>, val surfaces: Int)

    private class Measurement(
        val leafSeen: List<String>,
        val originalSeen: List<String>,
        val samples: Int,
        val maxSurfaces: Int,
        val readyMillis: Long,
        val observedMillis: Long,
    )

    private fun accessibleNodes(context: AccessibleContext?, result: MutableList<AccessibleContext>, depth: Int = 0) {
        if (context == null || depth > 80) return
        result.add(context)
        repeat(context.accessibleChildrenCount) { accessibleNodes(context.getAccessibleChild(it)?.accessibleContext, result, depth + 1) }
    }

    /** The first Root the actual Main draws (whatever page it is on), as the live Windows video fixture waits for it. */
    private fun awaitFirstRoot(latest: AtomicReference<DesktopOriginalRootValidationTap.Frame?>): DesktopOriginalRootValidationTap.Frame {
        val deadline = System.nanoTime() + Duration.ofSeconds(FIRST_ROOT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            val frame = latest.get()
            if (frame != null) return frame
            Thread.sleep(100)
        }
        error("The actual Main drew no Root within $FIRST_ROOT_SECONDS seconds")
    }

    private fun mainWindow(): JFrame = Window.getWindows().filterIsInstance<JFrame>()
        .filter { it.isShowing && it.title == MAIN_WINDOW_TITLE }.single()

    private fun ownedBy(main: Window, candidate: Window): Boolean {
        var current: Window? = candidate
        while (current != null) {
            if (current === main) return true
            current = current.owner
        }
        return false
    }

    /** EDT only: the names of every accessible node of the main window and of each showing window it owns. */
    private fun observeOnEdt(frame: DesktopOriginalRootValidationTap.Frame): Observation {
        check(EventQueue.isDispatchThread())
        check(frame.handle.isActive() && frame.routes.owns() && frame.handle.route.get() === frame.routes) {
            "The actual Root of the measured video was replaced"
        }
        check(frame.routes.currentKey is BiliPaiNavKey.VideoDetail) {
            "The measured route is no longer an ordinary VideoDetail: ${frame.routes.currentKey}"
        }
        val main = mainWindow()
        val surfaces = Window.getWindows().filter { it.isShowing && ownedBy(main, it) }
        val names = linkedSetOf<String>()
        surfaces.forEach { surface ->
            val nodes = mutableListOf<AccessibleContext>()
            accessibleNodes(surface.accessibleContext, nodes)
            nodes.forEach { node -> node.accessibleName?.takeIf(String::isNotBlank)?.let { names.add(it) } }
        }
        return Observation(names, surfaces.size)
    }

    private fun observe(frame: DesktopOriginalRootValidationTap.Frame): Observation {
        var observed: Observation? = null
        EventQueue.invokeAndWait { observed = observeOnEdt(frame) }
        return requireNotNull(observed)
    }

    /** Samples until the page identified itself (or the deadline), then for a fixed window, and keeps the union of anchors seen. */
    private fun measure(frame: DesktopOriginalRootValidationTap.Frame): Measurement {
        val leafSeen = linkedSetOf<String>()
        val originalSeen = linkedSetOf<String>()
        var samples = 0
        var maxSurfaces = 0
        fun take() {
            val observation = observe(frame)
            samples += 1
            maxSurfaces = maxOf(maxSurfaces, observation.surfaces)
            WindowsOriginalVideoRouteMount.LEAF_ANCHORS.forEach { anchor ->
                if (WindowsOriginalVideoRouteMount.containsAnchor(observation.names, anchor)) leafSeen.add(anchor)
            }
            WindowsOriginalVideoRouteMount.ORIGINAL_ANCHORS.forEach { anchor ->
                if (WindowsOriginalVideoRouteMount.containsAnchor(observation.names, anchor)) originalSeen.add(anchor)
            }
        }
        val started = System.nanoTime()
        val readyDeadline = started + Duration.ofSeconds(READY_SECONDS).toNanos()
        while (true) {
            take()
            val identified = leafSeen.size == WindowsOriginalVideoRouteMount.LEAF_ANCHORS.size || originalSeen.isNotEmpty()
            if (identified || System.nanoTime() >= readyDeadline) break
            Thread.sleep(SAMPLE_MILLIS)
        }
        val readyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis()
        val observeStarted = System.nanoTime()
        val observeEnd = observeStarted + Duration.ofMillis(OBSERVE_MILLIS).toNanos()
        while (System.nanoTime() < observeEnd) {
            Thread.sleep(SAMPLE_MILLIS)
            take()
        }
        return Measurement(leafSeen.toList(), originalSeen.toList(), samples, maxSurfaces, readyMillis,
            Duration.ofNanos(System.nanoTime() - observeStarted).toMillis())
    }

    private fun strings(values: Collection<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun receipt(
        frame: DesktopOriginalRootValidationTap.Frame,
        bvid: String,
        measurement: Measurement,
        verdict: OriginalVideoRouteVerdict,
        expectation: OriginalVideoRouteExpectation,
    ): JsonObject {
        val leaf = WindowsOriginalVideoRouteMount.LEAF_ANCHORS
        val original = WindowsOriginalVideoRouteMount.ORIGINAL_ANCHORS
        return buildJsonObject {
            put("schema", 1)
            put("slice", "V12a")
            put("fixture", WindowsOriginalVideoRouteMountFixture::class.java.name)
            put("bvid", bvid)
            put("keyType", frame.key.javaClass.simpleName)
            put("expectation", expectation.argument)
            put("verdict", verdict.name)
            put("meetsExpectation", expectation.accepts(verdict))
            put("originalVideoDetailBranchMounted", measurement.originalSeen.isNotEmpty())
            put("desktopLeafMounted", measurement.leafSeen.containsAll(leaf))
            put("leafAnchors", strings(leaf))
            put("leafAnchorsSeen", strings(measurement.leafSeen))
            put("leafAnchorsMissing", strings(leaf.filterNot { it in measurement.leafSeen }))
            put("originalAnchors", strings(original))
            put("originalAnchorsSeen", strings(measurement.originalSeen))
            put("samples", measurement.samples)
            put("maxSurfaces", measurement.maxSurfaces)
            put("readyMillis", measurement.readyMillis)
            put("observedMillis", measurement.observedMillis)
            put("basis", "accessible names of the actual Main window and every showing window it owns, showing or hidden nodes alike")
            put("routeMutatedByFixture", false)
            put("preferencesWrittenByFixture", false)
            put("realAccountUsed", false)
            put("limits", buildJsonArray {
                add(JsonPrimitive("No positive control on the original page in this run: an absent original anchor corroborates the shell's exclusive branches, it does not prove absence by itself."))
                add(JsonPrimitive("Which original anchors are on screen depends on the layout the original page picks, so the original anchor list is wide and one hit means mounted."))
                add(JsonPrimitive("The leaf anchors are the LEGACY control bar; under the future DESKTOP player layout they must be revisited."))
            })
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 4..5) {
            "Usage: <empty-report-dir> <health-file> <token> <public-BVID> [desktop-leaf|original|any]"
        }
        val report = Path.of(args[0]).toRealPath()
        Files.list(report).use { require(it.findAny().isEmpty) { "Report directory must be fresh" } }
        val health = Path.of(args[1]).toAbsolutePath().normalize()
        require(!Files.exists(health)) { "Do not reuse a startup health receipt" }
        val token = args[2]
        require(UUID.fromString(token).toString() == token)
        val bvid = args[3]
        require(Regex("BV[0-9A-Za-z]{10}").matches(bvid))
        val expectation = OriginalVideoRouteExpectation.parse(args.getOrNull(4) ?: OriginalVideoRouteExpectation.DESKTOP_LEAF.argument)
        val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
        val completed = AtomicBoolean(false)
        val actions = OriginalOnboardingUiActions(latest::get, report, token)
        actions.requireAcknowledged(false)
        actions.requireFreshGuestStartup()
        DesktopOriginalRootValidationTap.install { frame -> latest.set(frame) }.use {
            val worker = Thread({
                try {
                    actions.requireAcknowledged(false)
                    val first = awaitFirstRoot(latest)
                    check(first.key != BiliPaiNavKey.Onboarding) { "Windows startup still mounted the mobile agreement gate" }
                    actions.awaitActualHealth(health)
                    // after = 0L: the first drawn frame may already be the VideoDetail one.
                    val opened = actions.awaitVideo(0L, first.handle, bvid)
                    check(opened.handle === first.handle) { "The Root handle changed between the first drawn Root and the video" }
                    check(opened.key is BiliPaiNavKey.VideoDetail) {
                        "The startup video opened as ${opened.key}, not as an ordinary VideoDetail; the measurement needs that route"
                    }
                    val measurement = measure(opened)
                    actions.requireAcknowledged(false)
                    val verdict = WindowsOriginalVideoRouteMount.classify(measurement.leafSeen.toSet(), measurement.originalSeen.toSet())
                    val record = receipt(opened, bvid, measurement, verdict, expectation)
                    Files.writeString(report.resolve(RECEIPT_NAME),
                        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), record), CREATE_NEW, WRITE)
                    val last = requireNotNull(latest.get())
                    actions.capture("200-v12a-video-route", last)
                    if (!expectation.accepts(verdict)) {
                        System.err.println("V12a: verdict $verdict does not meet the expectation ${expectation.argument}; see $RECEIPT_NAME")
                        kotlin.system.exitProcess(EXIT_EXPECTATION_MISSED)
                    }
                    completed.set(true)
                    actions.closeOwnedWindow(last)
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    kotlin.system.exitProcess(EXIT_MEASUREMENT_FAILED)
                }
            }, "Original video route mount actual Root observer")
            worker.isDaemon = true
            worker.start()
            val mainArgs = arrayOf("--update-health-file", health.toString(), "--update-health-token", token, "--video=$bvid")
            com.bilipai.desktop.main(mainArgs)
            check(completed.get()) { "Actual Main closed before the original route mount measurement completed" }
            worker.join(10_000)
            check(!worker.isAlive) { "The route mount observer did not terminate after Main shutdown" }
        }
    }
}

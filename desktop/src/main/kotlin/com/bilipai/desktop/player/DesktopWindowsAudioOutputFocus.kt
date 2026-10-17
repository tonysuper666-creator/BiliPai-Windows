package com.bilipai.desktop.player

import java.util.IdentityHashMap

/** A physical-output lease, independent of queues, accounts and media ownership.
 * No native IO, callback, coroutine or player lock is entered under this monitor.
 * An intent permits an attempt; it never proves that WASAPI actually opened.
 */
internal class DesktopWindowsAudioOutputFocus {
    internal class Actor internal constructor()
    internal class NativeSession internal constructor(internal val actor: Actor)
    internal class Intent internal constructor(
        internal val actor: Actor,
        val sourceVersion: Long,
        val playbackRevision: Long,
        val pauseSerial: Long,
        internal val nativeSession: NativeSession?,
        val sequence: Long,
    )
    private class SessionState {
        var released = false // Unknown, including an initializing/closing core, blocks a peer.
        var closing = false
        var sequence = 0L
        var openIntent: Intent? = null
    }
    private class ActorState { var live = true; var latest: Intent? = null }
    private val actors = IdentityHashMap<Actor, ActorState>()
    private val sessions = IdentityHashMap<NativeSession, SessionState>()
    private var desired: Intent? = null
    private var intentSequence = 0L
    private var activatedSequence = 0L
    internal data class Observation(val actors: Int, val nativeSessions: Int, val unreleasedSessions: Int,
        val desiredSequence: Long?, val desiredSourceVersion: Long?, val desiredPlaybackRevision: Long?,
        val desiredPauseSerial: Long?, val requestedSequence: Long?, val requestedSourceVersion: Long?,
        val requestedPlaybackRevision: Long?, val requestedPauseSerial: Long?)
    /** Bounded, immutable diagnostic metadata; contains no URLs, account data or native pointers. */
    @Synchronized fun observation(): Observation {
        val requested = actors.values.mapNotNull { it.latest }.maxByOrNull { it.sequence }
        return Observation(actors.size, sessions.size, sessions.values.count { !it.released },
            desired?.sequence, desired?.sourceVersion, desired?.playbackRevision, desired?.pauseSerial,
            requested?.sequence, requested?.sourceVersion, requested?.playbackRevision, requested?.pauseSerial)
    }

    @Synchronized fun registerActor(): Actor = Actor().also { actors[it] = ActorState() }
    @Synchronized fun registerSession(actor: Actor): NativeSession {
        check(actors[actor]?.live == true) { "Audio actor has retired" }
        return NativeSession(actor).also { sessions[it] = SessionState() }
    }
    @Synchronized fun request(actor: Actor, sourceVersion: Long, playbackRevision: Long,
        pauseSerial: Long, nativeSession: NativeSession?,
    ): Intent {
        check(actors[actor]?.live == true)
        check(nativeSession == null || (nativeSession.actor === actor && sessions[nativeSession]?.closing == false))
        intentSequence = Math.incrementExact(intentSequence)
        // Queueing an old/unauthorized Load must not pause another actual player.
        // Only activate(), inside that Load/resume's media admission, can do so.
        return Intent(actor, sourceVersion, playbackRevision, pauseSerial, nativeSession, intentSequence)
            .also { actors.getValue(actor).latest = it }
    }
    /** Attach or retarget an unchanged logical intent after HWND/session recreation.
     * A late attachment cannot reacquire focus from a newer actor or resurrect a token.
     * The old native core remains in [sessions] until its real teardown acknowledges.
     */
    @Synchronized fun attach(intent: Intent, session: NativeSession): Intent? {
        if (actors[intent.actor]?.latest !== intent || actors[intent.actor]?.live != true ||
            intent.sequence < activatedSequence || session.actor !== intent.actor ||
            sessions[session]?.closing != false) return null
        if (intent.nativeSession === session) return intent
        val old = intent.nativeSession
        if (old != null && sessions[old]?.closing == false) return null
        // Retargeting preserves logical order: a late HWND cannot become newer
        // than a music/video request merely because its native worker starts late.
        return Intent(intent.actor, intent.sourceVersion, intent.playbackRevision, intent.pauseSerial, session, intent.sequence)
            .also { actors.getValue(intent.actor).latest = it; if (desired === intent) desired = it }
    }
    @Synchronized fun isEligible(intent: Intent, session: NativeSession): Boolean =
        actors[intent.actor]?.live == true && actors[intent.actor]?.latest === intent &&
            intent.nativeSession === session && sessions[session]?.closing == false &&
            (intent.sequence > activatedSequence || desired === intent)
    /** The real native Load/resume calls this ONLY inside its current source publication. */
    @Synchronized fun activate(intent: Intent, session: NativeSession): Boolean {
        if (!isEligible(intent, session)) return false
        activatedSequence = intent.sequence
        desired = intent
        return true
    }
    @Synchronized fun isCurrent(intent: Intent, session: NativeSession): Boolean =
        desired === intent && actors[intent.actor]?.live == true && actors[intent.actor]?.latest === intent && intent.nativeSession === session &&
            sessions[session]?.closing == false

    /** Called immediately before native IO, using the same worker's increasing IO sequence. */
    @Synchronized fun beginOpen(intent: Intent, session: NativeSession, sequence: Long): Boolean {
        val own = sessions[session] ?: return false
        if (!isCurrent(intent, session) || sequence <= own.sequence ||
            sessions.any { (key, value) -> key !== session && !value.released }) return false
        own.sequence = sequence
        own.released = false
        own.openIntent = intent
        return true
    }
    @Synchronized fun holds(intent: Intent, session: NativeSession): Boolean =
        isCurrent(intent, session) && sessions[session]?.let { !it.released && it.openIntent === intent } == true
    @Synchronized fun isReleased(session: NativeSession): Boolean = sessions[session]?.released == true

    /** Only an actual parked/no-AO readback can release a live core. A pause or
     * option command acknowledgement alone is deliberately insufficient. */
    @Synchronized fun acknowledgeReleased(session: NativeSession, sequence: Long,
        readback: DesktopWindowsAudioOutputReleaseReadback,
    ): Boolean {
        val own = sessions[session] ?: return false
        if (sequence <= own.sequence || !readback.confirmsNoPhysicalOutput) return false
        own.sequence = sequence
        own.released = true
        own.openIntent = null
        return true
    }
    @Synchronized fun cancel(intent: Intent?) {
        if (intent == null || actors[intent.actor]?.latest !== intent) return
        actors.getValue(intent.actor).latest = null
        if (desired?.actor === intent.actor) desired = null
    }
    @Synchronized fun markClosing(session: NativeSession) { sessions[session]?.closing = true }
    /** Called ONLY after mpv_terminate_destroy returns (or when no handle was created). */
    @Synchronized fun acknowledgeDestroyed(session: NativeSession) {
        if (sessions.remove(session) == null) return
        val actor = session.actor
        if (actors[actor]?.live == false && sessions.keys.none { it.actor === actor }) actors.remove(actor)
    }
    @Synchronized fun retire(actor: Actor) {
        if (!actors.containsKey(actor)) return
        if (desired?.actor === actor) desired = null
        actors.getValue(actor).live = false
        actors.getValue(actor).latest = null
        if (sessions.keys.none { it.actor === actor }) actors.remove(actor)
    }
    internal companion object {
        // Native resources can outlive a Root/Surface. A replacement controller
        // must also wait for those cores; no application/account state lives here.
        val process = DesktopWindowsAudioOutputFocus()
    }
}

/** Park on MPV's explicit null driver without deselecting the audio track. Unlike
 * aid=no, this preserves audio-only media, its selected language and resume cursor.
 * It is an intentional released state, NEVER a successful shared/exclusive output
 * or a fallback after a real output failure. MPV_ERROR_PROPERTY_UNAVAILABLE=-10
 * must be distinguished from arbitrary native errors and missing properties.
 */
internal data class DesktopWindowsAudioOutputReleaseReadback(
    val deviceCode: Int, val device: String?,
    val driverCode: Int, val driver: String?,
    val formatCode: Int, val format: String?,
) {
    val confirmsNoPhysicalOutput: Boolean get() = deviceCode >= 0 && device == "null" &&
        ((driverCode >= 0 && driver == "null") ||
            (driverCode == -10 && driver == null && formatCode == -10 && format == null))
}

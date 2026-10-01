package com.bilipai.desktop.ui

import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.*
import kotlinx.coroutines.Job
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Real temp SessionStore/Repository receipt admission; callbacks model only the
 * native command/ACK boundary. No MPV, account, HWND, socket or full VM is built. */
object InitialNativePublicationFixture {
    @JvmStatic fun main(args: Array<String>) {
        val store = DesktopSessionStore.temporary()
        val repository = DesktopRepository(store)
        val publication = DesktopRepositoryPlaybackPublication(repository)
        val alive = AtomicBoolean(true)
        val generation = AtomicBoolean(true)
        var entryOpen = true
        var executed = 0
        val checks = mutableListOf<String>()
        fun verify(ok: Boolean, label: String) { check(ok) { label }; checks += label }
        fun authorized(): PlaybackSource {
            val receipt = repository.capturePlaybackAuthorization(store.generation) { alive.get() }.receipt
            return PlaybackSource("https://fixture.invalid/ordinary", authorizationReceipt = receipt)
        }
        fun initial(job: Job, source: PlaybackSource = authorized()) = DesktopOriginalVideoInitialPublication(
            publication, source, job, generation::get, alive::get,
            { block -> if (!entryOpen) false else { block(); true } })

        val canceled = Job().apply { cancel() }
        verify(!initial(canceled).admit { executed++ } && executed == 0,
            "Canceled request cannot execute queued initial Load")

        val completed = Job().apply { complete() }
        verify(!completed.isActive && !completed.isCancelled, "Successful request has completed without cancellation")
        val completedPublication = initial(completed)
        verify(completedPublication.admit { executed++; completedPublication.onLoadCommandAccepted() },
            "Successfully completed request can finish its already queued initial Load")

        val noOpJob = Job()
        val noOp = initial(noOpJob)
        verify(noOp.admit {}, "Admitted stale native no-op returns without a success ACK")
        noOpJob.cancel()
        verify(!noOp.admit { executed++ }, "No-op does not consume initial cancellation guard")

        val leaseJob = Job()
        val retained = initial(leaseJob)
        verify(retained.admit { executed++; retained.onLoadCommandAccepted() }, "Actual successful command consumes first-request guard by ACK")
        leaseJob.cancel(); generation.set(false)
        verify(retained.admit { executed++ }, "ACKed source replays after canceled request and failed later request generation")
        alive.set(false)
        verify(!retained.admit { executed++ }, "ACK never bypasses retired accepted entry/source lease")
        alive.set(true); generation.set(true)

        val throwsJob = Job()
        val failing = initial(throwsJob)
        try { failing.admit { throw IllegalStateException("Synthetic native command failure") }; error("Failure swallowed") }
        catch (_: IllegalStateException) { verify(true, "Native failure propagates without consuming request guard") }
        throwsJob.cancel()
        verify(!failing.admit { executed++ }, "Failed native command has no successful ACK")

        generation.set(false)
        verify(!initial(Job()).admit { executed++ }, "Superseded generation rejects first Load before command")
        generation.set(true)

        val recoveryJob = Job().apply { cancel() }
        val recovery = publication.ownedSource(authorized(), alive::get)
        verify(recoveryJob.isCancelled && recovery.nativePublication!!.admit { executed++ },
            "Existing accepted recovery/adoption publication is independent of old request Job")

        entryOpen = false
        verify(!completedPublication.admit { executed++ }, "Consumed guard still requires entry atomic admission")
        entryOpen = true

        val epochInitial = initial(Job())
        val epochRetained = initial(Job())
        verify(epochRetained.admit { epochRetained.onLoadCommandAccepted() }, "Same actual receipt admits before epoch retirement")
        store.logout()
        verify(!epochInitial.admit { executed++ } && !epochRetained.admit { executed++ },
            "Actual SessionStore epoch retirement rejects both initial and ACKed source")

        val origins = listOf(DesktopSessionStore::class.java, DesktopRepository::class.java,
            DesktopRepositoryPlaybackPublication::class.java, DesktopNativePlaybackPublication::class.java,
            DesktopOriginalVideoInitialPublication::class.java).joinToString(",") { type ->
                "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location.toURI()}\"}"
            }
        Files.writeString(Path.of(args.single()), "{\"passed\":true,\"assertions\":${checks.size},\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[$origins],\"scope\":\"Prepared initial publication over actual60 Repository/SessionStore; synthetic native command ACK only, no actual MPV/HWND/network/account/full-owner acceptance\"}\n")
        println("PASS ${checks.size} initial publication admission assertions")
    }
}

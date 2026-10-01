package com.bilipai.desktop.ui

import com.bilipai.desktop.download.DownloadMuxer
import com.bilipai.desktop.player.DesktopPlaybackPublication
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.OkHttpClient
import java.nio.file.Path

/** Fixture-only ABI accommodation: keep the historical fixture source byte-identical.
 * The actual manager now requires a publication port; this list-only fixture forbids
 * every enqueue/transport admission, rather than bypassing the actual product gate.
 */
fun DesktopDownloadManager(client: OkHttpClient, stateFile: Path, muxer: DownloadMuxer): com.bilipai.desktop.download.DesktopDownloadManager =
    com.bilipai.desktop.download.DesktopDownloadManager(client, stateFile, muxer, publication = object : DesktopPlaybackPublication {
        override val requiresAccountReceipt = true
        override fun isCurrent(source: PlaybackSource): Boolean = error("List fixture forbids playback publication")
        override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T =
            error("List fixture forbids enqueue or network publication")
        override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?): Call.Factory =
            error("List fixture forbids playback HTTP")
    })

package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.ui.DesktopOriginalVideoSupplementEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSupplementOwnerTest {
    private fun subject(id: String, generation: Long) = VideoSubjectSnapshot(id, 1, 2, 3, id, "", 60000, generation)

    @Test fun supersededDeferredResultCannotReplaceCurrentSubject() = runTest {
        val old = CompletableDeferred<VideoSupplementSeed>()
        val vm = VideoSupplementViewModel(DesktopOriginalVideoSupplementEnvironment(this, { true }, { it(); true }),
            VideoSupplementLoader { s -> if (s.bvid == "old") withContext(NonCancellable) { old.await() } else VideoSupplementSeed(onlineCount = "new-loaded") }, 10)
        vm.bindSubject(subject("old", 1), VideoSupplementSeed(onlineCount = "old-seed"))
        advanceTimeBy(10); runCurrent()
        vm.bindSubject(subject("new", 2), VideoSupplementSeed(onlineCount = "new-seed"))
        advanceTimeBy(10); runCurrent()
        assertEquals("new-loaded", vm.uiState.value.onlineCount)
        old.complete(VideoSupplementSeed(onlineCount = "stale")); runCurrent()
        assertEquals("new", vm.uiState.value.subject?.bvid)
        assertEquals("new-loaded", vm.uiState.value.onlineCount)
        vm.close()
    }

    @Test fun visibilityCloseAndRetiredOwnerKeepRealCommitAdmission() = runTest {
        var owned = true
        var loads = 0
        val vm = VideoSupplementViewModel(DesktopOriginalVideoSupplementEnvironment(this, { owned }, { action -> if (owned) { action(); true } else false }),
            VideoSupplementLoader { loads++; VideoSupplementSeed(onlineCount = "loaded") }, 10)
        vm.bindSubject(subject("one", 1), VideoSupplementSeed(onlineCount = "seed"))
        vm.setVisible(false); advanceTimeBy(20); runCurrent()
        assertEquals(0, loads)
        vm.setVisible(true); advanceTimeBy(10); runCurrent()
        assertEquals(1, loads)
        assertEquals("loaded", vm.uiState.value.onlineCount)
        vm.bindSubject(subject("two", 2), VideoSupplementSeed(onlineCount = "two-seed"))
        vm.close(); advanceTimeBy(20); runCurrent()
        assertEquals(1, loads)
        owned = false
        assertFailsWith<CancellationException> { vm.sync(VideoSupplementSeed(onlineCount = "forbidden")) }
        assertEquals("two-seed", vm.uiState.value.onlineCount)
    }
}

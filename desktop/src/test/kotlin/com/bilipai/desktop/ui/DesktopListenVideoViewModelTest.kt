package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.FavoriteData
import com.android.purebilibili.data.model.response.FavoriteResourceData
import com.android.purebilibili.data.model.response.Upper
import com.android.purebilibili.feature.audio.library.ListenVideoCollectedFoldersPage
import com.android.purebilibili.feature.audio.library.ListenVideoLibraryDataSource
import com.android.purebilibili.feature.audio.library.ListenVideoSection
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The upstream ListenVideoViewModel (compiled from the merged root, patched to take its owner) against a fake owner.
 * The first four cases are upstream's `ListenVideoLibraryLoaderTest` view-model cases that the generated v0.3.8 test copy
 * (`DesktopV038ListenVideoLibraryLoaderTest`, 11 cases) does not have, kept here word for word; the last two are desktop additions
 * for the publication gate a retired personal-lists entry relies on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopListenVideoViewModelTest {
    private class FakeOwner(
        override val scope: CoroutineScope,
        override val dataSource: ListenVideoLibraryDataSource,
        private val mid: Long,
    ) : DesktopOriginalListenVideoOwner {
        @Volatile var retired = false
        override fun currentMid(): Long { if (retired) throw CancellationException("retired"); return mid }
        override fun <T> ownedState(initial: T): MutableStateFlow<T> =
            DesktopHomeOwnedMutableStateFlow(initial) { block -> if (retired) false else { block(); true } }
    }

    private fun TestScope.viewModel(source: ListenVideoLibraryDataSource, mid: Long): Pair<ListenVideoViewModel, FakeOwner> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val owner = FakeOwner(scope, source, mid)
        return ListenVideoViewModel(owner) to owner
    }

    @Test
    fun `new refresh cancels old generation and only latest result is published`() = runTest {
        val source = RestartingSource()
        val (viewModel, owner) = viewModel(source, mid = 42L)
        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2L, viewModel.uiState.value.generation)
        assertEquals(listOf("Latest"), viewModel.uiState.value.playlists.map { it.title })
        assertTrue(source.firstRequestCancelled)
        assertFalse(viewModel.uiState.value.isLoading)
        owner.scope.cancel()
    }

    @Test
    fun `logged out refresh skips remote requests`() = runTest {
        val source = CountingSource()
        val (viewModel, owner) = viewModel(source, mid = 0L)
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertEquals(0, source.ownedFolderRequests)
        owner.scope.cancel()
    }

    @Test
    fun `detail retry repeats the last playlist request`() = runTest {
        val source = RetryDetailSource()
        val (viewModel, owner) = viewModel(source, mid = 42L)
        viewModel.refresh()
        advanceUntilIdle()
        val playlist = viewModel.uiState.value.playlists.single()

        viewModel.openPlaylist(playlist)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.detailError != null)

        viewModel.retryDetail()
        advanceUntilIdle()

        assertEquals(listOf("BV-RETRY"), viewModel.uiState.value.selectedTracks.map { it.bvid })
        assertEquals(3, source.detailRequests)
        owner.scope.cancel()
    }

    @Test
    fun `switching between albums and artists does not restart active index`() = runTest {
        var folderRequests = 0
        val source = object : CountingSource() {
            override suspend fun ownedFolders(mid: Long) = Result.success(
                listOf(FavFolder(id = 1L, title = "Large folder", cover = "cover")),
            )
            override suspend fun folderPage(mediaId: Long, page: Int): Result<FavoriteResourceData> {
                folderRequests++
                awaitCancellation()
            }
        }
        val (viewModel, owner) = viewModel(source, mid = 42L)
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectSection(ListenVideoSection.ALBUMS)
        runCurrent()
        viewModel.selectSection(ListenVideoSection.ARTISTS)
        runCurrent()
        assertEquals(1, folderRequests)
        assertTrue(viewModel.uiState.value.isIndexing)
        viewModel.pauseBackgroundLoading()
        runCurrent()
        assertFalse(viewModel.uiState.value.isIndexing)
        owner.scope.cancel()
    }

    @Test
    fun `a retired owner admits no state write and its late result is dropped`() = runTest {
        val source = RestartingSource()
        val (viewModel, owner) = viewModel(source, mid = 42L)
        viewModel.refresh()
        runCurrent()
        val before = viewModel.uiState.value
        owner.retired = true // the personal-lists entry is gone while the first request is still in flight
        assertFailsWith<CancellationException> { viewModel.selectSection(ListenVideoSection.ALBUMS) }
        owner.scope.cancel()
        advanceUntilIdle()
        assertEquals(before, viewModel.uiState.value, "nothing was published after the owner retired")
    }

    @Test
    fun `the model reads the account from its owner at every refresh`() = runTest {
        val source = CountingSource()
        val (viewModel, owner) = viewModel(source, mid = 7L)
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertEquals(1, source.ownedFolderRequests)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, source.ownedFolderRequests)
        assertEquals(2L, viewModel.uiState.value.generation)
        owner.scope.cancel()
    }
}

private open class CountingSource : ListenVideoLibraryDataSource {
    var ownedFolderRequests = 0
        private set

    override suspend fun ownedFolders(mid: Long): Result<List<FavFolder>> {
        ownedFolderRequests += 1
        return Result.success(emptyList())
    }

    override suspend fun collectedFolders(mid: Long, page: Int): Result<ListenVideoCollectedFoldersPage> =
        Result.success(ListenVideoCollectedFoldersPage(emptyList(), hasMore = false))

    override suspend fun folderPage(mediaId: Long, page: Int): Result<FavoriteResourceData> =
        Result.failure(IllegalStateException("missing folder page $mediaId/$page"))

    override suspend fun albumPage(seasonId: Long, page: Int): Result<FavoriteResourceData> =
        Result.failure(IllegalStateException("unused"))
}

private class RestartingSource : ListenVideoLibraryDataSource {
    private var ownedCalls = 0
    var firstRequestCancelled = false
        private set

    override suspend fun ownedFolders(mid: Long): Result<List<FavFolder>> {
        ownedCalls += 1
        if (ownedCalls == 1) {
            try {
                awaitCancellation()
            } finally {
                firstRequestCancelled = true
            }
        }
        return Result.success(listOf(FavFolder(id = 1L, title = "Latest")))
    }

    override suspend fun collectedFolders(mid: Long, page: Int): Result<ListenVideoCollectedFoldersPage> =
        Result.success(ListenVideoCollectedFoldersPage(emptyList(), hasMore = false))

    override suspend fun folderPage(mediaId: Long, page: Int): Result<FavoriteResourceData> =
        Result.success(FavoriteResourceData())

    override suspend fun albumPage(seasonId: Long, page: Int): Result<FavoriteResourceData> =
        Result.success(FavoriteResourceData())
}

private class RetryDetailSource : ListenVideoLibraryDataSource {
    var detailRequests = 0
        private set

    override suspend fun ownedFolders(mid: Long): Result<List<FavFolder>> =
        Result.success(listOf(FavFolder(id = 9L, title = "Retry playlist")))

    override suspend fun collectedFolders(mid: Long, page: Int): Result<ListenVideoCollectedFoldersPage> =
        Result.success(ListenVideoCollectedFoldersPage(emptyList(), hasMore = false))

    override suspend fun folderPage(mediaId: Long, page: Int): Result<FavoriteResourceData> {
        detailRequests += 1
        if (detailRequests <= 2) return Result.failure(IOException("preview and first detail failure"))
        return Result.success(
            FavoriteResourceData(
                medias = listOf(FavoriteData(bvid = "BV-RETRY", title = "Recovered", upper = Upper(mid = 7L, name = "Artist")))
            )
        )
    }

    override suspend fun albumPage(seasonId: Long, page: Int): Result<FavoriteResourceData> =
        Result.failure(IllegalStateException("unused"))
}

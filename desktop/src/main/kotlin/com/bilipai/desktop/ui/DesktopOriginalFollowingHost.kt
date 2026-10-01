package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import com.android.purebilibili.feature.following.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

internal class DesktopFollowingEntry(private val root:DesktopPersonalListsRoot,
    val key:BiliPaiNavKey.Following):AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val job=SupervisorJob(root.scope.coroutineContext[Job])
    val scope=CoroutineScope(root.scope.coroutineContext+job)
    lateinit var environment:DesktopFollowingEnvironment;private set
    lateinit var viewModel:FollowingListViewModel;private set
    val saveableKey=java.util.UUID.randomUUID().toString()
    fun owns()=!closed.get() && job.isActive && root.owns()
    fun assertOwned(){if(!owns())throw CancellationException("Following entry retired")}
    fun commit(block:()->Unit)=root.gate.commit {if(owns())block()} && owns()
    fun install(environment:DesktopFollowingEnvironment) {check(!this::viewModel.isInitialized);assertOwned();this.environment=environment;viewModel=FollowingListViewModel(environment)}
    override fun close(){if(closed.compareAndSet(false,true))job.cancel()}
}

@Composable
internal fun DesktopOriginalFollowingHost(entry:DesktopFollowingEntry,onBack:()->Unit,
    onUserClick:(Long)->Unit,isCurrentPage:Boolean) {
    if (!entry.owns()) return
    androidx.compose.runtime.CompositionLocalProvider(LocalDesktopDetailForeground provides isCurrentPage) {
        FollowingListScreen(entry.key.mid,{if(entry.owns())onBack()},{mid->if(entry.owns())onUserClick(mid)},entry.viewModel)
    }
}

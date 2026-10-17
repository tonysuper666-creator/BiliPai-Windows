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
    lateinit var fansViewModel:FansListViewModel;private set
    val saveableKey=java.util.UUID.randomUUID().toString()
    fun owns()=!closed.get() && job.isActive && root.owns()
    fun assertOwned(){if(!owns())throw CancellationException("Following entry retired")}
    fun commit(block:()->Unit)=root.gate.commit {if(owns())block()} && owns()
    fun install(environment:DesktopFollowingEnvironment) {
        check(!this::environment.isInitialized);assertOwned();this.environment=environment
        if(key.fans) fansViewModel=FansListViewModel(key.mid,environment)
        else viewModel=FollowingListViewModel(environment)
    }
    override fun close(){if(closed.compareAndSet(false,true))job.cancel()}
}

@Composable
internal fun DesktopOriginalFollowingHost(entry:DesktopFollowingEntry,onBack:()->Unit,
    onUserClick:(Long)->Unit,onFansClick:(Long)->Unit,isCurrentPage:Boolean) {
    if (!entry.owns()) return
    val foreground = isCurrentPage && LocalDesktopDetailForeground.current
    androidx.compose.runtime.CompositionLocalProvider(LocalDesktopDetailForeground provides foreground) {
        if(entry.key.fans) FansListScreen(entry.key.mid,{if(entry.owns() && foreground)onBack()},
            {mid->if(entry.owns() && foreground)onUserClick(mid)},{mid->if(entry.owns() && foreground)onFansClick(mid)},entry.fansViewModel,foreground)
        else FollowingListScreen(entry.key.mid,{if(entry.owns() && foreground)onBack()},{mid->if(entry.owns() && foreground)onUserClick(mid)},
            {mid->if(entry.owns() && foreground)onFansClick(mid)},entry.viewModel,foreground)
    }
}

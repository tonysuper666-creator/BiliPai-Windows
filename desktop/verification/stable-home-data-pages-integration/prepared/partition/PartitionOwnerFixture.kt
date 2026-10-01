package com.bilipai.desktop.ui

import com.android.purebilibili.feature.partition.PartitionFeedViewModel
import com.android.purebilibili.feature.partition.PartitionCategory
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*

private class ScriptedPartitionRequests(
    val popular: suspend (Int) -> Result<List<VideoItem>>,
    val region: suspend (Int, Int) -> Result<List<VideoItem>>,
) : DesktopHomeVideoRequests {
    override suspend fun getPopularVideos(page: Int) = popular(page)
    override suspend fun getRegionVideos(tid: Int, page: Int) = region(tid,page)
    override suspend fun getHomeVideos(idx: Int): Result<List<VideoItem>> = error("Unused Home protocol")
    override suspend fun getRankingVideos(rid: Int,type: String): Result<List<VideoItem>> = error("Unused ranking protocol")
    override suspend fun getWeeklyMustWatchVideos(): Result<List<VideoItem>> = error("Unused weekly protocol")
    override suspend fun getPreciousVideos(): Result<List<VideoItem>> = error("Unused precious protocol")
    override suspend fun getNavInfo(): Result<NavData> = error("Unused nav protocol")
    override suspend fun getPreviewVideoUrl(bvid: String,cid: Long): String? = error("Unused preview protocol")
}

internal suspend fun provePartitionOwner(): List<String> {
    val gates = mutableListOf<String>()
    fun video(name:String) = VideoItem(bvid=name,title=name)
    val errors = mutableListOf<Throwable>()
    val handler = CoroutineExceptionHandler { _,failure -> errors += failure }
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined+handler)
    var active = true
    val lock=Any()
    val popularPages=mutableListOf<Int>()
    var returnEmpty=false
    val requests=ScriptedPartitionRequests(popular={page ->
        popularPages += page
        Result.success(if(returnEmpty) emptyList() else listOf(video("BV$page")))
    },region={tid,page -> Result.success(listOf(video("BV${tid}_$page")))})
    val environment=DesktopPartitionEnvironment(requests,scope,{synchronized(lock){active}},
        { block -> synchronized(lock){if(active){block();true}else false} })
    val vm=PartitionFeedViewModel(environment)
    check(popularPages==listOf(1) && vm.uiState.value.videos.map{it.bvid}==listOf("BV1"))
    check(!vm.uiState.value.isLoading)
    gates += "original_popular_first_page_and_loading_receipt"
    vm.loadMore()
    check(popularPages==listOf(1,2) && vm.uiState.value.videos.map{it.bvid}==listOf("BV1","BV2"))
    gates += "original_append_advances_cursor"
    vm.refresh()
    check(popularPages==listOf(1,2,3) && vm.uiState.value.videos.map{it.bvid}==listOf("BV3"))
    check(!vm.uiState.value.isRefreshing)
    gates += "original_replace_refresh_fetches_next_page"
    returnEmpty=true;vm.refresh()
    check(popularPages.last()==4 && vm.uiState.value.videos.map{it.bvid}==listOf("BV3"))
    returnEmpty=false;vm.refresh()
    check(popularPages.last()==1 && vm.uiState.value.videos.map{it.bvid}==listOf("BV1"))
    gates += "empty_refresh_keeps_previous_list_and_restarts_page"
    vm.selectPartition(PartitionCategory(188,"科技"))
    check(vm.uiState.value.selectedPartition.id==188 && vm.uiState.value.videos.single().bvid=="BV188_1")
    gates += "original_region_reset_uses_raw_tid_and_page"

    val pending=CompletableDeferred<Result<List<VideoItem>>>()
    val pendingScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined+handler)
    val switched=PartitionFeedViewModel(DesktopPartitionEnvironment(
        ScriptedPartitionRequests({pending.await()},{tid,page -> Result.success(listOf(video("BV${tid}_$page")))}),
        pendingScope,{true},{ block -> block();true }))
    check(switched.uiState.value.isLoading)
    switched.selectPartition(PartitionCategory(3,"音乐"))
    check(switched.uiState.value.videos.single().bvid=="BV3_1")
    pending.complete(Result.success(listOf(video("BV_OLD"))))
    yield()
    check(switched.uiState.value.videos.single().bvid=="BV3_1" && !switched.uiState.value.isLoading)
    gates += "generation_rejects_late_old_partition_response"

    val late=CompletableDeferred<Result<List<VideoItem>>>()
    val retiredScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined+handler)
    var current=true
    val retired=PartitionFeedViewModel(DesktopPartitionEnvironment(
        ScriptedPartitionRequests({withContext(NonCancellable){late.await()}},{_,_->error("unused")}),
        retiredScope,{current},{block->if(current){block();true}else false}))
    val before=retired.uiState.value
    current=false
    late.complete(Result.success(listOf(video("BV_RETIRED"))))
    yield()
    check(retired.uiState.value==before)
    gates += "retired_entry_rejects_uncancellable_late_state_write"
    scope.cancel();pendingScope.cancel();retiredScope.cancel()
    check(errors.isEmpty())
    return gates
}

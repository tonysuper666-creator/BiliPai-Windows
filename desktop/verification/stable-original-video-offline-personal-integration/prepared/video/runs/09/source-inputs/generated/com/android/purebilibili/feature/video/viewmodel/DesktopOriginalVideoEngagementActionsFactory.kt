package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
internal fun originalVideoEngagementActions(useCase: VideoInteractionUseCase): VideoEngagementActions = object : VideoEngagementActions {
    override suspend fun toggleFollow(mid:Long,currentlyFollowing:Boolean)=useCase.toggleFollow(mid,currentlyFollowing)
    override suspend fun toggleLike(aid:Long,currentlyLiked:Boolean,bvid:String)=useCase.toggleLike(aid,currentlyLiked,bvid)
    override suspend fun toggleDislike(aid:Long,currentlyDisliked:Boolean,bvid:String)=useCase.toggleDislike(aid,currentlyDisliked,bvid)
    override suspend fun toggleFavorite(aid:Long,currentlyFavorited:Boolean,bvid:String)=useCase.toggleFavorite(aid,currentlyFavorited,bvid)
    override suspend fun toggleWatchLater(aid:Long,currentlyInWatchLater:Boolean,bvid:String)=useCase.toggleWatchLater(aid,currentlyInWatchLater,bvid)
    override suspend fun doCoin(aid:Long,count:Int,alsoLike:Boolean,bvid:String)=useCase.doCoin(aid,count,alsoLike,bvid)
    override suspend fun doTripleAction(aid:Long)=useCase.doTripleAction(aid)
}

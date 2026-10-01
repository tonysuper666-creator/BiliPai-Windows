package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
internal fun originalVideoEngagementActions(useCase: VideoInteractionUseCase): VideoEngagementActions = DefaultVideoEngagementActions(useCase)

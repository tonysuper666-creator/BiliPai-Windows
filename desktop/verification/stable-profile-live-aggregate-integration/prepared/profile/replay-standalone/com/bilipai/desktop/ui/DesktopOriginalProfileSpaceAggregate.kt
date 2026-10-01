package com.bilipai.desktop.ui
import com.android.purebilibili.core.network.buildSpaceAggregateParams

internal suspend fun DesktopProfileEnvironment.getDesktopProfileSpaceAggregate(
    mid: Long
): com.android.purebilibili.data.model.response.SpaceAggregateResponse {
    val credentials = accounts.accessTokenCredentials()
    return spaceApi.getSpaceAggregate(
        buildSpaceAggregateParams(
            mid = mid,
            accessToken = credentials.first,
            accessTokenPlatform = credentials.second
        )
    )
}

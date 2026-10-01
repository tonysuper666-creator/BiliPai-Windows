package com.android.purebilibili.data.repository
data class CreatorCardStats(
    val followerCount: Int,
    val videoCount: Int,
    val vipStatus: Int = 0,
    val vipType: Int = 0,
    val officialType: Int = -1,
    val pendantImage: String = "",
)


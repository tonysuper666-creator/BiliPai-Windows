package com.android.purebilibili.data.repository

data class DanmakuCloudFilterRule(
    val id: Long,
    val type: Int,
    val filter: String
)

data class DanmakuCloudFilterRules(
    val rules: List<DanmakuCloudFilterRule>,
    val toast: String? = null
)

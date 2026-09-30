package com.android.purebilibili.core.database.entity

import kotlinx.serialization.Serializable

@Serializable
data class BlockedUp(
    val mid: Long,
    val name: String,
    val face: String,
    val blockedAt: Long = System.currentTimeMillis(),
    val level: Int? = null,
    val sign: String = "",
    val vipLabel: String = "",
    val officialTitle: String = "",
    val follower: Long? = null,
    val archiveCount: Int? = null,
    val isDeleted: Boolean = false,
    val lastSyncedAt: Long? = null
)

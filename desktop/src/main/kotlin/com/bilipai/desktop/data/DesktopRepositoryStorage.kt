package com.bilipai.desktop.data

import android.content.Context
import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.data.repository.DesktopCommentFraudDao
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Explicit storage binding over the caller's Context. It owns no files or account state.
 * Root passes its existing global block store and its account-owned record DAO. */
internal class DesktopRepositoryStorageContext(
    private val delegate: Context,
    blockedUps: DesktopBlockedUpStore? = null,
    val commentFraudDao: DesktopCommentFraudDao? = null,
) : Context(delegate.dataDir), androidx.datastore.preferences.DesktopPreferencesDataStoreOwner {
    val blockedUpDao = blockedUps?.let(::DesktopBlockedUpDao)
    override val applicationContext: Context get() = this
    override val filesDir get() = delegate.filesDir
    override val cacheDir get() = delegate.cacheDir
    override val assets get() = delegate.assets
    override val resources get() = delegate.resources
    override val contentResolver get() = delegate.contentResolver
    override fun getExternalFilesDir(type: String?) = delegate.getExternalFilesDir(type)
    override fun getSharedPreferences(name: String, mode: Int) = delegate.getSharedPreferences(name, mode)
    override fun desktopPreferencesDataStore(name: String) =
        (delegate as? androidx.datastore.preferences.DesktopPreferencesDataStoreOwner)
            ?.desktopPreferencesDataStore(name)
            ?: throw UnsupportedDesktopCapability("DataStore $name", "the caller's Context has no preference store")
}

/** The upstream DAO surface over the existing global JSON store, including legacy migration.
 * Reads fail on corrupt/missing storage instead of reporting an empty block list. */
internal class DesktopBlockedUpDao(private val store: DesktopBlockedUpStore) {
    private fun checkStorage() { store.migrateLegacyDiscoveryMids().getOrThrow() }
    fun getAllBlockedUps(): Flow<List<BlockedUp>> {
        checkStorage()
        return store.records.map { checkStorage(); it }
    }
    suspend fun getAllBlockedUpsSnapshot(): List<BlockedUp> { checkStorage(); return store.records.value }
    suspend fun getBlockedUp(mid: Long): BlockedUp? = getAllBlockedUpsSnapshot().firstOrNull { it.mid == mid }
    suspend fun insert(blockedUp: BlockedUp) = store.upsert(blockedUp)
    suspend fun delete(mid: Long) = store.remove(mid)
    fun isBlocked(mid: Long): Flow<Boolean> = getAllBlockedUps().map { rows -> rows.any { it.mid == mid } }
}

internal fun desktopBlockedUpDao(context: Context): DesktopBlockedUpDao =
    (context as? DesktopRepositoryStorageContext)?.blockedUpDao
        ?: throw UnsupportedDesktopCapability("BlockedUpDao", "Root must supply its existing DesktopBlockedUpStore")

internal fun desktopCommentFraudDao(context: Context): DesktopCommentFraudDao =
    (context as? DesktopRepositoryStorageContext)?.commentFraudDao
        ?: throw UnsupportedDesktopCapability("CommentFraudDao", "Root must supply its account-owned DesktopCommentFraudJsonDao")

/** Upstream MessageCenterPreferences uses MultiProcess DataStore on Android. Its
 * namespace and key live in the caller's existing JSON preference document here. */
internal fun desktopMessageLikesDisabled(context: Context): Boolean =
    context.getSharedPreferences("message_center_preferences", Context.MODE_PRIVATE).getBoolean("disable_likes", false)

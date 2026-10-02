package com.bilipai.desktop.ui

import com.android.purebilibili.data.repository.BlockedUpRelationSource
import com.android.purebilibili.data.repository.BlockedUpWriteResult
import com.android.purebilibili.core.database.entity.BlockedUp
import com.bilipai.desktop.data.DesktopBlockedUpRepository
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One entry's view of Root's existing global blocked-UP store and Repository.
 * Capture each actual action Job. The same primary service/CSRF/bootstrap is used
 * for relation sync; no new client, account, list, disk store or worker is made. */
internal class DesktopOriginalPortraitBlockedUpsBinding(
    private val repository: DesktopBlockedUpRepository,
    private val settings: DesktopOriginalPlayerSettingsContext,
    private val captureRequest: suspend () -> DesktopOriginalVideoRepositoryBinding,
) : DesktopOriginalPortraitBlockedUps, DesktopHomeBlockedRequests {
    init { require(repository.store.context.store === settings.pluginContext.store) }
    override fun isBlocked(mid: Long): Flow<Boolean> {
        require(mid > 0L); settings.requireCurrent()
        return repository.store.mids.map { settings.requireCurrent(); mid in it }.distinctUntilChanged()
    }
    override fun getAllBlockedUps(): Flow<List<BlockedUp>> = repository.store.records
    override suspend fun blockUp(mid: Long, name: String, face: String) {
        val operation = operation()
        withContext(Dispatchers.IO) {
            repository.store.upsertCaptured(BlockedUp(mid = mid, name = name, face = face),
                operation::check, operation::permit)
        }
    }
    private class Operation(val binding: DesktopOriginalVideoRepositoryBinding,
        private val caller: Job, private val settings: DesktopOriginalPlayerSettingsContext) {
        fun check() { caller.ensureActive(); settings.requireCurrent(); binding.assertCurrent() }
        fun permit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            lateinit var permit: DesktopPluginStore.OriginalPreferenceWritePermit
            if (!binding.admitCurrentMutation {
                caller.ensureActive(); settings.requireCurrent()
                permit = DesktopPluginStore.OriginalPreferenceWritePermit(settings.pluginContext.store)
            }) throw CancellationException("Portrait blocked-UP write owner retired")
            return permit
        }
    }
    private suspend fun operation(): Operation {
        currentCoroutineContext().ensureActive(); settings.requireCurrent()
        val caller = checkNotNull(currentCoroutineContext()[Job])
        return Operation(captureRequest(), caller, settings).also { it.check() }
    }
    override suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String): BlockedUpWriteResult {
        val operation = operation(); val binding = operation.binding
        return repository.blockUpWithCapturedLocalWrite(mid, name, face, BlockedUpRelationSource.PROFILE,
            binding.receipt.accountEpoch, operation::check,
            { repository.store.upsertCaptured(it, operation::check, operation::permit) },
            binding.primaryApi, binding::primaryCsrf, binding.environment.ensureBuvid)
    }
    override suspend fun unblockUpWithBilibiliSync(mid: Long): BlockedUpWriteResult {
        val operation = operation(); val binding = operation.binding
        return repository.unblockUpWithCapturedLocalWrite(mid, BlockedUpRelationSource.PROFILE,
            binding.receipt.accountEpoch, operation::check,
            { repository.store.removeCaptured(it, operation::check, operation::permit) },
            binding.primaryApi, binding::primaryCsrf, binding.environment.ensureBuvid)
    }
}

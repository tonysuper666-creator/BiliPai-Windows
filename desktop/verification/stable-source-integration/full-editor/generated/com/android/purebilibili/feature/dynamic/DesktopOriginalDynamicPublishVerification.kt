// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt; do not edit.
// LF-normalized SHA-256: 8b9186a097d15302f33e5439f28c94dd8a124235937d9fe7404a30c85731918c
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.DynamicDetailResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
private const val DYNAMIC_CREATE_ANTIFRAUD_DELAY_MS = 5_000L
internal suspend fun verifyDesktopOriginalDynamicPublish(
    createdId: String, fetchDetail: suspend (String) -> DynamicDetailResponse,
    onResult: (Boolean, String) -> Unit,
) {
    if (createdId.isBlank()) return
    try {
        delay(DYNAMIC_CREATE_ANTIFRAUD_DELAY_MS)
        val verify = fetchDetail(createdId)
        if (verify.code != 0 || verify.data?.item == null) {
            onResult(true, "发布成功，但动态可能暂未生效（可在网页端确认）")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }}

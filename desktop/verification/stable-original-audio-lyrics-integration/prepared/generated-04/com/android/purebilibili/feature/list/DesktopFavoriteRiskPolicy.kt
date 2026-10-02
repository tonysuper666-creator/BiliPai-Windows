// Generated from app/src/main/java/com/android/purebilibili/feature/list/FavoriteCollectionPolicy.kt; do not edit.
// LF-normalized SHA-256: 4aec028dea899247fd8416632c37edd617517f1643c63a875ad30ca430b4696b
package com.android.purebilibili.feature.list

import com.android.purebilibili.data.repository.FavoriteRequestException

internal fun isFavoriteRiskControlError(throwable: Throwable): Boolean {
    val requestError = throwable as? FavoriteRequestException
    if (requestError?.apiCode in setOf(-412, 412, -429, 429)) return true
    if (requestError?.httpCode in setOf(412, 429)) return true
    val message = throwable.message.orEmpty()
    return message.contains("HTTP 412", ignoreCase = true) ||
        message.contains("HTTP 429", ignoreCase = true) ||
        throwable.cause?.let(::isFavoriteRiskControlError) == true
}

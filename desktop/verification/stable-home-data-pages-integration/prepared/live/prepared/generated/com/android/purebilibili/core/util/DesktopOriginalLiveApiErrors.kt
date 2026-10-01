package com.android.purebilibili.core.util

object ApiErrorCodes {
    const val SUCCESS = 0
    const val NOT_LOGIN = -101
    const val ACCOUNT_BANNED = -102
    const val CSRF_ERROR = -111
    const val NOT_MODIFIED = -304
    const val BAD_REQUEST = -400
    const val NOT_FOUND = -404
    const val TOO_MANY_REQUESTS = -412
    const val RISK_CONTROL = -352
    const val VIDEO_NOT_EXIST = 62002
    const val VIDEO_REVIEWING = 62004
    
    fun isLoginRequired(code: Int): Boolean = code == NOT_LOGIN
    fun isRateLimited(code: Int): Boolean = code == TOO_MANY_REQUESTS
    fun isRiskControlled(code: Int): Boolean = code == RISK_CONTROL
}


fun getApiErrorMessage(code: Int, defaultMessage: String? = null): String {
    return when (code) {
        ApiErrorCodes.SUCCESS -> "成功"
        ApiErrorCodes.NOT_LOGIN -> "请先登录"
        ApiErrorCodes.ACCOUNT_BANNED -> "账号已被封禁"
        ApiErrorCodes.CSRF_ERROR -> "CSRF 验证失败，请重新登录"
        ApiErrorCodes.BAD_REQUEST -> "请求参数错误"
        ApiErrorCodes.NOT_FOUND -> "内容不存在"
        ApiErrorCodes.TOO_MANY_REQUESTS -> "请求过于频繁，请稍后重试"
        ApiErrorCodes.RISK_CONTROL -> "触发风控，请稍后重试"
        ApiErrorCodes.VIDEO_NOT_EXIST -> "视频不存在或已删除"
        ApiErrorCodes.VIDEO_REVIEWING -> "视频审核中"
        else -> defaultMessage ?: "未知错误 ($code)"
    }
}

package com.android.purebilibili.feature.space
import kotlinx.coroutines.CancellationException
internal inline fun <T> ownedSpaceRunCatching(block:()->T):Result<T> = try { Result.success(block()) }
catch(cancelled:CancellationException) { throw cancelled }
catch(failure:Throwable) { Result.failure(failure) }

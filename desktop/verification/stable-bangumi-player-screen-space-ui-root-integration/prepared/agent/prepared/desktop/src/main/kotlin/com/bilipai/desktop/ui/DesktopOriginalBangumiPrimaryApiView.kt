package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Same invocation's primary service, following the existing ordinary action
 * façade. This is a stateless method view, never an API client/network/session
 * constructor; every original VM operation captures its real caller Binding. */
internal fun desktopOriginalBangumiPrimaryApiView(owner: DesktopOriginalVideoOwnerAssembly): BilibiliApi =
    Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
        val request = owner.invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository
            ?: error("Original PGC primary operation requires its actual invocation")
        request.binding.assertCurrent()
        try { method.invoke(request.primaryApi, *(args ?: emptyArray())) }
        catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    } as BilibiliApi

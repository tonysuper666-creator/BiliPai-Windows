package com.bilipai.desktop.data

import kotlin.test.*

class DesktopLiveSendTransportTest {
    @Test fun mutationTransportDisablesFollowUpsWithoutReplacingSharedAccountOrResources() {
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val shared = repository.httpClient
        val mutation = desktopLiveSendTransport(shared)
        try {
            assertTrue(shared.retryOnConnectionFailure)
            assertTrue(shared.followRedirects); assertTrue(shared.followSslRedirects)
            assertNotSame(shared, mutation)
            assertFalse(mutation.retryOnConnectionFailure)
            assertFalse(mutation.followRedirects); assertFalse(mutation.followSslRedirects)
            assertSame(shared.cookieJar, mutation.cookieJar)
            assertSame(shared.connectionPool, mutation.connectionPool)
            assertSame(shared.dispatcher, mutation.dispatcher)
            assertEquals(shared.interceptors.size + 1, mutation.interceptors.size)
            shared.interceptors.forEachIndexed { index, interceptor ->
                assertSame(interceptor, mutation.interceptors[index + 1])
            }
            assertEquals(shared.networkInterceptors, mutation.networkInterceptors)
            assertSame(shared.proxySelector, mutation.proxySelector)
            assertEquals(shared.connectTimeoutMillis, mutation.connectTimeoutMillis)
            assertEquals(shared.readTimeoutMillis, mutation.readTimeoutMillis)
            assertEquals(shared.writeTimeoutMillis, mutation.writeTimeoutMillis)
        } finally {
            shared.dispatcher.executorService.shutdownNow(); shared.connectionPool.evictAll()
        }
    }
}

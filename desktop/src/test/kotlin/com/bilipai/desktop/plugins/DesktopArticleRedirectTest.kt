package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.feed.fetchArticleHtml
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopArticleRedirectTest {
    @Test
    fun `article relative links use the final response URL after a redirect`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a") { exchange ->
            try {
                exchange.responseHeaders.add("Location", "/b/article.html")
                exchange.sendResponseHeaders(302, -1)
            } finally {
                exchange.close()
            }
        }
        server.createContext("/b/article.html") { exchange ->
            try {
                val page = """
                    <!doctype html><html><head><title>A local article</title></head><body>
                    <article class="article-body">
                      <p>This article contains enough ordinary text to represent a readable page.
                      Its content remains available after the server redirects the original request.</p>
                      <p>The reader should preserve the illustration and this
                      <a href="next.html">related page</a> within the article.</p>
                      <img src="img.png" alt="An illustration in the article">
                    </article></body></html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, page.size.toLong())
                exchange.responseBody.use { it.write(page) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val html = withTimeout(10_000) { fetchArticleHtml("$origin/a").getOrThrow() }
            val article = Jsoup.parseBodyFragment(html)
            assertEquals("$origin/b/img.png", assertNotNull(article.selectFirst("img")).attr("src"))
            assertEquals("$origin/b/next.html", assertNotNull(article.selectFirst("a")).attr("href"))
        } finally {
            server.stop(0)
        }
    }
}

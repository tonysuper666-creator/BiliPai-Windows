package com.android.purebilibili.core.util

import java.net.URI
import java.nio.charset.StandardCharsets

sealed interface BilibiliNavigationTarget {
    data class Video(val videoId: String) : BilibiliNavigationTarget
    data class Dynamic(val dynamicId: String) : BilibiliNavigationTarget
    data class Search(val keyword: String) : BilibiliNavigationTarget
    data class Space(val mid: Long) : BilibiliNavigationTarget
    data class Live(val roomId: Long) : BilibiliNavigationTarget
    data class BangumiSeason(val seasonId: Long, val mediaId: Long = 0L) : BilibiliNavigationTarget
    data class BangumiEpisode(val epId: Long) : BilibiliNavigationTarget
    data class Music(val musicId: String) : BilibiliNavigationTarget
    data class Article(
        val articleId: Long,
        val commentRootRpid: Long = 0L,
        val commentTargetRpid: Long = 0L,
    ) : BilibiliNavigationTarget
    data class ArticleCollection(val collectionId: Long) : BilibiliNavigationTarget

    /** 赛事比赛详情页，contestId 即 cid。 */
    data class Match(val contestId: Long) : BilibiliNavigationTarget

    /**
     * 站内热门榜单页(weekly/rank/all/comprehensive)。
     * 每周必看可指定 weeklyNumber,导航层打开原生选期页面;其余映射到首页子分类。
     */
    data class PopularFeed(val subCategoryKey: String, val weeklyNumber: Int? = null) : BilibiliNavigationTarget
}

object BilibiliNavigationTargetParser {

    private val knownHosts = setOf(
        "b23.tv",
        "bilibili.com",
        "www.bilibili.com",
        "m.bilibili.com",
        "space.bilibili.com",
        "search.bilibili.com",
        "live.bilibili.com",
        "t.bilibili.com",
        "music.bilibili.com"
    )

    fun parse(input: String): BilibiliNavigationTarget? {
        if (input.isBlank()) return null

        parseSingleCandidate(input)?.let { return withArticleCommentLocation(it, input) }

        BilibiliUrlParser.extractUrls(input).forEach { url ->
            parseSingleCandidate(url)?.let { return withArticleCommentLocation(it, url) }
        }

        return null
    }

    private fun withArticleCommentLocation(target: BilibiliNavigationTarget, input: String): BilibiliNavigationTarget {
        if (target !is BilibiliNavigationTarget.Article) return target
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return target
        val query = extractQueryParameters(uri.rawQuery)
        fun positive(vararg keys: String): Long = keys.firstNotNullOfOrNull {
            query[it]?.toLongOrNull()?.takeIf { value -> value > 0L }
        } ?: 0L
        val reply = positive("comment_secondary_id", "comment_id", "reply_id", "rpid", "target_id", "anchor", "source_id")
            .takeIf { it > 0L } ?: Regex("reply(\\d+)", RegexOption.IGNORE_CASE)
                .find(uri.fragment.orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val root = positive("comment_root_id", "root_reply_id", "root_id", "root")
            .takeIf { it > 0L } ?: reply
        // 包装链接没有外层位置参数时，保留内层文章携带的位置。
        if (root <= 0L) return target
        return target.copy(commentRootRpid = root, commentTargetRpid = reply.takeIf { it != root } ?: 0L)
    }

    suspend fun resolve(input: String): BilibiliNavigationTarget? {
        parse(input)?.let { return it }

        for (shortUrl in collectShortLinkCandidates(input)) {
            val resolvedUrl = BilibiliUrlParser.resolveShortUrl(shortUrl) ?: continue
            parse(resolvedUrl)?.let { return it }
        }

        return null
    }

    private fun parseSingleCandidate(input: String): BilibiliNavigationTarget? {
        val normalizedInput = normalizeInput(input) ?: input.trim()
        val plainTextTarget = mapParseResult(BilibiliUrlParser.parse(normalizedInput))
        if ("://" !in normalizedInput) {
            Regex("rl([0-9]+)", RegexOption.IGNORE_CASE).matchEntire(normalizedInput)
                ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L }?.let {
                    return BilibiliNavigationTarget.ArticleCollection(it)
                }
            return plainTextTarget ?: parseBangumiPlayValue(normalizedInput)
        }

        val uri = runCatching { URI(normalizedInput) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
        val pathSegments = uri.path
            ?.split("/")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val queryMap = extractQueryParameters(uri.rawQuery)

        if (scheme in listOf("http", "https") && !isBilibiliWebHost(host)) return null

        resolveWrappedUrl(queryMap)?.let { wrappedUrl ->
            parse(wrappedUrl)?.let { return it }
        }

        if (scheme in listOf("bilibili", "bili")) {
            mapParseResult(BilibiliUrlParser.parseDeepLink(normalizedInput))?.let { return it }
            resolveCustomSchemeTarget(host, pathSegments, queryMap)?.let { return it }
        }

        if (scheme !in listOf("http", "https")) return null

        if (isBilibiliWebHost(host)) {
            mapParseResult(BilibiliUrlParser.parseDeepLink(normalizedInput))?.let { return it }
        }
        resolveHttpTarget(host, pathSegments, queryMap)?.let { return it }
        return null
    }

    private fun resolveCustomSchemeTarget(
        host: String,
        pathSegments: List<String>,
        queryMap: Map<String, String>
    ): BilibiliNavigationTarget? {
        when {
            host == "popular" -> {
                pathSegments.firstOrNull()?.lowercase()?.let { key ->
                    return BilibiliNavigationTarget.PopularFeed(
                        subCategoryKey = key,
                        weeklyNumber = queryMap["number"]?.toIntOrNull()?.takeIf { it > 0 }
                    )
                }
            }

            host == "space" -> {
                pathSegments.firstOrNull()?.toLongOrNull()?.let {
                    return BilibiliNavigationTarget.Space(it)
                }
            }

            host == "search" -> {
                resolveSearchKeyword(queryMap)?.let {
                    return BilibiliNavigationTarget.Search(keyword = it)
                }
            }

            host == "live" -> {
                pathSegments.firstOrNull()?.toLongOrNull()?.let {
                    return BilibiliNavigationTarget.Live(it)
                }
            }

            host == "following" && pathSegments.firstOrNull()?.equals("detail", ignoreCase = true) == true -> {
                pathSegments.getOrNull(1)?.takeIf(::isNumericId)?.let {
                    return BilibiliNavigationTarget.Dynamic(it)
                }
            }

            host == "opus" -> {
                pathSegments.lastOrNull()?.takeIf(::isNumericId)?.let {
                    return BilibiliNavigationTarget.Dynamic(it)
                }
            }

            host == "bangumi" -> {
                resolveBangumiTarget(pathSegments)?.let { return it }
            }

            host == "cheese" -> {
                val seasonIndex = pathSegments.indexOfFirst { it.equals("season", ignoreCase = true) }
                if (seasonIndex >= 0) {
                    pathSegments.getOrNull(seasonIndex + 1)?.toLongOrNull()?.let {
                        return BilibiliNavigationTarget.BangumiSeason(it)
                    }
                }
                resolveBangumiTarget(pathSegments)?.let { return it }
            }

            host == "pgc" -> {
                resolvePgcTarget(pathSegments)?.let { return it }
            }

            host == "readlist" -> {
                resolveArticleCollectionTarget(listOf("readlist") + pathSegments, queryMap)?.let { return it }
            }

            host == "read" -> {
                resolveArticleCollectionTarget(pathSegments, queryMap)?.let { return it }
                resolveArticleTarget(pathSegments, queryMap, allowBareReadPath = true)?.let { return it }
            }

            host == "article" -> {
                pathSegments.firstOrNull()?.takeIf(::isNumericId)?.toLongOrNull()?.let {
                    return BilibiliNavigationTarget.Article(it)
                }
            }

            host == "music" -> {
                queryMap["music_id"]?.takeIf { it.isNotBlank() }?.let {
                    return BilibiliNavigationTarget.Music(it)
                }
            }
        }

        return null
    }

    private fun resolveHttpTarget(
        host: String,
        pathSegments: List<String>,
        queryMap: Map<String, String>
    ): BilibiliNavigationTarget? {
        when {
            host in setOf("www.bilibili.com", "bilibili.com", "m.bilibili.com") &&
                pathSegments.take(2) == listOf("v", "popular") &&
                pathSegments.getOrNull(2) == "weekly" -> {
                return BilibiliNavigationTarget.PopularFeed(
                    "weekly", queryMap["num"]?.toIntOrNull()?.takeIf { it > 0 }
                        ?: queryMap["number"]?.toIntOrNull()?.takeIf { it > 0 }
                )
            }
            host == "space.bilibili.com" -> {
                pathSegments.firstOrNull()?.toLongOrNull()?.let {
                    return BilibiliNavigationTarget.Space(it)
                }
            }

            host == "live.bilibili.com" -> {
                pathSegments.firstOrNull()?.toLongOrNull()?.let {
                    return BilibiliNavigationTarget.Live(it)
                }
            }

            host == "search.bilibili.com" ||
                (isBilibiliHost(host) && pathSegments.firstOrNull()?.equals("search", ignoreCase = true) == true) -> {
                resolveSearchKeyword(queryMap)?.let {
                    return BilibiliNavigationTarget.Search(keyword = it)
                }
            }

            host == "music.bilibili.com" &&
                pathSegments.contains("music-detail") -> {
                queryMap["music_id"]?.takeIf { it.isNotBlank() }?.let {
                    return BilibiliNavigationTarget.Music(it)
                }
            }

            isBilibiliHost(host) -> {
                resolveMatchTarget(pathSegments, queryMap)?.let { return it }
                resolveArticleCollectionTarget(pathSegments, queryMap)?.let { return it }
                resolveArticleTarget(pathSegments, queryMap, allowBareReadPath = false)?.let { return it }
                resolveBangumiTarget(pathSegments)?.let { return it }
            }
        }

        return null
    }

    /**
     * 赛事比赛详情链接：h5/match/data/detail 或 match/singledata，
     * cid 可能是路径末段数字，也可能在 query 里；h5/match/data/home 等首页路径不匹配。
     */
    private fun resolveMatchTarget(
        pathSegments: List<String>,
        queryMap: Map<String, String>
    ): BilibiliNavigationTarget? {
        val joinedPath = pathSegments.joinToString("/").lowercase()
        val isMatchDetail = "match/data/detail" in joinedPath || "match/singledata" in joinedPath
        if (!isMatchDetail) return null

        pathSegments.lastOrNull()?.takeIf(::isNumericId)?.toLongOrNull()?.let {
            return BilibiliNavigationTarget.Match(it)
        }
        listOf("cid", "contest_id", "id").firstNotNullOfOrNull { key ->
            queryMap[key]?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        }?.toLongOrNull()?.let {
            return BilibiliNavigationTarget.Match(it)
        }
        return null
    }

    private fun resolveBangumiTarget(pathSegments: List<String>): BilibiliNavigationTarget? {
        val playIndex = pathSegments.indexOfFirst { it.equals("play", ignoreCase = true) }
        if (playIndex >= 0) {
            val value = pathSegments.getOrNull(playIndex + 1).orEmpty()
            parseBangumiPlayValue(value)?.let { return it }
        }

        val seasonIndex = pathSegments.indexOfFirst { it.equals("season", ignoreCase = true) }
        if (seasonIndex >= 0) {
            val next = pathSegments.getOrNull(seasonIndex + 1).orEmpty()
            when {
                next.equals("ep", ignoreCase = true) -> {
                    pathSegments.getOrNull(seasonIndex + 2)?.toLongOrNull()?.let {
                        return BilibiliNavigationTarget.BangumiEpisode(it)
                    }
                }

                next.toLongOrNull() != null -> {
                    return BilibiliNavigationTarget.BangumiSeason(next.toLong())
                }
            }
        }

        val mediaIndex = pathSegments.indexOfFirst { it.equals("media", ignoreCase = true) }
        if (mediaIndex >= 0) {
            parseBangumiPlayValue(pathSegments.getOrNull(mediaIndex + 1).orEmpty())?.let { return it }
        }

        return null
    }

    private fun resolveArticleCollectionTarget(
        pathSegments: List<String>,
        queryMap: Map<String, String>,
    ): BilibiliNavigationTarget.ArticleCollection? {
        val index = pathSegments.indexOfFirst {
            it.equals("readlist", ignoreCase = true) || it.equals("mobile-readlist", ignoreCase = true)
        }
        if (index < 0) return null
        val raw = pathSegments.getOrNull(index + 1) ?: queryMap["list_id"] ?: queryMap["id"] ?: return null
        val value = raw.replace(Regex("^rl", RegexOption.IGNORE_CASE), "")
        if (value.isEmpty() || !value.all(Char::isDigit)) return null
        return value.toLongOrNull()?.takeIf { it > 0L }?.let { BilibiliNavigationTarget.ArticleCollection(it) }
    }

    private fun resolveArticleTarget(
        pathSegments: List<String>,
        queryMap: Map<String, String>,
        allowBareReadPath: Boolean
    ): BilibiliNavigationTarget? {
        if (pathSegments.any { it.equals("readlist", true) || it.equals("mobile-readlist", true) }) return null
        val readIndex = pathSegments.indexOfFirst { it.equals("read", ignoreCase = true) }
        if (readIndex < 0 && !allowBareReadPath) return null

        val firstReadValue = if (readIndex >= 0) {
            pathSegments.getOrNull(readIndex + 1)
        } else {
            pathSegments.firstOrNull()
        }

        firstReadValue?.let { value ->
            parseArticleId(value)?.let { return BilibiliNavigationTarget.Article(it) }
            if (value.equals("mobile", ignoreCase = true)) {
                resolveArticleIdFromQuery(queryMap)?.let {
                    return BilibiliNavigationTarget.Article(it)
                }
            }
        }

        if (readIndex >= 0 || allowBareReadPath) {
            resolveArticleIdFromQuery(queryMap)?.let { return BilibiliNavigationTarget.Article(it) }
        }
        return null
    }

    private fun resolveArticleIdFromQuery(queryMap: Map<String, String>): Long? {
        return listOf("id", "cvid", "cv_id", "article_id")
            .firstNotNullOfOrNull { key -> queryMap[key]?.let(::parseArticleId) }
    }

    private fun parseArticleId(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed
            .removePrefix("cv")
            .removePrefix("CV")
        return normalized.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun resolveSearchKeyword(queryMap: Map<String, String>): String? {
        return listOf("keyword", "query", "search", "q")
            .firstNotNullOfOrNull { key -> queryMap[key]?.trim()?.takeIf { it.isNotEmpty() } }
    }

    private fun resolvePgcTarget(pathSegments: List<String>): BilibiliNavigationTarget? {
        val mediaIndex = pathSegments.indexOfFirst { it.equals("media", ignoreCase = true) }
        if (mediaIndex >= 0) {
            parseBangumiPlayValue(pathSegments.getOrNull(mediaIndex + 1).orEmpty())?.let { return it }
        }
        val seasonIndex = pathSegments.indexOfFirst { it.equals("season", ignoreCase = true) }
        if (seasonIndex < 0) return null

        val next = pathSegments.getOrNull(seasonIndex + 1).orEmpty()
        if (next.equals("ep", ignoreCase = true)) {
            pathSegments.getOrNull(seasonIndex + 2)?.toLongOrNull()?.let {
                return BilibiliNavigationTarget.BangumiEpisode(it)
            }
        }
        next.toLongOrNull()?.let { return BilibiliNavigationTarget.BangumiSeason(it) }
        return null
    }

    private fun parseBangumiPlayValue(value: String): BilibiliNavigationTarget? {
        return when {
            value.startsWith("ss", ignoreCase = true) -> value.removePrefix("ss")
                .removePrefix("SS")
                .toLongOrNull()
                ?.let { BilibiliNavigationTarget.BangumiSeason(it) }

            value.startsWith("ep", ignoreCase = true) -> value.removePrefix("ep")
                .removePrefix("EP")
                .toLongOrNull()
                ?.let { BilibiliNavigationTarget.BangumiEpisode(it) }

            value.startsWith("md", ignoreCase = true) -> value.removePrefix("md")
                .removePrefix("MD")
                .toLongOrNull()
                ?.let { BilibiliNavigationTarget.BangumiSeason(seasonId = 0L, mediaId = it) }

            else -> null
        }
    }

    private fun mapParseResult(result: BilibiliUrlParser.ParseResult): BilibiliNavigationTarget? {
        if (!result.isValid) return null
        result.getVideoId()?.let { return BilibiliNavigationTarget.Video(it) }
        result.getDynamicTargetId()?.let { return BilibiliNavigationTarget.Dynamic(it) }
        return null
    }

    private fun collectShortLinkCandidates(input: String): List<String> {
        val candidates = linkedSetOf<String>()
        val direct = normalizeInput(input)
        if (direct != null && isShortLinkHost(direct)) {
            candidates += direct
        }
        BilibiliUrlParser.extractUrls(input)
            .mapNotNull(::normalizeInput)
            .filter(::isShortLinkHost)
            .forEach(candidates::add)
        return candidates.toList()
    }

    private fun isShortLinkHost(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return isBilibiliShortHost(uri.host)
    }

    private fun normalizeInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if ("://" in trimmed) return trimmed

        return knownHosts.firstOrNull { host ->
            trimmed.startsWith(host, ignoreCase = true)
        }?.let {
            "https://$trimmed"
        }
    }

    private fun extractQueryParameters(encodedQuery: String?): Map<String, String> {
        if (encodedQuery.isNullOrBlank()) return emptyMap()

        return encodedQuery
            .split("&")
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val pair = part.split("=", limit = 2)
                val key = decodeUrlComponentCompat(pair[0], StandardCharsets.UTF_8)
                val value = decodeUrlComponentCompat(pair.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                key to value
            }
            .toMap()
    }

    private fun resolveWrappedUrl(queryMap: Map<String, String>): String? {
        val wrappedUrlKeys = listOf("url", "jump_url", "target_url", "web_url", "origin_url")
        return wrappedUrlKeys.firstNotNullOfOrNull { key ->
            queryMap[key]?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun isNumericId(value: String): Boolean = value.isNotEmpty() && value.all(Char::isDigit)
}

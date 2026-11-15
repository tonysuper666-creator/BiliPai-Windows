package com.android.purebilibili.navigation

/** Shared input bytes for the two compiled consumers, not a second policy implementation. */
internal object DesktopSearchSubmitCases {
    data class Case(val name: String, val input: String, val expected: String)

    val cases = buildList {
        listOf("+", "=", ":", "@", "-", "_", "/", ".", "%2D", "~", "!", "$", "&", "'", "(", ")", "*", ",", ";", "[", "]", "\\", "é").forEachIndexed { index, separator ->
            listOf("b23.tv/abc${separator}123", "https://b23.tv/abc${separator}123", "【标题】 https://b23.tv/abc${separator}123 复制后打开").forEachIndexed { variant, input ->
                add(Case("invalid-path-$index-$variant", input, "search:$input"))
            }
        }
        listOf("https://b23.tv/", "b23.tv/", "https://b23.tv/?a=1", "https://b23.tv/#part", "https://b23.tv/，").forEachIndexed { index, input ->
            add(Case("empty-$index", input, "search:$input"))
        }
        mapOf(
            "b23.tv/AbC123" to "https://b23.tv/AbC123",
            "HTTPS://B23.TV/AbC123" to "HTTPS://B23.TV/AbC123",
            "http://b23.tv/AbC123" to "http://b23.tv/AbC123",
            "https://evil.b23.tv/AbC123" to "https://evil.b23.tv/AbC123",
            "【BV1xx411c7mD 标题】 https://b23.tv/AbC123 复制后打开" to "https://b23.tv/AbC123",
            "看这个https://b23.tv/AbC123哈哈" to "https://b23.tv/AbC123",
            "快看：https://b23.tv/AbC123。" to "https://b23.tv/AbC123",
            "(https://b23.tv/AbC123)" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123." to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123!" to "https://b23.tv/AbC123",
            "https://b23.tv/AbC123?share_source=copy_link" to "https://b23.tv/AbC123?share_source=copy_link",
            "https://b23.tv/AbC123#part" to "https://b23.tv/AbC123#part",
            "https://b23.tv/AbC123?next=a+b=c:@123#part" to "https://b23.tv/AbC123?next=a+b=c:@123#part",
        ).entries.forEachIndexed { index, (input, url) -> add(Case("valid-$index", input, "link:$url")) }
        listOf(
            "https://notb23.tv/abc123", "https://b23.tv.evil.example/abc123",
            "https://example.com/b23.tv/abc123", "file:///b23.tv/abc123", "ftp://b23.tv/abc123",
            "javascript:https://b23.tv/abc123", "https://evil.example@b23.tv/abc123",
            "https://b23.tv@evil.example/abc123", "https://b23.tv:bad/abc123",
            "先看 https://b23.tv/abc+123 再看 https://b23.tv/AbC123",
            "https://b23.tv/abc+123?valid=1#part",
            "猫和老鼠",
        ).forEachIndexed { index, input -> add(Case("text-or-unsafe-$index", input, "search:$input")) }
        add(Case("blank", "  ", ""))
        add(Case("native-bv", "BV1xx411c7mD", "native:Video(videoId=BV1xx411c7mD)"))
        add(Case("native-url", "https://www.bilibili.com/video/BV1xx411c7mD", "native:Video(videoId=BV1xx411c7mD)"))
        add(Case("search-url", "https://search.bilibili.com/all?keyword=%E7%8C%AB", "search:猫"))
    }
}

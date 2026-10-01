// File: feature/article/ArticleRichTextRenderer.kt
package com.android.purebilibili.feature.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 专栏正文的行内富文本渲染：把解析出的字号/颜色/加粗/斜体/删除线
 * span 转成 AnnotatedString。无样式 span 时退化为纯文本。
 */
internal fun buildArticleAnnotatedString(
    spans: List<ArticleTextSpan>,
    defaultColor: Color,
    baseFontSize: TextUnit
): AnnotatedString {
    val styled = spans.filter { span ->
        span.fontSizeSp != null || span.colorArgb != null ||
            span.bold || span.italic || span.strikethrough
    }
    if (styled.isEmpty()) {
        return AnnotatedString(spans.joinToString(separator = "") { it.text })
    }
    return buildAnnotatedString {
        styled.forEachIndexed { index, span ->
            val text = when {
                index == 0 && styled.size > 1 -> span.text.trimStart()
                index == styled.lastIndex && styled.size > 1 -> span.text.trimEnd()
                styled.size == 1 -> span.text.trim()
                else -> span.text
            }
            if (text.isEmpty()) return@forEachIndexed
            val style = SpanStyle(
                color = span.colorArgb?.let(::Color) ?: defaultColor,
                fontSize = span.fontSizeSp?.sp ?: baseFontSize,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null
            )
            withStyle(style) { append(text) }
        }
    }
}

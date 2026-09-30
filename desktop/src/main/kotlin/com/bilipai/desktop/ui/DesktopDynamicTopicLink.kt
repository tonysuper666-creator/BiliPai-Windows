package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.android.purebilibili.data.model.response.RichTextNode
import com.android.purebilibili.feature.dynamic.components.*

/** Decode the original annotated topic payload, including its keyword fallback. */
internal fun desktopDynamicTopicLinkAction(node: RichTextNode): DynamicRichTextLinkAction? {
    val builder = AnnotatedString.Builder()
    with(builder) { appendDynamicRichTextTopic(node, Color.Unspecified, null) }
    val text = builder.toAnnotatedString()
    val tag = (text.getLinkAnnotations(0, text.length).firstOrNull()?.item as? LinkAnnotation.Clickable)?.tag ?: return null
    return resolveDynamicRichTextLinkAction(tag)
}

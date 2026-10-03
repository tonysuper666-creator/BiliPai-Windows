package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.android.purebilibili.feature.live.formatLiveSuperChatCountdown
import com.android.purebilibili.feature.live.resolveLiveSuperChatRemainingSec
import com.android.purebilibili.feature.live.shouldExpireLiveSuperChat
import com.android.purebilibili.feature.live.shouldShowLiveSuperChatFlash
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Swing rendering adapter for the original realtime item model; every queue/cache has a bound. */
internal class LiveDanmakuRenderer(private val scope: CoroutineScope) {
    private data class Entry(val item: LiveDanmakuItem, val comment: DanmakuComment, val receivedNanos: Long,
        val rendered: StyledDesktopDanmaku?)
    private val entries = ArrayDeque<Entry>()
    private val superChats = linkedMapOf<Long, Entry>()
    private val images = LinkedHashMap<String, BufferedImage>(16, 0.75f, true)
    private val fetching = mutableSetOf<String>()
    private val failedImages = linkedSetOf<String>()
    private var imageBytes = 0L
    private var nextId = 1
    private var originNanos = System.nanoTime()
    private var dirty = true
    private var scheduler = DanmakuScheduler(emptyList(),DanmakuSettings(),liveAdmission=true)
    private var lastSettings: DanmakuSettings? = null
    private var lastRebuild = 0L
    private var processor: DanmakuPluginProcessor? = null

    val size: Int get() = entries.count { it.rendered != null } + superChats.values.count { it.rendered != null }
    fun setProcessor(next: DanmakuPluginProcessor?) {
        check(SwingUtilities.isEventDispatchThread())
        processor = next
        val mapped = entries.map { it.copy(rendered = applyDesktopDanmakuPlugin(it.comment, next)) }
        entries.clear(); entries.addAll(mapped)
        superChats.replaceAll { _, value -> value.copy(rendered = applyDesktopDanmakuPlugin(value.comment, next)) }
        dirty = true
    }
    fun reset() {
        entries.clear(); superChats.clear(); originNanos = System.nanoTime(); nextId = 1; dirty = true
        scheduler = DanmakuScheduler(emptyList(),DanmakuSettings(),liveAdmission=true); lastSettings = null; lastRebuild = 0L
    }

    fun add(item: LiveDanmakuItem) {
        check(SwingUtilities.isEventDispatchThread())
        if (item.text.isBlank() && item.emoticonUrl.isNullOrBlank()) return
        val now = System.nanoTime()
        val id = nextId++
        val text = item.text.replace("\u0000", "").take(2_000).ifBlank { "[表情]" }
        val comment = DanmakuComment(id, seconds(now), if (item.mode in listOf(1, 4, 5)) item.mode else 1,
            25, item.color and 0xffffff, text, serverId = item.superChatId, userHash = item.uid.toString())
        val entry = Entry(item.copy(text = text), comment, now, applyDesktopDanmakuPlugin(comment, processor))
        if (item.isSuperChat) {
            val key = item.superChatId.takeIf { it != 0L } ?: -id.toLong()
            superChats.remove(key); superChats[key] = entry
            while (superChats.size > 30) superChats.remove(superChats.keys.first())
        } else {
            entries.addLast(entry)
            while (entries.size > 600) entries.removeFirst()
            dirty = true
            item.emoticonUrl?.let(::fetchImage)
        }
    }

    fun removeSuperChats(ids: List<Long>) { ids.take(1_000).forEach(superChats::remove) }

    fun expire(now: Long = System.nanoTime()) {
        while (entries.firstOrNull()?.let { now - it.receivedNanos > 90_000_000_000L } == true) { entries.removeFirst(); dirty = true }
        superChats.entries.removeAll { shouldExpireLiveSuperChat(it.value.item.superChatDuration, ((now - it.value.receivedNanos) / 1_000_000_000).toInt()) }
    }

    fun paint(context: Graphics2D, width: Int, height: Int, danmakuHeight:Int, config:DanmakuRenderConfig, settings: DanmakuSettings) {
        val now = System.nanoTime()
        expire(now)
        val safeSettings = settings.normalized()
        if (lastSettings != safeSettings || (dirty && now - lastRebuild > 100_000_000L)) {
            scheduler = DanmakuScheduler(entries.mapNotNull { it.rendered?.comment }, safeSettings,liveAdmission=true)
            lastSettings = safeSettings; lastRebuild = now; dirty = false
        }
        val rowHeight=config.lineHeightPx.toInt().coerceAtLeast(1)
        val indexed = entries.associateBy { it.comment.id }
        fun font(entry: Entry?): Font {
            val style = entry?.rendered?.style
            return desktopDanmakuFont(config,entry?.rendered?.comment ?: entry?.comment ?: error("Live font requested without its entry"),style?.scale ?: 1f,style?.bold==true)
        }
        fun image(entry: Entry?): BufferedImage? {
            val raw = entry?.item?.emoticonUrl ?: return null
            val url = trustedImageUrl(raw) ?: return null
            return images[url].also { if (it == null) fetchImage(url) }
        }
        scheduler.frame(seconds(now), width, danmakuHeight, config) { comment ->
            val graphic = image(indexed[comment.id])
            val metrics=context.getFontMetrics(font(indexed[comment.id]))
            DesktopDanmakuTextMetrics(
                if (graphic != null) (rowHeight * graphic.width.toDouble() / graphic.height.coerceAtLeast(1)).toInt().coerceIn(rowHeight, rowHeight * 5)
                else metrics.stringWidth(comment.text),
                if(graphic!=null)rowHeight.toDouble() else metrics.ascent.toDouble(),
            )
        }.forEach { positioned ->
            val graphic = image(indexed[positioned.comment.id])
            val entry = indexed[positioned.comment.id]
            val style = entry?.rendered?.style
            val font = font(entry)
            pluginAwtColor(style?.backgroundColor)?.let { color ->
                val metrics = context.getFontMetrics(font)
                context.color = color
                context.fillRoundRect(positioned.x.toInt() - 4, positioned.baseline.toInt() - metrics.ascent - 2,
                    positioned.textWidth + 8, metrics.height + 4, 6, 6)
            }
            if (graphic != null) {
                context.drawImage(graphic, positioned.x.toInt(), (positioned.baseline - rowHeight).toInt(), positioned.textWidth, rowHeight - 2, null)
            } else {
                val shape = font.createGlyphVector(context.fontRenderContext, positioned.comment.text).getOutline(positioned.x.toFloat(), positioned.baseline.toFloat())
                if (safeSettings.strokeEnabled && safeSettings.strokeWidth > 0) {
                    context.color = pluginAwtColor(style?.borderColor) ?: Color.BLACK
                    context.stroke = BasicStroke(config.strokeWidthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); context.draw(shape)
                }
                context.color = pluginAwtColor(style?.textColor) ?: Color(positioned.comment.color)
                context.fill(shape)
            }
        }
        if (shouldShowLiveSuperChatFlash(true, safeSettings.enabled, safeSettings.allowSpecial)) {
            val cardWidth = (width * 0.38).toInt().coerceIn(130, 360).coerceAtMost(width - 12)
            var top = 8
            superChats.values.toList().takeLast(3).asReversed().forEach { entry ->
                val rendered = entry.rendered ?: return@forEach
                if (!safeSettings.enabled || !safeSettings.allowSpecial || com.android.purebilibili.feature.video.danmaku.shouldBlockDanmakuByRules(rendered.comment.text,safeSettings.blockedRules+safeSettings.blockedKeywords,rendered.comment.userHash)) return@forEach
                val style = rendered.style
                val cardFont = config.typeface.deriveFont((config.textSizePx*0.7f*(style?.scale ?: 1f)).coerceAtLeast(1f)).let {
                    if(style?.bold==true)it.deriveFont(mapOf(java.awt.font.TextAttribute.WEIGHT to java.awt.font.TextAttribute.WEIGHT_BOLD)) else it
                }
                val elapsed = ((now - entry.receivedNanos) / 1_000_000_000).toInt()
                val remaining = resolveLiveSuperChatRemainingSec(entry.item.superChatDuration, elapsed)
                val metrics = context.getFontMetrics(cardFont)
                val body = wrap(rendered.comment.text, cardWidth - 16, metrics::stringWidth).take(4)
                val cardHeight = (body.size + 1) * metrics.height + 14
                if (top + cardHeight > height) return@forEach
                context.color = pluginAwtColor(style?.backgroundColor) ?: Color(entry.item.superChatBackgroundColor.takeIf { it != 0 } ?: 0xdd5b6a)
                context.fillRoundRect(width - cardWidth - 8, top, cardWidth, cardHeight, 12, 12)
                pluginAwtColor(style?.borderColor)?.let { color ->
                    context.color = color; context.stroke = BasicStroke(2f)
                    context.drawRoundRect(width - cardWidth - 8, top, cardWidth, cardHeight, 12, 12)
                }
                context.color = pluginAwtColor(style?.textColor) ?: Color.WHITE; context.font = cardFont
                val headline = "${entry.item.uname.take(14)} ¥${entry.item.superChatPrice} · ${formatLiveSuperChatCountdown(remaining)}"
                context.drawString(headline.take(60), width - cardWidth, top + metrics.ascent + 5)
                body.forEachIndexed { index, line -> context.drawString(line, width - cardWidth, top + (index + 2) * metrics.height) }
                top += cardHeight + 6
            }
        }
    }

    private fun seconds(now: Long) = ((now - originNanos) / 1_000_000_000.0).coerceAtLeast(0.0)

    private fun fetchImage(raw: String) {
        val url = trustedImageUrl(raw) ?: return
        if (images.containsKey(url) || url in fetching || url in failedImages || fetching.size >= 4) return
        fetching.add(url)
        scope.launch(Dispatchers.IO) {
            val image = runCatching {
                imageClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "Emoticon request failed" }
                    val body = response.body
                    check(body.contentLength() <= MAX_IMAGE_BYTES)
                    val bytes = body.byteStream().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
                    check(bytes.size <= MAX_IMAGE_BYTES)
                    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: error("Unsupported emoticon image")
                        try {
                            reader.input = stream
                            val width = reader.getWidth(0); val height = reader.getHeight(0)
                            check(width in 1..4_096 && height in 1..4_096 && width.toLong() * height <= 1_000_000) { "Emoticon dimensions are too large" }
                            reader.read(0)
                        } finally { reader.dispose() }
                    }
                }
            }.getOrNull()
            SwingUtilities.invokeLater {
                fetching.remove(url)
                if (image != null) {
                    images[url] = image; imageBytes += image.width.toLong() * image.height * 4
                    while (images.size > 64 || imageBytes > 16 * 1024 * 1024) {
                        val key = images.keys.first(); val removed = requireNotNull(images.remove(key))
                        imageBytes -= removed.width.toLong() * removed.height * 4
                    }
                    dirty = true
                } else {
                    failedImages.add(url)
                    while (failedImages.size > 128) failedImages.remove(failedImages.first())
                }
            }
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private val imageClient by lazy { OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Referer", "https://live.bilibili.com/")
                .header("User-Agent", PlaybackSource.DEFAULT_USER_AGENT).build()) }.build() }

        internal fun trustedImageUrl(raw: String): String? {
            val normalized = if (raw.startsWith("//")) "https:$raw" else raw
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            return normalized.takeIf { uri.scheme == "https" && uri.userInfo == null && normalized.length <= 4_000 &&
                (host == "hdslb.com" || host.endsWith(".hdslb.com") || host == "bilibili.com" || host.endsWith(".bilibili.com")) }
        }

        private fun wrap(text: String, width: Int, measure: (String) -> Int): List<String> {
            val lines = mutableListOf<String>(); val line = StringBuilder()
            text.take(500).forEach { char ->
                if (char == '\n' || (line.isNotEmpty() && measure(line.toString() + char) > width)) { lines.add(line.toString()); line.clear() }
                if (char != '\n' && char != '\r') line.append(char)
            }
            if (line.isNotEmpty()) lines.add(line.toString())
            return lines
        }
    }
}

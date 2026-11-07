package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Desktop painterResource / vectorResource / imageResource for upstream `R.drawable` and `R.mipmap` ids (TON-1072),
 * over the files and index that generateUpstreamDrawableResources packages from the upstream app res directories.
 * The variant is chosen the way Android chooses it: -night under a dark app theme, anydpi before density buckets, the
 * nearest bucket at or above the window density. Colour references were resolved at build time; the vector root
 * `android:tint` (SrcIn) and `android:autoMirrored` that the desktop XML parser does not read are applied here.
 * An unknown id, or a drawable kind the desktop does not draw, throws instead of painting nothing.
 */
@Composable
fun painterResource(id: Int): Painter {
    val density = LocalDensity.current
    val variant = DesktopDrawableTable.select(id, com.bilipai.desktop.appearance.isDesktopInDarkTheme(), density.densityDpi())
    return painterFor(variant, density)
}

@Composable
fun ImageVector.Companion.vectorResource(id: Int): ImageVector {
    val density = LocalDensity.current
    val variant = DesktopDrawableTable.select(id, com.bilipai.desktop.appearance.isDesktopInDarkTheme(), density.densityDpi())
    if (variant.kind != "vector") throw IllegalArgumentException("${variant.name} is a ${variant.kind} drawable, not a vector")
    return remember(variant, density) { DesktopDrawableTable.vector(variant, density) }
}

@Composable
fun ImageBitmap.Companion.imageResource(id: Int): ImageBitmap {
    val density = LocalDensity.current
    val variant = DesktopDrawableTable.select(id, com.bilipai.desktop.appearance.isDesktopInDarkTheme(), density.densityDpi())
    if (variant.kind != "raster") throw IllegalArgumentException("${variant.name} is a ${variant.kind} drawable, not a bitmap")
    return remember(variant, density) { DesktopDrawableTable.scaledBitmap(variant, density.densityDpi()) }
}

@Composable
private fun painterFor(variant: DesktopDrawableTable.Variant, density: Density): Painter = when (variant.kind) {
    "vector" -> rememberVectorPainter(remember(variant, density) { DesktopDrawableTable.vector(variant, density) })
    "raster" -> remember(variant, density) {
        DesktopDrawableTable.bitmap(variant).let { DensityScaledBitmapPainter(it, DesktopDrawableTable.bitmapScale(variant, density.densityDpi())) }
    }
    "adaptive" -> {
        val (background, foreground) = remember(variant) { DesktopDrawableTable.adaptiveLayers(variant) }
        AdaptiveIconPainter(layerPainter(background, density), layerPainter(foreground, density), density)
    }
    else -> DesktopDrawableTable.unsupported(variant)
}

@Composable
private fun layerPainter(layer: DesktopDrawableTable.Layer, density: Density): Painter = when (layer) {
    is DesktopDrawableTable.Layer.Solid -> remember(layer) { ColorPainter(layer.color) }
    is DesktopDrawableTable.Layer.Resource -> painterFor(
        DesktopDrawableTable.select(layer.id, com.bilipai.desktop.appearance.isDesktopInDarkTheme(), density.densityDpi()), density)
}

internal fun Density.densityDpi(): Int = (density * 160f).roundToInt()

/** A bitmap drawn at its Android size: bucket pixels scaled by window dpi / bucket dpi (nodpi keeps its pixels). */
internal class DensityScaledBitmapPainter(private val image: ImageBitmap, private val scale: Float) : Painter() {
    override val intrinsicSize: Size get() = Size(image.width * scale, image.height * scale)
    override fun DrawScope.onDraw() {
        drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}

/**
 * AdaptiveIconDrawable: both 108dp layers are drawn at 150% of the bounds, centred, inside the system icon mask, which is
 * the AOSP default circle (config_icon_mask). The intrinsic size is the 72dp visible area.
 */
internal class AdaptiveIconPainter(private val background: Painter, private val foreground: Painter, density: Density) : Painter() {
    private val side = 72f * density.density
    override val intrinsicSize: Size get() = Size(side, side)
    override fun DrawScope.onDraw() {
        val mask = Path().apply { addOval(Rect(Offset.Zero, size)) }
        clipPath(mask) {
            val layer = Size(size.width * 1.5f, size.height * 1.5f)
            translate(-size.width * 0.25f, -size.height * 0.25f) {
                with(background) { draw(layer) }
                with(foreground) { draw(layer) }
            }
        }
    }
}

internal object DesktopDrawableTable {
    class Variant(val id: Int, val name: String, val night: Boolean, val density: Int, val kind: String, val path: String)
    sealed interface Layer {
        data class Solid(val color: Color) : Layer
        data class Resource(val id: Int) : Layer
    }

    private val variants: Map<Int, List<Variant>> by lazy {
        val stream = DesktopDrawableTable::class.java.classLoader.getResourceAsStream("upstream-drawables/index.tsv")
            ?: throw IllegalStateException("The generated upstream drawable table (upstream-drawables/index.tsv) is not on the classpath")
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val (id, name, night, density, kind, path) = line.split('\t').let { Sextuple(it) }
                Variant(id.removePrefix("0x").toInt(16), name, night == "1", density.toInt(), kind, path)
            }.toList()
        }.groupBy { it.id }
    }
    private val ids: Map<String, Int> by lazy { variants.values.flatten().associate { it.name to it.id } }

    private class Sextuple(private val parts: List<String>) {
        init { require(parts.size == 6) { "Malformed upstream drawable index row: $parts" } }
        operator fun component1() = parts[0]; operator fun component2() = parts[1]; operator fun component3() = parts[2]
        operator fun component4() = parts[3]; operator fun component5() = parts[4]; operator fun component6() = parts[5]
    }

    fun idOf(name: String): Int = ids[name] ?: throw IllegalArgumentException("No upstream drawable resource $name")

    /** The variant Android would pick; an alias (res/values `<item type="mipmap">`) resolves to its target under the same configuration. */
    fun select(id: Int, dark: Boolean, densityDpi: Int): Variant {
        val variant = selectRow(id, dark, densityDpi)
        return if (variant.kind == "alias") select(idOf(variant.path), dark, densityDpi) else variant
    }

    private fun selectRow(id: Int, dark: Boolean, densityDpi: Int): Variant {
        val all = variants[id] ?: throw IllegalArgumentException("No drawable resource 0x${id.toString(16)} in the generated desktop drawable table")
        val pool = all.filter { it.night }.takeIf { dark && it.isNotEmpty() } ?: all.filter { !it.night }
        if (pool.isEmpty()) throw IllegalArgumentException("${all.first().name} has only -night variants and the app theme is light")
        pool.firstOrNull { it.density == -2 }?.let { return it }
        val buckets = pool.filter { it.density > 0 }.sortedBy { it.density }
        if (buckets.isNotEmpty()) return buckets.firstOrNull { it.density >= densityDpi } ?: buckets.last()
        return pool.firstOrNull { it.density == 0 } ?: pool.first()
    }

    /**
     * The app theme for image requests made outside composition (coil Int models): coil requests carry no theme, the
     * way Android resolves them against the activity configuration. DesktopAppearanceTheme keeps it current.
     */
    @Volatile var nightForImageRequests: Boolean = false

    fun encoded(variant: Variant): ByteArray = bytes(variant)

    private fun bytes(variant: Variant): ByteArray =
        DesktopDrawableTable::class.java.classLoader.getResourceAsStream("upstream-drawables/${variant.path}")?.use { it.readBytes() }
            ?: throw IllegalStateException("Packaged upstream drawable ${variant.path} is missing")

    fun unsupported(variant: Variant): Nothing = when (variant.kind) {
        "unavailable" -> throw UnsupportedDesktopCapability("Drawable ${variant.name}", variant.path)
        else -> throw UnsupportedDesktopCapability("<${variant.kind}> drawable ${variant.name}",
            "Android splash-screen compositions are not drawn by the desktop")
    }

    private val vectors = ConcurrentHashMap<Pair<Variant, Float>, ImageVector>()
    private val bitmaps = ConcurrentHashMap<Variant, ImageBitmap>()

    fun vector(variant: Variant, density: Density): ImageVector = vectors.getOrPut(variant to density.density) {
        val text = bytes(variant).toString(Charsets.UTF_8)
        val parsed = loadXmlImageVector(org.xml.sax.InputSource(text.reader()), density)
        val root = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(org.xml.sax.InputSource(text.reader())).documentElement
        val tint = root.getAttributeNS(ANDROID, "tint").takeIf { it.isNotEmpty() }?.let { parseHexColour(it, variant.name) }
        val mirror = root.getAttributeNS(ANDROID, "autoMirrored") == "true"
        parsed.withRootAttributes(tint, mirror)
    }

    fun bitmap(variant: Variant): ImageBitmap = bitmaps.getOrPut(variant) {
        org.jetbrains.skia.Image.makeFromEncoded(bytes(variant)).toComposeImageBitmap()
    }

    fun bitmapScale(variant: Variant, densityDpi: Int): Float = when (variant.density) {
        -1 -> 1f
        0 -> densityDpi / 160f
        else -> densityDpi / variant.density.toFloat()
    }

    /** ImageBitmap.imageResource decodes at the window density like BitmapFactory.decodeResource. */
    fun scaledBitmap(variant: Variant, densityDpi: Int): ImageBitmap {
        val source = bitmap(variant)
        val scale = bitmapScale(variant, densityDpi)
        if (scale == 1f) return source
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val target = ImageBitmap(width, height)
        androidx.compose.ui.graphics.Canvas(target).drawImageRect(source, dstSize = IntSize(width, height),
            paint = androidx.compose.ui.graphics.Paint().apply { filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium })
        return target
    }

    fun adaptiveLayers(variant: Variant): Pair<Layer, Layer> {
        val root = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(org.xml.sax.InputSource(bytes(variant).inputStream())).documentElement
        fun layer(tag: String): Layer {
            val element = root.getElementsByTagName(tag).item(0) as? org.w3c.dom.Element
                ?: throw IllegalStateException("${variant.name}: adaptive icon without <$tag>")
            val reference = element.getAttributeNS(ANDROID, "drawable")
            return when {
                reference.startsWith("#") -> Layer.Solid(Color(parseHexColour(reference, variant.name).value))
                reference.startsWith("@drawable/") || reference.startsWith("@mipmap/") -> Layer.Resource(idOf(reference.removePrefix("@")))
                else -> throw UnsupportedDesktopCapability("${variant.name} <$tag> `$reference`", "only colour and drawable layers are drawn by the desktop")
            }
        }
        return layer("background") to layer("foreground")
    }

    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    private fun parseHexColour(text: String, where: String): Color {
        val digits = text.removePrefix("#")
        val argb = when (digits.length) {
            3 -> "FF" + digits.map { "$it$it" }.joinToString("")
            4 -> digits.map { "$it$it" }.joinToString("")
            6 -> "FF$digits"
            8 -> digits
            else -> throw IllegalArgumentException("$where: unsupported colour `$text`")
        }
        return Color(argb.toLong(16).toInt())
    }

    private fun ImageVector.withRootAttributes(tint: Color?, mirror: Boolean): ImageVector {
        if (tint == null && !mirror) return this
        val builder = ImageVector.Builder(name, defaultWidth, defaultHeight, viewportWidth, viewportHeight,
            tintColor = tint ?: Color.Unspecified, tintBlendMode = BlendMode.SrcIn, autoMirror = mirror)
        fun copy(group: VectorGroup) {
            for (node in group) when (node) {
                is VectorGroup -> {
                    builder.addGroup(node.name, node.rotation, node.pivotX, node.pivotY, node.scaleX, node.scaleY,
                        node.translationX, node.translationY, node.clipPathData)
                    copy(node)
                    builder.clearGroup()
                }
                is VectorPath -> builder.addPath(node.pathData, node.pathFillType, node.name, node.fill, node.fillAlpha,
                    node.stroke, node.strokeAlpha, node.strokeLineWidth, node.strokeLineCap, node.strokeLineJoin,
                    node.strokeLineMiter, node.trimPathStart, node.trimPathEnd, node.trimPathOffset)
            }
        }
        copy(root)
        return builder.build()
    }
}

package coil3.request

/**
 * coil3's Android-only `allowHardware` for upstream requests (`import coil3.request.allowHardware`). On Android it only
 * permits (true) or forbids (false) hardware bitmaps. The desktop has none: it always decodes to a Skia raster bitmap
 * whose pixels can be read, which satisfies both values, so the request is returned unchanged.
 */
@Suppress("UNUSED_PARAMETER")
fun ImageRequest.Builder.allowHardware(enable: Boolean): ImageRequest.Builder = this

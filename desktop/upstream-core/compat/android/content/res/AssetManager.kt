package android.content.res

import java.io.InputStream

/** Desktop stand-in for android.content.res.AssetManager: [open] reads a packaged asset by name; a missing one throws. */
class AssetManager(private val opener: (String) -> InputStream) {
    fun open(fileName: String): InputStream = opener(fileName)
}

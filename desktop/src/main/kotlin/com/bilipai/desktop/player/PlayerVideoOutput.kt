package com.bilipai.desktop.player

/** Safe current native GPU/video metadata. No media addresses, plugin code or request credentials. */
data class PlayerVideoOutputState(
    val sourceVersion: Long = 0,
    val maximumTextureDimension: Int? = null,
    val intermediateFormat: String? = null,
    /** Pre-filter decoded size; never feed an enhanced output back into its own sizing policy. */
    val inputWidth: Int = 0,
    val inputHeight: Int = 0,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val gamma: String? = null,
    val dolbyVisionProfile: Int? = null,
    /** Actual same-poll OSD video bounds; absent while no owned native video is ready. */
    val viewport:PlayerVideoViewport? = null,
    /** Actual video HWND's monitor, including Windows' HDR user switch and active color mode. */
    val hdrDisplay: WindowsHdrDisplayState = WindowsHdrDisplayState(),
)

/** Native OSD observations, including legitimate negative crop/pan margins. No aspect/fit inference. */
data class PlayerVideoViewport(val osdWidth:Int,val osdHeight:Int,val left:Int,val top:Int,val contentWidth:Int,val contentHeight:Int) {
    init {require(osdWidth>0&&osdHeight>0&&contentWidth>0&&contentHeight>0)}
    fun sourceToPhysicalTransform(width:Int,height:Int,sourceWidth:Int,sourceHeight:Int):java.awt.geom.AffineTransform {
        require(width>0&&height>0&&sourceWidth>0&&sourceHeight>0)
        return java.awt.geom.AffineTransform.getScaleInstance(width.toDouble()/osdWidth,height.toDouble()/osdHeight).apply {
            translate(left.toDouble(),top.toDouble())
            scale(contentWidth.toDouble()/sourceWidth,contentHeight.toDouble()/sourceHeight)
        }
    }
}

/** These two exact strings are emitted by the pinned vo=gpu backend, independently of an account/source. */
internal sealed interface NativeVideoCapability {
    data class MaximumTextureDimension(val dimension: Int) : NativeVideoCapability
    data class IntermediateFormat(val name: String) : NativeVideoCapability
}

internal fun parseNativeVideoCapability(prefix: String, text: String): NativeVideoCapability? {
    val line = text.trim()
    if (prefix == "vo/gpu/d3d11") {
        val match = Regex("Maximum Texture2D size: ([0-9]+)x([0-9]+)").matchEntire(line) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        if (width != height || width !in 1..0x08000000) return null
        return NativeVideoCapability.MaximumTextureDimension(width)
    }
    if (prefix == "vo/gpu") {
        val match = Regex("Using FBO format ([a-z][a-z0-9_]{0,31})\\.").matchEntire(line) ?: return null
        return NativeVideoCapability.IntermediateFormat(match.groupValues[1])
    }
    return null
}

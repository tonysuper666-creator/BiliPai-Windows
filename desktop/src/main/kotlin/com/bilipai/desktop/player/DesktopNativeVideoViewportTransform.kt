package com.bilipai.desktop.player

/** Owned MPV render properties, not an Android texture/surface capability. */
internal data class DesktopNativeVideoViewportTransform(
    val resizeMode:Int,val scale:Float,val panX:Float,val panY:Float,val flipHorizontal:Boolean,val flipVertical:Boolean,
) {
    init {require(resizeMode in 0..4);require(scale.isFinite() && scale>0 && panX.isFinite() && panY.isFinite())}
    fun nativeProperties():List<Pair<String,String>> = listOf(
        "keepaspect" to if(resizeMode==3)"no"else"yes",
        "panscan" to if(resizeMode==4)"1"else"0",
        "video-zoom" to (kotlin.math.ln(scale.coerceIn(0.1f,10f).toDouble())/kotlin.math.ln(2.0)).toString(),
        // NativeViewport converts the original pixel pans to actual viewport fractions before constructing this value.
        "video-pan-x" to panX.coerceIn(-3f,3f).toString(), "video-pan-y" to panY.coerceIn(-3f,3f).toString(),
    )
}

package android.graphics

/** The edge-treatment constants of android.graphics.Shader that upstream passes to RenderEffect.createBlurEffect. */
open class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR, DECAL }
}

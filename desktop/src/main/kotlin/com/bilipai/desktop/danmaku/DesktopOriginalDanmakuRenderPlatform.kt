package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.DanmakuConfig
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.danmaku.parser.resolveBilibiliDanmakuFontScale
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Window
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import javax.swing.UIManager
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Required platform carriers; the original pure configuration algorithms own every computed value. */
internal interface DesktopOriginalDanmakuRenderPlatform {
    fun resolveTypeface(fontWeight:Int):Font
    fun systemChromeInsetPx():Int
    fun maximumDisplayShortSidePx():Float
}

/** Uses the existing mounted Root window and its actual installed look-and-feel font. */
internal class DesktopWindowsDanmakuRenderPlatform(private val rootWindow:()->Window):DesktopOriginalDanmakuRenderPlatform {
    override fun resolveTypeface(fontWeight:Int):Font {
        val window=rootWindow()
        val font=window.font ?: requireNotNull(UIManager.getFont("Label.font")) { "Mounted Root has no platform UI font" }
        val weight=when(fontWeight.coerceIn(1,9)) {
            1->TextAttribute.WEIGHT_EXTRA_LIGHT
            2->TextAttribute.WEIGHT_LIGHT
            3->TextAttribute.WEIGHT_DEMILIGHT
            4->TextAttribute.WEIGHT_REGULAR
            5->TextAttribute.WEIGHT_MEDIUM
            6->TextAttribute.WEIGHT_SEMIBOLD
            7->TextAttribute.WEIGHT_BOLD
            8->TextAttribute.WEIGHT_HEAVY
            else->TextAttribute.WEIGHT_ULTRABOLD
        }
        return font.deriveFont(mapOf(TextAttribute.WEIGHT to weight,TextAttribute.POSTURE to TextAttribute.POSTURE_REGULAR))
    }
    override fun maximumDisplayShortSidePx():Float {
        val mode=requireNotNull(rootWindow().graphicsConfiguration) { "Mounted Root has no monitor configuration" }.device.displayMode
        check(mode.width>0 && mode.height>0) { "Current physical monitor has no valid display mode" }
        return minOf(mode.width,mode.height).toFloat()
    }
    override fun systemChromeInsetPx():Int {
        val window=rootWindow()
        val transform=requireNotNull(window.graphicsConfiguration).defaultTransform
        return (window.insets.top*hypot(transform.shearX,transform.scaleY)).roundToInt().coerceAtLeast(0)
    }
}

internal object DesktopDanmakuConfigLog {
    private val logger=System.getLogger("BiliPai.DanmakuConfig")
    fun i(tag:String,message:String) {logger.log(System.Logger.Level.DEBUG,"$tag: $message")}
}

/** AWT paints in logical units; original algorithms receive the corresponding actual device pixels. */
internal data class DesktopDanmakuPaintGeometry(val viewport:DanmakuViewport,val scaleX:Double,val scaleY:Double) {
    fun configurePhysicalPixels(context:Graphics2D) {context.scale(1.0/scaleX,1.0/scaleY)}
    companion object {
        fun from(width:Int,height:Int,transform:AffineTransform,referenceShortSidePx:Float):DesktopDanmakuPaintGeometry? {
            val x=hypot(transform.scaleX,transform.shearY)
            val y=hypot(transform.shearX,transform.scaleY)
            if(!x.isFinite()||!y.isFinite()||x<=0.0||y<=0.0)return null
            val viewport=resolveDanmakuViewport((width*x).roundToInt(),(height*y).roundToInt(),y.toFloat()) ?: return null
            return DesktopDanmakuPaintGeometry(viewport,x,y)
        }
    }
}

/** Ephemeral settings-to-config projection. No persistent keys, pool, account or renderer is constructed. */
internal fun DanmakuSettings.originalConfig(platform:DesktopOriginalDanmakuRenderPlatform):DanmakuConfig =
    DanmakuConfig(platform).also {
        it.isEnabled=enabled;it.opacity=opacity;it.fontScale=fontScale;it.fontWeight=fontWeight
        it.speedFactor=speedFactor;it.scrollDurationSeconds=scrollDurationSeconds;it.displayAreaRatio=displayAreaRatio
        it.lineHeight=lineHeight;it.strokeEnabled=strokeEnabled;it.strokeWidth=strokeWidth
        it.staticDurationSeconds=staticDurationSeconds;it.scrollFixedVelocity=scrollFixedVelocity
        it.staticDanmakuToScroll=staticDanmakuToScroll;it.massiveMode=massiveMode
        it.mergeDuplicates=mergeDuplicates;it.duplicateMergeWindowMs=duplicateMergeWindowMs
        it.duplicateMergeCountThreshold=duplicateMergeCountThreshold
        it.allowScroll=allowScroll;it.allowTop=allowTop;it.allowBottom=allowBottom
        it.allowColorful=allowColorful;it.allowSpecial=allowSpecial;it.weightFilterLevel=weightFilterLevel
        it.blockedRules=(blockedKeywords+blockedRules).distinct()
        it.smartOcclusionEnabled=smartOcclusionEnabled
        // Original smart-on default band; updateFaceOcclusion has no caller at the fixed tag.
        it.safeBandTopRatio=0f;it.safeBandBottomRatio=if(smartOcclusionEnabled)displayAreaRatio else 1f
        // Portrait SCREEN_TOP remains a separate actual-layout consumer.
    }

internal fun desktopDanmakuFont(config:DanmakuRenderConfig,comment:DanmakuComment,pluginScale:Float,pluginBold:Boolean):Font {
    val size=config.textSizePx*resolveBilibiliDanmakuFontScale(comment.size.toFloat())*pluginScale
    val font=config.typeface.deriveFont(size.coerceAtLeast(1f))
    return if(pluginBold)font.deriveFont(mapOf(TextAttribute.WEIGHT to TextAttribute.WEIGHT_BOLD)) else font
}

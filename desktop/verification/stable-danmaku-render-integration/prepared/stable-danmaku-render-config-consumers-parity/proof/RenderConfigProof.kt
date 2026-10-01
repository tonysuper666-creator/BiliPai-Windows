package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.*
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.live.*
import java.awt.Font
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.UIManager
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

private var assertions=0
private fun expect(value:Boolean,label:String) {assertions++;check(value){label}}
private fun equal(a:Any?,b:Any?,label:String)=expect(a==b,"$label expected=$b actual=$a")
private val actualLafFont=requireNotNull(UIManager.getFont("Label.font"))
/** Explicit fixture platform: a real installed AWT font and declared 16px fixture chrome; no Root window is constructed. */
private val fixturePlatform=object:DesktopOriginalDanmakuRenderPlatform {
    override fun resolveTypeface(fontWeight:Int):Font=actualLafFont.deriveFont(mapOf(java.awt.font.TextAttribute.WEIGHT to java.awt.font.TextAttribute.WEIGHT_REGULAR))
    override fun systemChromeInsetPx()=16
}
private fun comment(id:Int,time:Double=0.0,mode:Int=1)=DanmakuComment(id,time,mode,25,0xffffff,"Comment $id")

fun main(args:Array<String>) {
    val output=File(args.single()).apply {mkdirs()}
    equal(resolveDanmakuScrollDurationMillis(1f,0.5f,false,1080),2000L,"minimum renderer duration")
    equal(resolveDanmakuScrollDurationMillis(50f,3f,false,1080),20000L,"saved wide range resolved by original clamp")
    equal(resolveDanmakuScrollDurationMillis(7f,1f,true,2160),14000L,"fixed velocity wide")
    equal(resolveDanmakuScrollDurationMillis(7f,1f,true,540),5250L,"fixed velocity minimum viewport factor")
    equal(resolveDanmakuScrollDurationMillis(7f,1f,false,2160),7000L,"fixed velocity disabled")
    equal(resolveDanmakuPinnedDurationMillis(1f),2000L,"pinned minimum")
    equal(resolveDanmakuPinnedDurationMillis(50f),15000L,"pinned maximum")
    equal(resolveDanmakuMinimumVisibleLines(.25f),2,"quarter minimum")
    equal(resolveDanmakuMinimumVisibleLines(.5f),3,"half minimum")
    equal(resolveDanmakuMinimumVisibleLines(.75f),5,"three quarter minimum")
    equal(resolveDanmakuMinimumVisibleLines(1f),6,"full minimum")
    equal(resolveDanmakuFallbackMaxLines(.25f),4,"quarter fallback")
    equal(resolveDanmakuFallbackMaxLines(.5f),8,"half fallback")
    equal(resolveDanmakuFallbackMaxLines(.75f),12,"three quarter fallback")
    equal(resolveDanmakuFallbackMaxLines(1f),16,"full fallback")
    equal(resolveDanmakuTextSizePx(DanmakuViewport(360,720,1f,1f),1f),20f,"original VOD20px base")
    equal(resolveDanmakuTextSizePx(DanmakuViewport(540,1080,1.5f,1f),1f),30f,"real DPI input")
    equal(resolveDanmakuLayerLineHeightPx(20f,3f),44f,"render line height clamp")
    val normal=DanmakuSettings().originalConfig(fixturePlatform).resolveRenderConfig(DanmakuViewport(360,720,1f,1f))
    equal(normal.lineCount,6,"original normal tracks")
    val massive=DanmakuSettings(massiveMode=true).originalConfig(fixturePlatform).resolveRenderConfig(DanmakuViewport(360,720,1f,1f))
    equal(massive.lineCount,7,"massive still constrained by exact engine budget")
    equal(normal.topMarginPx,0f,"original fallback top")
    equal(normal.bottomMarginPx,360f,"original selected-area bottom inset")
    equal(normal.alpha,216,"original quantized alpha")
    equal(normal.typeface,actualLafFont.deriveFont(mapOf(java.awt.font.TextAttribute.WEIGHT to java.awt.font.TextAttribute.WEIGHT_REGULAR)),"actual platform font")
    val config=DanmakuSettings().originalConfig(fixturePlatform)
    config.smartOcclusionEnabled=true;config.safeBandTopRatio=.4f;config.safeBandBottomRatio=.6f
    val band=config.resolveRenderConfig(DanmakuViewport(360,720,1f,1f))
    expect(abs(band.topMarginPx-288f)<.001f,"original normalized band top pure algorithm")
    expect(abs(band.bottomMarginPx-288f)<.001f,"original normalized band bottom pure algorithm")
    config.safeBandBottomRatio=.45f
    equal(config.resolveRenderConfig(DanmakuViewport(360,720,1f,1f)).topMarginPx,0f,"narrow detected band original fallback")
    equal(DanmakuConfig.getStatusBarHeight(fixturePlatform),16,"required platform chrome port, not Android resources")
    equal(resolveBilibiliDanmakuFontScale(18f),.72f,"sole original 18 size helper")
    equal(resolveBilibiliDanmakuFontScale(25f),1f,"sole original normal size helper")
    equal(resolveBilibiliDanmakuFontScale(36f),1.44f,"sole original 36 size helper")
    equal(resolveBilibiliDanmakuFontScale(Float.NaN),1f,"original invalid size fallback")
    equal(resolveDanmakuRenderLayerType(4,true),DANMAKU_LAYER_SCROLL,"bottom conversion")
    equal(resolveDanmakuRenderLayerType(5,true),DANMAKU_LAYER_SCROLL,"top conversion")
    equal(resolveDanmakuRenderLayerType(6,true),DANMAKU_LAYER_REVERSE,"reverse preserved")
    val low=comment(1).copy(weight=1,originalElement=DanmakuProto.DanmakuElem(weight=1,isSelf=false))
    val self=low.copy(originalElement=DanmakuProto.DanmakuElem(weight=1,isSelf=true))
    val weighted=DanmakuSettings(weightFilterLevel=5)
    expect(!weighted.allows(low),"video original low weight rejected")
    expect(weighted.allows(self),"video original packet self survives low weight")
    expect(!weighted.allows(low.copy(originalElement=null,userHash="own-mid")),"XML remains original false; no guessed MID/hash exemption")
    expect(weighted.allows(low.copy(weight=5)),"threshold weight accepted")
    expect(weighted.allowsLive(low),"original live has no video-only weight filter")
    val converted=DanmakuSettings(staticDanmakuToScroll=true,allowTop=false,allowScroll=true)
    expect(converted.allows(comment(2,mode=5)),"video converted type matches scroll toggle")
    expect(!converted.allowsLive(comment(2,mode=5)),"original live filters incoming type before layer conversion")
    expect(!converted.copy(allowTop=true,allowScroll=false).allows(comment(2,mode=5)),"converted video obeys scroll block")
    equal(DanmakuSettings(weightFilterLevel=99).normalized().weightFilterLevel,10,"original weight range")
    val advanced=DanmakuSettings(scrollFixedVelocity=true,staticDanmakuToScroll=true,massiveMode=true,weightFilterLevel=6)
    equal(Json.decodeFromString<DanmakuSettings>(Json.encodeToString(advanced)),advanced,"scalar serialization uses single current model")
    val projected=com.bilipai.desktop.ui.projectOriginalDanmakuRendererSettings(DanmakuSettings(),
        com.android.purebilibili.core.store.DanmakuSettings(scrollFixedVelocity=true,staticDanmakuToScroll=true,massiveMode=true,weightFilterLevel=6))
    expect(projected.scrollFixedVelocity,"actual canonical original settings fixed-velocity projection")
    expect(projected.staticDanmakuToScroll,"actual canonical original settings static conversion projection")
    expect(projected.massiveMode,"actual canonical original settings massive projection")
    equal(projected.weightFilterLevel,6,"actual canonical original settings weight projection")
    equal(desktopOriginalDanmakuPinnedLineCount(normal.copy(lineCount=4)),0,"original engine disables small pinned budget")
    equal(desktopOriginalDanmakuPinnedLineCount(normal.copy(lineCount=7)),3,"original engine pinned half budget")
    equal(desktopOriginalDanmakuItemMargin(normal.copy(viewportScale=.5f)),12f,"exact original engine item margin scaling")
    val live=resolveDesktopOriginalLiveDanmakuRenderConfig(advanced,2160,360,.5f,fixturePlatform)
    equal(live.textSizePx,42f,"full original live config keeps42px base, not VOD20")
    equal(live.scrollDurationMs,14000L,"live exact fixed velocity timing input")
    equal(live.topMarginPx,0f,"original live view owns its band")
    equal(live.bottomMarginPx,0f,"original live full constructor preserves neutral default")
    equal(live.viewportScale,1f,"original live neutral scale default")
    equal(resolveLiveSuperChatRemainingSec(12,11),1,"original SC duration independent")
    expect(shouldExpireLiveSuperChat(12,12),"original SC expires")
    expect(!shouldExpireLiveSuperChat(0,59),"original missing SC duration60 only in SC expiry policy")
    expect(shouldExpireLiveSuperChat(0,60),"original missing SC expires after60")
    equal(formatLiveSuperChatCountdown(61),"1:01","original SC formatter")

    val image=BufferedImage(1080,720,BufferedImage.TYPE_INT_ARGB)
    val context=image.createGraphics()
    try {
        val geometry=requireNotNull(DesktopDanmakuPaintGeometry.from(720,480,AffineTransform.getScaleInstance(1.5,1.5)))
        equal(geometry.viewport.widthPx,1080,"real logical->physical width")
        equal(geometry.viewport.heightPx,720,"real logical->physical height")
        equal(geometry.viewport.density,1.5f,"physical DPI density")
        equal(geometry.viewport.scale,1f,"original viewport cap")
        expect(DesktopDanmakuPaintGeometry.from(0,480,AffineTransform())==null,"invalid actual geometry denied")
        context.scale(1.5,1.5);geometry.configurePhysicalPixels(context)
        equal(context.transform,AffineTransform(),"single physical coordinate conversion avoids double DPI")
        val settings=DanmakuSettings(displayAreaRatio=1f,scrollFixedVelocity=true,mergeDuplicates=false)
        val render=settings.originalConfig(fixturePlatform).resolveRenderConfig(geometry.viewport)
        val packets=(0 until 36).map {comment(it,it*.14,if(it%9==0)6 else 1)}
        val scheduler=DanmakuScheduler(packets,settings,liveAdmission=false)
        fun metrics(c:DanmakuComment):DesktopDanmakuTextMetrics {
            val font=desktopDanmakuFont(render,c,1f,false);val m=context.getFontMetrics(font)
            return DesktopDanmakuTextMetrics(m.stringWidth(c.text),m.ascent.toDouble())
        }
        var shown=0
        repeat(140) {tick ->
            val frame=scheduler.frame(tick/10.0,1080,720,render,::metrics)
            shown+=frame.size
            frame.forEachIndexed {i,a ->frame.drop(i+1).filter {abs(it.baseline-a.baseline)<.01}.forEach {b ->
                expect(!(a.x<b.x+b.textWidth && b.x<a.x+a.textWidth),"existing Windows collision backend still separates actual tracks")
            }}
        }
        expect(shown>0,"actual font/metrics scheduler emitted items")
        val pause=scheduler.frame(2.0,1080,720,render,::metrics)
        equal(scheduler.frame(2.0,1080,720,render,::metrics),pause,"paused media timeline stable")
        equal(scheduler.frame(40.0,1080,720,render,::metrics).size,0,"seek excludes expired window using original durations")
        expect(scheduler.frame(1.0,1080,720,render,::metrics).isNotEmpty(),"backward seek rebuild")
        val zero=render.copy(lineCount=0)
        equal(scheduler.frame(1.0,1080,720,zero,::metrics).size,0,"zero original line budget does not invent track")
        val scrollConverted=DanmakuScheduler(listOf(comment(400,mode=5)),converted,liveAdmission=false)
        val a=scrollConverted.frame(1.0,1080,720,render,::metrics).single()
        val b=scrollConverted.frame(2.0,1080,720,render,::metrics).single()
        expect(b.x<a.x,"static-to-scroll actually moves in current scheduler")
        val liveFiltered=DanmakuScheduler(listOf(comment(400,mode=5)),converted,liveAdmission=true)
        equal(liveFiltered.frame(1.0,1080,720,live,::metrics).size,0,"same live consumer preserves original admission")
        val originalFont=desktopDanmakuFont(render,comment(0),1f,false)
        equal(originalFont.size2D,render.textSizePx,"current actual font uses original resolved textsize")
        equal(desktopDanmakuFont(render,comment(0).copy(size=36),1f,false).size2D,render.textSizePx*1.44f,"original size grade applied")
        context.color=Color(0xff192536.toInt(),true);context.fillRect(0,0,1080,720)
        val visual=DanmakuScheduler(listOf(comment(901,mode=1),comment(902,.4,6),comment(903,.8)),settings,liveAdmission=false)
        visual.frame(1.5,1080,720,render,::metrics).forEach {p ->
            val font=desktopDanmakuFont(render,p.comment,1f,false)
            val glyph=font.createGlyphVector(context.fontRenderContext,p.comment.text).getOutline(p.x.toFloat(),p.baseline.toFloat())
            context.color=Color.WHITE;context.fill(glyph)
        }
        ImageIO.write(image,"png",File(output,"actual-awt-font-geometry.png"))
        expect(image.getRGB(0,0)!=0,"actual BufferedImage pixels produced")
    } finally {context.dispose()}
    // Load and inspect every prepared class family without constructing a native player/window or issuing HTTP.
    val loaded=listOf(DanmakuConfig::class.java,DanmakuRenderConfig::class.java,DanmakuScheduler::class.java,
        DanmakuOverlay::class.java,LiveDanmakuRenderer::class.java,DesktopWindowsDanmakuRenderPlatform::class.java)
    loaded.forEach {c ->expect(c.declaredMethods.isNotEmpty(),"actual prepared JVM class/method ABI ${c.name}")}
    val report="{\"status\":\"PASS\",\"groups\":6,\"assertions\":$assertions,\"nativeWindowOrHTTP\":false,\"actualAWTFont\":\"${actualLafFont.family}\",\"byteDanceCollisionParityClaim\":false}"
    File(output,"proof-result.json").writeText(report+"\n")
    println(report)
}

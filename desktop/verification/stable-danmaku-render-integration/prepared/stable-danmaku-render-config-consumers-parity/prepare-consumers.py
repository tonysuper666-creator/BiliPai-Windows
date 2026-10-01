from pathlib import Path
import hashlib,json
L=Path(__file__).resolve().parent;ROOT=L.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,o):write(p,json.dumps(o,ensure_ascii=False,indent=2)+'\n')
H=json.loads(read(L/'local-hunks.json'));B=json.loads(read(L/'canonical-baselines.json'))
def patch(path,s,old,new,reason):
 assert s.count(old)==1,(path,old[:150],s.count(old))
 out=s.replace(old,new);H.append(dict(path=path,old=old,new=new,beforeSha256LF=sha(s),afterSha256LF=sha(out),reason=reason));return out
def baseline(path):
 p=L/'baseline'/path
 if not safe(p).exists():write(p,read(ROOT/path))
 s=read(p);B.append(dict(path=path,source=str(p),sha256LF=sha(s),canonical='actual22 existing Windows consumer'));return s
# One Scheduler consumes complete original neutral config; its existing collision checks remain the Windows backend.
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuScheduler.kt';s=baseline(path)
s=patch(path,s,'import kotlin.math.abs','import kotlin.math.abs\nimport com.android.purebilibili.danmaku.engine.*\nimport com.android.purebilibili.feature.video.danmaku.resolveDanmakuRenderLayerType\nimport com.android.purebilibili.feature.video.danmaku.desktopOriginalDanmakuPinnedLineCount\nimport com.android.purebilibili.feature.video.danmaku.desktopOriginalDanmakuItemMargin','Original neutral config and sole layer/engine budget policies')
s=patch(path,s,'data class PositionedDanmaku','data class DesktopDanmakuTextMetrics(val width:Int,val ascent:Double)\n\ndata class PositionedDanmaku','Actual font width/ascent required at frame admission')
s=patch(path,s,'settings: DanmakuSettings = DanmakuSettings(displayAreaRatio = 1f, scrollDurationSeconds = 8f, staticDurationSeconds = 5f)','settings: DanmakuSettings','Remove production legacy timing/default settings; every caller supplies its actual projection')
s=patch(path,s,'class DanmakuScheduler(comments: List<DanmakuComment>, settings: DanmakuSettings)','class DanmakuScheduler(comments: List<DanmakuComment>, settings: DanmakuSettings, private val liveAdmission:Boolean)','Required original video/live admission semantics; no guessed live self/weight behavior')
s=patch(path,s,'val visible = originalComments.filter(settings::allows)','val visible = originalComments.filter { if(liveAdmission)settings.allowsLive(it) else settings.allows(it) }','Live preserves original pre-layer admission with no video-only weight filter')
s=patch(path,s,'    private data class Scheduled(val comment: DanmakuComment, val lane: Int, val width: Int, val duration: Double) {','    internal val currentSettings:DanmakuSettings get()=settings\n    private data class Scheduled(val comment: DanmakuComment, val layer: Int, val trackTop:Double, val baseline:Double, val width: Int, val duration: Double) {','Actual layer/physical track and baseline')
s=patch(path,s,'    private var viewport = Triple(0, 0, 0)','    private data class Geometry(val width:Int,val height:Int,val config:DanmakuRenderConfig)\n    private var viewport:Geometry?=null','Config changes remeasure and rebuild visible window')
start=s.index('    fun frame(');end=s.index('    private fun lowerBound(',start);old=s[start:end]
new='''    fun frame(time: Double, width: Int, height: Int, config:DanmakuRenderConfig, measure: (DanmakuComment) -> DesktopDanmakuTextMetrics): List<PositionedDanmaku> {
        if (!time.isFinite() || time < 0 || width <= 0 || height <= 0 || config.lineHeightPx <= 0f) return emptyList()
        val geometry = Geometry(width, height, config)
        if (!previousTime.isFinite() || time < previousTime || abs(time - previousTime) > 1.0 || viewport != geometry) {
            active.clear()
            val longestDuration = maxOf(config.scrollDurationMs, config.pinnedDurationMs)/1000.0
            cursor = lowerBound((time - longestDuration).coerceAtLeast(0.0))
            viewport = geometry
        }
        if(config.lineCount<=0){active.clear();previousTime=time;return emptyList()}
        val lineStep=(config.lineHeightPx+config.lineMarginPx).toDouble()
        // Exact original ByteDanceDanmakuEngine.updateConfig pinned-layer budget; no invented extra tracks.
        val pinnedLineCount=desktopOriginalDanmakuPinnedLineCount(config)
        while (cursor < comments.size && comments[cursor].timeSeconds <= time) {
            val comment = comments[cursor++]
            active.removeAll { it.end <= comment.timeSeconds }
            val layer=resolveDanmakuRenderLayerType(comment.mode,settings.staticDanmakuToScroll)
            val metrics=measure(comment)
            val textWidth=metrics.width.coerceAtLeast(1)
            val fixed=layer==DANMAKU_LAYER_TOP || layer==DANMAKU_LAYER_BOTTOM
            val duration=(if(fixed)config.pinnedDurationMs else config.scrollDurationMs)/1000.0
            val lineCount=if(fixed)pinnedLineCount else config.lineCount
            val track=(0 until lineCount).firstOrNull { candidate ->
                val trackTop=if(layer==DANMAKU_LAYER_BOTTOM)height-config.bottomMarginPx-config.lineHeightPx-candidate*lineStep
                    else config.topMarginPx+candidate*lineStep
                val occupants = active.filter { abs(it.trackTop-trackTop)<config.lineHeightPx }
                if (fixed) occupants.isEmpty() else occupants.all { previous ->
                    if (previous.layer==DANMAKU_LAYER_TOP || previous.layer==DANMAKU_LAYER_BOTTOM ||
                        (previous.layer==DANMAKU_LAYER_REVERSE) != (layer==DANMAKU_LAYER_REVERSE)) false else {
                        val age = comment.timeSeconds - previous.comment.timeSeconds
                        val oldSpeed = (width + previous.width) / previous.duration
                        val newSpeed = (width + textWidth) / duration
                        val gap = oldSpeed * age - previous.width
                        val catchUpDistance = (newSpeed - oldSpeed).coerceAtLeast(0.0) * (previous.duration - age)
                        gap >= desktopOriginalDanmakuItemMargin(config) + catchUpDistance
                    }
                }
            }
            if (track != null) {
                val top=if(layer==DANMAKU_LAYER_BOTTOM)height-config.bottomMarginPx-config.lineHeightPx-track*lineStep
                    else config.topMarginPx+track*lineStep
                active += Scheduled(comment,layer,top,top+metrics.ascent,textWidth,duration)
            }
        }
        active.removeAll { it.end <= time }
        previousTime = time
        return active.map { scheduled ->
            val progress = (time - scheduled.comment.timeSeconds) / scheduled.duration
            val x = when (scheduled.layer) {
                DANMAKU_LAYER_BOTTOM,DANMAKU_LAYER_TOP -> (width - scheduled.width) / 2.0
                DANMAKU_LAYER_REVERSE -> -scheduled.width + (width + scheduled.width) * progress
                else -> width - (width + scheduled.width) * progress
            }
            PositionedDanmaku(scheduled.comment, x, scheduled.baseline, scheduled.width)
        }
    }

'''
s=patch(path,s,old,new,'Required full original resolved durations/line budget/bands and real font metrics, keeping Windows collision algorithm explicit')
s=patch(path,s,'    companion object {\n        const val SCROLL_DURATION = 8.0\n        const val FIXED_DURATION = 5.0\n        private const val MIN_GAP = 24.0\n    }','    // Timing and item spacing are consumed from the sole original config/policies.','Remove unused legacy Windows timing/spacing constants')
write(L/'review-only'/path,s)
# Live queue and original SC expiry policies remain unchanged. Only font/physical geometry/config inputs change.
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/LiveDanmakuRenderer.kt';s=baseline(path)
s=patch(path,s,'import com.android.purebilibili.feature.live.LiveDanmakuItem','import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig\nimport com.android.purebilibili.feature.live.LiveDanmakuItem','Neutral config input for same actual Live settings')
s=patch(path,s,'    private var scheduler = DanmakuScheduler(emptyList())','    private var scheduler = DanmakuScheduler(emptyList(),DanmakuSettings(),liveAdmission=true)','Empty unmounted scheduler explicit original defaults, replaced with actual settings before paint')
s=patch(path,s,'        scheduler = DanmakuScheduler(emptyList());','        scheduler = DanmakuScheduler(emptyList(),DanmakuSettings(),liveAdmission=true);','Empty reset only; actual Live settings arrive before any paint')
s=patch(path,s,'DanmakuScheduler(entries.mapNotNull { it.rendered?.comment }, safeSettings)','DanmakuScheduler(entries.mapNotNull { it.rendered?.comment }, safeSettings,liveAdmission=true)','Actual live original admission semantics before render layer conversion')
s=patch(path,s,'fun paint(context: Graphics2D, width: Int, height: Int, scale: Float, settings: DanmakuSettings)','fun paint(context: Graphics2D, width: Int, height: Int, danmakuHeight:Int, config:DanmakuRenderConfig, settings: DanmakuSettings)','Required original Live config on actual physical-pixel band; SC retains full existing height')
old='''        val largestScale = entries.maxOfOrNull { it.rendered?.style?.scale ?: 1f }?.coerceAtLeast(1f) ?: 1f
        val rowHeight = (48 * scale * 0.9f * safeSettings.fontScale * safeSettings.lineHeight * largestScale).toInt().coerceAtLeast(18)'''
s=patch(path,s,old,'        val scale=config.viewportScale\n        val rowHeight=config.lineHeightPx.toInt().coerceAtLeast(1)','Original resolved line height, not independent live duration or guessed row budget')
old='''            val size = (25 * scale * 0.9f * safeSettings.fontScale * (style?.scale ?: 1f)).toInt().coerceIn(10, 192)
            return Font("Microsoft YaHei UI", if (safeSettings.fontWeight >= 5 || style?.bold == true) Font.BOLD else Font.PLAIN, size)'''
s=patch(path,s,old,'            return desktopDanmakuFont(config,entry?.rendered?.comment ?: entry?.comment ?: error("Live font requested without its entry"),style?.scale ?: 1f,style?.bold==true)','Actual required Root font/weight and original item size grade')
old='''        scheduler.frame(seconds(now), width, height, rowHeight) { comment ->
            val graphic = image(indexed[comment.id])
            if (graphic != null) (rowHeight * graphic.width.toDouble() / graphic.height.coerceAtLeast(1)).toInt().coerceIn(rowHeight, rowHeight * 5)
            else context.getFontMetrics(font(indexed[comment.id])).stringWidth(comment.text)
        }'''
new='''        scheduler.frame(seconds(now), width, danmakuHeight, config) { comment ->
            val graphic = image(indexed[comment.id])
            val metrics=context.getFontMetrics(font(indexed[comment.id]))
            DesktopDanmakuTextMetrics(
                if (graphic != null) (rowHeight * graphic.width.toDouble() / graphic.height.coerceAtLeast(1)).toInt().coerceIn(rowHeight, rowHeight * 5)
                else metrics.stringWidth(comment.text),
                if(graphic!=null)rowHeight.toDouble() else metrics.ascent.toDouble(),
            )
        }'''
s=patch(path,s,old,new,'Scheduler receives original neutral timing and actual live image/font extents')
s=patch(path,s,'(positioned.baseline - rowHeight + 6).toInt()','(positioned.baseline - rowHeight).toInt()','Baseline derived from real image extent, no former hardcoded ascent adjustment')
s=patch(path,s,'BasicStroke(safeSettings.strokeWidth * scale,','BasicStroke(config.strokeWidthPx,','Actual resolved original stroke width')
s=patch(path,s,'if (!safeSettings.allows(rendered.comment)) return@forEach','if (!safeSettings.enabled || !safeSettings.allowSpecial || com.android.purebilibili.feature.video.danmaku.shouldBlockDanmakuByRules(rendered.comment.text,safeSettings.blockedRules+safeSettings.blockedKeywords,rendered.comment.userHash)) return@forEach','SC original admission uses allowSpecial/rules, not a video-only weight filter or static standard type flag')
old='''                val cardFont = Font("Microsoft YaHei UI", if (style?.bold == true) Font.BOLD else Font.PLAIN,
                    (14 * scale * (style?.scale ?: 1f)).toInt().coerceIn(11, 64))'''
s=patch(path,s,old,'                val cardFont = config.typeface.deriveFont((config.textSizePx*0.7f*(style?.scale ?: 1f)).coerceAtLeast(1f)).let {\n                    if(style?.bold==true)it.deriveFont(mapOf(java.awt.font.TextAttribute.WEIGHT to java.awt.font.TextAttribute.WEIGHT_BOLD)) else it\n                }','Same actual platform font for existing SC renderer; original SC expiry/countdown untouched')
write(L/'review-only'/path,s)
# Overlay required Root font port, original config cached only by actual settings/viewport/font.
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt';s=baseline(path)
s=patch(path,s,'import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport','import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport\nimport com.android.purebilibili.feature.video.danmaku.DanmakuViewport\nimport com.android.purebilibili.danmaku.engine.DanmakuRenderConfig','Original complete neutral config')
s=patch(path,s,'class DanmakuOverlay(','class DanmakuOverlay internal constructor(','Required internal platform seam without exposing internal type as public API')
s=patch(path,s,'    private val player: MpvPlayer,','    private val player: MpvPlayer,\n    private val renderPlatform:DesktopOriginalDanmakuRenderPlatform,','Actual existing Root window/font provider required at every production constructor')
s=patch(path,s,'DanmakuScheduler(emptyList(), settings)','DanmakuScheduler(emptyList(), settings,liveAdmission=false)','Actual video source admission policy')
s=patch(path,s,'DanmakuScheduler(processed.comments, settings)','DanmakuScheduler(processed.comments, settings,liveAdmission=false)','Actual processed video source preserves original video self/weight policy')
s=patch(path,s,'    private val measuredWidths = mutableMapOf<Triple<Int, Int, Int>, Int>()','    private val measuredWidths = mutableMapOf<Pair<Int,Font>, Int>()\n    private data class ConfigKey(val settings:DanmakuSettings,val viewport:DanmakuViewport,val font:Font,val live:Boolean)\n    private var resolvedConfig:Pair<ConfigKey,DanmakuRenderConfig>?=null','Actual font/config cache with mode identity and no second persistent state or renderer')
old='''                    liveRenderer.paint(context, width, height, viewport.scale, configuration)'''
new='''                    val geometry=DesktopDanmakuPaintGeometry.from(width,height,context.transform) ?: return
                    val key=ConfigKey(configuration,geometry.viewport,renderPlatform.resolveTypeface(configuration.fontWeight),true)
                    val bandHeight=(geometry.viewport.heightPx*configuration.displayAreaRatio).toInt().coerceAtLeast(1)
                    val config=resolvedConfig?.takeIf { it.first==key }?.second ?: com.android.purebilibili.feature.video.danmaku.resolveDesktopOriginalLiveDanmakuRenderConfig(configuration,geometry.viewport.widthPx,bandHeight,configuration.displayAreaRatio,renderPlatform).also { resolvedConfig=key to it }
                    val physical=context.create() as Graphics2D
                    try {geometry.configurePhysicalPixels(physical);physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f);liveRenderer.paint(physical,geometry.viewport.widthPx,geometry.viewport.heightPx,bandHeight,config,configuration)}
                    finally {physical.dispose()}'''
s=patch(path,s,old,new,'Live receives actual DPI/device-pixel geometry and the same complete original config')
start=s.index('                if (measuredWidths.size > 5_000)');end=s.index('                advancedRenderer.paint(',start);old=s[start:end]
new='''                val geometry=DesktopDanmakuPaintGeometry.from(width,height,context.transform) ?: return
                val key=ConfigKey(configuration,geometry.viewport,renderPlatform.resolveTypeface(configuration.fontWeight),false)
                val config=resolvedConfig?.takeIf { it.first==key }?.second ?: configuration.originalConfig(renderPlatform).resolveRenderConfig(geometry.viewport).also { resolvedConfig=key to it }
                val physical=context.create() as Graphics2D
                try {
                    geometry.configurePhysicalPixels(physical)
                    physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f)
                    if (measuredWidths.size > 5_000) measuredWidths.clear()
                    fun font(comment:DanmakuComment):Font {
                        val style=styles[comment.id]
                        return desktopDanmakuFont(config,comment,style?.scale ?: 1f,style?.bold==true)
                    }
                    val positioned = scheduler.frame(displayTime, geometry.viewport.widthPx, geometry.viewport.heightPx, config) { comment ->
                        val font=font(comment)
                        val metrics=physical.getFontMetrics(font)
                        val width=measuredWidths.getOrPut(comment.id to font) {metrics.stringWidth(comment.text)}
                        DesktopDanmakuTextMetrics(width,metrics.ascent.toDouble())
                    }
                    positioned.forEach { item ->
                        val style = styles[item.comment.id]
                        val font = font(item.comment)
                        pluginAwtColor(style?.backgroundColor)?.let { color ->
                            val metrics = physical.getFontMetrics(font)
                            physical.color = color
                            physical.fillRoundRect(item.x.toInt() - 4, item.baseline.toInt() - metrics.ascent - 2,
                                item.textWidth + 8, metrics.height + 4, 6, 6)
                        }
                        val shape = font.createGlyphVector(physical.fontRenderContext, item.comment.text)
                            .getOutline(item.x.toFloat(), item.baseline.toFloat())
                        if (config.strokeWidthPx > 0f) {
                            physical.color = pluginAwtColor(style?.borderColor) ?: Color(config.strokeColor,true)
                            physical.stroke = BasicStroke(config.strokeWidthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            physical.draw(shape)
                        }
                        if (item.comment.isVipGradualColor && style?.textColor == null) {
                            val x = item.x.toFloat()
                            physical.paint = LinearGradientPaint(x, 0f, x + item.textWidth.coerceAtLeast(1), 0f,
                                floatArrayOf(0f, 0.5f, 1f), arrayOf(Color(0xff7cba), Color(0xa798ff), Color(0x70d6ff)))
                        } else physical.color = pluginAwtColor(style?.textColor) ?: Color(item.comment.color)
                        physical.fill(shape)
                    }
                } finally {physical.dispose()}
'''
s=patch(path,s,old,new,'Full config -> standard actual AWT paint/Scheduler, separate old special-mode renderer remains explicit')
write(L/'review-only'/path,s)
# Every constructor supplies a real mounted Window; callbacks are resolved during paint, after mount.
for path,old,new in [
 ('desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt','DanmakuOverlay(it, httpClient = repository.httpClient)','DanmakuOverlay(it, renderPlatform = com.bilipai.desktop.danmaku.DesktopWindowsDanmakuRenderPlatform { requireNotNull(hostWindow) { "Danmaku requires the mounted Root window" } }, httpClient = repository.httpClient)'),
 ('desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopOverlayNativeSmoke.kt','DanmakuOverlay(player, source = noNetwork)','DanmakuOverlay(player, renderPlatform = DesktopWindowsDanmakuRenderPlatform { requireNotNull(SwingUtilities.getWindowAncestor(player.surface)) }, source = noNetwork)'),
 ('desktop/src/main/kotlin/com/bilipai/desktop/ui/DownloadScreens.kt','DanmakuOverlay(player)','DanmakuOverlay(player, renderPlatform = com.bilipai.desktop.danmaku.DesktopWindowsDanmakuRenderPlatform { requireNotNull(javax.swing.SwingUtilities.getWindowAncestor(player.surface)) })'),
]:
 s=baseline(path);s=patch(path,s,old,new,'Required actual existing window for original font/DPI platform seam');write(L/'review-only-callers'/path,s)
 if path.endswith('/DesktopShell.kt'):
  s=patch(path,s,'val danmaku = remember(player, repository) {','val danmaku = remember(player, repository, hostWindow) {','Mounted window is an actual remembered renderer owner key, not a stale captured font/window provider');write(L/'review-only-callers'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/MediaScreens.kt';s=baseline(path)
old='DanmakuOverlay(it, repository.httpClient)';new='DanmakuOverlay(it, renderPlatform = com.bilipai.desktop.danmaku.DesktopWindowsDanmakuRenderPlatform { requireNotNull(javax.swing.SwingUtilities.getWindowAncestor(it.surface)) }, httpClient = repository.httpClient)'
assert s.count(old)==2
# Each full unique original caller expression includes its distinct nearby remember owner block.
for line in ['    val ownedOverlay = remember(player, repository, sharedDanmaku) { if (sharedDanmaku == null) player?.let { '+old+' } else null }']:
 # Identical lines occur in the two distinct page functions; exact ordered occurrence hunks remain count-bound.
 before=s;after=s.replace(old,new,1);H.append(dict(path=path,old=old,new=new,occurrence=0,requiredOldCount=2 if old in after else 1,beforeSha256LF=sha(before),afterSha256LF=sha(after),reason='First genuine page overlay constructor; actual surface window resolved after mount'));s=after
 before=s;assert s.count(old)==1;after=s.replace(old,new,1);H.append(dict(path=path,old=old,new=new,beforeSha256LF=sha(before),afterSha256LF=sha(after),reason='Second genuine page overlay constructor'));s=after
write(L/'review-only-callers'/path,s)
save(L/'local-hunks.json',H);save(L/'canonical-baselines.json',B)
print('PREPARED',len(H),'sequential narrow hunks; no shared source edits')

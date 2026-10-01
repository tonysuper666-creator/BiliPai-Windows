from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';PANEL=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,o):write(p,json.dumps(o,ensure_ascii=False,indent=2)+'\n')
H=[];B=[]
def patch(path,s,old,new,reason):
 assert s.count(old)==1,(path,old[:160],s.count(old))
 out=s.replace(old,new);H.append(dict(path=path,old=old,new=new,beforeSha256LF=sha(s),afterSha256LF=sha(out),reason=reason));return out
producerPath='desktop/tools/extract-upstream-danmaku-list-menu.py'
old=read(L/'baseline'/producerPath);s=old
extension=r'''
 # Full original neutral config and pure geometry/timing algorithms. Only actual Windows platform carriers change.
 full_config=source[PATHS[6]];rows=[];s=full_config
 s=adapt(s,'import android.content.Context','import com.bilipai.desktop.danmaku.DesktopOriginalDanmakuRenderPlatform',rows)
 s=adapt(s,'import android.graphics.Typeface','import java.awt.Font as Typeface',rows)
 s=adapt(s,'import android.os.Build','import com.bilipai.desktop.danmaku.DesktopDanmakuConfigLog',rows)
 s=adapt(s,'class DanmakuConfig {','class DanmakuConfig internal constructor(private val platform: DesktopOriginalDanmakuRenderPlatform) {',rows)
 s=adapt(s,'typeface = resolveDanmakuTypeface(fontWeight),','typeface = resolveDanmakuTypeface(fontWeight, platform),',rows)
 s=adapt(s,'strokeColor = android.graphics.Color.BLACK,','strokeColor = java.awt.Color.BLACK.rgb,',rows)
 status=function(full_config,'getStatusBarHeight','        ')[0]
 s=adapt(s,status,'        internal fun getStatusBarHeight(platform: DesktopOriginalDanmakuRenderPlatform): Int = platform.systemChromeInsetPx()',rows)
 font=function(full_config,'resolveDanmakuTypeface','')[0]
 s=adapt(s,font,'internal fun resolveDanmakuTypeface(fontWeight: Int, platform: DesktopOriginalDanmakuRenderPlatform): Typeface = platform.resolveTypeface(fontWeight)',rows)
 unique_scale=function(full_config,'resolveBilibiliDanmakuFontScale','')[0]
 s=adapt(s,unique_scale,'// Sole resolveBilibiliDanmakuFontScale is selected in DesktopOriginalDanmakuItemParser.kt.',rows)
 s=adapt(s,constant.group(0),'// Sole BILIBILI_STANDARD_DANMAKU_FONT_SIZE is selected beside its original scale helper.',rows)
 s=adapt(s,'        android.util.Log.i(','        DesktopDanmakuConfigLog.i(',rows)
 emit('com/android/purebilibili/feature/video/danmaku/DanmakuConfig.kt',s,PATHS[6],'selected-full-config-with-required-windows-platform',rows,full_config)
 # Full original constructor, preserving every default except the required real platform font carrier.
 models=source[PATHS[11]];start=models.index('data class DanmakuRenderConfig(')
 end=balanced(masked(models),models.index('(',start));raw=models[start:end];rows=[]
 model=adapt(raw,'val typeface: Typeface? = Typeface.DEFAULT,','val typeface: Typeface,',rows)
 model='package com.android.purebilibili.danmaku.engine\nimport java.awt.Font as Typeface\n\n'+model+'\n'
 emit('com/android/purebilibili/danmaku/engine/DanmakuRenderConfig.kt',model,PATHS[11],'selected-complete-neutral-render-schema',rows)
 band=class_body(source[PATHS[12]],'DanmakuDisplayBand')
 emit('com/android/purebilibili/feature/video/danmaku/DanmakuDisplayBand.kt','package com.android.purebilibili.feature.video.danmaku\n\n'+band+'\n',PATHS[12],'selected-complete-original-display-band')
 manager=source[PATHS[5]]
 render_layer=function(manager,'resolveDanmakuRenderLayerType','')[0]
 original_map=function(manager,'mapLayerTypeToDanmakuType')[0]
 layer_map=original_map.replace('    private fun','internal fun',1)
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuLayerPolicy.kt','package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.danmaku.engine.*\n\n'+render_layer+'\n\n'+layer_map+'\n',PATHS[5],'selected-complete-layer-mapping')
 live=source[PATHS[13]];start=live.index('            val textSize = DEFAULT_DANMAKU_TEXT_SIZE_PX *');end=live.index('\n        }\n    )',start);raw=live[start:end];rows=[];s=raw
 s=adapt(s,'            view.engine.updateConfig(','            return (',rows)
 s=adapt(s,'typeface = resolveDanmakuTypeface(danmakuSettings.fontWeight),','typeface = resolveDanmakuTypeface(danmakuSettings.fontWeight, platform),',rows)
 s=adapt(s,'speedFactor = danmakuSettings.speed,','speedFactor = danmakuSettings.speedFactor,',rows)
 s=adapt(s,'viewportWidthPx = view.width','viewportWidthPx = viewWidthPx',rows)
 s=adapt(s,'visibleHeightPx = view.height.toFloat(),','visibleHeightPx = viewHeightPx.toFloat(),',rows)
 s=adapt(s,'val strokeWidth = danmakuSettings.strokeWidth.coerceAtLeast(0f)','val strokeWidth = if(danmakuSettings.strokeEnabled)danmakuSettings.strokeWidth.coerceAtLeast(0f) else 0f',rows)
 body='package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.danmaku.engine.DanmakuRenderConfig\nimport com.bilipai.desktop.danmaku.DanmakuSettings\nimport com.bilipai.desktop.danmaku.DesktopOriginalDanmakuRenderPlatform\n\ninternal fun resolveDesktopOriginalLiveDanmakuRenderConfig(danmakuSettings:DanmakuSettings,viewWidthPx:Int,viewHeightPx:Int,safeDisplayArea:Float,platform:DesktopOriginalDanmakuRenderPlatform):DanmakuRenderConfig {\n'+s+'\n}\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalLiveDanmakuRenderConfig.kt',body,PATHS[13],'selected-complete-original-live-config-constructor',rows)
 start=live.index('            val typeFilter = DanmakuTypeFilterSettings(');end=live.index('            val currentEngine = engine',start);raw=live[start:end];rows=[];s=raw
 s=adapt(s,'                return@collect','                return false',rows) if s.count('                return@collect')==1 else s
 # Three original rejection paths become the corresponding Boolean-policy returns, with exact count-bound replacement.
 before='                return@collect';count=s.count(before)
 if count:
  assert count==3
  s=s.replace(before,'                return false');rows.append(dict(before=before,after='                return false',occurrenceCount=3))
 s=adapt(s,'settings.blockRules,','settings.blockedRules + settings.blockedKeywords,',rows)
 body='package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.feature.live.LiveDanmakuItem\nimport com.bilipai.desktop.danmaku.DanmakuSettings\n\ninternal fun desktopOriginalLiveDanmakuAllows(item:LiveDanmakuItem,settings:DanmakuSettings):Boolean {\n'+s+'            return true\n}\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalLiveDanmakuAdmission.kt',body,PATHS[13],'selected-complete-original-live-admission',rows)
 engine=source[PATHS[14]]
 pinned=re.search(r'(?m)^            top\.lineCount = ([^\n]+)$',engine);assert pinned
 assert '            bottom.lineCount = '+pinned.group(1) in engine
 margin=re.search(r'(?m)^            scroll\.itemMargin = ([^\n]+)$',engine);assert margin
 body='package com.android.purebilibili.feature.video.danmaku\nimport com.android.purebilibili.danmaku.engine.DanmakuRenderConfig\n\n/** Exact original updateConfig expressions, exposed as explicit Windows consumer policies; not a replacement engine. */\ninternal fun desktopOriginalDanmakuPinnedLineCount(config:DanmakuRenderConfig):Int = '+pinned.group(1)+'\n\ninternal fun desktopOriginalDanmakuItemMargin(config:DanmakuRenderConfig):Float = '+margin.group(1)+'\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuEngineBudgets.kt',body,PATHS[14],'selected-exact-original-engine-budget-expressions')
'''
s=patch(producerPath,s,"'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/DanmakuModels.kt']","'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/DanmakuModels.kt',BASE+'feature/video/danmaku/FaceOcclusionPolicy.kt',BASE+'feature/video/ui/overlay/LiveDanmakuOverlay.kt','danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt']",'Append fixed source identities for complete DisplayBand, Live config/admission and exact engine budget expressions')
# Existing producer uses PATHS[-1] for Models; append makes index explicit, preserving current item model origin.
s=patch(producerPath,s,'original=source[PATHS[-1]]','original=source[PATHS[11]]','Preserve original neutral item source after new source append')
s=patch(producerPath,s,"emit('com/android/purebilibili/danmaku/engine/DanmakuItem.kt',s,PATHS[-1],","emit('com/android/purebilibili/danmaku/engine/DanmakuItem.kt',s,PATHS[11],",'Preserve item origin')
marker=" repo_source=source[PATHS[7]];state="
s=patch(producerPath,s,marker,extension+'\n'+marker,'Select full Config/RenderConfig/DisplayBand/layer policies in the existing sole producer')
s=patch(producerPath,s,"pending=['Full DanmakuSettingsPanel/cloud/filter import/smart occlusion','Transparent native overlay pointer/hit testing','Actual Root list/settings entry mounting and native seek callback','Root original TextSelectionBottomSheet delegate under existing owned card provider'],","pending=['Native smart mask acquisition/bitmap ownership','Transparent native overlay pointer/hit testing','Portrait SCREEN_TOP placement and separate portrait-fullscreen renderer','Full original Android Live append-only queue/bitmap release closure','ByteDance collision/native engine parity and original special-mode renderer closure','Actual Root window/DPI/runtime acceptance for this source-only delta'],",'Update feature inventory after frozen179/515; do not retain obsolete panel/menu consumer pending claims')
write(L/'prepared'/producerPath,s)
spec=importlib.util.spec_from_file_location('generator',L/'prepared'/producerPath);g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
inventory=g.generate(ROOT,L/'generated')
save(L/'generated-source-inventory.json',inventory)
for p in sorted(safe(L/'platform').glob('*.kt')):write(L/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/danmaku'/p.name,p.read_text(encoding='utf-8'))
# Canonical frozen179 Scalar/Projection extension; no independent settings producer or storage keys.
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt';s=read(PANEL/'selected-files/DesktopDanmakuSettings.kt');B.append(dict(path=path,source=str(PANEL/'selected-files/DesktopDanmakuSettings.kt'),sha256LF=sha(s),canonical='Settings179'))
s=patch(path,s,'import com.android.purebilibili.feature.video.danmaku.shouldDisplayStandardDanmaku','import com.android.purebilibili.feature.video.danmaku.shouldDisplayStandardDanmaku\nimport com.android.purebilibili.feature.video.danmaku.resolveDanmakuRenderLayerType\nimport com.android.purebilibili.feature.video.danmaku.mapLayerTypeToDanmakuType','Reuse original layer mapping before type filtering')
s=patch(path,s,'    val staticDurationSeconds: Float = 4f,','    val staticDurationSeconds: Float = 4f,\n    val scrollFixedVelocity: Boolean = false,\n    val staticDanmakuToScroll: Boolean = false,\n    val massiveMode: Boolean = false,\n    val weightFilterLevel: Int = 0,','Original four advanced fields/defaults')
s=patch(path,s,'        fontWeight = fontWeight.coerceIn(1, 9),','        fontWeight = fontWeight.coerceIn(1, 9),\n        weightFilterLevel = weightFilterLevel.coerceIn(0, 10),','Original Manager weight setter range')
s=patch(path,s,'            shouldDisplayStandardDanmaku(comment.mode, comment.color, typeFilters, comment.isVipGradualColor) &&','            shouldDisplayStandardDanmaku(mapLayerTypeToDanmakuType(resolveDanmakuRenderLayerType(comment.mode, staticDanmakuToScroll)), comment.color, typeFilters, comment.isVipGradualColor) &&\n            !(weightFilterLevel > 0 && comment.originalElement?.isSelf != true && comment.weight < weightFilterLevel) &&','Original converted type and exact !isSelf/weight filter; XML original false, never MID/hash guess')
s=patch(path,s,'    fun allowsAdvanced(comment: AdvancedDanmakuData): Boolean = enabled &&','    internal fun allowsLive(comment:DanmakuComment):Boolean = enabled &&\n        com.android.purebilibili.feature.video.danmaku.desktopOriginalLiveDanmakuAllows(\n            com.android.purebilibili.feature.live.LiveDanmakuItem(text=comment.text,mode=comment.mode,color=comment.color,uid=comment.userHash.toLongOrNull() ?: 0L),this)\n\n    fun allowsAdvanced(comment: AdvancedDanmakuData): Boolean = enabled &&','Live uses full original pre-layer/type/rules admission; original live has no video weight predicate')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDanmakuSettingsProjection.kt';s=read(PANEL/'platform/DesktopDanmakuSettingsProjection.kt');B.append(dict(path=path,source=str(PANEL/'platform/DesktopDanmakuSettingsProjection.kt'),sha256LF=sha(s),canonical='Settings179'))
s=patch(path,s,'    staticDurationSeconds=original.staticDurationSeconds,lineHeight=original.lineHeight,','    staticDurationSeconds=original.staticDurationSeconds,lineHeight=original.lineHeight,\n    scrollFixedVelocity=original.scrollFixedVelocity,staticDanmakuToScroll=original.staticDanmakuToScroll,\n    massiveMode=original.massiveMode,weightFilterLevel=original.weightFilterLevel,','Same full original global preferences projected into sole renderer settings')
write(L/'review-only'/path,s)
save(L/'local-hunks.json',H);save(L/'canonical-baselines.json',B)
print('PREPARED generator extension and canonical179 delta; generated',len(inventory['emitted']))

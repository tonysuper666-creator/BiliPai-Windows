from pathlib import Path
import hashlib,json,subprocess,importlib.util,re
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2];COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
parser=load(REPO/'desktop/tools/sync-upstream.py','shareparser');media=load(REPO/'desktop/tools/extract-upstream-media.py','shareselector')
names=['VideoShareSheet','VideoSharePolicy','VideoShareSheetMotion','VideoShareToFollowingDialog','VideoShareMoreTargetsSheet','VideoShareCoverService','VideoShareCardService']
paths=[BASE+'feature/video/share/'+n+'.kt' for n in names]+[BASE+'feature/home/components/CrashTrackingConsentDialog.kt',BASE+'data/repository/MessageRepository.kt',BASE+'core/store/SettingsManager.kt',BASE+'core/ui/common/ClipboardUtils.kt']
paths=[p for p in paths if (REPO/p).exists()]
pins={}
for p in paths:
 s=subprocess.run(['git','show',COMMIT+':'+p],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n');assert s==read(REPO/p),p
 pins[p]=sha(s);write(LANE/'original-stable'/p,s)
write(LANE/'pins.json',json.dumps(pins,indent=2))
out=LANE/'prepared/generated';records=[]
def emit(p,body,target=None):
 target=target or 'com/android/purebilibili/'+p.removeprefix(BASE)
 write(out/target,body);records.append(dict(source=p,output=target,originalSha256LF=pins[p],preparedSha256LF=sha(body)))
def full(n):return read(REPO/(BASE+'feature/video/share/'+n+'.kt'))
def imports(s):
 return '\n'.join(l for l in s.splitlines() if not (l.startswith('import android.') or l.startswith('import androidx.compose.ui.viewinterop.') or l.startswith('import androidx.compose.ui.platform.LocalContext') or l.startswith('import androidx.compose.ui.platform.LocalConfiguration')))+'\n'
def context(s):
 s=imports(s);s=s.replace('val context = LocalContext.current','val context = com.bilipai.desktop.ui.LocalDesktopVideoShareBindings.current')
 s=s.replace('LocalConfiguration.current.screenHeightDp','com.bilipai.desktop.ui.LocalDesktopVideoShareBindings.current.screenHeightDp')
 s=re.sub(r'Toast\.makeText\(context, (.*?), Toast.LENGTH_SHORT\)\.show\(\)',r'context.showFeedback(\1)',s)
 return s
p=BASE+'feature/video/share/VideoSharePolicy.kt';s=full('VideoSharePolicy')
selected=['resolveVideoShareRecipientIds','VideoShareStyle','resolveVideoShareCardMetaLine','resolveVideoShareCardFileName','resolveVideoShareChooserTitle']
appear=load(REPO/'desktop/tools/extract-appearance-platform.py','sharedecl')
body=appear.declarations(parser,s,selected)
body='package com.android.purebilibili.feature.video.share\nimport com.android.purebilibili.data.model.response.FollowingUser\n\n'+body
body+='\ninternal enum class VideoShareTarget { BILIBILI_FRIENDS,SYSTEM_SHARE,SAVE_CARD,COPY_LINK,MORE }\n'
emit(p,body,'com/android/purebilibili/feature/video/share/DesktopOriginalVideoSharePolicy.kt')
p=BASE+'feature/video/share/VideoShareSheetMotion.kt';s=imports(full('VideoShareSheetMotion'))
s=s.replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion')
s=s.replace('LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE','com.bilipai.desktop.ui.LocalDesktopVideoShareBindings.current.isLandscape')
emit(p,s)
p=BASE+'feature/video/share/VideoShareToFollowingDialog.kt';s=context(full('VideoShareToFollowingDialog'))
for prefix in ['com.android.purebilibili.core.network.NetworkModule','com.android.purebilibili.core.store.TokenManager','com.android.purebilibili.data.repository.MessageRepository']:s=s.replace('import '+prefix+'\n','')
s=s.replace('    val selfMid = TokenManager.midCache','    val context = com.bilipai.desktop.ui.LocalDesktopVideoShareBindings.current\n    val selfMid = context.currentMid()')
s=s.replace('NetworkModule.api.getFollowings(selfMid, pn = nextPage, ps = 50)','context.getFollowings(selfMid, nextPage, 50)')
s=s.replace('MessageRepository.sendTextMessage','context.sendTextMessage')
# Source nullable API data crosses a module boundary: capture once, preserve original branches.
s=s.replace('            if (response.code != 0 || response.data == null)', '            val data = response.data\n            if (response.code != 0 || data == null)')
s=s.replace('response.data.list','data.list').replace('response.data.total','data.total')
emit(p,s)
p=BASE+'feature/video/share/VideoShareMoreTargetsSheet.kt';s=context(full('VideoShareMoreTargetsSheet'))
start=s.index('internal data class VideoShareAppTarget');end=s.index('@OptIn',start)
s=s[:start]+'''internal data class VideoShareAppTarget(
    val target: VideoShareTarget,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

internal fun findVideoShareAppTargets(context: com.bilipai.desktop.ui.DesktopVideoShareBindings, mimeType: String): List<VideoShareAppTarget> =
    context.availableTargets(mimeType)

'''+s[end:]
s=s.replace('it.packageName','it.target.name').replace('选择应用继续分享','选择实际可用的 Windows 分享方式').replace('系统面板的屏幕方向由设备决定','系统面板列出由 Windows 注册的接收应用；是否接收由该应用决定')
start=s.index('                            AndroidView(');end=s.index('\n                        },',start)
s=s[:start]+'''                            com.android.purebilibili.core.ui.components.AppIcon(
                                imageVector = target.icon,
                                contentDescription = target.label,
                                modifier = Modifier.size(48.dp),
                            )'''+s[end:]
emit(p,s)
p=BASE+'feature/video/share/VideoShareSheet.kt';s=context(full('VideoShareSheet'))
s=s.replace('import com.android.purebilibili.core.ui.common.copyPlainTextToClipboard\n','')
s=s.replace('VideoShareTarget.WECHAT','VideoShareTarget.SYSTEM_SHARE').replace('VideoShareTarget.QQ','VideoShareTarget.SAVE_CARD')
s=s.replace('label = "微信"','label = "系统分享"').replace('iconText = "微"','iconText = "享"').replace('label = "QQ"','label = "保存卡片"').replace('iconText = "Q"','iconText = "存"')
start=s.index('                context.startTargetedVideoShare(');end=s.index('\n                onDismiss()',start)
s=s[:start]+'''                shareScope.launch {
                    context.performTarget(target.target, payload, moreShareMedia)
                    onDismiss()
                }'''+s[end:];s=s.replace('                }\n                onDismiss()','                }',1)
s=s.replace('                context.startMoreVideoShare(payload, moreShareMedia)\n                onDismiss()','                shareScope.launch { context.performTarget(VideoShareTarget.SYSTEM_SHARE,payload,moreShareMedia);onDismiss() }')
s=s.replace('                                    val packageName = item.target.packageName ?: return@VideoShareSheetItemView\n','')
s=s.replace('                                                style = shareStyle,','                                                style = if(item.target == VideoShareTarget.SAVE_CARD) VideoShareStyle.CARD else shareStyle,',1)
start=s.index('                                            context.startTargetedVideoShare(');end=s.index('\n                                        } finally',start)
s=s[:start]+'''                                            context.performTarget(item.target,payload,shareMedia)'''+s[end:]
s=s.replace('copyPlainTextToClipboard(context, payload.url, "视频链接")','context.copyText(payload.url)')
s=s.replace('context.startMoreVideoShare(payload, shareMedia)','context.performTarget(VideoShareTarget.SYSTEM_SHARE,payload,shareMedia)')
start=s.index('    val appIcon = remember(context, item.target)');end=s.index('\n    Column(',start);s=s[:start]+s[end:]
start=s.index('            if (appIcon != null)');end=s.index('                if (item.target',start)
s=s[:start]+'''            if (item.iconVector != null) {
'''+s[end:]
s=s.replace('    context: Context,','    context: com.bilipai.desktop.ui.DesktopVideoShareBindings,')
start=s.index('\nprivate fun Context.startTargetedVideoShare');s=s[:start]+'\n'
emit(p,s)
p=BASE+'feature/home/components/CrashTrackingConsentDialog.kt';s=imports(read(REPO/p))
s=s.replace('import com.android.purebilibili.core.store.SettingsManager\n','').replace('import com.android.purebilibili.core.util.CrashReporter\n','')
s=s.replace('val context = LocalContext.current','val context = com.bilipai.desktop.ui.LocalDesktopCrashConsentBindings.current')
s=s.replace('mutableStateOf(true)','mutableStateOf(context.enhancedEnabled)')
s=s.replace('                SettingsManager.setCrashTrackingEnabled(context, isEnabled)\n                SettingsManager.setCrashTrackingConsentShown(context, true)\n                CrashReporter.setEnabled(isEnabled)','                context.saveChoice(isEnabled)')
s=s.replace('默认仅启用崩溃追踪；使用情况统计默认关闭。播放器诊断日志仍可手动开启，便于排查黑屏、卡顿等播放问题。','Windows 版本仅在本地保存脱敏的基础错误与崩溃快照；不自动上传。增强本地诊断默认关闭，便于排查黑屏、卡顿等播放问题。')
s=s.replace('数据仅用于改善稳定性，之后可随时在「设置」中调整。','增强日志仅用于改善稳定性，可随时在「设置」中调整、清理或主动导出。')
s=s.replace('启用崩溃追踪','启用增强本地诊断').replace('开启后将在崩溃时上传诊断信息','开启后额外保留本地运行诊断（最多 256KB）').replace('关闭后不上传崩溃报告','关闭后不保留增强日志；基础错误与崩溃快照仍保留')
emit(p,s)
# Full original sendText/sendMessage protocol; only logging and same owner credential getters adapt.
p=BASE+'data/repository/MessageRepository.kt';s=read(REPO/p)
body='\n\n'.join(media.function(s,n,parser) for n in ['sendTextMessage','sendMessage'])
body=body.replace('TokenManager.csrfCache','csrf()').replace('TokenManager.midCache','mid()').replace('getDeviceId()','deviceId()')
body='\n'.join(l for l in body.splitlines() if 'Logger.d(' not in l and 'android.util.Log.e(' not in l)
body=body.replace('        } catch (e: Exception) {\n            Result.failure(e)','        } catch (e: Exception) {\n            if(e is CancellationException)throw e\n            Result.failure(e)')
body='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
internal class DesktopOriginalVideoShareMessages(
 private val api:MessageApi,
 private val csrf:()->String?,
 private val mid:()->Long?,
 private val deviceId:()->String,
) {
'''+body+'\n}\n'
emit(p,body,'com/android/purebilibili/data/repository/DesktopOriginalVideoShareMessages.kt')
p=BASE+'feature/video/share/VideoShareCardService.kt';s=full('VideoShareCardService')
constants='\n'.join(line for line in s.splitlines() if line.startswith('private const val CARD_'))
emit(p,read(LANE/'platform-card.template.kt').replace('__ORIGINAL_CONSTANTS__',constants))
p=BASE+'feature/video/share/VideoShareCoverService.kt';s=full('VideoShareCoverService')
emit(p,read(LANE/'platform-cover.template.kt').replace('__ORIGINAL_MIME__',media.function(s,'resolveVideoShareCoverMimeType',parser)).replace('__ORIGINAL_EXTENSION__',media.function(s,'resolveVideoShareCoverExtension',parser)))
write(LANE/'generation-records.json',json.dumps(records,ensure_ascii=False,indent=2))
print('prepared',len(records),'sources')

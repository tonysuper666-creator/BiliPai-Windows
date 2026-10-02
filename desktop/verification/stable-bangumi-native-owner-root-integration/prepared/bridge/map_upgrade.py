from pathlib import Path
import difflib,hashlib,json,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023'
OLD='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';NEW='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
def source(commit,path):return subprocess.check_output(['git','-C',str(C),'show',commit+':'+path]).decode('utf8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def entry(old,new,note):
 a=source(OLD,old);b=source(NEW,new);patch=''.join(difflib.unified_diff(a.splitlines(keepends=True),b.splitlines(keepends=True),fromfile=old,tofile=new,n=3))
 additions=sum(line.startswith('+')and not line.startswith('+++')for line in patch.splitlines());deletions=sum(line.startswith('-')and not line.startswith('---')for line in patch.splitlines())
 path=P/'upgrade-mapping'/Path(new).name.replace('.kt','.patch');path.parent.mkdir(parents=True,exist_ok=True);path.write_text(patch,encoding='utf8',newline='\n')
 rows.append(dict(v023Path=old,v025Path=new,v023Sha256LF=sha(a),v025Sha256LF=sha(b),added=additions,removed=deletions,patchPath=str(path),patchSha256LF=sha(patch),requiredAdaptation=note,applied=False,rootAccepted=False))
app='app/src/main/java/com/android/purebilibili/';rows=[]
for name,note in [
 ('BangumiPlayerViewModel','原完整业务顺序不变；迁移 core.player.PlaybackProgressManager import，保留新增明确 nonnull 断言；不能更换现有 Windows 唯一 progress/session owner'),
 ('BangumiPlayerScreen','Windows 现有窗口 lease 对齐退出恢复系统栏语义；无折叠铰链时仍保留新 supportingContent 主/辅 pane 状态机与原 PUGV/下载/分享回调'),
 ('ui/player/BangumiPlayerOverlayHost','新原 overlay 接口由参数拆为 VideoPlayerOverlayState/Actions；将全部原回调映射现有同一 assembly overlay owner'),
 ('ui/player/BangumiPlayerContent','保持 episode keyed 状态；新增 comment covered blur +主评论/子回复 refresh 回调、nullable episodes/briefImgs；读取/发送 topic33 仍必须贯穿现有评论 owner'),
 ('BangumiDashManifestPolicy','只迁移新的 core.player.dash.buildLocalDashManifest source identity/path；复用原 MPD 构造与现有 Windows 单播放器')]:
 path=app+'feature/bangumi/'+name+'.kt';entry(path,path,note)
entry(app+'data/repository/BangumiRepository.kt',app+'data/repository/BangumiRepository.kt','本文件变化为多个成功 response 的明确 nonnull 断言；getBangumiPlayUrl 此版本完整 method 是否相等另由正文比对，不以整 Repo 移植替换现有唯一捕获请求')
entry(app+'feature/video/ui/components/CommentInputDialog.kt',app+'feature/video/ui/components/CommentInputDialog.kt','同原 composer owner 复用新增保存正文/选区/图片/转动态草稿、包排序、颜文字、CommentEmoteTextField 与 IME/面板状态机；Windows 键盘与窗口边界适配')
entry(app+'feature/video/viewmodel/VideoCommentViewModel.kt',app+'feature/video/viewmodel/VideoCommentViewModel.kt','原请求 ID/job 取消、保持当前排序 refreshComments/refreshSubReplies 及失败状态；同实际 subject(type33)/Store/session/主 API 与 compositor')
entry(app+'core/network/ApiClient.kt','core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt','模块路径移动、CoreNetworkRuntime/config/log、emote 包详情 API 与主登录协议变化；由 Root 全局授权 owner 统一移植，禁止本桥增加另一个 HTTP client/cookie jar 或修改 manifest baseline；此对照未发现播放并发插件实现，不能将其归给此文件')
entry(app+'core/network/WbiKeyManager.kt','core-data/src/main/java/com/android/purebilibili/core/network/WbiKeyManager.kt','模块路径移动；核心 runtime 取原主账户 nav API，Windows 仍使用同现有 homeWbiKeys 单缓存')
entry(app+'feature/video/controller/PlaybackProgressManager.kt','core-player/src/main/java/com/android/purebilibili/core/player/PlaybackProgressManager.kt','该文件仅 package/log 移到 core-player；不能把路径搬迁算新后台进度功能。其他后台差量必须同 Windows 唯一 progress writer/Session/原 PGC type4 心跳，不添加第二进度 owner')
entry(app+'feature/video/viewmodel/VideoPlaybackViewModel.kt',app+'feature/video/viewmodel/VideoPlaybackViewModel.kt','本文件迁移 adaptive/quality imports 到 core-player，新增 CdnTransferRuntime 的真实缓冲/bitrate readback、public notes 分页等；由 Root 主播放器统一迁移，本桥保留 v023 同 assembly 令牌/授权/源事实')
entry(app+'feature/plugin/CdnTransferRuntime.kt',app+'feature/plugin/CdnTransferRuntime.kt','并发取流 runtime 差量需沿现有唯一 cache/transport/plugin owner 移植；本对照不把并发取流自动解释为切换授权账户，最终语义由 Root 统一审计')
result=dict(v023Commit=OLD,v025Commit=NEW,currentBridgeBaseline='v0.2.3',productBaselineChanged=False,currentPlayerBridgeUpgraded=False,
 entries=rows,untracedRequestedDependencies=[dict(feature='并发授权取流插件',sourceIdentityVerified=False,rootOwnerUpgradeRequired=True,accepted=False)])
(P/'v025-upgrade-mapping.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('Pinned upgrade mapping only:',len(rows),'source pairs; no current product/producer baseline change')

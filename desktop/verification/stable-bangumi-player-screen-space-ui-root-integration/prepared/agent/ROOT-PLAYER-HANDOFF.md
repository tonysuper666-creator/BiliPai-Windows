完整原番剧播放器源码交付，固定 cd8317 / actual90 / v0.2.3 3d5。

五个完整原页面 body（Screen、Content、Components、OverlayHost、CollapsedPlayerBar）保留全体业务，逐个可逆重建原文件。新增唯一 UI producer 生成这五个 body 以及三个原策略/设置闭包，共八个输出；生成文件不得拷入产品源。

真实 Shell 的 typed BangumiPlayer 入口消费 seasonId、epId、resumePositionMs、isCourse、preferredAid，借已有 Assembly、SessionStore、Mpv、Window、评论、HTTP/WBI、Mini、队列、设置、分享和下载管理器。覆盖页面仅移除视图 lease；原播放域和 Mini 继续保留，实际接管才取消域并清除自己注册的回调。

原 PUGV 评论类型 33 和原当前 episode oid 完整保留；普通番剧评论类型 1 使用实际 aid。课程下载保留原分组、顺序、老师、画质、时长及当前接受来源完整多段/凭据。课程模型没有 PGC 的 allow_download 字段；显式禁止仍拒绝，缺省仅原课程播放 URL 链按原行为处理，不制造权限字段。下载失败呈现失败信息，不虚构已入队。

退出和换集前捕获当前实际同分集 publication 的位置、同 request/receipt、主账号 CSRF/mid，借现有 Root scope 完成原 type4 心跳；换账号、缺字段、隐私、取消、服务器失败均按真实原策略处理。底层 source recovery 替换 accepted 对象后按同来源事实重新核对，其他分集不能借用旧元数据。监听器清理只移除借入注册，绝不释放全局播放器。

安装合同仅允许 6 个新文件、7 个既有文本 family 的 10 个 exact fragments，另做 registry 5 个身份添加 + 4 个 feature union（1229→1234 / 244 resources，无依赖差量）。全旧文件覆盖、生成文件拷入、旧桥 jar 混入 classpath 均禁止；Space/消息/分享及既有播放器全体字段必须保留。

Android OS Handoff registration 在 Windows 明确不可用；已有 typed resume/同 SessionStore 为 Windows 路径。Media3、物理传感器旋转、AndroidView/GLES/音量/系统栏的边界接已有 Windows native/Window/enhancement/settings，未伪造能力。

证据只接受本包 actual90 窄编译、完整 body 逆向重建、唯一 producer 字节重放和 CPU 负例。主流程仍必须完成无 overrides 正常产品编译、实际 Root/native ACK/首帧/故障恢复、真实账号 PUGV 评论/下载/心跳、普通视频接管和覆盖页面 Mini 测试，方可增加相应验证级别。

v0.2.5 升级映射在 sibling `stable-original-bangumi-player-v025-upgrade-mapping`；本包没有宣称新版完成。

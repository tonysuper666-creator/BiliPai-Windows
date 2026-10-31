本卡仍是部分交付。2026-10-31 已 rebase 到集成分支 `9e53964eb`（PR #26、#30 已合入），不再等待网络层授权；共享登录范围仍以 issue metadata `write_scope` 为准，无需再次申请。

本轮已有 6/11 个登录文件进入直接编译，适配只保存为构建时补丁：

- `BiliPaiQrDecoder.kt`：AWT 图片输入，保留四向识别和支持的登录 URL 校验。
- `OfficialQrAuthorizationViewModel.kt`、`OfficialQrAuthorizationContent.kt`：沿用现有 Profile 窗口、账号和请求归属，不回退进程单例。
- `BiliPaiTransferScanner.kt`：调用同一 Profile 窗口的真实图片选择器，无相机空替身，不自动打开识别到的 URL。
- `CaptchaManager.kt`：系统浏览器和本地回调由真实 `DesktopCaptchaBridge` 实现；对话框的密码、短信、风控验证均经过该平台入口。原 `RsaEncryption` 原文保留，生成副本已登记退役。
- `LoginViewModel.kt`：`AdvancedLoginDialog` 的 TV / Web 二维码表单使用原状态流与 HTTP 轮询；默认调用 `loadQrCode()`（当前上游走 Android-HD 签名的 TV QR 接口）。位图走 AWT；请求使用本次尝试的候选 Cookie jar；保存继续经原 `DesktopLoginRepository` / M3 的校验、账号代际和安装回执。每次刷新建立新 VM 并取消旧尝试；所有任务保留原 update hold 登记，二维码保留八分钟期限。

`later-steps.tsv` 仍保留以下 5 个文件：

- `BiliPaiTransferContent.kt`
- `BiliPaiTransferKeyStore.kt`
- `BiliPaiTransferSession.kt`
- `LoginComponents.kt`
- `LoginScreen.kt`

具体依赖是 `BiliPaiTransferContent.kt` 的 `BiliPaiTransferSettingsBridge`：它调用尚未进入桌面编译的 `SettingsShareService.createExportArtifact`、`buildTransferImportSession`、`applyImport`。该服务属于设置收尾 TON-1116（其依赖包含 TON-1073 的通用平台接口），当前为 backlog。直接编译该服务、复用其统一导入导出接口两条路径均尚未具备；在登录卡另造设置序列化器或给 Context/存储接口空实现会引入第二套设置写入规则，因此等待既有设置实现，再继续传输链、KeyStore、组件和页面的整体接线。

`WebCookieSync.kt` 尚未登记为直编。它不属于现有 M4d 区域，后续必须显式登记，并接实际受窗口/账号代际约束的 Cookie 消费者，补同步、切号及注销行为测试。验证码浏览器仍与账号 Cookie 隔离；不得把 Cookie 写入外部系统浏览器来冒充同步实现。旧 Argon 恢复附件中的全局内存 Cookie sink 未采用。

共享范围已经批准：构建只改登录 / WebCookieSync 的源码登记及被替代的旧声明；m4d 表只改获准的登录行，冲突保留其他卡改动。完整迁移后的正式验收需再 rebase 最新集成分支，两个新目录跑四个 JVM、Python 和唯一类名检查，再做唯一一轮 Windows VM 二维码及真实极验验收，随后 reviewer 和 cross_review。本轮没有启动客户端或消耗 Windows GUI 轮次。

李政亲手验收清单（完整候选包就绪后执行；当前均未验证）：

1. 打开登录对话框，确认默认是 TV 二维码；扫码后先显示等待手机确认，再显示正确账号。不要回传密码、短信码或 Cookie。
2. 重开客户端确认保存账号可用；二维码过期后刷新，应出现新的可扫描二维码。
3. 触发真实极验，在系统浏览器完成并返回桌面；关闭/取消、加载失败及超时均应有清楚提示。未触发真实极验不算通过。
4. 退出后登录功能重新要求登录；旧回调不能恢复退出的尝试。删除保存账号后再启动，确认不再出现。
5. 只回报通过/失败与不含个人信息的报错；账号数据保留本机。

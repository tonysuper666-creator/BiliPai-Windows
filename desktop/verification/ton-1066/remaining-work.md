本分支只交付验证码浏览器桥接的可靠性与回调行为测试，**没有完成 TON-1066 的上游直编迁移**。

按基线 `4768375f0` 核对，`desktop/upstream-core/m4d/later-steps.tsv` 中的 11 个登录文件全部保留：

- BiliPaiQrDecoder.kt
- BiliPaiTransferContent.kt
- BiliPaiTransferKeyStore.kt
- BiliPaiTransferScanner.kt
- BiliPaiTransferSession.kt
- CaptchaManager.kt
- LoginComponents.kt
- LoginScreen.kt
- LoginViewModel.kt
- OfficialQrAuthorizationContent.kt
- OfficialQrAuthorizationViewModel.kt

继续迁移前需主控协调的范围：

1. 仓库 `AGENTS.md` 明确写着 “Root owns … build files” 和 “if a range overlaps yours … hand the overlap to Mika to sequence”。`desktop/build.gradle.kts` 当前与 PR #18、#20、#21、#26、#29、#30、#31 等共用；`m4d/later-steps.tsv` 又与 TON-1067 共用。本轮未修改这些共享文件。
2. 拟申请的构建改动仅限登录及 `feature/web/WebCookieSync.kt` 的源码登记、旧登录声明的退役登记，不改共享生成机制、版本或打包配置。`WebCookieSync.kt` 不在现有 `migratedAppM4dAreas` 中，不能只删登录清单行就宣称它已直编。
3. `extract-login-platform.py` 仍从旧 `CaptchaManager.kt` 切出 `RsaEncryption`。完整直编该文件时，要同时处理生成副本，避免同名类重复。
4. 上游 `LoginViewModel` 的网络和会话写入必须绑定桌面当前登录尝试及账号代际；不能仅为编译而放入全局网络单例。凭据继续走现有 M3 平台存储；验证码接真实桥接；Android KeyStore、相机、CookieManager 都不能补空替身。

主控确认顺序和范围后，在同一张卡继续完整迁移；保留 `AdvancedLoginDialog`，最后才安排一次 Windows VM 构建、二维码过期刷新截图及真实验证码验证/失败证据。当前没有启动客户端、连接 Windows 主机或产生真实验证码录屏。

李政亲手验收清单（迁移完成、有已验证的 Windows 候选包后执行；当前均未验证）：

1. 打开个人中心的登录对话框，确认默认是 TV 二维码；扫码后，桌面先显示等待手机确认，再显示正确账号。不要把密码、短信码或登录 Cookie 发到评论或日志。
2. 关闭并重新打开客户端，确认已保存的账号仍可用；二维码过期后点击刷新，应生成可扫描的新二维码。
3. 需要安全验证时，在系统浏览器完成极验，返回桌面继续登录。关闭/取消验证后应有明确提示；页面加载失败和超时后能重新发起，不能永久卡在“处理中”。未触发真实极验不算通过这一项。
4. 退出当前账号后，消息等登录功能应重新要求登录；旧浏览器回调不能恢复已退出的登录尝试。删除保存账号后再重启，确认该账号不再出现。
5. 只回报每项通过/失败与不含个人信息的报错。真实账号数据保留在本机，不上传服务器。

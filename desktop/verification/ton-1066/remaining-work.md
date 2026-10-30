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

共享范围已由 Mika 在 2026-10-30 授权（以 issue metadata `write_scope` 为准），无需再次申请。PR #33 的 m4d 表有合并优先权，其余共享文件按先合入者落地，最终验证前 rebase。下面保留迁移约束：

1. `desktop/build.gradle.kts` 只改登录 / WebCookieSync 源码登记及被替代的旧登录生成声明；m4d 表只改获准的登录行，不整理其他区域。
2. `WebCookieSync.kt` 不在现有 `migratedAppM4dAreas` 中，不能只删登录清单行就宣称它已直编。
3. `extract-login-platform.py` 仍从旧 `CaptchaManager.kt` 切出 `RsaEncryption`。完整直编该文件时，要同时处理生成副本，避免同名类重复。
4. 上游 `LoginViewModel` 的网络和会话写入必须绑定桌面当前登录尝试及账号代际；不能仅为编译而放入全局网络单例。凭据继续走现有 M3 平台存储；验证码接真实桥接；Android KeyStore、相机、CookieManager 都不能补空替身。

当前具体依赖（2026-10-30 复核）：

- `LoginViewModel.kt:89` 等请求直接调用 `NetworkModule.passportApi`；`completeLogin`（约 890 行）经 `SessionRepository.save` 写凭据，`syncCurrentAccountSession`（约 942 行）读取 `NetworkModule.api` 后写 `TokenManager` / `AccountSessionStore`。这些入口必须共享桌面当前登录尝试的寿命和账号代际，不能接进程级全局 Cookie。
- PR #30 / TON-1082 提供 `DesktopUpstreamOwner` / `DesktopUpstreamScope` / `DesktopUpstreamCalls` 及 owner-bound NetworkModule，叠在 PR #26 上。当前两 PR 都 OPEN，TON-1082 为 `in_review`；集成分支仍为 `4768375f0`，上述 owner 接口尚不存在。按 Mika 的指令，不复制这些数据层文件，等待其合入再 rebase 接线。
- `BiliPaiTransferContent.kt` 还调用 `feature/settings/share/SettingsShareService`，完整迁移时需重新核对该接口的直编状态。此处不据此扩大本轮等待范围。

失败任务 `01a18b89-1dc4-7c4b-bd59-62697d3bad91` 的六个未提交文件已另存为 issue 附件 `TON-1066-Argon-recovery.zip`，未应用到本分支。它在补丁生成失败后已移除六个 login 清单条目，Cookie bridge 无生产调用方绑定，不能算迁移成果。其唯一编译尝试因找不到 `python` 在提取任务阶段失败，且发生在草稿生成前；没有迁移代码通过编译的证据。恢复时应参考附件，重新实现和验证，不能直接应用整包。

等待 TON-1082 合入后，在同一张卡继续完整迁移；保留 `AdvancedLoginDialog`，最后才安排一次 Windows VM 构建、二维码过期刷新截图及真实验证码验证/失败证据。当前没有启动客户端、连接 Windows 主机或产生真实验证码录屏。

李政亲手验收清单（迁移完成、有已验证的 Windows 候选包后执行；当前均未验证）：

1. 打开个人中心的登录对话框，确认默认是 TV 二维码；扫码后，桌面先显示等待手机确认，再显示正确账号。不要把密码、短信码或登录 Cookie 发到评论或日志。
2. 关闭并重新打开客户端，确认已保存的账号仍可用；二维码过期后点击刷新，应生成可扫描的新二维码。
3. 需要安全验证时，在系统浏览器完成极验，返回桌面继续登录。关闭/取消验证后应有明确提示；页面加载失败和超时后能重新发起，不能永久卡在“处理中”。未触发真实极验不算通过这一项。
4. 退出当前账号后，消息等登录功能应重新要求登录；旧浏览器回调不能恢复已退出的登录尝试。删除保存账号后再重启，确认该账号不再出现。
5. 只回报每项通过/失败与不含个人信息的报错。真实账号数据保留在本机，不上传服务器。

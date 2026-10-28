# 熟人分发通道（开发中）

目标是朋友拿到链接即可安装，无需安装 Java；后续更新验证通过后自动切换，启动失败继续使用旧版。本目录是待验收实现，不是已发布渠道。

## 已接入的客户端行为

新构建使用 `signed` 更新源，默认 `https://learn.tonysuper666.com/bilipai`、`dev` 频道。`stable` 是独立频道；朋友用 stable，李政先用 dev。原来的 GitHub 无签名路径仅供内部旧协议测试工厂使用，生产构造器拒绝加载这种配置。

构建时可配置：

```sh
./gradlew -p desktop processResources \
  -PdistributionChannel=dev \
  -PdistributionBase=https://bili.tonysuper666.com
```

`distributionManifestUrl` 和 `distributionConfigUrl` 可以同时指向自己 GitHub Releases 下的**同一份签名文档**，更新 ZIP 也可用自己 GitHub 的发布资产地址。此选项是构建配置，没有加入面向朋友的服务器设置界面。签名错误不会触发降级到无签名 GitHub 更新。

更新请求使用独立 OkHttp 客户端，无 Cookie jar 或 B 站认证头。manifest 查询参数只有 `version`、`channel` 和随机 `installationId`。ID 保存在本机更新目录，不使用硬件信息。配置和 ZIP 请求不添加账号或安装标识。服务没有账号接收接口和遥测接口。

manifest 和配置使用 Ed25519 envelope：

```json
{"payload":"<原始 UTF-8 JSON 的 base64>","signature":"<64 字节签名的 base64>"}
```

签名输入分别为 `BiliPai-Windows/manifest/v1\n`、`BiliPai-Windows/config/v1\n` 加原始 payload 字节。更新 ZIP 的独立签名输入是 `BiliPai-Windows/archive/v1\n` 加 ZIP SHA-256 的小写十六进制 ASCII。客户端先验 manifest 和 ZIP 摘要签名，再下载并校验实际 ZIP 的摘要，最后才交给现有 `SafeUpdateZip`。

公钥固定在 `DistributionTrust.PUBLIC_KEY`。生产私钥只在 Mac 仓库外的 `~/.config/bilipai/signing/distribution-ed25519.pem`，权限 600，目录 700。私钥不上传服务器；服务器只保存签名后的公开文档和产物。

签名文档包含频道、递增序号、签发时间和最多 7 天的有效期。客户端持久记录每个频道/文档类型的最高序号，同序号内容替换及旧序号重放都会拒绝。到期后更新失败会保留当前应用；发布人需要定期签发新的文档。

manifest 的 `rolloutPercent` 在 0–100 之间，通过随机安装 ID 与版本的 SHA-256 固定分桶。`withdrawn` 列出撤回版本。下载前、激活前均重新拉取并验证当前 manifest；撤回后的已下载版本不会被启动。暂存记录保存原签名，重启恢复时重新验证签名和 ZIP。

激活继续使用现有的空闲检查、下载任务交接、`StartupHealth` 和活动安装记录；这些流程未被替换。真正的 Windows 安装、升级与坏版本回退仍需下述沙盒验收，JVM 控制进程测试不代替该证据。

远程配置只接受 `automaticUpdatesEnabled`、`checkIntervalHours`（1–24）与 `announcement`（最多 1000 字符）；未知字段拒绝。自动更新开关在自动激活前再次确认。公告走现有通知展示。配置不支持脚本、命令、动态类或其他可执行代码。

## 下载服务与部署候选

`server.mjs` 仅依赖 Node 标准库，绑定 loopback，前面使用 Caddy。临时路径使用 `BILIPAI_URL_PREFIX=/bilipai`；专用域名从根目录提供页面时，设 `BILIPAI_URL_PREFIX` 为空字符串并在 Caddy 使用普通 `reverse_proxy`。邀请码在服务器 `data/private/invitations.json` 中保存哈希，每个码有单独标签和频道权限。浏览器表单提交后获得 HttpOnly/Secure/SameSite 会话；每次下载重新检查原邀请码是否有效，作废也立即使现有会话失效。

安装包下载需要邀请码。供已安装客户端使用的签名 manifest、配置和 ZIP 更新资产公开提供，避免更新请求携带邀请码或账号数据。下载日志仅记录邀请码标签、时间和版本，不记原始邀请码、IP、User-Agent、安装 ID 或 B 站信息。没有 HTTP 访问日志输出。

服务器目录：

```text
/opt/bilipai-distribution/
  server.mjs, invitation.mjs, publish.py
  data/private/invitations.json, downloads.jsonl
  data/channels/<channel>/current.json
  data/channels/<channel>/<sequence>/manifest.json, config.json, publish.json
  data/public/artifacts/<version>/<zip>
  data/installers/<version>/<msi-or-exe>
```

`publish.py` 先检查上传文件摘要和不可变资产冲突，再写入完整版本目录，最后用单次原子替换切换 `current.json`，同时切换该频道的 manifest、配置和下载页版本。失败时旧指针保持不变。保留旧版本目录，以便签发新序号撤回版本。

`bilipai-distribution.service` 和 `Caddyfile.fragment` 是候选配置。安装服务前由独立复审确认服务账户、目录权限、端口 8140 与已有服务无冲突。下载服务只需读取公开产物，私有目录允许写下载日志；发布操作由 SSH 运维用户执行。

首次修改 Caddy 前备份：

```sh
sudo cp -p /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-TON1013
sudo caddy validate --config /path/to/reviewed/Caddyfile --adapter caddyfile
# 仅校验和独立复审通过后复制候选文件并 reload
sudo systemctl reload caddy
```

回滚：恢复备份，`caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` 成功后 `systemctl reload caddy`，停掉新服务。不要修改其他站点、清空旧目录或覆盖不可变安装包。本轮仅校验了候选文件，没有重载生产 Caddy。

运维创建/撤销邀请码（服务器执行，码不进仓库，不将结果放公开评论）：

```sh
node invitation.mjs create friend-01 dev
node invitation.mjs revoke friend-01
```

## 签名和上传入口

```sh
BILIPAI_ARTIFACT_DIR=/path/to/verified-artifacts \
  desktop/distribution/release.sh 0.2.427.202 dev prepare
# 合并和独立复审之后才使用 publish
BILIPAI_ARTIFACT_DIR=/path/to/verified-artifacts \
  desktop/distribution/release.sh 0.2.427.202 dev publish
```

目录必须含对应 ZIP、安装包、`release-notes.txt` 和现有 `build.ps1` 生成的 `release-gate.json`。脚本核对该 gate 的全部原门禁、源提交、版本与 ZIP 摘要，再读取 ZIP 中实际打包的 `windows-update.json` 核对签名源/频道/地址。安装包名可用 `BILIPAI_INSTALLER_NAME` 指定；签名密钥可用 `BILIPAI_SIGNING_KEY` 指定，但公钥必须与客户端一致。

发布前先保留当前频道指针和旧安装包。`publish` 还会检查提交已进入桌面集成分支，上传到隔离 incoming 目录，再调用服务器发布程序，不需要交互登录。脚本打印撤回命令：

```sh
node desktop/distribution/withdraw.mjs 0.2.427.202 dev
```

撤回命令在 Mac 上读取并验证服务器当前签名，签发更高序号，禁止继续提供该版本。它不是把旧 manifest 复制回去，也不会强制降级已经运行健康的版本。启动坏版本的回退由客户端 `StartupHealth` 负责。

`stable` 还要求实时读取 TON-1009 为 done，以及 Mac 上 `BILIPAI_STABLE_APPROVAL` 文件证明李政先用过 dev：`{"devUsedBy":"李政","devVersion":"…","evidence":"实际使用的验收记录"}`。该文件不进仓库。当前没有此批准，也没有发布 stable。

**未完成的一键范围**：此入口已连接签名、上传和指针切换；尚未串联 Mac 全门禁、Windows jpackage 构建、Sandbox 场景和安全启动任务。没有对应验收产物会直接失败，不能称为已完成的一键发版。既有 `build.ps1 -ReleaseGate` 含直接 GUI 启动，不能未经改造在 winbox 上运行，真机启动仍只能走 `BiliPai-MacTest`。

## 验证与剩余验收

```sh
node --test desktop/distribution/server.test.mjs
python3 desktop/distribution/publish_test.py
PYTHONPATH=desktop/tools python3.12 -m unittest discover \
  -s desktop/tools/tests -p test_publish_release.py -v
# Mac 按 TON-987 的 POSIX 验证适配环境运行：
./gradlew -p desktop :compileKotlin :compileTestKotlin :test \
  --tests 'com.bilipai.desktop.update.*'
```

已覆盖真实下载 HTTP、单独作废码/会话、只记录允许字段、不可变上传和原子频道切换，以及客户端实际 downloader/暂存恢复/改一字节拒绝/撤回不启动/防重放。测试 ZIP、MSI 数据是隔离 fixture，不是可用安装包。不要把其大小或结果当正式安装证据。

剩余必须交付：

- 当前提交的 Windows 自带运行时安装包、大小；干净系统双击到首页的计时、开始菜单快捷方式和卸载残留检查。
- Sandbox 截图序列或录屏：v(n) → v(n+1)，再投放启动崩溃的 v(n+2)，证实回到 v(n+1)。由验收者独立改一字节复测。
- SmartScreen 实际截图说明；当前下载页只有文字步骤。
- 串联上述安全构建/沙盒门禁的一条命令。
- 独立安全复审后部署服务和临时路径，再验证公网有效/作废码的状态与完整下载摘要。
- 给 `bili.tonysuper666.com` 添加 A 记录后再切换专用域名；不申请新的 Cloudflare 凭据。

截至本轮，winbox 未发现 `WindowsSandbox.exe`；Mac 的 `prlctl list -a --json` 返回空列表，没有可用 Windows 虚拟机。需要李政在 Windows 上启用 Sandbox（可能需要重启），之后再测，不自行修改主机功能或在真机安装客户端。

一手接口依据：[JDK 21 jpackage](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)、[Java Signature](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/Signature.html)、[Microsoft Sandbox 安装要求](https://learn.microsoft.com/en-us/windows/security/application-security/application-isolation/windows-sandbox/windows-sandbox-install)。

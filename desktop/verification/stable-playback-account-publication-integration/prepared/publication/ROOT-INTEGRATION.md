本包是 Playback84 之后的最终消费者补片，尚未安装产品。固定原源是 v0.2.3/3d5d19a2f994daccd0e2f8b5f522b6d82f43d589；窄证明用 actual43 的不可变 97CP，加明确列出的候选生产类覆盖。它没有创建另一账号、缓存、下载队列、客户端、CookieJar 或播放器。

安装顺序：

1. 先消费既有 `stable-playback-account-protocol-parity/frozen-handoff.json`（b990563a…，84 artifacts）。该历史包全部原字节已重新核验。本包 `prepared` 内 Auth 文件只是证明输入副本，不能再注册第二份。
2. 安装本包四个新 manual：`player/DesktopPlaybackPublication.kt`、`cast/DesktopCastMediaPublication.kt`、`su/litvak/chromecast/api/v2/DesktopCastPublication.java`、`DesktopCastWriter.java`。精确路径和 SHA 在 `install-recipe.json`。
3. 按 `local-hunks.json` 的顺序应用现有 18 manual 文件 family 的 94 个声明/调用点 hunk。每行的 before/count 必须匹配；不要覆盖 `proof-only` 的 whole files。唯一 `producerRequired` 原下载器行不能修改上游 app 文件，转下一步 producer。相同 path 多行是顺序补片，baseLF 是上一行产物；Repository 第一行已假定84先安装。
4. `producer-hunks.json` 只给唯一 `extract-cast-platform.py` 追加两个选取适配，其他五个原生成文件逐字未变。`prepared/desktop/tools/extract-upstream-download-transport.py` 产生原下载器全文，只有构造字段 `OkHttpClient` → `Call.Factory`。合并 `registry-delta.json` 的同一原 identity（保留其余 feature），DIRECT 改 platform-rewrite；既有 Sync 会移除旧 direct copy。接 `build-registration-hunks.json` 的独立任务/目录依赖，不留同 FQN 的两个生成源。
5. Channel 是现有唯一 vendor fork。应用 `cast-provenance-hunks.json` 更新现 Channel 记录，加两个新 manual 记录；原 Maven 坐标、原 archive、32原文件、三项已声明 modified original、证书/依赖/license 全不变。不要关闭现 verifyGoogleCastSources。
6. 应用 `synthetic-migration/local-hunks.json`：7 Controller harness、4 Listen harness、DownloadManagerTest 11次 internal ctor 与 NativeSmoke 2次 caller。原断言/原 transport 行为不改。Controller/Smoke gate 捕获原 scope Job，Listen fixture 新增仅该 fixture 的 monitor/alive 并在 close 先退役，Download helper捕获实际 test Job。无 synthetic source 默认权限，也不把 local gate 冒充 Account receipt。Root 必须 compileTestKotlin；本包只窄编 main NativeSmoke，没有重跑旧 suite。

Shell 必须 semantic merge：本包 Shell before 来自 Root 保存的 exact42 raw baseline。保留 actual43 四个 Retainer/Palette lifetime hunk和之后 Root 的 image/global修改；绝不安装本包 whole Shell。`currentCastMedia` 新包捕获 immutable route/sourceVersion/primary epoch/request Job，真正 media resolution 尾部仍做原 current-source 比对；两个 dialogs 的 required factory 改为 `suspend () -> DesktopCastMediaPublication?`，各按原协议消费同 Root wrapper。

授权与取消合同：

- data 层 `PlaybackSource` 的 receipt 通过原 copy、CDN、premium audio、PGC/PUGV、audio mapping和 plugin rewrite传到底。Windows native wrapper另持同 receipt；原原始 API DTO不增加字段。插件 rewrite 后保留来源 receipt，原对象未改时保持其 identity，避免误走另一 CDN 分支。
- Controller/Listen 的 injected data source必须显式 publication。普通 Root ctor沿唯一 Repository/Store；只有原 AU/live/external非选播主账号源，显式 allowPrimaryAccountSource + captured primaryAccountEpoch。null receipt不是 Root 授权的默认绕过。
- Store → queue/native/channel短 gate。Controller load/recover、PGC、Listen先 final admission，再送同 Mpv actor；实际排队 `Action.Load` 再检查 frame与现 sourceVersion/revision/closing。不能在 Store/entry 锁内调用 native close/join。已开始的 native loadfile/HTTP/cast写属于 in-flight，选播 MID 改变不自动停已经播放的 source。
- HttpFactory委派传入的现 client（保留原 proxy redirects/networkInterceptor）；Store只包 newCall/enqueue，响应/执行等待在锁外。execute中断会关闭已到或迟到 response；取消不执行账号回退。挂起 Store monitor期间仅请求Job取消也拒绝迟发。
- Download queue只提交原内存 task。目录/JSON持久化/调度在 Store 释放后。receipt/cookie/header只存在原 wrapper 的 transient 字段；原序列化 Task不改。已排队 captured receipt退休时 PAUSED，不换新账号重抓；恢复的持久化 task缺receipt时，先用原 resolver获取 fresh owned source。普通URL失效重试保留原算法，但先确认旧 receipt；取消不能 fallback。
- Channel只有一个 writer queue；enqueue与worker开始是短 gate线性化点。write/flush/ACK/连接与close drain均在 Store外；worker开始前拒 stale/cancel/closing。开始后不能撤回已发送字节。close取消待发并原 socket中断，外锁有界drain；deadline/close timeout不能声称线程已经退出。
- 同 owner 的授权取消只清自己的 Controller opening / Listen loading，旧 generation不能清replacement。长期 watchdog/ended观察器拒绝单次 retired command，不让这次授权取消永久取消该观察器。同 Listen pause后重新生成 replay frame，expected sourceVersion保证不能重播foreign source。

证明与限制：

`runs/compile-08` 是最终完整 prospective main source窄编（31 inputs、explicit overrides清单及 compiler/runtime前后pin均在结果）；`runs/fixture-05` 是4组39断言、11个实际 loaded class CodeSource/class bytes，fixture类与产品类交集0。真实现 Store、原任务模型、actualController/Listen/Manager/Channel writer被调用；假数据源/终止 OkHttp application interceptor明确 memory-only，未 socket/HTTP/账号读/发/GUI/HWND/nativeDLL。

Controller测试对比真正 Mpv requested-source snapshot和实际 publication callback；Listen ended状态为明确的 task-only memory stimulus。它们不是物理“已在解码播放”的验收。Root安装后需要整包 classes/compileTestKotlin和新的不可变图0override focused proof；真实 native加载/媒体恢复、LAN receiver/ACK、PGC UI按钮、账号 Profile实际交互及Root app关闭行为仍未由本包验收。原32类 fork运行库和任何旧frozen都未改。

失败历史保留：compile01（OkHttp5 tag签名/旧Jar overload）、fixture01（异常类import）、fixture02（原 NativeSource.copy JVM变化而未重编未改 helper）、synthetic prepare01（同字 restored构造误要求单 occurrence）。其中 copy ABI由全产品重编自然闭合，本包显式补编未改 `PlaybackStreamHeaders.kt`，不能把它登记成新上游选取。

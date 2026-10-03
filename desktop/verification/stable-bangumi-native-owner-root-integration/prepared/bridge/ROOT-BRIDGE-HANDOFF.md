# 原番剧 Player：可安装的共享 native-owner 桥，完整页面尚未接入

固定原源 v0.2.3 `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`；实际 Candidate 基线 `464f5573331dfc7f7c2f1e47a5bf6bbac8cfd201`，本 lane 未写 Candidate。父级独立番剧四页及消息七页、share provider 已在此基线，本桥不覆盖其整文件。

`install-contract.json` 是安装合同：只复制列出的 6 个新文件；9 个既存 target family 只按 `exact-hunks.json` 的 17 个精确 fragment 安装，全部 forward/reverse replay 已独立验证。`prepared` 中既存文件只作对照，禁止整文件覆盖。生成目录只由实际 Gradle sole producer 生成，不复制 `.local/generated` 到产品。Registry 是 1219→1225 原 source identities、244 resources 不变，1 个现有 BangumiRepository feature union，零新 dependency。源条目数不表示功能完成率。

本 slice 的具体业务：

* 完整原 BasePlayerViewModel / BangumiPlayerViewModel / Resume / EpisodeSkip / DashManifest / Overlay 六个 body，以及完整原 PGC/PUGV/WBI/legacy/error getBangumiPlayUrl body；七个输出各由单一 producer 生成。独立 git-show / edit inverse 验证原方法保留，新增只有已收到 raw playData 的三个 metadata 存储点。所有 required Base/Download/Actions 端口保留，不提供默认成功。
* 原 PGC VM 的具体 native presenter 借既存 OriginalVideoOwnerAssembly、CoroutineScope、Invocation、同一个 Portrait captures map / PlaybackSessionStore token / NativeOwner / Mpv。没有新 HTTP client、repository、store、player 或 source counter。ThreadContextElement 跟随当前真实 Job，不保留 latest binding；原 VM owned flow 的 commit 必须注入 presenter.commit，才能在最后 Store gate 拒绝旧响应。
* 初始与画质请求经过同一 request authorization、media-byte preparation、native.publish 和原 Video VM Subject/Mini adoption。PGC/cheese Referer、原始完整 MPD、raw signed mirrors、所有 DURL 顺序/时长、备用地址、seek/pause intent 均保留；合法空 BV/aid/CID 0 不伪造标识。缺失 uploader / dimensions / episode statistics 维持缺失，不能把季播放总数当单集统计。
* 缓存恢复与换音轨经过同 RootMediaFactory/NativeOwner.acceptedMedia。新 overload 保留旧普通 ABI；原 accepted receipt/Cookie/headers 不变，最终发布和 byte admission 均检查 presenter。完整 progressive plan 在 accepted 工厂保留，恢复不能丢第二段。原生 cache transport、accepted lease 与源版本仍由原 owner 管理。
* 同一普通 full Video VM 在 PGC present/source referer 下暂停 ordinary type3 heartbeat、结束监听、普通 quality/recovery/plugin 等生产者；普通视频接管退役 PGC scope。相同 BV/CID 的接管仍强制真实 ordinary reload，不能误复用 PGC source。Scope 取消在 Store/entry/native gate 外进行。

验证层级必须保留：final `runs/06` 在 immutable actual88 的 105 项真实 CP 上通过 19 个显式源窄编译；7 个既存 source overlays（6 个 handwritten + 原 full Video VM 生成源），362 个实际 class entries 交集已确认仅来自这些 family。没有 normal product compile。`proof-runs/04` 的 39 个 CPU 断言通过：原物理 plan、实际 original Store token、真实 NativeOwner 在未挂载 Mpv 上的 requested-source/version bookkeeping、取消与最后 publication 拒绝。未模拟 loadfile ACK、frame、网络响应、缓存命中或账号成功；Root/native/account runtime 均未接受。旧 run01/02/03/05 中失败、旧实际86/87 与修复历史保持原标记，不能用其替换 final evidence。

完整 Player 仍未接入，具体缺口如下。所有这些缺口在最终合同为 false，不增加用户功能完成度：

| 边界 | 本 slice | 继续工作 |
| --- | --- | --- |
| 原 Screen + 4 UI body / typed Root leaf | 原文在已冻结四页 lane，尚未生成；当前 Root 仍进 BangumiBrowserScreen | 完整原状态/overlay/collapsed/content body，seasonId / epId / resume / isCourse / preferredAid 与 Login/Space/Web 回调 |
| Root full VM / environment 实例化 | required contracts 与具体 native presenter 已编译；Root 尚未构造 PGC VM | 同 assembly 借 scope，实际 decoder/settings、Sponsor、actions、state commit 接所有 required ports；不能用宽松默认值代替业务 |
| 原 PGC heartbeat / listener / 自动下一集 | 原完整 VM 定时 type4 body 保留；ordinary 生产者已隔离 | 捕获旧源的最终离页/接管 heartbeat 与保存进度、实际 listener、Mini 下一集/回退；目前 scope retire 仅取消，不能算最终 flush 已完成 |
| PUGV type33 评论 | 当前原 VM/Screen body保留；没有 Root topic33 consumer | 同 comments/composer 的读取、reply、发送、点赞/删除均携带 epId/type33，普通 PGC aid/type1 |
| 完整原下载及失效刷新 | 原 VM 下载方法保留；required download contract 未接 | 同现有 offline owner 的真实 PGC/course metadata/resolver、全 DURL、会员/购买提示；禁止普通 endpoint 或假成功 |
| Root window / carrier / Mini / PiP / keep-awake | 只借现存同 native owner；无新 surface 或全局 player | 原 Screen.dispose 不得释放唯一 Mpv；covered/back 保留 PGC presenter，ordinary/portrait 接管及时取消未发表的 PGC producer |
| 音轨状态投影 | 来源与原 receipt 受实际 admission；原 PGC VM 在 accepted 后提交音质 | 完整 UI 接入时把成功后的 PGC audio quality 同 Subject/Mini 投影，当前桥发布瞬间投影可能沿用旧音质字段 |

另有独立 `v025-upgrade-mapping.json` 与 pinned 原 diff：新源为 `79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40`。它只记录升级任务，未改变本 slice 的 source/producer/manifest baseline，也不声称 v0.2.5 完成。包括新版 Player VM/Screen/Content/Overlay、comment draft/emoji/保排序 refresh，以及 core-data / core-player 路径迁移。并发授权取流的具体全局 owner 仍需 Root 统一追踪，不从 ApiClient 的模块迁移推断该功能已复用。

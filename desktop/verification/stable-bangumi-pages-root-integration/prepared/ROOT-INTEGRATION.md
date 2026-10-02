# 可合入四页 slice；原 Player 尚未接受

基线为 Candidate `6ac84c8036ed4c75e2944d92d1020566e283e983`。本 lane 没有写 Candidate。仅安装 `install-contract.json` 的 2 个新文件，再逐条安装 `exact-hunks.json` 的 9 个精确片段；禁止把旧 Shell、RootRouteAssembly、Gradle 或整个 registry 文件复制回去。正式 Kotlin/Gradle 验证仍由 Root 完成。

新 sole producer `desktop/tools/extract-upstream-bangumi-pages.py` 对每个原文件检查固定 v0.2.3 LF hash，正常 Gradle 输入包括本工具、共享 Kotlin parser、`extract-upstream-media.py` 和显式 registry 源。+8 原 source identities，总数从 1183 到 1191；0 资源、0 依赖。现有原 Home PGC Hub producer 不变，它仍是该 Hub 类和已有 follow/status/policies 的唯一 producer。

完整原文件有 Catalog Screen、Detail Screen、TimelineContent、DetailComponents、Review Screen、combined BangumiViewModel、BangumiReviewRepository 原 body 和 MyFollowStats。新增缺失原 follow-preload、Episode page/preview/order、PosterDetailSkeleton 的完整选择 body；完整原 season/index/media/PUGV/follow 共 8 个方法。`source-inverse-audit.json` 独立从固定 upstream git 对照，列出全文/选择函数逆变换一致性。

Root 消费实际 typed Bangumi(initialType)、BangumiDetail(seasonId,epId,mediaId)、BangumiReview(mediaId,title)，按原 AppNavigation 精确携带原 episode.id/aid/course 判据。季度切换原行为是 replaceTop，新方法只扩展既有 RootRouteAssembly 的 admission 和 native beforeCommit，并没有直接写 stack。原 TimelineContent 本身没有独立 typed Timeline NavKey；Catalog 的实际时间表仍由原 Hub/Content 渲染，不能把这个兼容组件说成额外独立原导航页。

环境必传实际 Root Home aggregate 的 stateless Hub repository、唯一 DesktopRepository owned raw BangumiApi/csrf/session authority，以及实际 ordinaryVideoResources.progress 的只读借用。每个真实 NavEntry 有原 VM 和 child scope，entry 移除/epoch 退休会取消，数据状态写入使用现有 short commit；点评/追番 mutation 另检查 active entry。request adapter 在 IO 前后检查 caller Job 和 owner，原 runCatching 不能吞掉取消后再把晚结果当成功。Android Toast 使用现有 feedback，RenderEffect haze 能力为真实 Windows 不支持；原业务提示与失败 Result 保留。

`runs/03` 是旧 actual84 的 13-source narrow compile。最终 `runs/05` 使用 actual85 的 101 ordered CP，包含 12 新生成源和 1 实际 RootHost；没有既有 class FQN 覆盖。独立 RootRouteAssembly 新方法和 Shell exact hunk 的最终整产品编译仍需 Root。`proof-runs/02` 执行完整原 combined VM 和原请求 body 的 34 个断言：PGC episode 优先、补齐分区、media→season、课程/付费回退、Review 短长/游标/错误/score、跟踪追番缓存、取消/晚返回/covered mutation、分页 preload、原 replaceTop 参数语义。这些是明确 raw protocol CPU 夹具，不证明实际 Root 网络、窗口、MPV 或个人账号。

Player 原完整 source closure 在 `original/` 与 `input-inventory.json` 固定保存，当前 `completeOriginalPlayerAccepted=false`。不得把这次 slice 计为原独立 PGC 功能组全部完成。原 Android ExoPlayer.Builder/BasePlayerViewModel/loadDash/createMediaSource/heartbeat/mini handoff/download/CDN/WBI 分支还须桥接到同一个 OriginalVideoAssembly invocation/session/native publication；目前禁止绕过它直接 section.load，也禁止新 MPV/client/Store。现有 Desktop Player typed fallback 本次尚未替换，需下一阶段继续。

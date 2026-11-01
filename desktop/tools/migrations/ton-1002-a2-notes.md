## A2 交付状态

本次交付可重复生成的完整 A2 配方与补丁，生产迁移仍为 **373/574**；A1 不重做。A2 尚未原子应用，不能据此宣称续播界面已验收。基点为 PR18 `e103c1e865dae652106f6527382b6ffbe9bc2a64`，目标 `desktop/integration-263-claude-main`。

当前指令禁止改共享 `desktop/build.gradle.kts`，仓库 AGENTS.md 也将构建文件归 Root。因此提供 `--export-patch`，六个计划文件作为同一个批次交 Root 应用，避免只注册组件或先删旧弹窗。工作树没有激活其中任何一个生产改动。

## 原始材料与例外

- 上游 `v0.5.0` 中的 `383be1d47ff1b46c8c793e4c78e28bcbf715b331` 已含最终续播组件；配方同时核验 tag 祖先关系、源码完整 SHA 和当前工作树字节。组件原始 hash 为 `82eb247fba09a6453b20d58cfafbff66a89af48716d216b06d1b439660953286`。较早的 A1 来源提交不含这份组件，不能沿用其来源声明。Builder-T 的组件 dry-run2 仅作为预检材料，不当作消费者验收。
- 组件唯一平台适配：`SystemClock` 接现有 `DesktopHomeClock`。`VideoDetailPlaybackSessionEffects` 保留 A1 的平台适配、VIP 渐变色例外和原始身份过滤，只移除 A1 的续播到旧过渡弹窗替换。
- 真实抑制信号已经存在：生成 holder 的 `suppressOverlayControlsForReturn` 来自 `returnVisualBudget` / `shouldSuppressOverlayControlsForReturnBudget`，目前字幕层也消费它。现有 `isActuallyLeaving` 在实际后退时置位、按 `currentBvid` 记忆重置；return-session phase 和 committed-return policy 决定预算。A2 只把这个现有布尔值传给 inline host 和独立全屏 Section state，host 参数没有常量默认值。
- holder 的 `playbackEventState = rememberVideoDetailPlaybackEventState(currentBvid)` 同时供现有 effects 和新增 `LocalPlaybackResumeFeedback` provider 使用。provider 包住实际 `VideoDetailScreenContent`，覆盖 main/overlay 分支及 movable player consumer，不另造状态持有者。
- Section 的 `MutableTransitionState` 实际驱动原封面 `AnimatedVisibility`，按现有 `currentPlaybackIdentity` 记忆。显隐表达式、enter/exit、布局和原生命周期均保留；ready 同时要求 `isIdle && !currentState && !targetState`、非手动开播封面、非 `showCover`、真实转场未抑制，并保留返回封面/PiP/竖屏全屏限制。没有固定延时或假抑制常量。
- 既有 holder 补丁从固定桌面基点读取并核验 SHA，再叠加 A2；不会覆盖先前评论草稿、手势等桌面行为。两个生产 renderer 均在临时目录读取固定 legacy commit，既不写共享 `.legacy-root`，也不依赖缓存 build 输出。
- 两处构建登记只涉及：新增 `PlaybackResumeFeedback.kt` 的迁移路径；把现有 `original-video-player-section-full` producer 接入现有 transition patch map。后者是使用既有补丁机制，不增加生成器或构建入口。
- 开工前核对其余 96 张开放卡 scope、15 个其他开放 PR 的文件清单，工具和五个目标 Kotlin 补丁无重叠。共享构建登记仍由 Root 排序。E/TON-1056、BGM/TON-1004、VIP/VM 通知发射及 owner/session/request/plugin 链不扩入；尤其没有实现 D 的 VM notice emission。

## 可重复验证

在仓库根目录运行：

```sh
python3 -m unittest discover -s desktop/tools/tests -p test_migrate_video_batch.py -v
python3 desktop/tools/migrate-video-batch.py desktop/tools/migrations/ton-1002-a2-resume-feedback.json --export-patch ../a2-complete.patch > ../a2-dry-run.json
git apply --check ../a2-complete.patch
python3 desktop/tools/migrations/ton-1002-a2-verify.py > ../a2-isolated-verification.json
```

结果：5 项工具测试通过；dry-run 为 6 个文件；完整 patch 的 `git apply --check` 通过。真实配方的一次性检出应用成功，重复计划为 0 改动，5 个生成的 Kotlin 补丁均对真实原始源码消费成功；篡改已审 holder 补丁时整批拒绝、六个目标均不再改变。验证器自己建立并清理临时检出，不修改生产文件、不调用 Gradle。

## Root 原子应用与待验收

先核当前 PR head、scope 重叠及完整 dry-run；基点或旧补丁发生变化时不得强行套用。Root 在其获准工作树对整个批次运行同一工具的 `--apply`（替代 `--export-patch`），随即 dry-run 验证 `changedFiles=0`；组件、provider、host、Section 和旧弹窗移除必须一并入编译。不要单独应用构建登记或 effects 补丁。

**未验证**：A2 消费者编译、`verifyUniqueClassNames`、同环境基线逐测试身份新增失败为 0 且数量不减，以及以下真实动画/生命周期行为。本卡未启动客户端或任何重构建。TON-1073 优先；所有重构建仍须共用 Coach heavy 锁、锁后核完整进程参数与进程树、nice 15、max-workers 2，前台最多等锁 15 分钟。取不到锁交回证据，不自动重试。

| 待验场景 | 源码门控 | 外部行为证据 |
| --- | --- | --- |
| 封面可见 | currentState/targetState 可见时不 ready | 未验证 |
| 封面退场中 | isIdle=false 时不 ready | 未验证 |
| 隐藏且动画完成 | idle/current/target 隐藏，其他门控均解除后允许 | 未验证 |
| 真实转场抑制/解除 | 抑制时禁止；解除仍检查动画状态 | 未验证 |
| 封面重新显现 | targetState=true 当次即禁止 | 未验证 |
| 切换 bvid/cid | 动画状态按真实身份重建，组件和 effects 保持 mediaKey 过滤 | 未验证 |

这是可应用的源码包与工具验收，尚非 A2 产品验收。原子应用后完成上述门禁，再按当时可用的异源 reviewer 链交接；本卡不因脚本落地置 done。

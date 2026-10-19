# T13 #6 fnd-home-card-cascade-definers（2026-10-19）

分支 grokbot/slices-batch1，提交 d7b465f14（父 6085b78f8）。

## 做了什么（窄范围）

- 新归档 desktop/upstream-slices/v046-home-card-cascade-definers/（例外①，T13.md 第 3 行）：v0.4.6 d6bf8b16 原样的 HomeOverlayPillButton.kt（blob 495e8686）和 AppPressShiftContent.kt（blob e93226d2）+ LICENSE + manifest（sha 6c17cf09…）。verify-archive ok，blob 与上游对象相等。两个路径都不在 upstream-sources.json 里，是新文件，不需要 exclude。
- 新生成器 desktop/tools/v046_home_card_cascade_definers.py（family A），作为 extract-upstream-home-page.py generate() 的最后一步原样发射（函数内静态 import，与 #3 的 v046_identity_badges 同样方式）；重跑覆盖普通文件，符号链接/目录/fifo fail closed；无 3.13 专有 API，无写死 /tmp。
- 清单 741/727/137/141 → 743/729/138/142，目录摘要 79eed4f2… → 3abef86889bfada5ee0fabed890ee15a3b2794362c1911a27f82c26ce528bf9c（工具算出）。build.gradle.kts 只在 extractOriginalHomePage 加两行 inputs。
- 现有测试只改夹具：test_v043_home_recommendation_badges_source.py 的局部仓库副本多拷一个新归档（与 #3 的做法相同），断言不变。
- 没有新增调用点；依赖（AppContentColors.primaryText、AppButton 重载、AppShapes.container(ContainerLevel.Pill)、AppSpacingTokens、HomeGlassResolvedColors）都已在编译集中。
- 路线图 coordination 写“大屏 UI 任务拥有首页/主题/卡片范围，编辑前确认”：本切片只加定义、不动任何界面消费者；T13 已明确指派 #6。

## BLOCKED / 需要 Root

- resolveOldContentDividerContainerColor（+ OLD_CONTENT_DIVIDER_CONTAINER_ALPHA）在 HomeRefreshUiPolicy.kt，resolveWallpaperGlassAlpha 在 VideoCardAdaptiveTintPolicy.kt：两者都是 direct 模式文件。v0.4.6 的 HomeRefreshUiPolicy 只比现版多这两个声明（其余逐字节相同）。整文件替换需要 exclude 行（超出例外④，同 #3 调色板、#46 第一步的问题）。备选：用新文件只发射这几个新声明（5.3 第 3 种），但以后整文件替换时会重复声明，需要 Root 决定。extract-upstream-home-full-card.py 的 tint 79e8 钉住重锚同样未做。
- 符号门禁数据（我不改 symbol-closure-expected-gaps.json）：本切片又让 2 个预期缺口变陈旧（core.ui.components.AppPressShiftContent、feature.home.components.HomeOverlayPillButton）。分支上合计陈旧 6 个（userVipPink + 3 个 data.model + 这 2 个），latent resolveVideoDisplayProgressState 已定义。CurrentIntegrationTree 仍是同样 4 个失败。

## 证据分级

- Linux 编译：compileKotlin、compileTestKotlin 通过（0 错误）；extractOriginalHomePage 重跑，两个文件已生成并编译出 AppPressShiftContentKt.class、HomeOverlayPillButtonKt.class。
- Python：新测试 8/8；test_refresh_generation_reentry 6、test_v040_native_chrome 9、test_v042_search_dependencies 8、test_v043_home_native_cards 10、test_v043_home_recommendation_badges 8、test_v046_identity_badges 7、test_sync_upstream 74、test_validate_source_cohort 25（1 个仅 Windows 跳过）、test_package_source_qualification 14 全过。已知旧失败：test_extract_upstream_home_load 1/5（v0.2.9 审计哈希，已知），test_extract_upstream_brand_consumers（子进程丢 shim，Linux 路径问题，已知）；test_symbol_closure 4 个 CurrentIntegrationTree（门禁数据待 Root）。
- JVM：无对应测试（上游没有这两个文件的测试）。
- 未验证：Windows 云端、Python 3.12 实跑、任何 UI 行为（两个组件目前没有调用方）。

# BiliPai Windows: upstream v0.2.5 到 v0.4.6 跟进路线图 (2026-10-18)

证据级别: 仅静态证据 (11 个批次的分类结果 + 只读 git/grep 抽查)。没有编译、没有运行任何 JVM/Gradle/应用，没有联系上游。
范围: 339 个提交、22 个发布 (79e8fa30 -> d6bf8b16)。机器可读版本: `roadmap-20261018.json` (同目录)。

## 0. 摘要 / Summary

1. 374 个分类条目中，**211 个**属于 Windows 适用且状态为 partial/missing/unknown，进入本路线图；73 个 not-applicable、5 个已被桌面自有代码取代、4 个适用性不明、81 个源码层面已完成 (均不进路线图)。
2. 这 211 个条目被分成 **59 个切片**，按依赖排序: T0 基础/合规/审计 (10)、T1 高中价值功能与正确性 (28)、T2 中价值打磨与廉价修复 (9)、T3 低价值/待决策 (10)、T4 仅决策/推迟 (2)。
3. 工作量数量级 (启发式，仅源码+Python 测试，不含编译/运行验证): 约 **285 人天** (合理区间 171-456)，约 14.2 人月；T0+T1 占 144 人天，却覆盖 63/79 个高/中用户价值条目。
4. 最先要做 (编译前就会炸): `fnd-identity-badges-vip-pink` (已携带的切片源码调用未定义的 `userVipPink`，grep 已核实)，以及 `fnd-symbol-resolution-gate` (纯 Python 的符号闭包检测，用来在编译被推迟期间提前发现级联缺口)。
5. 最值得 4K 桌面用户的功能: 选集/合集选择器 (L, high)、番剧播放器功能 (L, high)、相关推荐加载 (S, high)、搜索保留结果、动态通知准确性、RSS 外站图片 Referer (S，已核实 DesktopRepository.kt:96-99 无主机限制)、字幕菜单滚动 (S)。
6. 需要 Root/用户先决定的事项共 12 项 (见第 5 节)，最关键: 卡片转场是否在范围内 (文档冲突)、4K 下字体基线、未合并的评论闭包分支、侧栏 vs 底部 Dock、低内存要求与 0.3.8 缓存预算的冲突。

## 1. 数字 / Totals

### 1.1 进入路线图的条目 (211)

| 维度 | 值 |
|---|---|
| 按状态 | missing 104, partial 98, unknown 9 |
| 按适用性 | applies 92, applies-with-adaptation 119 |
| 按用户价值 | high 5, medium 74, low 132 |
| 按条目成本 (剩余工作) | S 86, M 96, L 26, XL 3 |
| 切片数 | 59 (S 6, M 29, L 21, XL 3) |

### 1.2 全部 374 条目的去向

| 去向 | 数量 |
|---|---|
| 进入路线图 (applies/awa + partial/missing/unknown) | 211 |
| not-applicable (Android-only/TV/版本号/合并/测试/构建) | 73 |
| applies-with-adaptation 但已被桌面自有代码取代 | 5 |
| 适用性 unclear | 4 |
| done-at-v046 (源码层面，未验证运行) | 81 |
| 合计 | 374 |

### 1.3 分层统计

| 层级 | 含义 | 切片 | 主条目 | 启发式人天 |
|---|---|---|---|---|
| T0 | Foundations, compliance and audit (unblock everything else; no or little direct user value) | 10 | 22 | 28 |
| T1 | High/medium-value user-visible features and correctness fixes | 28 | 94 | 116 |
| T2 | Medium-value polish and cheap independent fixes | 9 | 41 | 41 |
| T3 | Low-value polish, phone-leaning or decision-gated families | 10 | 52 | 78 |
| T4 | Defer / decision only | 2 | 2 | 22 |
| 合计 | | 59 | 211 | 285 |

## 2. 排序原则 / Ordering rules

- **先基础后消费者**: design-system/core 符号 (sheet 动效、语义色、VIP 粉色、主题 token、core-data 重定位策略、卡片级联 definer) 先于所有消费它们的功能切片。每个切片的 `prerequisites` 都指向更早的切片 (构建脚本已断言)。
- **消费者规则 (consumer rule)**: 0.3.8 语义色层牵动约 148 个已消费文件。不要把它们逐个升到 0.3.8；每个功能切片运行时把自己的文件直接升到 v0.4.6 内容，因为后续版本又改过大多数。同理 PagesSelector/BottomControlBar/PortraitVideoPager 等一次性升到最新。
- **符号闭包门 (symbol-resolution gate)**: 编译被用户推迟 (「不需要急着编译」)，因此用纯 Python 扫描生成器输出里的 `com.android.purebilibili` 导入是否都有 definer，代替编译来发现级联。每个切片把自己的已知缺口加进门的期望清单。
- **快速收益 (quickWin) 可提前**: 约 10 个切片的第一步只要 S 级且独立，例如字幕菜单滚动、RSS Referer、直播排行榜类型参数、动态冷启动空状态、聊天游标序列化。
- **重复与记账条目**: v029/v030 直播列表隔离、v039/v040 历史原位更新是同一件事 (合并在同一切片)；PR #870/#905 合并类条目 (3 个) 标记 `bookkeepingOnly`，只在其依赖切片完成后消除。
- **全程遵守**: 一个切片 = 一个生成器 (+ 桌面适配器) + Python 源码测试；不启动 Gradle/JVM/客户端；Root 才合并与发布。

### 2.1 建议的前几步 / Suggested first moves

1. Decide D1 (card transitions), D2 (4K typography), D4 (merge of the comment-closure branch) and D5 (dock vs sidebar) before starting the slices they gate.
2. #1 fnd-symbol-resolution-gate: Python-only closure scan, seeded with the known missing symbols.
3. #3 fnd-identity-badges-vip-pink: compile blocker; interim shim cherry-picked from e46fe930d if needed earlier.
4. #4 fnd-sheet-motion-and-glyph, #5 fnd-core-data-relocated-policies, #6 fnd-home-card-cascade-definers: independent foundations, can run in parallel.
5. In parallel, independent S-sized first steps (no prerequisites): #12 plr-subtitle-menu-hotfix, #14 rss-referer-opml-groups (Referer host gate), #25 live-data-correctness (contribution-rank apiValue), #35 vid-next-watch-and-toggle (settings switch), #13 plr-gesture-tap-small-fixes, #46 mfx-small-protocol-fixes (chat cursor serializer), #2 cmp-third-party-notices (read-only first).
6. #10 audit-unknown-entries, then #18 hist-in-place-progress and #30 acct-account-scoped-requests (both start with a read/audit step).
7. Then the high-value T1 features in table order: #17 vid-load-on-demand-closure, #11 vid-engagement-state-safety, #20 vid-info-section-repin, #21 lst-episode-selector-collection, #23 plr-overlay-and-controlbar-repin, #24 cmt-closure-merge-and-polish, #27 bgm-player-viewmodel-and-features.

### 2.2 多个切片触碰同一上游文件 / Shared upstream files (collision list)

18 个切片 (共 59) 在风险或第一步里明确提到 hash-pinned 配方/recipe 需要重新锚定。下表列出出现在两个及以上切片 `upstreamFiles` 里的上游 Kotlin 文件 (由我写的切片文件清单统计，只含上游路径，不含桌面文件，也不是代码扫描)。这些文件应当**只整文件升一次到 v0.4.6 内容**，不要各切片各改一个 hunk，否则切片之间会互相踩同一个 hash-pinned 配方。若表里靠前的切片只涉及单个 hunk (例如 `PortraitFullscreenOverlay.kt` 的关注 hunk)，整文件 re-pin 以后面那个整文件切片为准，v0.4.6 整文件已包含这些 hunk。

结合 `strategy-20261018.md` 第 3 步 (只有剩余 Windows 适用面很大时才值得投资 recipe re-pin 助手): 剩余面是 211 条、约 285 人天，数量级偏大，倾向于支持该投资，但决定权属于 Root。

| 上游文件 | 切片数 | 切片 (按路线图顺序) |
|---|---|---|
| `HomeCategoryPage.kt` | 3 | `card-geometry-lists-and-grid`, `chrome-tab-rails-and-geometry`, `perf-compose-narrowing` |
| `LiveListScreen.kt` | 3 | `live-browse-request-policy`, `space-profile-content-pager`, `live-room-ui-redesign` |
| `SettingsManager.kt` | 3 | `plr-gesture-tap-small-fixes`, `srch-state-landing-and-entry`, `vid-next-watch-and-toggle` |
| `AppEntryIconPalette.kt` | 2 | `fnd-design-system-leaf-completion`, `misc-ui-polish-and-toggles-low` |
| `AppLiquidAwareSearchField.kt` | 2 | `srch-state-landing-and-entry`, `chrome-tab-rails-and-geometry` |
| `AppTonalPillTabPolicy.kt` | 2 | `fnd-design-system-leaf-completion`, `chrome-tab-rails-and-geometry` |
| `AudioNowPlayingBar.kt` | 2 | `audio-listen-video-closure`, `chrome-dock-consumers` |
| `BangumiCardTransition.kt` | 2 | `bgm-hub-pages`, `srch-container-transitions` |
| `BangumiScreen.kt` | 2 | `bgm-hub-pages`, `srch-container-transitions` |
| `BottomControlBar.kt` | 2 | `plr-subtitle-menu-hotfix`, `plr-overlay-and-controlbar-repin` |
| `DampedDragAnimation.kt` | 2 | `fnd-design-system-leaf-completion`, `chrome-tab-rails-and-geometry` |
| `HomeHeader.kt` | 2 | `chrome-tab-rails-and-geometry`, `chrome-dock-consumers` |
| `ImagePreviewDialog.kt` | 2 | `rss-readability-public-network`, `mfx-image-viewer` |
| `LandscapeSidePanel.kt` | 2 | `bgm-player-viewmodel-and-features`, `plr-control-center-motion` |
| `ListViewModel.kt` | 2 | `hist-in-place-progress`, `acct-account-scoped-requests` |
| `ListenVideoViewModel.kt` | 2 | `audio-listen-video-closure`, `acct-account-scoped-requests` |
| `LiveRepository.kt` | 2 | `live-data-correctness`, `live-browse-request-policy` |
| `LiveSearchScreen.kt` | 2 | `live-room-ui-redesign`, `srch-container-transitions` |
| `PartitionScreen.kt` | 2 | `chrome-glass-tuning`, `chrome-tab-rails-and-geometry` |
| `PlaybackSettingsScreen.kt` | 2 | `plr-gesture-tap-small-fixes`, `vid-next-watch-and-toggle` |
| `PortraitFullscreenOverlay.kt` | 2 | `vid-engagement-state-safety`, `plr-portrait-story-pager-repin` |
| `SettingsSections.kt` | 2 | `fnd-theme-typography-accent-tokens`, `misc-ui-polish-and-toggles-low` |
| `VideoActionSection.kt` | 2 | `vid-engagement-state-safety`, `vid-info-section-repin` |
| `VideoContentSection.kt` | 2 | `vid-info-section-repin`, `lst-episode-selector-collection` |
| `VideoDetailScreenStateHolder.kt` | 2 | `vid-load-on-demand-closure`, `plr-holder-collapse-family` |

## 3. 路线图总表 / Roadmap table

| # | 切片 | 层 | 成本 | 价值 | 主条目 | 前置 | 快赢 |
|---|---|---|---|---|---|---|---|
| 1 | `fnd-symbol-resolution-gate` | T0 | M | medium | 0 | - | yes |
| 2 | `cmp-third-party-notices` | T0 | S | low | 1 | - | yes |
| 3 | `fnd-identity-badges-vip-pink` | T0 | M | medium | 2 | `fnd-symbol-resolution-gate` | yes |
| 4 | `fnd-sheet-motion-and-glyph` | T0 | M | low | 2 | `fnd-symbol-resolution-gate` |  |
| 5 | `fnd-core-data-relocated-policies` | T0 | M | low | 4 | `fnd-symbol-resolution-gate` |  |
| 6 | `fnd-home-card-cascade-definers` | T0 | S | low | 2 | `fnd-symbol-resolution-gate` |  |
| 7 | `fnd-theme-typography-accent-tokens` | T0 | M | low | 2 | `fnd-symbol-resolution-gate` |  |
| 8 | `fnd-design-system-leaf-completion` | T0 | L | medium | 1 | `fnd-symbol-resolution-gate`, `fnd-identity-badges-vip-pink`, `fnd-theme-typography-accent-tokens` |  |
| 9 | `fnd-motion-gate-and-resource-hooks` | T0 | M | medium | 6 | `fnd-symbol-resolution-gate` |  |
| 10 | `audit-unknown-entries` | T0 | S | low | 2 | `fnd-symbol-resolution-gate` | yes |
| 11 | `vid-engagement-state-safety` | T1 | M | medium | 6 | `fnd-symbol-resolution-gate` |  |
| 12 | `plr-subtitle-menu-hotfix` | T1 | S | medium | 1 | - | yes |
| 13 | `plr-gesture-tap-small-fixes` | T1 | S | medium | 3 | - | yes |
| 14 | `rss-referer-opml-groups` | T1 | M | medium | 2 | - | yes |
| 15 | `dyn-notification-accuracy` | T1 | M | medium | 1 | `fnd-symbol-resolution-gate` |  |
| 16 | `srch-state-landing-and-entry` | T1 | M | medium | 4 | `fnd-design-system-leaf-completion` |  |
| 17 | `vid-load-on-demand-closure` | T1 | M | high | 3 | `fnd-symbol-resolution-gate` |  |
| 18 | `hist-in-place-progress` | T1 | M | medium | 2 | `audit-unknown-entries` |  |
| 19 | `dmk-protocol-hardening` | T1 | M | medium | 5 | `fnd-symbol-resolution-gate` |  |
| 20 | `vid-info-section-repin` | T1 | M | medium | 7 | `fnd-design-system-leaf-completion`, `vid-engagement-state-safety` |  |
| 21 | `lst-episode-selector-collection` | T1 | L | high | 3 | `fnd-design-system-leaf-completion`, `vid-info-section-repin` |  |
| 22 | `dmk-command-merge-hot-banners` | T1 | M | medium | 2 | `fnd-design-system-leaf-completion`, `vid-engagement-state-safety` |  |
| 23 | `plr-overlay-and-controlbar-repin` | T1 | L | medium | 9 | `fnd-sheet-motion-and-glyph`, `fnd-design-system-leaf-completion` |  |
| 24 | `cmt-closure-merge-and-polish` | T1 | M | medium | 6 | `fnd-identity-badges-vip-pink`, `fnd-sheet-motion-and-glyph`, `fnd-core-data-relocated-policies` |  |
| 25 | `live-data-correctness` | T1 | M | medium | 4 | `fnd-symbol-resolution-gate` | yes |
| 26 | `live-browse-request-policy` | T1 | M | medium | 3 | `live-data-correctness` |  |
| 27 | `bgm-player-viewmodel-and-features` | T1 | L | high | 3 | `fnd-design-system-leaf-completion`, `fnd-sheet-motion-and-glyph`, `cmt-closure-merge-and-polish`, `plr-overlay-and-controlbar-repin` |  |
| 28 | `bgm-hub-pages` | T1 | L | medium | 2 | `bgm-player-viewmodel-and-features`, `fnd-design-system-leaf-completion` |  |
| 29 | `audio-listen-video-closure` | T1 | L | medium | 5 | `fnd-design-system-leaf-completion` |  |
| 30 | `acct-account-scoped-requests` | T1 | M | medium | 2 | `audit-unknown-entries` |  |
| 31 | `dyn-category-merge-and-detail-return` | T1 | L | medium | 5 | `acct-account-scoped-requests` |  |
| 32 | `space-profile-content-pager` | T1 | M | medium | 2 | `fnd-design-system-leaf-completion` |  |
| 33 | `card-geometry-lists-and-grid` | T1 | L | medium | 6 | `fnd-core-data-relocated-policies`, `fnd-design-system-leaf-completion`, `fnd-home-card-cascade-definers` |  |
| 34 | `vid-up-queue-sheet` | T1 | M | medium | 1 | `card-geometry-lists-and-grid` |  |
| 35 | `vid-next-watch-and-toggle` | T1 | M | medium | 2 | - | yes |
| 36 | `set-settings-search-items` | T1 | L | medium | 3 | `fnd-design-system-leaf-completion` |  |
| 37 | `rss-readability-public-network` | T1 | L | medium | 1 | `rss-referer-opml-groups`, `cmp-third-party-notices` |  |
| 38 | `chrome-glass-tuning` | T1 | M | medium | 1 | - |  |
| 39 | `ui-draggable-scrollbar-alias` | T2 | L | medium | 1 | `fnd-design-system-leaf-completion` |  |
| 40 | `chrome-tab-rails-and-geometry` | T2 | M | medium | 9 | `fnd-design-system-leaf-completion` |  |
| 41 | `live-room-ui-redesign` | T2 | L | medium | 4 | `live-data-correctness`, `live-browse-request-policy`, `chrome-tab-rails-and-geometry`, `fnd-theme-typography-accent-tokens` |  |
| 42 | `chrome-dock-consumers` | T2 | L | medium | 12 | `chrome-tab-rails-and-geometry`, `fnd-design-system-leaf-completion`, `chrome-glass-tuning`, `fnd-home-card-cascade-definers` |  |
| 43 | `theme-palette-presets-and-schedule` | T2 | M | medium | 3 | `fnd-design-system-leaf-completion`, `fnd-theme-typography-accent-tokens`, `cmp-third-party-notices` |  |
| 44 | `srch-container-transitions` | T2 | M | low | 3 | `bgm-hub-pages`, `srch-state-landing-and-entry` |  |
| 45 | `wallpaper-foundation-and-adjust` | T2 | L | medium | 2 | `fnd-design-system-leaf-completion` |  |
| 46 | `mfx-small-protocol-fixes` | T2 | M | low | 5 | - | yes |
| 47 | `mfx-image-viewer` | T2 | S | low | 2 | - |  |
| 48 | `home-hero-preview-polish` | T3 | M | low | 3 | `card-geometry-lists-and-grid` |  |
| 49 | `misc-ui-polish-and-toggles-low` | T3 | M | low | 12 | `fnd-theme-typography-accent-tokens`, `fnd-design-system-leaf-completion` |  |
| 50 | `msg-inbox-chat-repin` | T3 | L | low | 1 | `fnd-core-data-relocated-policies`, `space-profile-content-pager` |  |
| 51 | `plr-portrait-story-pager-repin` | T3 | L | low | 6 | `plr-overlay-and-controlbar-repin`, `fnd-design-system-leaf-completion` |  |
| 52 | `nav-card-transition-family` | T3 | XL | low | 8 | `chrome-dock-consumers` |  |
| 53 | `perf-compose-narrowing` | T3 | L | low | 3 | - |  |
| 54 | `plr-control-center-motion` | T3 | L | low | 3 | `fnd-sheet-motion-and-glyph`, `plr-overlay-and-controlbar-repin` |  |
| 55 | `plr-holder-collapse-family` | T3 | L | low | 8 | `vid-info-section-repin`, `cmt-closure-merge-and-polish` |  |
| 56 | `wallpaper-surfaces-consumers` | T3 | XL | low | 3 | `wallpaper-foundation-and-adjust`, `card-geometry-lists-and-grid` |  |
| 57 | `rss-reader-page-repin` | T3 | L | low | 5 | `wallpaper-foundation-and-adjust`, `rss-readability-public-network`, `rss-referer-opml-groups` |  |
| 58 | `dep-haze-2-0-1-decision` | T4 | L | low | 1 | - |  |
| 59 | `audio-waveform-atmosphere-deferred` | T4 | XL | low | 1 | - |  |

## 4. 切片详情 / Slice details

### T0: Foundations, compliance and audit (unblock everything else; no or little direct user value)

#### 1. `fnd-symbol-resolution-gate`: Static symbol-resolution gate (Python-only cascade detector)

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: - | 快赢 | 基础设施
- 前置: 无
- 覆盖条目: 无 (基础设施切片)
- 上游文件: desktop/tools/*.py (new check_slice_symbol_closure.py); desktop/upstream-slices/*/manifest.json; desktop/upstream-sources.json
- 风险:
  - A static import/definer scan is not a compile: typealias, extension and generated-source resolution can give false positives and negatives.
  - Must scan generator OUTPUT, not stale desktop/build/generated; needs an allowlist for Android SDK/AndroidX/third-party imports and Windows shims.
  - Compile is deferred by policy, so this gate is the only cheap way to see dependency cascades early.
- **第一步**: Write desktop/tools/check_slice_symbol_closure.py that runs the generators into a scratch directory, collects top-level declarations of every carried slice file, desktop/src file and generator output, and fails on com.android.purebilibili imports whose definer exists nowhere. Seed the expected-miss list with: userVipPink, resolveVideoDisplayProgressState, resolveWatchLaterDisplayProgressState, resolveVideoCardStatsTexts, AppPressShiftContent, HomeOverlayPillButton, resolveWallpaperGlassAlpha, DanmakuProto.parseFailed, failedSegmentCount, invalidateDanmakuSegment, AppBottomSheetMotion.reducedMotion, ForegroundSettleBudget. Add tools/tests/test_symbol_closure.py.
- 需协调: Root (owns validation tooling and the source-cohort validators)
- 备注: Covers no entry directly; every later slice should extend its expected-miss list instead of waiting for a compile.

#### 2. `cmp-third-party-notices`: Third-party license notices for carried upstream assets

- 成本 **S** (约 1 人天，启发式) | 用户价值 **low** | 发布: 0.3.4-beta.2 | 快赢
- 前置: 无
- 覆盖条目:
  - `v034b2-third-party-license-notices` (unknown/S/low): AndroidLiquidGlass (Apache-2.0) and PiliPlus (GPL-3.0) license notices
- 上游文件: design-system/src/main/assets/licenses/AndroidLiquidGlass.LICENSE; docs/third-party/piliplus-LICENSE.txt; docs/third-party/piliplus-tv-play.md; artwork/third-party/piliplus/LICENSE; app/src/main/assets/licenses/readability4j.LICENSE (later, with RSS slice); THIRD_PARTY_NOTICES.md (Nagram note, later)
- 风险:
  - Compliance, not behaviour: the Windows tree carries piliplus_custom_icons.ttf and ic_pili_tv_play.png (PiliPlus, GPL-3.0 provenance) and derived liquid-lens code (AndroidLiquidGlass, Apache-2.0).
  - I did not inspect the packaged notice manifest, so whether notices already ship through another path is UNKNOWN.
  - Not legal advice; the GPL-3.0 implication for the Windows distribution needs a human decision.
- **第一步**: Read-only first: list desktop/resources/common/licenses, THIRD_PARTY_NOTICES.md and the notice-manifest generator; diff against the license list above; then add missing license assets through the existing notice generator and assert their presence in a Python source test.
- 需协调: Root (release packaging / notice manifest owner)

#### 3. `fnd-identity-badges-vip-pink`: Identity badge palette and userVipPink definer

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.7, 0.4.0 | 快赢
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v037-shared-controls-badges-surfaces` (partial/M/low): Badges/surfaces on theme roles (AppBadge, VIP/UP/level badges)
  - `v040-identity-badge-palette-vip-pink` (partial/M/medium): Identity badge palette + userVipPink (carried sources call an undefined symbol)
- 上游文件: design-system/.../core/ui/components/UserVipBadge.kt; design-system/.../core/ui/OfficialVerifyBadgePolicy.kt; design-system/.../core/ui/UserAvatarCornerMark.kt; app/.../core/ui/components/UpBadgeNamePolicy.kt; app/.../core/ui/components/UserUpBadge.kt; design-system/.../core/ui/components/AppBadge.kt; app/.../feature/bangumi/policy/BangumiUiPolicy.kt
- 风险:
  - COMPILE BLOCKER (inferred from Root's 2026-10-18 source260 failure report): carried slice sources call userVipPink() at five sites (v040-reply-medal ReplyComponents.kt:1634,1940; v041-dynamic-comment-identity DynamicCommentSheet.kt:1349,1566; v041-video-status-labels SpaceScreen.kt:3120) and I verified by grep that no definer exists in upstream-slices, desktop/src, desktop/tools or desktop/third-party. Whether those slice files are compiled (not reference-only) is inferred from the report.
  - The files are direct/policy-extract copies at the 79e8 pin; re-pinning changes hash-pinned recipes in several generators.
  - 0.4.0 re-edits these badges after 0.3.7, so take the v0.4.6 blobs, not the 0.3.7 ones.
- **第一步**: Create slice archive v046-identity-badges with the listed files at their d6bf8b16 blobs plus generator desktop/tools/v046_identity_badges.py and test tools/tests/test_v046_identity_badges_source.py asserting userVipPink is declared exactly once and every caller resolves. If an earlier compile gate needs it, cherry-pick the DesktopUserVipPink shim from e46fe930d as an interim, then delete it when the real file lands.
- 需协调: Root (owns merging the comment-closure branch that carries the interim shim)

#### 4. `fnd-sheet-motion-and-glyph`: Bottom-sheet motion, controlled dismiss and glyph text style (design-system)

- 成本 **M** (约 3 人天，启发式) | 用户价值 **low** | 发布: 0.3.4-beta.2, 0.4.3
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v034b2-themed-sheets-pickers-toolbar` (partial/M/medium): Themed sheets + AppNumberPicker/AppDurationPicker/AppFloatingToolbar/AppWindowActionMenu
  - `v043-design-system-sheet-glyph-foundation` (missing/M/low): AppBottomSheetMotion.reducedMotion/dragSettleSpec, controlled dismiss, appIconGlyphTextStyle
- 上游文件: design-system/.../core/ui/AppSheetComponents.kt; design-system/.../core/ui/AppPrimitiveThemeDefaults.kt; design-system/.../core/ui/components/AppNumberPicker.kt; design-system/.../core/ui/components/AppDurationPicker.kt; design-system/.../core/ui/components/AppFloatingToolbar.kt; design-system/.../core/ui/components/AppWindowActionMenu.kt; design-system/src/test/.../AdaptiveBottomSheetPolicyTest.kt
- 风险:
  - Carried AppSheetComponents is the 0.3.4-beta.2 blob (slice v037-window-modal-blur); it declares AppBottomSheetMotion but lacks reducedMotion, dragSettleSpec and onDismissStarted (verified).
  - The file is selected by extract-upstream-dynamic-detail-container.py and tools/v037_window_modal_blur.py with hash-pinned recipes; a re-pin crosses the 0.3.7/0.3.8/0.4.3/0.4.5 edits and must keep the Windows modal-blur adaptation.
  - The comment-closure branch papers over the gap with a generated compat extension (reducedMotion = false); this slice replaces that shim.
- **第一步**: Diff the carried v037-window-modal-blur/AppSheetComponents.kt (b2091b1d) against the d6bf8b16 blob, extend v037_window_modal_blur.py to emit the v0.4.6 blob with the same blur-behind adaptation, add appIconGlyphTextStyle to AppPrimitiveThemeDefaults, and assert in a test that reducedMotion is declared once and no compat extension is emitted.
- 需协调: Root (comment-closure branch merge ordering)

#### 5. `fnd-core-data-relocated-policies`: Relocated core-data/design-tokens policy files (cascade traps)

- 成本 **M** (约 3 人天，启发式) | 用户价值 **low** | 发布: 0.2.6, 0.2.9, 0.3.1
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v026-shared-progress-policy-core` (partial/M/low): Progress display + watch-later policies moved to core-data (VideoProgressDisplayPolicy, WatchLaterProgressPolicy)
  - `v029-core-data-shared-repos` (partial/S/low): Shared repos moved to core-data (SubtitleContentRepository, DynamicFeedPageDataSource, ...)
  - `v029-feed-typography-stats-refactor` (missing/S/low): FeedContentTextRoles + resolveVideoCardStatsTexts (cascade trap)
  - `v031-comment-stack-core-data-move` (partial/S/low): Comment stack moved app -> core-data (path remap, BuvidManager, onCommentPosted hook)
- 上游文件: core-data/.../data/model/VideoProgressDisplayPolicy.kt; core-data/.../data/model/WatchLaterProgressPolicy.kt; core-data/.../data/model/VideoCardStatsTextPolicy.kt; design-tokens/.../core/ui/FeedContentTextRoles.kt; design-system/.../core/ui/FeedContentTokens.kt; core-data/.../data/repository/SubtitleContentRepository.kt; core-data/.../data/repository/DynamicFeedPageDataSource.kt; core-data/.../data/repository/CommentPaginationPolicy.kt; core-data/.../core/network/BuvidManager.kt
- 风险:
  - Carried HistoryPersonalCard, FavoritePersonalCard, WatchLaterScreen and the archived VideoCard import symbols that no tree defines (resolveVideoDisplayProgressState, resolveWatchLaterDisplayProgressState, resolveVideoCardStatsTexts).
  - AppTypographyTokens moved from FeedContentTokens to FeedContentTextRoles: keeping the v025 FeedContentTokens next to the new file redeclares it.
  - Several generators still pin app/ paths for the comment stack while v036_comment_route.py pins core-data CommentRepository at v0.3.7: path remapping must be done once.
- **第一步**: Grep the generator output for the three unresolved symbols to fix the exact missing set, then carry the four policy files at v0.4.6 blobs into slice v046-core-data-policies with a generator and a symbol-closure test entry.

#### 6. `fnd-home-card-cascade-definers`: Home/card cascade definers (pill button, press shift, wallpaper glass alpha)

- 成本 **S** (约 1 人天，启发式) | 用户价值 **low** | 发布: 0.2.6
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v026-home-card-wallpaper-glass-alpha` (missing/S/low): resolveWallpaperGlassAlpha definer (VideoCardAdaptiveTintPolicy) + glass alpha 0.44/0.36
  - `v026-home-overlay-pill-press-shift` (unknown/M/low): HomeOverlayPillButton, AppPressShiftContent, old-content divider alpha (definers not carried)
- 上游文件: app/.../feature/home/components/HomeOverlayPillButton.kt; design-system/.../core/ui/components/AppPressShiftContent.kt; app/.../feature/home/HomeRefreshUiPolicy.kt; app/.../feature/home/components/cards/VideoCardAdaptiveTintPolicy.kt; app/.../core/ui/components/AnimatedCountText.kt
- 风险:
  - Carried HomeCategoryPage, HomeScreen, DynamicScreen and TopBar slice sources already call these definers; they are pinned only at 79e8 or not carried (extract-upstream-home-full-card.py pins the 79e8 VideoCardAdaptiveTintPolicy).
  - Whether the pill/press-shift polish reaches generated Windows output is UNKNOWN (entry status unknown); definers are certainly missing.
- **第一步**: Carry HomeOverlayPillButton, AppPressShiftContent, HomeRefreshUiPolicy (resolveOldContentDividerContainerColor) and the 0.2.6 VideoCardAdaptiveTintPolicy at v0.4.6 blobs, re-anchor the extract-upstream-home-full-card.py pin, and add the symbols to the closure gate.
- 需协调: Large-screen UI task owns home/theme/card scope: confirm before editing

#### 7. `fnd-theme-typography-accent-tokens`: Theme tokens: Material baseline typography, accent pairs, Color.kt residue

- 成本 **M** (约 3 人天，启发式) | 用户价值 **low** | 发布: 0.3.7, 0.3.9
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v037-theme-typography-contrast` (partial/M/low): Material baseline typography, Miuix title scale, accessible contrast, accent pairs
  - `v039-theme-picker-control-colors` (partial/S/medium): Colour picker residue: design-tokens Color.kt, AdaptivePreferenceComponents, SettingsSections
- 另外涉及 (主切片在别处): `v037-surface-dark-theme-consistency`
- 上游文件: design-system/.../core/theme/Type.kt; design-tokens/.../core/theme/AppTextStyles.kt; design-system/.../core/theme/AdaptiveAccentColorPolicy.kt; design-system/.../core/theme/AndroidNativeVariantThemePolicy.kt; design-system/.../core/theme/AppDisplayPolicy.kt; design-tokens/.../core/theme/Color.kt; design-system/.../core/ui/components/AdaptivePreferenceComponents.kt (platform-adapter-reference); app/.../feature/settings/ui/SettingsSections.kt
- 风险:
  - GLOBAL VISIBLE CHANGE: 0.3.7 reverts body/label sizes to the Material baseline (bodySmall 13 to 12sp, labelSmall 12 to 11sp). On a 32-inch 4K window this makes text smaller; needs a Windows density-scale decision.
  - Carried Theme.kt v0.4.3 already calls resolveMaterialTypography(...).toMiuixTextStyles() (semantic version skew; no compile break found).
  - Color.kt removes ActionLikeDark/CoinDark/FavoriteDark/ShareDark/CommentDark: grep consumers first. AdaptivePreferenceComponents is a platform-adapter-reference, so Windows ships its own AppSearchEntry.
- **第一步**: Carry the five 0.3.7 theme files at v0.4.6 blobs as one slice, then add a Python test that Theme.kt's call chain (resolveMaterialTypography, toMiuixTextStyles) resolves; record the 4K typography decision before enabling.
- 需协调: Large-screen UI task (typography/density on 4K)

#### 8. `fnd-design-system-leaf-completion`: Design-system leaf files not yet carried (semantic colours, popup surface, preference, tab policy)

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.3.8
- 前置: `fnd-symbol-resolution-gate`, `fnd-identity-badges-vip-pink`, `fnd-theme-typography-accent-tokens`
- 覆盖条目:
  - `v038-semantic-content-colors` (partial/XL/medium): Semantic content colour layer (foundation files only; ~148 consumers re-pinned per feature)
- 另外涉及 (主切片在别处): `v038-chrome-geometry-exact-touch-bounds`, `v038-settings-entry-colorful-icons`, `v039-sidebar-icon-colors`
- 上游文件: design-system/.../core/ui/MediaContrastPalette.kt; design-system/.../core/ui/components/AppPopupSurface.kt; design-system/.../core/ui/AppSemanticVisualPolicy.kt; design-system/.../core/ui/components/AppPreferenceComponents.kt; design-system/.../core/ui/components/AppSelectionPreferenceComponents.kt (optionLeading); design-system/.../core/ui/AppEntryIconPalette.kt; design-system/.../core/ui/components/AppCard.kt; design-system/.../core/ui/components/AppDesktopInteraction.kt; design-system/.../core/ui/AppChromeSizeTokens.kt (v0.4.6 e4e989c9); design-system/.../core/ui/renderer/material3/AppTonalPillTabPolicy.kt; design-system/.../core/ui/animation/DampedDragAnimation.kt
- 风险:
  - CONSUMER RULE: about 148 consumed files import the 0.3.8 semantic colour layer. Do NOT re-pin them to 0.3.8; re-pin each straight to v0.4.6 content when its owning feature slice runs.
  - AdaptivePreferenceComponents and AdaptiveDialogComponents are platform-adapter-references, so some leaf files need Windows adapters rather than verbatim copies.
  - Already carried at v0.4.6 blobs (do not redo): AppContentColors, ButtonSlotColor, AppActionColors, AppSurfaceTokens, AppChromeOverflow (AppExactTouchTargets), AppTextSearchHighlight.
- **第一步**: Build the 'consumed file to design-system symbol' table with a read-only git script at d6bf8b16, then carry the leaf files above in one slice archive v046-design-system-leaves with a closure-gate test over the carried slice sources.
- 需协调: Large-screen UI task (shared chrome tokens)
- 备注: Primary owner of the XL v038-semantic-content-colors entry for its foundation half; the ~148 consumer edits are distributed to feature slices by the consumer rule.

#### 9. `fnd-motion-gate-and-resource-hooks`: Motion provider, decorative animation gate and window background hooks

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.2, 0.3.5, 0.3.8, 0.4.2
- 前置: `fnd-symbol-resolution-gate` | 软前置: `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v032-glass-cache-motion-gating` (partial/S/low): LocalComponentMotionEnabled provider (no Windows provider), ProgressiveFade cache
  - `v035-decorative-animation-gate` (missing/S/medium): DecorativeAnimationGate: pause decorative loops when offscreen/unfocused
  - `v038-background-trim-policy` (missing/S/low): Background cache budgets 24/8 -> 64/32 MiB (memory trade-off vs user request)
  - `v038-foreground-settle-budget` (missing/S/low): ForegroundSettleBudget (window restore settle window)
  - `v038-cover-color-parallelism` (missing/S/low): Cover colour extraction: 2-way semaphore instead of single mutex
  - `v042-theme-color-transition` (partial/S/low): 240 ms theme colour transition (ThemeColorTransition carried, unused)
- 上游文件: design-system/.../core/ui/AppThemeConfig.kt (LocalComponentMotionEnabled provider; blob carried); app/.../core/ui/DecorativeAnimationGate.kt; app/.../core/lifecycle/ForegroundSettleBudget.kt; app/.../core/lifecycle/BackgroundMemoryTrimPolicy.kt (0.3.8 cd37b804); app/.../feature/home/components/cards/VideoCardCoverColorStore.kt (desktop-owned DesktopVideoCardPalette.kt); app/.../core/theme/ThemeColorTransition.kt (carried, unused)
- 风险:
  - MEMORY TENSION: 0.3.8 raises the background image-cache budget from 24/8 MiB to 64/32 MiB and stops clearing wallpaper palettes, against the user's lower-memory request. Root must decide; the gate and settle-budget parts do not have this cost.
  - DecorativeAnimationGate needs the Compose Desktop window-focus signal; consumers (music background, dock skin) are desktop-owned owners needing small adapters.
  - No Windows provider exists for LocalComponentMotionEnabled (default true); wiring it must honour the existing DesktopSystemReduceMotion (SPI_GETCLIENTAREAANIMATION).
  - ForegroundSettleBudget is already called by reference copies (v043 HomeScreen:3139, v044 AppNavigation:672) but defined nowhere; AppScope.mainScope has no desktop equivalent.
- **第一步**: Add a desktop-owned DesktopDecorativeAnimationGate (copy of the 50-line original keyed on window focus), wire it into the Windows music background and dock-skin owners, and pin its source hash in a Python test.
- 需协调: Root (memory budget decision); Large-screen UI task (dock skin owner)

#### 10. `audit-unknown-entries`: Resolve the 'unknown' entries by reading generated output

- 成本 **S** (约 1 人天，启发式) | 用户价值 **low** | 发布: 0.2.7, 0.3.4-beta.1 | 快赢
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v027-dissolve-delete-layout-fixes` (unknown/S/low): Dissolve-delete lookahead fix in lists (AnimatedVideoListItem useLookaheadBounds)
  - `v034-upstream-tests-added` (unknown/S/low): Upstream JVM policy tests added with the changes (per-test carry status not enumerated)
- 另外涉及 (主切片在别处): `v026-home-overlay-pill-press-shift`, `v026-search-entry-centered-transition`, `v026-dynamic-top-tabs-scroll`, `v027-home-feed-row-alignment`, `v034b2-third-party-license-notices`, `v034b2-card-return-source-session`, `v040-history-in-place-progress`
- 上游文件: desktop/build/generated/** (regenerate with the Python generators into a scratch dir; do not trust stale output); desktop/tools/tests/*
- 风险:
  - desktop/build/generated may be older than current generator inputs (noted by the 0.4.2 reviewer).
  - No Gradle or JVM may be run; only Python generators.
- **第一步**: Run the relevant Python generators into a scratch directory and read the output for each unknown entry (home overlay pill, centred search entry, dynamic tab scroll, dissolve-delete lookahead, home row alignment, card-return source session, history in-place progress, license notices, upstream tests); write audit-unknown-20261018.json and re-classify each as done, partial or missing.
- 备注: Owns two entries as primary (v027-dissolve-delete-layout-fixes, v034-upstream-tests-added) and is a secondary audit step for seven more.

### T1: High/medium-value user-visible features and correctness fixes

#### 11. `vid-engagement-state-safety`: Like/follow/favourite state safety: NonCancellable like, retry UI, unknown-state consumers, unfollow confirmation

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.0, 0.3.6, 0.3.8, 0.4.2, 0.4.6
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v030-like-toggle-survives-detail-exit` (missing/S/medium): Like request survives leaving the detail page (NonCancellable)
  - `v036-unknown-state-phone-audio-story-leftovers` (missing/M/medium): Unknown like/follow state: phone detail, audio, story, input bar consumers
  - `v036-merge-pr905-integration` (partial/S/low bookkeeping): PR #905 merge (bookkeeping; status = union of dependents)
  - `v038-interaction-status-retry` (partial/M/medium): Like/follow/favourite status timeout + bounded retry (UI half and Bangumi hunks)
  - `v042-follow-pending-recovery` (partial/S/medium): Follow pending retry (PortraitFullscreenOverlay hunk missing)
  - `v046-unfollow-confirmation` (missing/M/medium): Unfollow confirmation dialog (FollowActionConfirmation) at six call sites
- 上游文件: app/.../feature/video/viewmodel/VideoEngagementViewModel.kt; app/.../core/ui/components/FollowActionConfirmation.kt (new); app/.../feature/video/screen/VideoDetailPhoneContent.kt; app/.../feature/video/ui/overlay/PortraitFullscreenOverlay.kt (single follow hunk); app/.../feature/video/ui/section/VideoActionSection.kt (single hunks); app/.../feature/video/ui/components/UpPreviewSheet.kt
- 风险:
  - Windows owner-retire rules reject post-retire writes, so the NonCancellable block must not publish into a retired owner.
  - Six hash-pinned call sites live in different generators (v042-owner-follow, v037-player-closure, v037-player-interaction-seed, extract-upstream-video-fullscreen-pager.py); desktop-owned community unfollow buttons (CommunitySearchScreens.kt:356) need the same state holder.
  - Phone-width detail layout builds VideoContentEngagementState without the known flags (inferred, unverified): like/follow may stay unconfirmed in narrow windows.
  - Real-account runtime behaviour is unverified for the whole follow-confirmation line (Root's 737-case source qualification, actualRuntimeVerified=false).
  - The Bangumi retry hunks of v038-interaction-status-retry (BangumiPlayerViewModel, BangumiPlayerOverlayHost) are delivered by bgm-player-viewmodel-and-features together with the whole-file re-pin, not here.
- **第一步**: S first: carry FollowActionConfirmation.kt and wire it into the desktop-owned unfollow button (CommunitySearchScreens.kt:356) and UpPreviewSheet; then add the NonCancellable like block with an owner-retire guard; then extend v037_player_known_presentation for the phone-content flags.
- 需协调: Root (owner-retire and account-switch rules)

#### 12. `plr-subtitle-menu-hotfix`: Subtitle menu scroll hunk (interim single-hunk fix)

- 成本 **S** (约 1 人天，启发式) | 用户价值 **medium** | 发布: 0.4.2 | 快赢
- 前置: 无
- 覆盖条目:
  - `v042-subtitle-menu-scroll` (partial/S/medium): Subtitle menu scrolls (verticalScroll hunk missing in BottomControlBar)
- 上游文件: app/.../feature/video/ui/overlay/BottomControlBar.kt (one verticalScroll modifier); app/.../feature/video/ui/overlay/PortraitSubtitleOverlay.kt (carried at v0.4.6)
- 风险:
  - tools/v042_player_readability.py:75 only handles TopControlBar and PortraitSubtitleOverlay paths; the BottomControlBar hunk is not applied (last generated output has the menu Column without verticalScroll).
  - Superseded by plr-overlay-and-controlbar-repin (the v0.4.6 blob contains the same line); keep this as a quick interim.
- **第一步**: Extend the path set in desktop/tools/v042_player_readability.py to BottomControlBar with the one-line verticalScroll hunk and add a source test asserting the generated subtitle menu Column scrolls.

#### 13. `plr-gesture-tap-small-fixes`: Player tap/gesture small fixes

- 成本 **S** (约 1 人天，启发式) | 用户价值 **medium** | 发布: 0.3.0, 0.3.4, 0.4.0 | 快赢
- 前置: 无
- 覆盖条目:
  - `v030-swipe-seek-unlimited-default` (missing/S/low): Swipe-seek default 'unlimited'
  - `v034-gesture-level-overlay` (partial/S/low): Gesture level overlay horizontal layout (0.3.8 revision missing)
  - `v040-player-tap-long-press-double-tap` (partial/S/medium): Long-press release not a double-tap (FullscreenPlayerOverlay detector swap missing)
- 上游文件: app/.../feature/video/ui/overlay/FullscreenPlayerOverlay.kt (detectPlayerTapGestures swap); app/.../feature/video/ui/gesture/GestureLevelOverlay.kt; app/.../core/store/SettingsManager.kt (swipe-seek defaults); app/.../feature/settings/screen/PlaybackSettingsScreen.kt
- 风险:
  - Whether the Windows mouse path routes through detectPlayerTapGestures at runtime is UNVERIFIED (inferred only from the carried section producer).
  - Swipe-seek 'unlimited' is a touch option; only worth exposing if mouse-drag seeking exists.
- **第一步**: Apply the one-line detectPlayerTapGestures swap in tools/extract-upstream-video-fullscreen-pager.py and add a test; then decide on the swipe-seek default.

#### 14. `rss-referer-opml-groups`: RSS external-image Referer gating and OPML group editing/export

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.6, 0.4.1 | 快赢
- 前置: 无
- 覆盖条目:
  - `v026-rss-opml-groups-async-titles` (partial/M/medium): RSS OPML groups: group-edit dialog + save-file export; background title fill progress
  - `v041-rss-image-referer-gating` (partial/S/medium): RSS external images must not carry Bilibili Referer/Origin (DesktopRepository.kt:96-99 has no host gate)
- 上游文件: core-data/.../core/network/BilibiliRequestHeaderPolicy.kt; desktop/src/.../data/DesktopRepository.kt (lines 96-99); desktop/src/.../ui/DesktopApplicationImageLoader.kt:46; app/.../core/plugin/feed/SubscriptionFeedStore.kt (setGroup); desktop/src/.../plugins/DesktopSubscriptionRepository.kt; desktop/src/.../ui/PluginProviderScreens.kt
- 风险:
  - Verified: DesktopRepository.kt:96-99 applies Referer/Origin https://www.bilibili.com to every host, and the image loader routes all images through it, so external RSS images carry the Bilibili Referer (inference: hotlink CDNs such as sspai may 403).
  - Whether the RSS article fetch uses the same client (DesktopSubscriptionRepository) is UNVERIFIED.
  - OPML export today is clipboard only; a save-file chooser reuses existing file-chooser leases.
- **第一步**: S: add a usesDefaultBilibiliHeaders(host) allow-list (bilibili.com, bilibili.tv, biliapi.net/.com, hdslb.com, bilivideo.com/.cn) gating the Referer/Origin branch in DesktopRepository's interceptor plus a unit test; then the group-name dialog calling SubscriptionFeedStore.setGroup and a save-file OPML export.

#### 15. `dyn-notification-accuracy`: Dynamic tray-notification accuracy (portal/uplist red-dot snapshot)

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.4.2
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v042-dynamic-notification-accuracy` (missing/M/medium): Dynamic notification accuracy: portal/uplist red-dot snapshot, no read_offset watermark
- 上游文件: core-data/.../core/network/DynamicAuthorUpdatesApi.kt (new); app/.../feature/message/notification/MessageNotificationChecker.kt; app/.../feature/message/notification/MessageNotificationPolicy.kt; app/.../feature/message/notification/MessageNotificationStateStore.kt; core-data/.../core/network/grpc/DynamicPersonalFeedApi.kt; core-data/.../core/network/ApiClient.kt
- 风险:
  - Needs an App access token for the DynAllPersonal preload: availability on Windows is UNKNOWN (check environment.accessToken()).
  - Windows tray balloons cannot be withdrawn once posted (DesktopWindowsMessageNotifications.kt); 'clear on read' degrades to removing tray-menu entries.
  - The v038 slice pins b69ce4c2 (0.3.8); the jump to v0.4.6 also crosses the 0.3.9 edits. Upstream admits the per-UP request volume and the web red-dot effect were unverified.
- **第一步**: Verify accessToken availability in DesktopMessageNotificationRootConsumer.kt:59, then create tools/v046_message_notifications.py re-pinning slice v038-message-notifications to the v0.4.6 blobs and carry the 666-line upstream test.
- 需协调: Root (account/session token ownership)

#### 16. `srch-state-landing-and-entry`: Search: keep results, no-history switch, landing readability, centred entry

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.6, 0.3.5, 0.4.0, 0.4.6
- 前置: `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v026-search-entry-centered-transition` (unknown/S/low): AppSearchEntry centeredContent layout (final static form only)
  - `v035-search-layout-typography` (partial/S/low): SearchLandingUi hierarchy/spacing (still v0.2.5 canonical)
  - `v040-search-landing-readability` (partial/S/low): Search landing larger text (SearchLandingUi still v0.2.5)
  - `v046-search-keep-results-silent-mode` (missing/M/medium): Search keeps results across edit/back; independent 'do not record search history' switch
- 上游文件: app/.../feature/search/SearchViewModel.kt (platform-rewrite via v041_search_all.py); app/.../feature/search/SearchContentStatePolicy.kt; app/.../feature/search/SearchScreen.kt; app/.../feature/search/SearchLandingUi.kt; app/.../feature/search/SearchMotionBudgetPolicy.kt; app/.../core/ui/components/AppLiquidAwareSearchField.kt; app/.../core/store/SettingsManager.kt (search_history_recording_enabled and two Appearance share keys)
- 风险:
  - platform-rewrite recipe for SearchViewModel is hash-pinned; submittedQuery must be separated from the draft.
  - The silent-mode flag must respect the Windows global privacy setting; DesktopSettingsShareWindowsCatalog must accept related_video_transition_enabled and video_return_content_follow_progress_enabled for Android-backup import.
  - Needs AppChromeSizeTokens.LiquidSearchFieldHeightDp (design-system leaf slice).
- **第一步**: Define KEY_SEARCH_HISTORY_RECORDING_ENABLED in the Windows settings store and share catalog, then re-pin SearchViewModel (submittedQuery vs draft) through tools/v041_search_all.py and add tests.

#### 17. `vid-load-on-demand-closure`: Video detail loading: related-list gating, on-demand verification, fullscreen target identity

- 成本 **M** (约 3 人天，启发式) | 用户价值 **high** | 发布: 0.3.4-beta.2, 0.3.5
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v034b2-related-deferred-load` (partial/S/high): Related list loads after playback (8 s timeout, one retry)
  - `v035-video-detail-on-demand-loading` (partial/M/medium): On-demand detail loading; related-list gate bound to bandwidth=null on Windows
  - `v035-fullscreen-nav-target-identity` (partial/M/medium): Fullscreen intent/target cover/cid survive next-video switch
- 上游文件: app/.../feature/video/viewmodel/VideoPlaybackViewModel.kt; app/.../feature/video/viewmodel/RelatedVideoLoadScheduler.kt; app/.../feature/video/viewmodel/VideoLoadTimeline.kt; app/.../feature/video/screen/VideoNavigationCidPolicy.kt (pin blob 7d44b3fb); app/.../feature/video/screen/VideoDetailScreenStateHolder.kt; desktop/src/.../ui/DesktopOriginalVideoOwnerNetworkBinding.kt:26-27; desktop/src/.../ui/DesktopOriginalVideoMetadataAdmission.kt:68-71
- 风险:
  - Verified: the related-list bandwidth input is bound to downstreamBandwidthKbps = null and the admission comment treats null as constrained, so (inference) the related list may always wait for playback READY, bounded at 6 s; upstream keeps the parallel path when no estimate exists.
  - VideoDetailScreenStateHolder is carried only as older pins (holder generator extract-upstream-video-detail-holder.py); the loader moved again by v0.4.6.
  - VideoNetworkPressureObserver is Android ConnectivityManager code and has no Windows counterpart.
- **第一步**: Decide the unknown-bandwidth semantics for a wired desktop (treat as unconstrained), change DesktopOriginalVideoOwnerNetworkBinding accordingly, and pin the decision with a Python test; then re-pin VideoNavigationCidPolicy for fullscreen intent.
- 需协调: Root (video owner binding)

#### 18. `hist-in-place-progress`: History keeps order and updates progress in place

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.9, 0.4.0
- 前置: `audit-unknown-entries`
- 覆盖条目:
  - `v039-history-in-place-progress` (missing/M/medium): History keeps order, updates progress in place after watching
  - `v040-history-in-place-progress` (unknown/S/low): History in-place progress (duplicate of v039 entry; generated output unread)
- 上游文件: app/.../feature/list/ListViewModel.kt (v0.4.6 blob already vendored in v046-favorite-editor); app/.../core/refresh/HistoryRefreshBus.kt; desktop/src/.../ui/DesktopOriginalPersonalListHost.kt:55-58; desktop/src/.../ui/DesktopHistoryRefresh.kt:12
- 风险:
  - Verified: the host calls HistoryViewModel.loadData on every isCurrentPage and DesktopHistoryRefresh invalidates the cloud-history cache on every HistoryRefreshBus change (the pre-0.3.9 reload-on-activation behaviour).
  - Windows needs its own browsing-session key (tab visit); AppNavigation's historyBrowsingSessionKey is not mounted.
  - Generated ListViewModel output not read, so the v040 'unknown' status is unresolved until the audit runs.
- **第一步**: Read the generated Windows ListViewModel (settles the unknown), bind the HistoryViewModel declarations from the vendored v046 ListViewModel, and swap loadData for enterBrowsingSession(sessionKey) in the personal-list host with the in-place progress update replacing invalidateCloudHistory.

#### 19. `dmk-protocol-hardening`: Danmaku protocol hardening, density fallback, like-state calibration, cadence

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.6, 0.2.8, 0.2.9, 0.3.9, 0.4.6
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v026-danmaku-density-fallback-curve` (partial/M/medium): Heat-curve fallback from local danmaku density when the pbp endpoint is empty/404
  - `v028-danmaku-segment-hardening` (partial/M/medium): Danmaku protobuf strict parse, failed-segment mark + retry/invalidate, empty segment = success
  - `v029-pr870-merge-commits` (partial/S/medium bookkeeping): PR #870 merges (bookkeeping); residue = DanmakuParser/AdvancedDanmakuData v025
  - `v039-danmaku-60fps-cadence` (partial/S/low): Danmaku overlay 60 fps absolute-deadline cadence (cap risk on high-refresh)
  - `v046-danmaku-tap-like-state` (partial/M/low): Danmaku tap like: 65006/65004 state calibration, vote count (request params carried)
- 上游文件: danmaku-engine/.../parser/DanmakuProto.kt (parseFailed); danmaku-engine/.../parser/DanmakuParser.kt; danmaku-engine/.../parser/AdvancedDanmakuData.kt (failedSegmentCount); core-data/.../data/repository/DanmakuContentRepository.kt (invalidateDanmakuSegment); app/.../data/repository/VideoRepository.kt (getDanmakuDensityProgressData); app/.../data/repository/DanmakuRepository.kt (resolveDanmakuLikeResult); danmaku-engine/.../RenderFrameCadence.kt; desktop/src/.../danmaku/DanmakuWindowLoader.kt
- 风险:
  - CASCADE: carried DanmakuManager (v029-bas-filter, v030-up-danmaku:2102) already references parseFailed, failedSegmentCount and invalidateDanmakuSegment, which no tree defines.
  - Cadence is fixed at 1e9/60 ns: on a high-refresh 4K monitor this caps the overlay (inference; needs a refresh-rate check).
  - bvc pbp endpoint reportedly 404s (upstream commit message), so the heat curve may be empty on Windows today; unverified.
  - Windows loader is desktop-owned; the Android DanmakuManager hunks do not transfer.
- **第一步**: Carry the definers (DanmakuProto.parseFailed, ParsedDanmaku.failedSegmentCount, DanmakuContentRepository.invalidateDanmakuSegment) as v0.4.6 blobs, have DanmakuWindowLoader call them, and test with a corrupt-bytes fixture.

#### 20. `vid-info-section-repin`: Detail info/action/content sections re-pinned to v0.4.6

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.4-beta.2, 0.3.8, 0.3.9, 0.4.0 (+0.4.1), 0.4.2, 0.4.3
- 前置: `fnd-design-system-leaf-completion`, `vid-engagement-state-safety`
- 覆盖条目:
  - `v034b2-detail-pane-skeletons` (partial/S/low): Detail pane skeletons (SkeletonComponents v0.4.6 delta)
  - `v038-video-detail-first-frame-stability` (missing/M/medium): Detail first-frame stability: early status requests, full like/follow buttons, intent-first expand
  - `v039-video-title-selection-comment-scroll` (missing/M/medium): Drag-select video title, separate expand arrow, stable comment refresh container
  - `v040-video-detail-info-scroll-chrome` (missing/M/medium): Detail info/scroll chrome: title tap-expand, continuous tab background, fixed comment refresh container
  - `v042-video-owner-stats` (missing/S/low): Owner stats row (resolveVideoDetailOwnerStatsLabels)
  - `v042-description-topic-tags` (missing/S/low): Topic-tag regex fix in VideoInfoSection
  - `v043-video-count-motion-triple-press` (missing/M/low): Action count motion + triple-press state machine
- 上游文件: app/.../feature/video/ui/section/VideoInfoSection.kt; app/.../feature/video/ui/section/VideoActionSection.kt; app/.../feature/video/ui/section/VideoInfoDisplayPolicy.kt; app/.../feature/video/playback/policy/PlaybackPostLoadPlan.kt (direct; 450 ms delays); app/.../feature/video/screen/VideoContentSection.kt; app/.../feature/video/screen/VideoCommentRefreshContainer.kt; app/.../feature/video/ui/feedback/VideoActionCountText.kt; app/.../feature/video/ui/feedback/VideoTriplePressState.kt; app/.../feature/video/ui/components/SkeletonComponents.kt
- 风险:
  - VideoInfoSection must keep Windows follow/like adapters (tools/v037_player_interaction.py); hash-pinned recipes also in v036_video_comment_viewport.py and extract-upstream-video-content-full.py.
  - PlaybackPostLoadPlan 0 ms delays only help if the card transition runs on Windows (decision pending).
  - Do one whole-file re-pin; per-hunk edits conflict with later releases touching the same files.
- **第一步**: Pin VideoInfoSection and VideoInfoDisplayPolicy at v0.4.6 and re-run the v037_player_interaction.py selection; keep the topic regex fix, owner stats row and selectable title.

#### 21. `lst-episode-selector-collection`: Episode/collection selector rework

- 成本 **L** (约 7 人天，启发式) | 用户价值 **high** | 发布: 0.3.5, 0.3.8, 0.3.9
- 前置: `fnd-design-system-leaf-completion`, `vid-info-section-repin`
- 覆盖条目:
  - `v035-collection-intro-expand-fix` (missing/S/low): Collection sheet intro expand fix (CollectionSheet)
  - `v038-video-episode-selector` (missing/L/high): Episode selector rework: cover-card row, list with covers, season chips, auto-locate
  - `v039-portrait-comment-viewport-pages` (missing/M/low): PagesSelector pages-only final form (reverts 0.3.8 collection episodes)
- 上游文件: app/.../feature/video/ui/components/PagesSelector.kt (v0.4.6 d1ce2370; carried 14282b60); app/.../feature/video/ui/components/PageCoverList.kt (new); app/.../feature/video/ui/components/EpisodeListNavigationActions.kt (new); app/.../feature/video/ui/components/CollectionSheet.kt (1901854f); app/.../feature/video/screen/VideoContentSection.kt; core-data/.../data/model/response/VideoDetailResponse.kt (Page.firstFrame)
- 风险:
  - Three generators are involved: extract-upstream-video-player-full-controls.py (PagesSelector), extract-stable-collection-sheet.py (CollectionSheet) and the VideoContentSection recipes.
  - 0.3.9 (b491ed2) removes the collection-in-PagesSelector feature and v0.4.6 keeps that revert: go straight to the v0.4.6 form, never the 0.3.8 form.
  - Two of the six 0.3.8 commits (74e26bd7, dfbe81f0) polish rows added by the first commit: land together.
- **第一步**: Carry Page.firstFrame in VideoDetailResponse plus PageCoverList and EpisodeListNavigationActions at v0.4.6 blobs; then extend extract-stable-collection-sheet.py to the 1901854f blob (introCanExpand).
- 备注: Most visible 0.3.8 change for a 4K desktop user who watches multi-part videos or series.

#### 22. `dmk-command-merge-hot-banners`: Command cards on real state, 15 s merge window, count banners

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.8
- 前置: `fnd-design-system-leaf-completion`, `vid-engagement-state-safety`
- 覆盖条目:
  - `v038-hot-danmaku-count-banners` (partial/M/low): Count banners: single switch, measured fit, mutual exclusion with normal danmaku
  - `v038-danmaku-merge-command-cards` (partial/M/medium): 15 s fixed merge window; command cards bound to real like/coin/fav/follow state
- 上游文件: app/.../feature/video/ui/overlay/CommandDanmakuOverlay.kt (v0.4.6 ea56abd3; carried 73c3faa4); app/.../feature/video/ui/overlay/HotDanmakuBar.kt; app/.../feature/video/danmaku/HotDanmakuPolicy.kt; app/.../feature/video/danmaku/DanmakuMerger.kt (carried); desktop/src/.../ui/DesktopHotDanmakuPlatform.kt:30; desktop/src/.../ui/DesktopOriginalDanmakuSettingsHost.kt:23-25; desktop/src/.../ui/DesktopCommandPopupWindow.kt
- 风险:
  - Removing the expanded-mode key must also remove DesktopHotDanmakuBindings' expandedMode flow and keep Android-backup import mapping consistent.
  - Mutual exclusion with normal danmaku must be re-implemented in danmaku/DanmakuOverlay.kt or DanmakuScheduler.kt: the ByteDance engine hunks do not transfer (no com.bytedance import in desktop/src).
  - Real-account vote/rating submission and mouse hit-testing were never verified.
- **第一步**: Re-pin CommandDanmakuOverlay.kt to ea56abd3 via tools/v037_command_overlay.py (new v046 generator), bind the real like/coin/fav/follow state, and test the duplicate-submit guard.

#### 23. `plr-overlay-and-controlbar-repin`: Player overlay and control bar re-pin (BottomControlBar, composer, progress layout)

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.2.9, 0.3.0, 0.3.4-beta.1, 0.3.8, 0.3.9, 0.4.0 (+0.4.1), 0.4.2, 0.4.3
- 前置: `fnd-sheet-motion-and-glyph`, `fnd-design-system-leaf-completion` | 取代: `plr-subtitle-menu-hotfix`
- 覆盖条目:
  - `v029-player-chrome-tighten-vote-card` (partial/S/medium): VideoProgressBarLayoutPolicy compaction (still v025 direct) + vote card
  - `v030-pr870-video-page-merge` (partial/S/high bookkeeping): PR #870 aggregate (bookkeeping): chrome compaction, pull-refresh gating, pause-hide
  - `v034b1-playback-progress-isolation` (partial/S/medium): Playback progress isolated from overlay recomposition (provider reads)
  - `v038-player-entry-cover-spinner` (partial/S/medium): Entry cover/spinner: buffering indicator gated on first frame
  - `v038-danmaku-switch-send-icon` (missing/M/low): Danmaku switch 40dp width + custom send icon
  - `v039-player-glass-dock-controls` (missing/M/low): Player glass dock/time area, fixed dark media surface
  - `v040-landscape-player-controls-danmaku-composer` (partial/L/medium): Landscape controls rework, PlainLandscapeDanmakuComposer (new 582 lines)
  - `v042-player-selection-accent` (partial/M/low): Selection accent in BottomControlBar/PagesSelector/progress bar
  - `v043-fullscreen-control-bar` (partial/M/medium): Fullscreen control bar spacing, selectionAccent, centred send icon
- 上游文件: app/.../feature/video/ui/overlay/BottomControlBar.kt (104 KB; carried e51b0374); app/.../feature/video/ui/components/PlainLandscapeDanmakuComposer.kt (new, 582 lines); app/.../feature/video/ui/components/LandscapeDanmakuComposer.kt; app/.../feature/video/ui/overlay/PlayerControlGlass.kt; app/.../feature/video/ui/overlay/VideoPlayerOverlay.kt; app/.../feature/video/ui/overlay/VideoPlayerOverlayContracts.kt; app/.../feature/video/ui/overlay/VideoProgressBarLayoutPolicy.kt (direct v025; v0.2.9 == v0.4.6); app/.../feature/video/ui/overlay/PersistentProgressBar.kt; desktop/src/.../ui/DesktopWindowsDanmakuDialog.kt
- 风险:
  - BottomControlBar is a hash-pinned generator input; tools/verify-upstream-video-player-full-controls.py expected hashes must be re-verified.
  - Whether a glass dock renders correctly over the native mpv HWND canvas is unknown; Compose cannot draw above the HWND.
  - The danmaku composer lives in a separate AWT dialog (DesktopWindowsDanmakuDialog.kt), so the Android exit animation does not map 1:1.
  - Bookkeeping entry v030-pr870-video-page-merge resolves here (chrome compaction).
- **第一步**: Re-pin BottomControlBar to v0.4.6 in tools/v037_player_chrome.py (it contains the subtitle verticalScroll, selectionAccent and adaptivePlainDockActionSpacing hunks) and refresh the expected hashes in verify-upstream-video-player-full-controls.py.
- 需协调: Large-screen UI task (control geometry on 4K)

#### 24. `cmt-closure-merge-and-polish`: Comment closure: merge unmerged branch, drop shims, small comment polish

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.7, 0.3.0, 0.3.6, 0.4.3, 0.4.5, 0.4.6
- 前置: `fnd-identity-badges-vip-pink`, `fnd-sheet-motion-and-glyph`, `fnd-core-data-relocated-policies`
- 覆盖条目:
  - `v027-comment-body-font` (missing/S/low): Comment body font bodySmall -> bodyMedium (VideoCommentAppearance, still the 79e8 direct copy)
  - `v030-comment-reply-2dp-gap` (missing/S/low): 2dp gap between text and reply button
  - `v036-video-comment-cache-route-memo` (partial/S/medium): Sub-reply skeleton slots (cache + route memo already carried)
  - `v043-comment-avatar-push` (partial/M/low): Comment avatar push + motion (exists only on unmerged branch)
  - `v045-comment-cascade-and-danmaku-composer-exit` (partial/M/low): Comment cascade (unmerged branch) + danmaku composer exit animation
  - `v046-bangumi-comment-panel-floating-chrome` (partial/M/medium): Bangumi comment panel + floating comment chrome (exists only on unmerged branch)
- 上游文件: desktop/upstream-slices/v046-comment-list (24 files at d6bf8b16, on unmerged branch); desktop/tools/v046_comment_list.py, v046_comment_thread.py (unmerged); app/.../feature/video/ui/components/VideoCommentAppearance.kt (79e8 direct); app/.../feature/video/ui/components/ReplyComponents.kt; app/.../feature/video/ui/components/SubReplyDetailComponents.kt (skeleton slots, 2dp gap); app/.../feature/video/ui/components/CommentFraudFeedbackHost.kt; app/.../feature/bangumi/ui/player/BangumiCommentPanel.kt; app/.../feature/video/ui/components/VideoCommentFloatingHeader.kt
- 风险:
  - The whole comment line lives on UNMERGED branches (desktop/source261-comment-closure-compile-repair: ade424f63 <- e46fe930d <- aad198489); Linux compile OK, no Windows validation; only Root merges.
  - The branch carries BangumiPlayerScreen/Content at d6bf8b16: avoid double ownership with bgm-player-viewmodel-and-features.
  - Existing test tools/tests/test_v029_reply_renderer.py:73 asserts no contentToActionSpacingDp and must be updated for the 2dp gap.
  - The Nagram license note (comment avatar push) needs the notices slice.
- **第一步**: Root decision: merge desktop/source261-comment-closure-compile-repair; once fnd-sheet-motion-and-glyph and fnd-identity-badges-vip-pink land, drop the generated compat shims (reducedMotion, DesktopUserVipPink) and re-run the comment Python tests; then apply the VideoCommentAppearance font bump and the 2dp gap.
- 需协调: Root (branch merge, final verification)
- 备注: Counts entries that exist only on the unmerged branch as partial, not done.

#### 25. `live-data-correctness`: Live data correctness: rank type, SuperChat expiry, colours, favourite tags, MPV recovery mapping

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.9, 0.3.0, 0.3.5 | 快赢
- 前置: `fnd-symbol-resolution-gate`
- 覆盖条目:
  - `v029-live-interaction-filters-superchat` (partial/M/medium): Live SuperChat absolute expiry API, emoticon unique id, report mapping
  - `v030-live-playback-recovery` (partial/S/medium): Live recovery: MPV event mapping (behind-live-window, audio-write)
  - `v030-live-moderation-sc-expiry` (partial/M/low): Live SC server deadline + #RRGGBB colour fix
  - `v035-live-data-api-fixes` (missing/M/medium): Live data fixes: contribution-rank API type, favourite tags sync, replyToUid, SC panel switch
- 上游文件: app/.../data/repository/LiveRepository.kt (v030-live-stream slice, line 1004); app/.../data/repository/LiveInteractionModels.kt; app/.../feature/live/LiveRealtimeMessagePolicy.kt; app/.../feature/live/LiveSuperChatExpiryPolicy.kt (direct v025); desktop/src/.../danmaku/LiveDanmakuRenderer.kt:5-8,83,152; core-data/.../data/model/response/LiveEmoticonModels.kt; desktop/src/.../ui/DesktopLiveNavigationBindings.kt:151-162
- 风险:
  - Verified: v030-live-stream/LiveRepository.kt:1004 still sends type.name.lowercase() (enum has no apiValue): a real request-parameter bug.
  - Switching SuperChat to the endTime API requires moving the renderer and DesktopLiveSession SC items to absolute end-time fields; carried v0.3.0 LivePlayerViewModel already calls the new API that no Windows source defines.
  - No real-room verification exists; behind-live-window and audio-write degradation have no MPV mapping (README.md:645, PARITY.md:165).
- **第一步**: S first: add LiveContributionRankType.apiValue and use it in tools/v030_live_stream.py's LiveRepository (smallest high-confidence fix), then carry the new SC expiry policy and adapt LiveDanmakuRenderer.

#### 26. `live-browse-request-policy`: Live list request isolation, nullable has_more, area index cache

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.9, 0.3.0, 0.3.6
- 前置: `live-data-correctness`
- 覆盖条目:
  - `v029-live-browse-request-isolation` (missing/M/medium): Live lists: LiveBrowseRequestPolicy stale isolation, terminal has_more (duplicate of v030 entry)
  - `v030-live-browse-request-isolation` (missing/M/medium): Live lists request isolation + same-page retry (duplicate of v029 entry)
  - `v036-live-area-index-space-paging-unported` (missing/M/low): Live area index cache; space bangumi paging + stale guard
- 上游文件: app/.../feature/live/LiveBrowseRequestPolicy.kt (new); app/.../data/repository/LiveAreaRoomsPolicy.kt (direct v025); app/.../data/repository/LiveRepository.kt (getLiveAreaIndex); app/.../feature/live/LiveListScreen.kt; app/.../feature/live/LiveAreaDetailScreen.kt; app/.../feature/live/LiveFollowingScreen.kt; desktop/src/.../ui/DesktopLiveListBindings.kt
- 风险:
  - Live generators pin the 79e8 sources (extract-upstream-live-list.py, extract-upstream-live-navigation.py); the Windows LiveListViewModel constructor is already adapted (parentScope, requests, owner), so the recipe must be re-anchored.
  - getLiveAreaIndex uses android.os.SystemClock and AppScope.ioScope: needs a clock and scope adapter.
  - Pin at the latest live version (0.4.5), not 0.3.0/0.3.6; the screens changed repeatedly.
  - Duplicate entries: v029 and v030 describe the same work.
- **第一步**: Carry LiveBrowseRequestPolicy.kt and LiveAreaRoomsPolicy.kt at v0.4.6 blobs and adapt the hasMore contract in DesktopOwnedOriginalLiveListRequests; test stale-response rejection.

#### 27. `bgm-player-viewmodel-and-features`: Bangumi player: VM at 0.3.8, default quality, danmaku send, subtitles, side panel

- 成本 **L** (约 7 人天，启发式) | 用户价值 **high** | 发布: 0.3.4-beta.1, 0.3.5, 0.3.8
- 前置: `fnd-design-system-leaf-completion`, `fnd-sheet-motion-and-glyph`, `cmt-closure-merge-and-polish`, `plr-overlay-and-controlbar-repin`
- 覆盖条目:
  - `v034b1-bangumi-default-quality` (partial/S/high): Bangumi default quality via shared policy (v0.3.6/0.3.8 revisions of the VM missing)
  - `v035-bangumi-player-features` (partial/L/high): Bangumi player: danmaku send, subtitles, side comment panel, audio-only, flips, sleep timer, save cover
  - `v038-bangumi-player-session-and-glass` (missing/M/low): Bangumi glass controls + listen-bar session handoff
- 另外涉及 (主切片在别处): `v036-bangumi-follow-preload-single-state`
- 上游文件: app/.../feature/bangumi/BangumiPlayerViewModel.kt (0.3.8 blob; carried via v034-pgc-initial-detail); app/.../feature/bangumi/BangumiPlayerScreen.kt; app/.../feature/bangumi/ui/player/BangumiPlayerComponents.kt; app/.../feature/bangumi/ui/player/BangumiPlayerContent.kt; app/.../feature/bangumi/ui/player/BangumiPlayerFeatures.kt; app/.../feature/bangumi/ui/player/BangumiPlayerOverlayHost.kt; app/.../feature/video/ui/components/LandscapeSidePanel.kt (closeProgress); app/.../feature/video/ui/components/DanmakuSendDialog.kt
- 风险:
  - PARITY .60 says the Bangumi files and the original player section are reference originals, not a complete runtime port; only downloadAudio is wired today (PARITY.md:37 '.62').
  - Android-only parts to drop or replace: PiP, auto-rotate, status bar, ACCELEROMETER_ROTATION.
  - The v0.4.6 comment closure also generates a Bangumi comment panel and carries BangumiPlayerScreen/Content: coordinate ownership.
  - Registry module bangumi-function-closure (RELEASED) previously owned the Root files.
- **第一步**: Re-pin the v034-pgc-initial-detail BangumiPlayerViewModel to the v0.3.8 blob (covers the 0.3.6/0.3.8 revisions: default quality, sendEpisodeDanmaku, saveCover, downloadAudio) and bind sendEpisodeDanmaku to the existing Windows danmaku send protocol.
- 需协调: Root (registry: bangumi-function-closure); Large-screen UI task
- 备注: High value for an anime/series viewer on a 4K desktop.

#### 28. `bgm-hub-pages`: Bangumi hub: single UiState, follow preload, index filter, channel bar

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.3.6, 0.4.0 (+0.4.1)
- 前置: `bgm-player-viewmodel-and-features`, `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v036-bangumi-follow-preload-single-state` (missing/L/medium): Bangumi follow preload + single BangumiUiState
  - `v040-bangumi-index-filter-and-channel-bar` (missing/M/medium): Bangumi index filter animation, channel bar above status bar, year range label
- 上游文件: app/.../feature/bangumi/BangumiViewModel.kt (single UiState, +311/-222); app/.../feature/bangumi/policy/BangumiFollowStatusPolicy.kt; app/.../data/repository/BangumiRepository.kt (page size 30); app/.../feature/bangumi/BangumiHubContent.kt; app/.../feature/bangumi/BangumiScreen.kt; app/.../feature/bangumi/HomeBangumiTabPage.kt; app/.../feature/bangumi/policy/BangumiHubPolicy.kt; app/.../feature/bangumi/BangumiCardTransition.kt
- 风险:
  - Two hash-pinned extraction generators (extract-upstream-bangumi-pages.py, extract-upstream-home-bangumi-page.py); DesktopOriginalBangumiPagesRoot.kt must be re-anchored.
  - The BangumiViewModel move from several StateFlows to one BangumiUiState breaks existing bindings.
  - BangumiCardTransition uses SharedCardContainerHost, carried at v0.4.6 via v042-search-layout.
- **第一步**: Carry BangumiFollowStatusPolicy and BangumiRepository (page size 30) at v0.4.6 blobs, then re-anchor the BangumiViewModel single-state recipe.
- 需协调: Root (registry: bangumi-function-closure)

#### 29. `audio-listen-video-closure`: Listen-video closure: recent plays, lyrics/subtitle controls, queue behaviour, glass controls

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.3.6, 0.3.8, 0.3.9, 0.4.0 (+0.4.1), 0.4.5
- 前置: `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v036-audio-music-glass-controls` (missing/M/low): Music player glass controls (MusicPlayerContent)
  - `v038-listen-direct-entry` (partial/S/medium): Listen-video direct entry (remaining MusicPlayerContent queue hunk)
  - `v039-audio-bar-listen-recent` (missing/M/medium): Listen-video 'recent plays' entry + audio bar changes
  - `v040-listen-video-recent-play-and-player` (missing/M/medium): Listen 'recent plays' + immersive player colour/pager fixes
  - `v045-listen-lyrics-subtitle-display-controls` (missing/M/medium): Listen lyrics/subtitle display controls (resolveLyricsDisplayDocument)
- 上游文件: app/.../feature/audio/screen/ListenVideoScreen.kt (v038 slice 9da3d48ed = pre-0.3.9); app/.../feature/audio/viewmodel/ListenVideoViewModel.kt; app/.../feature/audio/screen/MusicPlayerContent.kt (policy-extract v0.2.5); app/.../feature/audio/lyrics/LyricsDisplayPolicy.kt (direct; resolveLyricsDisplayDocument); app/.../feature/video/screen/AudioModeMusicPlayer.kt; app/.../feature/audio/screen/AudioNowPlayingBar.kt; desktop/src/.../ui/DesktopOriginalListenVideoHost.kt:54-70
- 风险:
  - UI only: must not disturb the WASAPI exclusive / TOSLINK output path (user priority). The waveform effect is excluded.
  - MusicPlayerContent changed seven times (0.2.5 to 0.4.5): one re-pin must absorb all deltas (extract-upstream-music-player-full.py hash-pinned at 79e8).
  - MiniPlayerManager.pausePlayback needs an adapter onto DesktopPlaybackController.
- **第一步**: Re-pin ListenVideoScreen to the v0.4.6 blob and add the onPlayFromHistory host callback (push AudioMode with bvid/cid) in DesktopOriginalListenVideoHost; then LyricsDisplayPolicy.
- 需协调: Root (audio output owner)

#### 30. `acct-account-scoped-requests`: Account-switch request scoping (audit then adopt)

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.9, 0.4.3
- 前置: `audit-unknown-entries`
- 覆盖条目:
  - `v029-dynamic-multi-account-isolation` (partial/S/low): DynamicRepository per-account pagination isolation (AccountPagination)
  - `v043-account-scoped-refresh` (partial/M/medium): Account switch cancels/resets favourites, history, watch-later, home, bangumi, profile
- 上游文件: app/.../core/coroutines/AccountScopedRequests.kt; app/.../data/repository/DynamicRepository.kt (AccountPagination); core-data/.../core/store/TokenManager.kt (accountIdentity); app/.../feature/home/HomeViewModel.kt; app/.../feature/list/ListViewModel.kt; app/.../feature/profile/ProfileViewModel.kt; app/.../feature/audio/viewmodel/ListenVideoViewModel.kt; desktop/src/.../data/DesktopRepository.kt (sessionEpoch)
- 风险:
  - Root's request-owner epoch retirement (DesktopRepository.sessionEpoch) may already cover most pages: a verbatim port would fight the Root owner.
  - Favourites/collections already rewrite accountRequests into Root environment calls (v044 generators); Home/Profile/WatchLater/Bangumi/Listen recipes still pin pre-0.4.3 content.
- **第一步**: Audit first: write a Python source test enumerating which owners read sessionEpoch for favourites, history, watch-later, home, bangumi hub, listen collection and profile; adopt upstream behaviour only where the test shows a gap.
- 需协调: Root (owns account/session retirement)

#### 31. `dyn-category-merge-and-detail-return`: Dynamic page: category merge, detail return retention, cold-start empty state, reaction sheets

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.2.6, 0.3.4-beta.2, 0.3.5, 0.4.5
- 前置: `acct-account-scoped-requests`
- 覆盖条目:
  - `v026-dynamic-top-tabs-scroll` (unknown/S/low): Dynamic tab row scrolls instead of overlapping
  - `v034b2-dynamic-reaction-user-list` (partial/S/medium): Dynamic reaction user list: re-pin the two sheets to v0.4.6 (10-line delta)
  - `v035-dynamic-cold-start-empty-flash` (missing/S/low): Dynamic tab empty-state flash on first cold-start switch (desktop-owned fix)
  - `v045-dynamic-category-merge` (missing/L/medium): Dynamic 'all'+'UP' merged, 'all dynamics' user-strip entry, author filter, key removal
  - `v045-dynamic-detail-return-retention` (missing/M/medium): Dynamic detail return keeps loaded content/comments/position
- 上游文件: app/.../feature/dynamic/DynamicTabPolicy.kt (direct); app/.../feature/dynamic/DynamicViewModel.kt; app/.../feature/dynamic/DynamicScreen.kt; app/.../feature/dynamic/DynamicDetailViewModel.kt (new); app/.../feature/dynamic/DynamicDetailScreen.kt; app/.../feature/dynamic/components/DynamicReactionSheet.kt; desktop/src/.../ui/DesktopDynamicTabsHost.kt; desktop/src/.../ui/DesktopDynamicTimeline.kt:183-184; desktop/src/.../ui/DesktopDynamicTabsPreferences.kt:21,50; desktop/src/.../settings/DesktopSettingsShareWindowsCatalog.kt:194
- 风险:
  - Removes the persisted key dynamic_all_tab_horizontal_user_list_visible and the 'up' tab id: map up to all in Android-backup import and fix DesktopDynamicTabsTest.
  - Touches several producers (extract-upstream-dynamic-tabs/-settings/-follow.py, v040_dynamic_pagination.py, v041_dynamic_author_search.py).
  - Verified: DesktopDynamicTimeline.kt:183-184 shows the empty message whenever !busy && displayed.isEmpty() && error == null, independent of `initialized` (cold-start flash).
- **第一步**: S desktop-only first: gate the empty-state text in DesktopDynamicTimeline.kt on state.initialized; then migrate the tab/settings keys and re-pin the dynamic view model.
- 需协调: Large-screen UI task (dynamic page layout)

#### 32. `space-profile-content-pager`: Space/profile/live content pager guard and space page small items

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.2.8, 0.4.5
- 前置: `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v028-space-banner-auto-rotate` (missing/S/low): UP space banner auto-rotates every 4 s
  - `v045-content-tab-pager-backtotop` (missing/M/medium): ContentTabPager + PagerState rebuild guard (async tab count crash) + back-to-top backdrop
- 另外涉及 (主切片在别处): `v036-skeleton-message-topic-space-unported`, `v036-live-area-index-space-paging-unported`
- 上游文件: app/.../core/ui/common/ContentTabPager.kt (new); app/.../feature/space/SpaceScreen.kt (policy-extract at 79e8 pin); app/.../feature/profile/ProfileScreen.kt; app/.../feature/live/LiveListScreen.kt; app/.../core/ui/components/AppLiquidGlassBackToTopButton.kt; app/.../core/ui/blur/FloatingChromeBackdrop.kt; desktop/src/.../ui/DesktopCompleteSpaceScreen.kt
- 风险:
  - Which Space screen is mounted (SpaceViewModel-based original vs desktop-owned DesktopCompleteSpaceScreen) is UNKNOWN and decides the cost.
  - The key(pageCount) PagerState rebuild guard prevents an out-of-bounds crash when tabs load asynchronously.
- **第一步**: Read DesktopOriginalSpacePagesRoot vs DesktopCompleteSpaceScreen to find the mounted Space screen, then apply the key(pageCount) rebuild guard where tab counts load asynchronously.

#### 33. `card-geometry-lists-and-grid`: Horizontal card geometry, equal-height grid rows, list pages

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.2.7, 0.3.0, 0.3.5, 0.4.1, 0.4.5
- 前置: `fnd-core-data-relocated-policies`, `fnd-design-system-leaf-completion`, `fnd-home-card-cascade-definers`
- 覆盖条目:
  - `v027-home-feed-row-alignment` (unknown/S/low): Home card row alignment when full title is hidden
  - `v030-home-grid-equal-row-height` (missing/S/medium): Home grid equal-height rows (IntrinsicSize.Min)
  - `v035-watch-later-bar-layout` (missing/M/low): Watch-later page: sort button in top bar, search/filter relocation
  - `v041-horizontal-card-geometry-wallpaper-surface` (partial/L/medium): Unified horizontal video-card geometry (list pages, related, personal cards)
  - `v045-recommendation-reason-native-navigation` (partial/S/medium): Recommendation reasons/native nav (residue: BiliPaiFeedFilterPlugin non-video guard)
  - `v045-favorite-folder-metadata` (partial/S/medium): Favourite metadata editor residue: FavoriteCategoryScreen dock slot width
- 上游文件: app/.../feature/home/components/cards/HorizontalVideoCardFrame.kt; app/.../feature/home/components/cards/HorizontalVideoCardLayoutPolicy.kt; app/.../feature/personal/PersonalMediaCard.kt; app/.../feature/video/ui/components/RelatedVideoItem.kt; app/.../feature/home/HomeCategoryPage.kt (tools/v029_home_load.py delta, lines 318-345); app/.../feature/watchlater/WatchLaterScreen.kt; app/.../feature/list/FavoriteCategoryScreen.kt; app/.../feature/plugin/BiliPaiFeedFilterPlugin.kt
- 风险:
  - 'direct' files are copied verbatim at the pin: a pin move may fan out to many generators.
  - HomeCategoryPage is patched by a hash-pinned recipe; the wallpaper-surface half depends on the wallpaper slices.
  - DownloadListScreen is a platform-adapter-reference.
- **第一步**: S: add the Row(height(IntrinsicSize.Min)) counted edit to the Delta in tools/v029_home_load.py (equal-height rows); then re-pin HorizontalVideoCardLayoutPolicy and Frame and the FeedFilter non-video guard.
- 需协调: Large-screen UI task (card/grid scope)

#### 34. `vid-up-queue-sheet`: UP video queue sheet rows

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.4.1
- 前置: `card-geometry-lists-and-grid`
- 覆盖条目:
  - `v041-up-video-queue-sheet` (partial/M/medium): UP video queue sheet rows: plays/comments/publish time, scroll to current
- 上游文件: app/.../feature/video/screen/VideoDetailOverlayHost.kt (ExternalPlaylistQueueSheetContent); app/.../feature/video/player/PlaylistManager.kt (carried); app/.../feature/space/SpacePlaybackPolicy.kt (carried); desktop/src/.../ui/DesktopWindowsVideoCollectionQueueHost.kt:17,168
- 风险:
  - The sheet is presented in a bounded native window above MPV: keep the rows and dismiss button, skip the slide gesture animation.
  - The host calls the 79e8 signature; the new onDismiss parameter requires a host change.
- **第一步**: Re-pin ExternalPlaylistQueueSheetContent (plays/comments/publishedAt rows, scroll to current, dismiss button) in extract-upstream-danmaku-list-menu.py and update the Windows host.

#### 35. `vid-next-watch-and-toggle`: Next-watch card mounts and on/off switch

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.4 | 快赢
- 前置: 无 | 软前置: `bgm-player-viewmodel-and-features`
- 覆盖条目:
  - `v034-next-watch-card` (partial/M/medium): Next-watch card: bangumi/offline/portrait mounts + v0.3.8 overlay revision
  - `v034-next-watch-toggle-setting` (partial/S/low): Next-watch switch: setter, settings row, share catalog (read side only today)
- 上游文件: app/.../feature/video/ui/overlay/NextWatchOverlay.kt (0.3.8 revision); app/.../feature/video/playback/next/NextWatchSuggestion.kt (carried); app/.../core/store/SettingsManager.kt (setter); app/.../feature/settings/screen/PlaybackSettingsScreen.kt; desktop/src/.../settings/DesktopSettingsShareWindowsCatalog.kt
- 风险:
  - Bangumi, offline-collection and portrait-pager mounts are not on Windows (PARITY .60); the Bangumi card depends on bgm-player-viewmodel-and-features.
  - Only the two original key/getter declarations are carried today, so the user cannot switch the card off.
- **第一步**: S: add a next_watch_hint_enabled switch to the Windows playback settings and the share catalog (the read side is already declared by DesktopOriginalNextWatchHintSettings.kt); then bump NextWatchOverlay to the v0.3.8 revision.

#### 36. `set-settings-search-items`: Settings search at item level (Windows-owned directory)

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.3.4-beta.1, 0.3.8, 0.3.9
- 前置: `fnd-design-system-leaf-completion` | 软前置: `vid-next-watch-and-toggle`
- 覆盖条目:
  - `v034b1-settings-item-level-search` (missing/L/medium): Settings search to item level: directory, anchors, breadcrumb
  - `v038-settings-search-descriptions` (missing/M/low): Settings search: description hits, keyword highlight, auto-locate
  - `v039-settings-search-highlight` (missing/M/low): Settings search focus claim + fade highlight
- 上游文件: app/.../feature/settings/SettingsSearchPolicy.kt; app/.../feature/settings/PlaybackSettingDirectory.kt; app/.../feature/settings/SettingsAdditionalDirectory.kt; app/.../feature/settings/SettingsItemAnchor.kt; app/.../feature/settings/SettingsItemPath.kt; app/.../feature/settings/SettingsSearchFocusPolicy.kt; app/.../feature/settings/SettingsDescriptionIndex.kt; desktop/src/.../settings/DesktopSettingsSearchScreen.kt:55-66; desktop/src/.../settings/DesktopSettingsSearchController.kt:36; desktop/src/.../settings/DesktopSettingsTree.kt
- 风险:
  - Needs a Windows-specific item directory (phone-only options were trimmed); the upstream directory is an Android map.
  - Windows search is desktop-owned and section-level only; desktop/GAP-AUDIT-v035.md item 30 records the same gap.
  - Item ids such as playback.next_watch_hint_enabled should exist before they are indexed.
- **第一步**: Define a Windows DesktopSettingsItemDirectory mapped from DesktopSettingsTree ids, extend the settings-search destination with an item anchor, and add a Python test that every tree item has an id.

#### 37. `rss-readability-public-network`: RSS: Readability extraction and anonymous public-content network

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.4.5
- 前置: `rss-referer-opml-groups`, `cmp-third-party-notices`
- 覆盖条目:
  - `v045-rss-readability-public-content-network` (partial/L/medium): RSS Readability extraction + anonymous public-content network + image Referer restore
- 上游文件: app/.../core/plugin/feed/FeedArticleExtractor.kt (direct); app/.../core/plugin/feed/FeedFetcher.kt; app/.../core/plugin/feed/PublicContentNetwork.kt (new); app/.../core/plugin/feed/PublicContentImageLoader.kt (new); app/.../feature/dynamic/components/ImagePreviewDialog.kt (publicContent flag); app/src/main/assets/licenses/readability4j.LICENSE; desktop/build.gradle.kts (dependencies)
- 风险:
  - New Gradle dependencies: readability4j 1.0.8 and jsoup >= 1.23.2 (Windows has jsoup 1.21.2, slf4j-api 2.0.17); jsoup upgrade may affect other users; dependency resolution cannot be tested offline here.
  - Windows already uses an anonymous plain OkHttp client (no cookie jar), so per-hop credential stripping is hardening rather than a fix.
  - License inventory must be extended (readability4j, jsoup, slf4j).
- **第一步**: Python-only first: vendor PublicContentNetwork.kt with a test pinning its source; record the proposed dependency versions in a note for Root without resolving them (heavy validation is deferred).
- 需协调: Root (dependency and license inventory)

#### 38. `chrome-glass-tuning`: Liquid-glass tuning values at 0.3.9

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.9
- 前置: 无
- 覆盖条目:
  - `v039-liquid-glass-tuning` (partial/M/medium): LiquidGlassTuning + BottomBarGlassMaterialPolicy at 0.3.9 values (default glass material)
- 上游文件: app/.../feature/home/components/LiquidGlassTuning.kt; app/.../feature/home/components/BottomBarGlassMaterialPolicy.kt; app/.../feature/partition/PartitionScreen.kt (export-material fix); desktop/src/.../ui/DesktopShell.kt:1740
- 风险:
  - Both files are 'extracted' at the pin through tools/extract-upstream-shared-liquid-tabs.py and each changed only in 4e15f57: re-anchor the hash-pinned recipes.
  - DesktopShell.kt:1740 calls the pinned resolveLiquidGlassTuning, so the default Windows glass still uses the older readability curve.
  - Original tests BottomBarGlassMaterialPolicyTest and LiquidGlassTuningTest changed in the same commit.
- **第一步**: Re-anchor the two 'extracted' recipes in extract-upstream-shared-liquid-tabs.py to the 4e15f57 blobs and add a test that DesktopShell.kt resolves the new linear 0.34 readability curve.
- 需协调: Large-screen UI task (glass material default)

### T2: Medium-value polish and cheap independent fixes

#### 39. `ui-draggable-scrollbar-alias`: Draggable scrollbars: AppLazyColumn/AppLazyVerticalGrid alias across list screens

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.3.4-beta.2
- 前置: `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v034b2-lazy-lists-draggable-scrollbar` (partial/L/medium): ~100 lists moved to AppLazyColumn: draggable scrollbar; AppStaggeredGrid
- 上游文件: design-system/.../core/ui/components/AppScrollableLists.kt (carried); design-system/.../core/ui/components/AppStaggeredGrid.kt (not carried); design-system/.../core/ui/components/AppHorizontalPager.kt (carried); ~100 list/grid/pager screens
- 风险:
  - Each of about 100 screens has a hash-pinned recipe; editing all is the expensive path.
  - Suggestion (inference): do a build-time alias substitution in the staging step instead of 100 recipe edits.
  - AppLazyVerticalStaggeredGrid needs its own carry.
- **第一步**: Prototype the alias substitution for one staged screen (CommonListScreen is already v0.4.6) and add a Python test asserting the LazyColumn to AppLazyColumn swap list.

#### 40. `chrome-tab-rails-and-geometry`: Tab rails and chrome geometry consumers

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.3.4-beta.1, 0.3.4, 0.3.5, 0.3.8, 0.3.9, 0.4.0 (+0.4.1), 0.4.0, 0.4.0 (+0.4.1 refinements), 0.4.1
- 前置: `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v034b1-shared-tab-rails-measured-width` (partial/S/medium): Shared tab rails: measured viewport width, explicit tab roles
  - `v034-md3-top-tab-underline-and-motion` (partial/S/low): MD3 top-tab underline consumers (HomeCategoryPage, RelatedVideoItem, back-to-top)
  - `v035-shared-tab-label-typography` (partial/S/low): Shared tab/segmented label typography; badge/feed tokens
  - `v038-chrome-geometry-exact-touch-bounds` (partial/L/medium): Chrome geometry + exact touch bounds (9 uncarried consumers)
  - `v039-chrome-geometry-merge` (partial/M/medium): PR #917 chrome geometry snapshot (count once with the 0.4.0 entries)
  - `v040-chrome-geometry-segmented-dock-core` (partial/M/medium): Segmented/dock geometry core (AppTonalPillTabPolicy, design-system DampedDragAnimation uncarried)
  - `v040-miuix-nonglass-settings-presentation` (partial/S/low): Miuix non-glass settings/list presentation
  - `v041-partition-sidebar-measured-indicator` (missing/M/low): Partition sidebar indicator on measured coordinates
  - `v041-glass-tab-consumers-compact` (partial/M/low): Glass tab consumers compact (search field, live list, today-watch, review)
- 上游文件: app/.../core/ui/components/AppLiquidAwareTabRow.kt (v0.4.3 revision; carried v0.4.2); design-system/.../renderer/material3/AppTonalPillTabPolicy.kt; design-system/.../core/ui/animation/DampedDragAnimation.kt; app/.../feature/dynamic/components/DynamicAdaptiveSegmentedControl.kt; app/.../core/ui/components/AppLiquidAwareSearchField.kt; app/.../feature/partition/PartitionScreen.kt; app/.../feature/home/components/HomeHeader.kt (selected); app/.../feature/home/HomeCategoryPage.kt
- 风险:
  - Each consumer sits in a different generator; most foundations are already carried at v0.4.6, so only direct/policy-extract consumers remain.
  - Several PR #917 entries describe the same final form: count once.
- **第一步**: Carry AppTonalPillTabPolicy and the design-system DampedDragAnimation at v0.4.6 (the two uncarried foundation files) and bump AppLiquidAwareTabRow to the v0.4.3 revision in tools/v042_search_dependencies.py.

#### 41. `live-room-ui-redesign`: Live room UI redesign (width-density grid, split chat, controls, emoticons)

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.2.9, 0.3.0, 0.3.5, 0.3.7
- 前置: `live-data-correctness`, `live-browse-request-policy`, `chrome-tab-rails-and-geometry`, `fnd-theme-typography-accent-tokens`
- 覆盖条目:
  - `v029-live-touch-targets-40dp` (partial/S/low): Live controls 48dp -> 40dp (LiveBiliPaiVisualPolicy)
  - `v030-live-emoticon-send` (partial/M/low): Live emoticon picker + emoticon send by unique id
  - `v035-live-ui-redesign` (missing/L/medium): Live pages/player redesign: width-density grid, split chat, landscape controls, cover save
  - `v037-dynamic-live-interaction-theme` (missing/M/low): Dynamic/live chat colours on theme roles (readableLiveBadgeColors)
- 上游文件: app/.../feature/live/LiveAreaScreen.kt; app/.../feature/live/LiveListScreen.kt; app/.../feature/live/LiveSearchScreen.kt; app/.../feature/live/components/LiveLandscapePlayerControls.kt (826 lines); app/.../feature/live/LiveRoomLayoutPolicy.kt; app/.../feature/live/LiveBiliPaiVisualPolicy.kt; app/.../feature/live/LiveChromePalette.kt; app/.../feature/live/components/LiveSendDanmakuSheet.kt
- 风险:
  - Pin at the latest live version (0.4.5), not 0.3.5.
  - Drop Android orientation/portrait-overlay modes; wrap the desktop-owned live source recovery and chat binding (DesktopLiveSourceRecovery, DesktopWindowsLiveSourceSheetBody, DesktopLiveChatMessages) instead of replacing them.
  - No real-room acceptance exists.
- **第一步**: Carry LiveRoomLayoutPolicy and LiveBiliPaiVisualPolicy at 0.4.5 blobs and apply the width-density grid (columns = ceil(widthDp/240)) to the live list: visible on a 4K window as a quick win.

#### 42. `chrome-dock-consumers`: Dock/header/audio-bar consumers (glass chrome polish)

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.3.0, 0.3.4-beta.2, 0.3.5, 0.3.8, 0.4.0 (+0.4.1), 0.4.0, 0.4.2
- 前置: `chrome-tab-rails-and-geometry`, `fnd-design-system-leaf-completion`, `chrome-glass-tuning`, `fnd-home-card-cascade-definers`
- 覆盖条目:
  - `v030-glass-indicator-geometry` (partial/S/low): Top glass indicator follows dock geometry
  - `v034b2-elastic-press-feedback` (partial/M/medium): Elastic press feedback consumers (dock, header, audio bar, search)
  - `v034b2-glass-dock-chrome-consumers` (partial/M/medium): Glass dock shell consumers (LinkedBottomDock, HomeHeader, TopBar)
  - `v035-glass-dock-motion-back-to-top` (partial/S/low): Dock/content motion sync, back-to-top short-drag stretch
  - `v035-liquid-glass-chrome-unification` (partial/M/medium): Global glass chrome reuses bottom-bar material + press spring
  - `v038-home-nav-line-icons` (missing/S/low): Line-style home nav icons (HomeNavigationIconPolicy)
  - `v040-global-drag-feedback-elastic` (partial/M/low): Global drag feedback: LinkedBottomDock home button, BottomControlBar dock
  - `v040-linked-dock-now-playing-handoff` (missing/L/medium): Linked dock: parent-allocated sizes, now-playing bar shares dock height/capsule
  - `v040-bottom-bar-ui-skin-fit` (missing/S/low): Bottom bar skin background Fit
  - `v041-home-top-nav-sizing-avatar` (partial/M/low): Home top nav sizing, theme-colour avatar icon (TopTabStylePolicy, HomeHeader)
  - `v042-glass-chrome-accent-polish` (partial/M/low): Glass accent consumers (back-to-top, linked dock, carousel, audio bar)
  - `v042-nav-icons-dock` (missing/M/low): Seven filled nav icons + dock/search spacing
- 另外涉及 (主切片在别处): `v026-home-overlay-pill-press-shift`
- 上游文件: app/.../feature/home/components/LinkedBottomDock.kt; app/.../feature/audio/screen/AudioNowPlayingBar.kt; app/.../feature/home/components/BottomBar.kt; app/.../feature/home/components/BottomBarUiSkin.kt; app/.../feature/home/components/HomeHeader.kt; app/.../feature/home/components/HomeTopTabFloatingDock.kt; app/.../feature/home/components/HomeNavigationIconPolicy.kt; app/.../core/ui/transition/NowPlayingBarHandoff.kt
- 风险:
  - Root-owned files (DesktopOriginalLinkedDockRoot.kt, DesktopOriginalFrostedAudioRoot.kt, DesktopWindowsNativeDockGeometryContractTest.kt); generators pin 79e8 (extract-upstream-linked-dock.py PIN, ASSETS list; extract-upstream-frosted-audio-renderer.py).
  - The large-screen UI task may replace the bottom dock with a sidebar, which would make this slice moot.
  - Seven filled nav icon drawables must go through DesktopLinkedDockSymbols.
- **第一步**: Coordinate with the large-screen UI task first; if the dock stays, add the seven bp_nav_*_filled_24 drawables and re-pin HomeNavigationIconPolicy in extract-upstream-linked-dock.py.
- 需协调: Large-screen UI task (decides whether the dock stays); Root (dock geometry contract test)

#### 43. `theme-palette-presets-and-schedule`: Palette presets, preview widgets and scheduled dark theme

- 成本 **M** (约 3 人天，启发式) | 用户价值 **medium** | 发布: 0.4.2
- 前置: `fnd-design-system-leaf-completion`, `fnd-theme-typography-accent-tokens`, `cmp-third-party-notices`
- 覆盖条目:
  - `v042-seed-palette-styles` (partial/S/medium): 21 palette styles: seed preset list + swatches + 2 license rows
  - `v042-theme-settings-preview` (partial/M/low): Theme preview widgets (home preview, palette fan), optionLeading swatches
  - `v042-scheduled-dark-theme` (missing/M/low): Scheduled dark theme (DarkThemeSchedule, time picker, minute timer)
- 上游文件: app/.../core/theme/SeedPresetColors.kt (carried, unused); app/.../feature/settings/ThemeHomePreview.kt; app/.../feature/settings/ThemeStylePalettePreview.kt; app/.../feature/settings/DarkThemeSchedule.kt; app/.../feature/settings/screen/ThemeTimeWheelPicker.kt; desktop/src/.../appearance/DesktopAppearanceSettings.kt:120-134; desktop/src/.../appearance/DesktopAppearanceTheme.kt:49
- 风险:
  - ThemeHomePreview imports BottomNavItem/resolveMaterialBottomBarIcon and R.drawable: map through DesktopLinkedDockSymbols.
  - The schedule needs a desktop minute timer, three DesktopThemePrefs keys and settings-search entries.
  - The whole theme engine is source-qualified only (actualRuntimeVerified=false).
- **第一步**: S: use the carried SeedPresetColors as the custom-colour preset list while a seed style is enabled (DesktopAppearanceSettings.kt:120-134) and add the two license rows; then the preview widgets.

#### 44. `srch-container-transitions`: Search and bangumi container transitions

- 成本 **M** (约 3 人天，启发式) | 用户价值 **low** | 发布: 0.3.9, 0.4.0 (+0.4.1), 0.4.2
- 前置: `bgm-hub-pages`, `srch-state-landing-and-entry`
- 覆盖条目:
  - `v039-search-bangumi-container-transition` (partial/M/low): Bangumi/search shared container transition (BangumiCardTransition)
  - `v041-search-result-container-transition` (partial/M/low): Search result container transition (bangumi card half uncarried)
  - `v042-search-composite-transitions` (partial/M/medium): Search composite page transitions; detail-screen shared expansion
- 上游文件: app/.../core/ui/transition/SharedCardContainerHost.kt (carried); app/.../feature/search/SearchResultContainerTransition.kt (carried); app/.../feature/bangumi/BangumiCardTransition.kt (new); app/.../feature/bangumi/BangumiScreen.kt; app/.../feature/article/ArticleDetailScreen.kt; app/.../feature/live/LiveSearchScreen.kt
- 风险:
  - Depends on Android predictive-back/shared-container navigation; Windows navigates through its own Root stack: skip the gesture parts.
  - Windows SearchScreen is at the 0.4.2-end blob, not v0.4.6.
- **第一步**: Map SharedCardContainerHost expand/return onto the Windows Root retained-entry owners for the bangumi card (needs a SharedTransitionScope host from Root).
- 需协调: Root (navigation owners)

#### 45. `wallpaper-foundation-and-adjust`: Wallpaper foundation and adjust sheet

- 成本 **L** (约 7 人天，启发式) | 用户价值 **medium** | 发布: 0.4.0
- 前置: `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v040-wallpaper-foundation-store-media` (missing/L/medium): Wallpaper foundation: transform/readability/playback policies, media type, atomic save
  - `v040-wallpaper-adjust-sheet` (missing/L/medium): Wallpaper position/zoom adjust sheet (re-adjust existing wallpaper)
- 上游文件: design-system/.../core/ui/wallpaper/WallpaperTransformPolicy.kt (new); design-system/.../core/ui/wallpaper/WallpaperReadabilityPolicy.kt; app/.../core/ui/wallpaper/WallpaperPlaybackCoordinator.kt; app/.../core/store/home/HomeWallpaperSelection.kt; app/.../feature/profile/HomeWallpaperAdjustmentSheet.kt (232 lines); desktop/src/.../ui/DesktopHomeWallpaperImages.kt; desktop/src/.../ui/DesktopOriginalHomeWallpaperPickerBindings.kt
- 风险:
  - Android pieces to replace: BitmapFactory probe, content: URIs, ExoPlayer video wallpaper, DataStore keys.
  - Video preview needs a desktop-owned surface; touch pan/zoom becomes mouse drag + wheel.
  - Decorative value; a 4K window benefits from crop control.
- **第一步**: Carry the three pure policies (WallpaperTransformPolicy, WallpaperReadabilityPolicy, WallpaperPlaybackCoordinator) with their original JVM tests and a Windows crop adapter.

#### 46. `mfx-small-protocol-fixes`: Small protocol/data fixes (independent micro-fixes)

- 成本 **M** (约 3 人天，启发式) | 用户价值 **low** | 发布: 0.2.8, 0.2.9, 0.3.4-beta.2, 0.3.4-beta.1 | 快赢
- 前置: 无
- 覆盖条目:
  - `v028-sponsorblock-vote-config` (partial/S/low): SponsorBlock vote JSON body, client headers, config normalised()
  - `v028-article-public-note-fallback` (missing/S/low): Article falls back to public note when the view API fails
  - `v029-chat-timeline-history-cursor` (partial/S/low): MessageHistoryCursorSerializer for UINT64_MAX min_seqno
  - `v034b1-log-redaction-patterns` (missing/S/low): LogRedactionPatterns shared immutable object
  - `v034b2-qr-album-decode` (missing/S/low): QR album decode loop fix (BiliPaiQrDecoder)
- 上游文件: app/.../data/repository/SponsorBlockRepository.kt; app/.../data/repository/ArticleRepository.kt; app/.../core/util/Logger.kt; app/.../feature/login/BiliPaiQrDecoder.kt; core-data/.../data/model/response/MessageHistoryCursorSerializer.kt; desktop/src/.../data/DesktopCommunityRepository.kt:510-519
- 风险:
  - Bundle of five independent micro-fixes, each with its own generator edit (extract-upstream-plugins.py, extract-upstream-diagnostics.py, extract-upstream-profile-main.py, ...).
  - SponsorBlock Origin/x-ext-version headers use BuildConfig.VERSION_NAME and need a Windows adaptation; whether the live server accepts the old query-string vote is unknown.
  - Log redaction patterns are Android-path oriented and do not obviously cover Windows drive paths (a Windows-owned extension, not upstream).
- **第一步**: S first: carry MessageHistoryCursorSerializer.kt and re-pin MessageResponse (empty chat history returns UINT64_MAX min_seqno which a Long field cannot parse).

#### 47. `mfx-image-viewer`: Image viewer motion and gestures

- 成本 **S** (约 1 人天，启发式) | 用户价值 **low** | 发布: 0.3.4-beta.2, 0.4.3
- 前置: 无
- 覆盖条目:
  - `v034b2-image-preview-gestures` (missing/S/low): Image preview exit-drag in root coordinates
  - `v043-image-preview-inertia-zoom` (missing/S/low): Image preview double-click zoom + drag inertia
- 上游文件: app/.../feature/dynamic/components/ZoomableImage.kt (direct); app/.../feature/dynamic/components/ZoomableImageMotionPolicy.kt (new); app/.../feature/dynamic/components/ImagePreviewDialog.kt; desktop/src/.../ui/DesktopCommentImageCanvas.kt
- 风险:
  - Bumping the direct ZoomableImage source also takes the 0.3.4-beta.2 changes: check ZoomableImageGesturePolicy/ScalePolicy still match.
  - Pinch handling is irrelevant on Windows; double-click zoom is useful.
- **第一步**: Bump the ZoomableImage direct source and add ZoomableImageMotionPolicy in the DIRECT list of tools/extract-upstream-dynamic-card.py:12.

### T3: Low-value polish, phone-leaning or decision-gated families

#### 48. `home-hero-preview-polish`: Home hero, preview dialog and card menu polish

- 成本 **M** (约 3 人天，启发式) | 用户价值 **low** | 发布: 0.3.0, 0.3.1, 0.4.2
- 前置: `card-geometry-lists-and-grid`
- 覆盖条目:
  - `v030-hero-carousel-longpress-dissolve` (missing/M/low): Home hero long-press menu + dissolve
  - `v031-home-preview-card-polish` (missing/M/low): Home long-press preview dialog: blur, menu positioning, dissolve
  - `v042-card-menu-no-emoji` (missing/S/low): Card long-press menu without emoji prefixes
- 上游文件: app/.../feature/home/components/HomeHeroCarousel.kt; app/.../feature/home/components/VideoPreviewDialog.kt (ExoPlayer); app/.../feature/home/components/cards/VideoCard.kt; app/.../core/ui/transition/VideoSharedTransitionPolicy.kt
- 风险:
  - VideoPreviewDialog uses ExoPlayer: the preview half needs an mpv preview adapter.
  - The dissolve path uses an Android RuntimeShader check: needs a Skia-side replacement.
  - The card-menu emoji strings come from the home-full-card generator; build/generated may be stale.
- **第一步**: S: remove the three emoji prefixes in the extract-upstream-home-full-card.py recipe and test the generated menu strings.

#### 49. `misc-ui-polish-and-toggles-low`: Low-value UI polish and settings toggles (bundle)

- 成本 **M** (约 3 人天，启发式) | 用户价值 **low** | 发布: 0.2.7, 0.3.0, 0.3.4-beta.2, 0.3.7, 0.3.8, 0.3.9, 0.4.1, 0.4.2
- 前置: `fnd-theme-typography-accent-tokens`, `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v027-keep-top-search-with-bottom-search` (partial/S/low): Setting: keep the top search bar when bottom search is on
  - `v030-list-header-collapse-split` (partial/S/low): List header collapse setting split, default SHOW_AT_TOP_ONLY
  - `v034b2-tv-style-cover-play-button` (partial/S/low): PiliPlus TV-style cover play button (later-release deltas only)
  - `v037-surface-dark-theme-consistency` (partial/M/low): isAppInDarkTheme sweep across surfaces (Desktop follows Windows mode otherwise)
  - `v037-material-expressive-sidebar-skin-timepicker` (missing/M/low): M3 Expressive sidebar, skin catalog, time input (glass-off branch only)
  - `v038-global-drag-feedback` (partial/S/low): Global drag feedback switch (config side carried, no Windows UI toggle)
  - `v038-settings-entry-colorful-icons` (missing/M/low): Colourful settings/plugin entry icons (AppEntryIconPalette, Miuix only)
  - `v039-sidebar-icon-colors` (missing/M/low): Sidebar entry icon colours (needs ProvideAppEntryIconColors)
  - `v041-vote-card-colors` (missing/S/low): Vote card colour pairing + contrast fallback
  - `v042-bangumi-hero-backdrop` (missing/M/low): Bangumi hero backdrop (phone narrow layout only; depends on SpaceBannerFusionEffect)
  - `v042-qr-authorization-layout` (missing/S/low): QR authorisation dialog text hierarchy
  - `v042-about-developers` (missing/S/low): About: developers list
- 另外涉及 (主切片在别处): `v037-dynamic-live-interaction-theme`
- 上游文件: app/.../feature/settings/screen/BottomBarSettingsScreen.kt (keep top search); app/.../feature/settings/screen/AnimationSettingsScreen.kt (drag feedback); app/.../feature/home/components/MaterialExpressiveSideBar.kt; design-system/.../core/ui/AppEntryIconPalette.kt consumers; app/.../feature/home/components/MineSideDrawer.kt; app/.../feature/video/ui/components/VideoCommentVoteCard.kt; app/.../feature/login/OfficialQrAuthorizationContent.kt; app/.../feature/settings/ui/SettingsSections.kt (about developers)
- 风险:
  - Bundle of independent cosmetic items, each with its own generator; v042-bangumi-hero-backdrop is phone-narrow only and depends on SpaceBannerFusionEffect (recommend skip).
  - isAppInDarkTheme sweep: Compose Desktop's system dark theme follows the Windows app mode, so surfaces misdetect darkness when the BiliPai theme differs (inference).
- **第一步**: S: add a global_drag_feedback_enabled switch to the Windows appearance settings (config side already carried) and the share catalog.

#### 50. `msg-inbox-chat-repin`: Inbox/message feed skeletons and parallel loading

- 成本 **L** (约 7 人天，启发式) | 用户价值 **low** | 发布: 0.3.6
- 前置: `fnd-core-data-relocated-policies`, `space-profile-content-pager`
- 覆盖条目:
  - `v036-skeleton-message-topic-space-unported` (missing/L/low): Message/space skeletons; InboxViewModel parallel loading
- 上游文件: app/.../feature/message/InboxViewModel.kt; app/.../feature/message/feed/MessageFeedCommon.kt; app/.../feature/message/feed/AtMeScreen.kt; app/.../feature/space/SpaceLoadingSkeleton.kt
- 风险:
  - Message screens change again in 0.3.8: re-pin to the v0.4.6 blobs, not 0.3.6.
  - extract-upstream-message-pages.py rewrites the v0.2.5 VM with owner admission.
- **第一步**: S: port only the InboxViewModel parallel unread/sessions loading (the single non-visual part).

#### 51. `plr-portrait-story-pager-repin`: Portrait/Story pager family re-pin

- 成本 **L** (约 7 人天，启发式) | 用户价值 **low** | 发布: 0.2.7, 0.2.9, 0.3.5, 0.3.7, 0.4.0
- 前置: `plr-overlay-and-controlbar-repin`, `fnd-design-system-leaf-completion`
- 覆盖条目:
  - `v027-portrait-fullscreen-declarations` (partial/S/low): Portrait fullscreen shows creation declarations (PortraitFullscreenOverlay)
  - `v029-portrait-interaction-white-icons` (missing/S/low): Portrait interaction bar: plain white icons
  - `v029-portrait-background-continue` (missing/S/low): Portrait pager keeps playing in background when policy allows
  - `v035-portrait-pager-preload-identity` (missing/M/low): Portrait pager preload queue + cid-aware media identity
  - `v037-portrait-glass-theme-colors-unported` (missing/M/low): Portrait glass controls on theme roles
  - `v040-portrait-controls-autohide-pages-wave` (missing/S/low): Portrait controls 4 s auto-hide
- 上游文件: app/.../feature/video/ui/pager/PortraitVideoPager.kt; app/.../feature/video/ui/overlay/PortraitFullscreenOverlay.kt; app/.../feature/video/ui/overlay/PortraitInteractionBar.kt; app/.../feature/video/ui/overlay/PortraitBottomInputBar.kt; app/.../feature/video/ui/pager/PortraitVideoLoadPolicy.kt; app/.../feature/video/ui/pager/PortraitPagerSwitchPolicy.kt; app/.../feature/video/ui/pager/PortraitControlsVisibilityPolicy.kt (new); app/.../feature/video/ui/pager/PortraitWifiPreloadQueue.kt (new)
- 风险:
  - The pager changed in ten releases (0.3.0 to 0.4.6): re-pin once to the latest version.
  - tools/extract-upstream-video-fullscreen-pager.py pins 79e8; Wi-Fi gating is Android-specific (make it unconditional on desktop).
  - Vertical feed is a minor surface on a 32-inch monitor.
- **第一步**: S: port only the cid-aware media-id match (PortraitPagerSwitchPolicy) for multi-part videos opened from the vertical feed.

#### 52. `nav-card-transition-family`: Card/return transition family (decision-gated)

- 成本 **XL** (约 15 人天，启发式) | 用户价值 **low** | 发布: 0.3.0, 0.3.4-beta.2, 0.3.5, 0.3.8, 0.3.9, 0.4.0 (+0.4.1), 0.4.5, 0.4.6
- 前置: `chrome-dock-consumers`
- 覆盖条目:
  - `v030-video-return-transition` (missing/L/low): Video return transition rework (stable size, deferred loading)
  - `v034b2-card-return-source-session` (unknown/M/medium): Card return: nested link keeps its parent source card
  - `v035-detail-transition-gating` (missing/M/low): Detail entry/return gating, per-frame reductions
  - `v038-card-transition-fixes` (missing/L/medium): Card transition: per-frame cost, white mask on return, source handoff
  - `v039-card-return-handoff` (missing/L/low): Card/mini-bar return handoff, large-screen host
  - `v040-video-card-handoff-return-seams` (missing/L/low): Card open/return seams, large-screen player slot black fill
  - `v045-video-card-landing-detail-return` (missing/L/low): Card landing bounce / return cover scaling / background work deferral
  - `v046-card-landing-refinements` (missing/M/low): Card landing refinements per layout branch
- 上游文件: app/.../core/ui/transition/VideoCardNativeSnapshot.kt; app/.../core/ui/transition/VideoCardReturnTimeline.kt; app/.../core/ui/transition/VideoCardLandingFeedback*.kt; app/.../feature/video/screen/VideoDetailReturnMediaLayout.kt; app/.../feature/video/screen/LargeScreenVideoPlayerTransitionHost.kt; app/.../navigation3/BiliPaiNavDisplayHost.kt; app/.../navigation3/BiliPaiVideoSourcePolicy.kt; desktop/src/.../ui/DesktopWindowsVideoCardReturnContent.kt
- 风险:
  - DOCS CONFLICT: README.md:365 and WINDOWS.md:151 say card transitions were removed; PARITY.md:355,363 say the 778-line card transition and survivor host are integrated. Whether the transition runs is UNKNOWN.
  - Compose cannot draw above the native mpv HWND; the Windows return host is desktop-owned (DesktopWindowsVideoCardReturnHost/Content/Snapshot).
  - About 20 hash-pinned files across eight entries; Root-owned survivor/frozen-snapshot code.
- **第一步**: Root decision memo: are card transitions in scope? If yes, read DesktopWindowsVideoCardReturnContent.kt to list which transition files the mounted path really uses.
- 需协调: Root (navigation/survivor code owner); Large-screen UI task

#### 53. `perf-compose-narrowing`: Compose recomposition/offscreen narrowing family

- 成本 **L** (约 7 人天，启发式) | 用户价值 **low** | 发布: 0.3.1, 0.3.2, 0.3.4-beta.1
- 前置: 无
- 覆盖条目:
  - `v031-offscreen-page-gating` (partial/M/low): Offscreen page request/image gating, pager preload scheduler
  - `v032-foreground-work-narrowing` (missing/L/low): Foreground smoothness pass (state narrowing, drawWithCache)
  - `v034b1-ui-scroll-recomposition-narrowing` (missing/M/low): Home header scroll connection / recomposition narrowing
- 上游文件: app/.../feature/home/HomeCategoryPage.kt; app/.../feature/home/HomeScreen.kt; app/.../feature/home/HomeHeaderScrollConnection.kt; app/.../navigation/BottomPagerPreloadScheduler.kt; app/.../core/ui/components/PageAwareAsyncImage.kt (emitted)
- 风险:
  - Gains were unmeasured upstream; Compose Desktop benefits only where hot paths match; the recipes are hash-pinned.
  - Defer until a Windows-side frame-time measurement shows a hitch.
- **第一步**: Add a Windows-side frame-time probe in a Python-visible diagnostics note first; adopt nothing without a measured hitch.

#### 54. `plr-control-center-motion`: Control-center drawer motion and danmaku panel host

- 成本 **L** (约 7 人天，启发式) | 用户价值 **low** | 发布: 0.3.8, 0.4.2
- 前置: `fnd-sheet-motion-and-glyph`, `plr-overlay-and-controlbar-repin`
- 覆盖条目:
  - `v038-landscape-drawer-sync-title-capsule` (partial/M/low): Landscape drawer width sync + title capsule shrink
  - `v042-danmaku-panel-appearance` (missing/M/low): Danmaku settings panel Host/Appearance split
  - `v042-control-center-motion` (missing/L/low): Control-center drawer motion (ControlCenterMotionSpec, VideoControlCenterMotion)
- 上游文件: app/.../feature/video/ui/components/ControlCenterMotionSpec.kt; app/.../feature/video/ui/components/VideoControlCenterMotion.kt; app/.../feature/video/ui/components/DanmakuSettingsPanelHost.kt; app/.../feature/video/ui/components/DanmakuSettingsPanelAppearance.kt; app/.../feature/video/ui/components/LandscapeSidePanel.kt; app/.../feature/video/ui/components/VideoSettingsPanel.kt
- 风险:
  - Touch drag overscroll/rubber-band polish; hard dependency only if LandscapeSidePanel/VideoSettingsPanel/DanmakuSettingsPanel are forced to v0.4.6 (they reference these files).
  - Entangled with the landscape drawer drag motion.
- **第一步**: Carry ControlCenterMotionSpec, VideoControlCenterMotion and DanmakuSettingsPanelHost as dependency definers (not enabled) so later re-pins resolve.

#### 55. `plr-holder-collapse-family`: Detail holder collapse/gesture family (phone-style)

- 成本 **L** (约 7 人天，启发式) | 用户价值 **low** | 发布: 0.3.0, 0.4.2, 0.4.6, post-0.4.6
- 前置: `vid-info-section-repin`, `cmt-closure-merge-and-polish`
- 覆盖条目:
  - `v030-detail-chrome-hidden-paused-collapsed` (missing/M/low): Hidden paused player counts as collapsed (phone detail)
  - `v030-detail-glass-gated-chrome-blur` (missing/S/low): Detail chrome keeps blur with liquid glass
  - `v042-detail-cover-169-skeleton` (partial/S/low): Detail cover 16:9 + skeleton animation flag
  - `v042-fullscreen-exit-scroll-recovery` (missing/M/low): Fullscreen exit terminates stale list/pager scrolls
  - `v042-compact-comment-autopause` (missing/S/low): Compact comment auto-pause decision fix
  - `v042-glass-pager-vertical-priority` (missing/M/low): Vertical-priority pager gesture arbitration
  - `v046-collapse-height-shared` (missing/L/low): Shared collapse height across intro/comments (phone-style gesture)
  - `post046-shared-collapse-detail-layouts` (partial/M/low): Collapse semantics + compact comment titles (d6bf8b16 = upstream HEAD)
- 上游文件: app/.../feature/video/screen/VideoDetailScreenStateHolder.kt; app/.../feature/video/screen/VideoDetailInlineCollapseState.kt; app/.../feature/video/screen/PlayerCollapseAutoPausePolicy.kt; app/.../feature/video/screen/VideoDetailInlineScrollRecovery.kt (new); app/.../core/ui/common/VerticalPriorityPagerGesture.kt; app/.../feature/video/screen/TabletVideoLayout.kt
- 风险:
  - Phone/narrow-window behaviour; many hash-pinned holder/tablet generators.
  - Only the skeleton-animation flag plausibly matters on a maximised 4K window; decide per hunk whether a Windows repro exists.
- **第一步**: Reproduce-or-skip: for each of the eight entries decide whether a Windows repro exists; carry only those with one.

#### 56. `wallpaper-surfaces-consumers`: Wallpaper across pages, settings glass entry and re-tone editor

- 成本 **XL** (约 15 人天，启发式) | 用户价值 **low** | 发布: 0.4.0 (+0.4.1), 0.4.1, 0.4.2
- 前置: `wallpaper-foundation-and-adjust`, `card-geometry-lists-and-grid`
- 覆盖条目:
  - `v040-wallpaper-global-surfaces` (partial/XL/low): Wallpaper across home/dynamic/profile/settings/messages (root backdrop layer)
  - `v041-settings-search-glass-entry` (missing/S/low): Settings search entry glass sampling (needs global wallpaper source)
  - `v042-wallpaper-retone-editor` (partial/L/low): Wallpaper re-tone editor (needs a Windows desktop-wallpaper seed provider)
- 上游文件: design-system/.../core/ui/wallpaper/GlobalWallpaperBackdrop.kt (5af9cd8d viewport rebuild); app/.../feature/home/HomeWallpaperBackdrop.kt; app/.../feature/home/components/ProgressiveTopChrome.kt; app/.../feature/message/MessageAppScaffold.kt; app/.../feature/settings/screen/SettingsSearchUi.kt; app/.../feature/settings/WallpaperPaletteEditor.kt; app/.../core/theme/SystemWallpaperSeed.kt
- 风险:
  - A new Root-level wallpaper recording layer for the Windows window (inference): needs coordination with DesktopOriginalRootStack/DesktopWindowsNavigationDisplay and native video window z-order.
  - The re-tone editor needs a new Windows desktop-wallpaper seed reader (platform work); recommend skipping.
  - Only Favourites hosts a global wallpaper source today (DesktopFavoriteWallpaperBackdrop.kt:33-34).
- **第一步**: Decorative; schedule after the foundation slice. First: re-point the Favourites-only source (DesktopFavoriteWallpaperBackdrop.kt) at a Root-level provider behind a feature flag.
- 需协调: Root (root stack / native video window z-order); Large-screen UI task

#### 57. `rss-reader-page-repin`: RSS reader page re-pin: wallpaper, chrome, instant article reader

- 成本 **L** (约 7 人天，启发式) | 用户价值 **low** | 发布: 0.2.6, 0.3.1, 0.4.5
- 前置: `wallpaper-foundation-and-adjust`, `rss-readability-public-network`, `rss-referer-opml-groups`
- 覆盖条目:
  - `v026-subscription-card-frost` (missing/M/low): RSS subscription cards reuse the home wallpaper frost
  - `v026-article-reading-wallpaper-uri` (missing/M/low): RSS reader dedicated wallpaper URI (fallback to home wallpaper)
  - `v031-rss-article-wallpaper-picker` (missing/M/low): RSS reader wallpaper picker sheet
  - `v045-instant-article-reader` (missing/L/low): Instant article reader route for external links
  - `v045-rss-reader-chrome-home-preview-dock` (missing/M/low): RSS reader chrome, home preview robustness, dock scroll sync
- 上游文件: app/.../feature/home/subscription/SubscriptionFeedPage.kt (pin blob 584155a4); app/.../feature/profile/SplashWallpaperPickerSheet.kt (SUBSCRIPTION_ARTICLE); app/.../feature/web/InstantArticleScreen.kt; app/.../feature/web/InstantArticleViewModel.kt; app/.../feature/web/InstantArticlePolicy.kt; app/.../navigation/AppNavigation.kt (+87 route); desktop/src/.../ui/DesktopSubscriptionPageBindings.kt
- 风险:
  - One generator (extract-upstream-subscription-page.py SOURCE_PINS) but a large page; the instant reader adds a route, a Root-owned ViewModel and link hooks in DynamicCard/ReplyComponents.
  - A desktop user usually prefers the real browser for external pages (value is low).
- **第一步**: Re-point extract-upstream-subscription-page.py from the 79e8 pin to the v0.4.6 SubscriptionFeedPage with the Windows page bindings and add a reading-block regression test.

### T4: Defer / decision only

#### 58. `dep-haze-2-0-1-decision`: Haze 2.0.1 upgrade versus per-call-site adaptation (decision)

- 成本 **L** (约 7 人天，启发式) | 用户价值 **low** | 发布: 0.3.1
- 前置: 无
- 覆盖条目:
  - `v031-haze-2-0-1-dependency-bump` (missing/L/low): Haze 2.0.0-alpha03 -> 2.0.1 API surface (decision item)
- 上游文件: gradle/libs.versions.toml; app/.../core/ui/blur/UnifiedBlur.kt; app/.../core/ui/blur/HazeEffectCompat.kt; desktop/build.gradle.kts:2383-2386 (haze 2.0.0-alpha03)
- 风险:
  - Verified: desktop pins haze-jvm/utils/blur/blur-materials 2.0.0-alpha03; desktop-JVM artifact availability for 2.0.1 and Compose 1.12.1 compatibility were NOT checked.
  - Later blur/glass files keep needing per-call-site adaptation unless Windows upgrades.
- **第一步**: Read-only: check the local Gradle caches for haze 2.0.1 desktop artifacts and write a short ADR (upgrade vs adapt); do not bump versions to mirror Android.
- 需协调: Root (dependency policy)

#### 59. `audio-waveform-atmosphere-deferred`: Audio waveform / music atmosphere (recommend defer)

- 成本 **XL** (约 15 人天，启发式) | 用户价值 **low** | 发布: 0.4.5
- 前置: 无
- 覆盖条目:
  - `v045-audio-waveform-music-atmosphere` (missing/XL/low): Audio waveform / music atmosphere (needs an mpv PCM tap; WASAPI risk)
- 上游文件: app/.../core/player/waveform/AudioWaveformSource.kt; app/.../core/player/waveform/WaveformAudioSink.kt; app/.../feature/audio/screen/MusicAtmosphereBackground.kt; desktop/src/.../player/MpvPlayer.kt
- 风险:
  - Upstream taps a Media3 AudioSink; Windows plays through mpv and WASAPI, so this needs a new mpv PCM tap that must coexist with exclusive/TOSLINK output (user priority).
  - Decorative and off by default upstream.
- **第一步**: None recommended until Root approves an audio-analysis tap that cannot affect the WASAPI path.
- 需协调: Root (audio output owner)

## 5. 需要 Root/用户决定 / Decisions needed

| # | 问题 | 为什么 | 决定方 |
|---|---|---|---|
| D1 | Are the video-card open/return transitions in scope on Windows? | Docs conflict: README.md:365 and WINDOWS.md:151 say card transitions were removed; PARITY.md:355,363 say the original 778-line card transition and survivor host are integrated. Gates nav-card-transition-family (XL) and parts of vid-info-section-repin, chrome-dock-consumers and plr-holder-collapse-family. | Root |
| D2 | Adopt the 0.3.7 Material baseline typography (smaller body text) on a 32-inch 4K window, or keep/scale the current sizes? | fnd-theme-typography-accent-tokens changes every text line; readability on 4K is a stated product concern. | Root + large-screen UI task |
| D3 | Adopt the 0.3.8 background image-cache budgets (24/8 to 64/32 MiB, wallpaper palette no longer cleared)? | Conflicts with the user's lower-memory request; only the budget part of fnd-motion-gate-and-resource-hooks is affected. | Root |
| D4 | Merge the unmerged comment-closure branch (desktop/source261-comment-closure-compile-repair)? | It carries the whole v046 comment list/thread/Bangumi comment panel and two compat shims; Linux compile only, no Windows validation. Only Root merges. | Root |
| D5 | Does the large-screen UI task keep the bottom dock or replace it with a sidebar? | Decides whether chrome-dock-consumers (L) is worth doing at all and what the home chrome generators should pin. | Large-screen UI task |
| D6 | Haze: upgrade 2.0.0-alpha03 to 2.0.1 or keep adapting call sites? | Root of the blur/glass cascade; desktop-JVM artifact availability and Compose 1.12.1 compatibility were not checked. | Root |
| D7 | Allow new Gradle dependencies readability4j 1.0.8 and jsoup >= 1.23.2 for the RSS slice? | Heavy validation is deferred; dependency resolution cannot be tested offline. | Root |
| D8 | Implement the audio waveform / music atmosphere effect? | Needs a new mpv PCM tap that must not affect WASAPI exclusive/TOSLINK output. Recommendation: defer. | Root + user |
| D9 | Scope of the shared wallpaper subsystem (foundation/adjust sheet yes; Root-level backdrop for every page?). | XL decorative work that adds a Root-level recording layer above the native video window constraint. | Root + user |
| D10 | Dynamic category merge removes the persisted key dynamic_all_tab_horizontal_user_list_visible and the 'up' tab id: accept the migration? | Needs up-to-all mapping in Android-backup import and test updates. | Root |
| D11 | Legal/compliance review of the PiliPlus (GPL-3.0) font and icon already carried in the Windows build. | Notices are not in THIRD_PARTY_NOTICES.md or desktop/third-party according to the reviewer; I did not inspect the packaged notice manifest. Not legal advice. | User/Root |
| D12 | When to batch compile/test validation for the source-only slices. | Policy (resource-policy.json: heavyBuildCurrentlyDeferred, 4 GiB heap, one worker) defers builds; each slice here is source + Python tests only, so validation debt accumulates. | Root |

## 6. 不进入路线图的条目 / Excluded entries

### 6.1 Android-only / TV / 版本号 / 合并 / 测试 / 构建 (not-applicable, 73)

分类: android-only 30, version-bookkeeping 17, superseded-by-desktop 8, tv-only 5, build-ci 4, tests-only 4, merge-bookkeeping 3, net-zero 2

| 条目 | 发布 | 类别 | 原因 |
|---|---|---|---|
| `v026-tv-ui-watch-progress-hero` | 0.2.6 | tv-only | Android TV module (app-tv); shared pieces it motivated are tracked in v026-shared-progress-policy-core. |
| `v026-release-metadata` | 0.2.6 | version-bookkeeping | Android Gradle version metadata only; Windows version is owned by Root. |
| `v027-release-metadata` | 0.2.7 | version-bookkeeping | versionCode metadata only. |
| `v028-release-metadata` | 0.2.8 | version-bookkeeping | CHANGELOG/version metadata only (e5a6b59a is also the pin of the v027 slices). |
| `v028-home-dock-wallpaper-backdrop-revert` | 0.2.8 | net-zero | Symbol added in f7123aa2 and removed in 5dd628c8; absent at the pin and at v0.4.6. |
| `v028-hide-paused-video-comment-scroll` | 0.2.8 | android-only | Phone-portrait detail gesture; Windows uses a fixed physical video leaf. |
| `v028-startup-animation-style` | 0.2.8 | android-only | Android splash; the Windows port deliberately deletes the splash (README.md:515, :527). |
| `v028-settings-adaptive-pane` | 0.2.8 | android-only | Tablet settings pane added in 0.2.8 and removed in 0.2.9; FLAG_BLUR_BEHIND is Android-only (Windows has v037-window-modal-blur). |
| `v028-status-bar-ambient-blur` | 0.2.8 | android-only | Android status-bar strip; Windows has no status bar (own v035-ambient slice). |
| `v028-android-lifecycle-miniplayer-snapshot` | 0.2.8 | android-only | PixelCopy snapshot, MiniPlayerManager overlay, Lifecycle ON_PAUSE recovery, phone landscape sidebar. |
| `v029-statusbar-ambient-blur-fix` | 0.2.9 | android-only | Android status bar (ImmersiveStatusBarBackdrop); archive already carries the fix. |
| `v029-settings-adaptive-pane-revert` | 0.2.9 | android-only | Tablet/foldable settings navigation reverted upstream; Windows settings are desktop-owned. |
| `v029-release-housekeeping` | 0.2.9 | version-bookkeeping | versionCode and CHANGELOG only. |
| `v029-tv-app-overhaul` | 0.2.9 | tv-only | app-tv overhaul and TV-only core-player stores. |
| `v029-update-checker-release-version-policy` | 0.2.9 | android-only | Android APK update checker; Windows has its own update subsystem (0.2.427.x). |
| `v029-build-infra-tv-scripts` | 0.2.9 | build-ci | Gradle module registration and TV architecture scripts. |
| `v030-tv-hero-carousel-container` | 0.3.0 | tv-only | app-tv only (first containing tag is v0.2.9). |
| `v030-android-splash-brand-text` | 0.3.0 | android-only | Android startup overlay text; no Windows splash. |
| `v030-live-control-sizing` | 0.3.0 | superseded-by-desktop | Phone touch-target sizing; Windows live room UI is desktop-owned. |
| `v030-pr872-live-merge-bookkeeping` | 0.3.0 | merge-bookkeeping | Merge commits; substance is in the v030-live-* entries. |
| `v030-richeditor-clipboard-compat` | 0.3.0 | android-only | Android clipboard crash workaround; desktop uses no RichTextEditor. |
| `v031-android-splash-wallpaper-wait` | 0.3.1 | android-only | Android system splash exit wait. |
| `v031-predictive-back-guard` | 0.3.1 | android-only | Android predictive-back driver (miuix-navigation). |
| `v031-release-bumps` | 0.3.1 | version-bookkeeping | Version/changelog metadata. |
| `v031-pr873-merge-bookkeeping` | 0.3.1 | merge-bookkeeping | Merge commits; substance counted in coin/2dp-gap entries. |
| `v031-tv-comments-page` | 0.3.1 | tv-only | app-tv comments page. |
| `v032-android-service-surface-build` | 0.3.2 | superseded-by-desktop | PlaybackService/MiniPlayerManager/texture-surface/Android 17 diagnostics; Windows media service is desktop-owned and replaces prefersTextureVideoSurface with false. |
| `v032-tv-dynamic-color` | 0.3.2 | tv-only | app-tv dynamic colour. |
| `v032-release-bump` | 0.3.2 | version-bookkeeping | Version/changelog metadata. |
| `v033-android-multiwindow-service-revert` | 0.3.3 | android-only | Freeform/split-screen orientation and Android service revert. |
| `v034b1-compose-owner-guard` | 0.3.4-beta.1 | build-ci | Build-time ASM rewrite of the Compose Android AAR; desktop uses Compose Desktop jars. |
| `v034-release-bookkeeping` | 0.3.4-beta.1 | version-bookkeeping | Version, docs and a WorkManager R8 rule. |
| `v034b1-download-manager-async-init` | 0.3.4-beta.1 | superseded-by-desktop | Android WorkManager/Application lifecycle; Windows owns DesktopDownloadManager (own queue and mutex). |
| `v034b1-bangumi-swipe-fullscreen-gestures` | 0.3.4-beta.1 | android-only | Phone drag zones driving AudioManager volume and window brightness. |
| `v034b1-webview-navigation-hardening` | 0.3.4-beta.1 | android-only | Android WebView (RenderProcessGone, BrowserWebView); the host-boundary rule is done in v034b1-link-host-boundary. |
| `v035-restore-merge-meta` | 0.3.5 | net-zero | Revert/restore churn; read the net diff instead. |
| `v035-release-bookkeeping` | 0.3.5 | version-bookkeeping | Version bumps and merge. |
| `v035-ci-semantic-lint` | 0.3.5 | build-ci | Android Lint module and CI workflows. |
| `v035-home-md3-refresh-indicator` | 0.3.5 | android-only | Touch pull-to-refresh polish; desktop home uses its own refresh path. |
| `v036-version-bumps-changelog` | 0.3.6 | version-bookkeeping | versionCode/changelog. |
| `v036-test-only-unknown-seeds` | 0.3.6 | tests-only | androidTest and VM tests only; Windows has VideoInteractionStatusCacheTest equivalents. |
| `v037-compose-detached-owner-guard-android` | 0.3.7 | build-ci | AGP bytecode instrumentation. |
| `v037-background-paused-release-android` | 0.3.7 | android-only | MiniPlayerManager/ExoPlayer low-memory/PiP lifecycle; Windows background playback is desktop-owned. |
| `v037-release-bump` | 0.3.7 | version-bookkeeping | versionCode/changelog. |
| `v038-shared-danmaku-session` | 0.3.8 | superseded-by-desktop | Threads the Android DanmakuManager through layouts; Windows danmaku is desktop-owned (DanmakuOverlay/DanmakuScheduler). |
| `v038-portrait-compact-level-overlay` | 0.3.8 | android-only | Phone portrait brightness/volume overlay. |
| `v038-profile-avatar-fusion` | 0.3.8 | android-only | Front-camera cutout fusion (Android Views); GPL-3.0 reference notice only matters if ever ported. |
| `v038-account-parse-memoize` | 0.3.8 | superseded-by-desktop | Android SharedPreferences/Keystore cost; Windows owns the session store and DesktopPlaybackCache. |
| `v038-frame-drop-guard-reset` | 0.3.8 | android-only | Android JankStats frame callbacks. |
| `v038-bottom-pager-preload` | 0.3.8 | android-only | Phone horizontal pager cancelled in favour of a fixed sidebar (WINDOWS.md:151). |
| `v038-foreground-return-benchmark` | 0.3.8 | tests-only | Android Macrobenchmark. |
| `v038-merge-commits` | 0.3.8 | merge-bookkeeping | Merge commits with no own content. |
| `v038-docs-release-housekeeping` | 0.3.8 | version-bookkeeping | Docs, notices and version bump. |
| `v039-space-banner-camera-fusion` | 0.3.9 | android-only | Punch-hole camera effect on banners. |
| `v039-plugin-icon-resource` | 0.3.9 | android-only | Android drawable resource name fix. |
| `v039-release-bookkeeping` | 0.3.9 | version-bookkeeping | Version, changelog and a test import. |
| `v041-dynamic-top-bar-sidebar-layout` | 0.4.1 | superseded-by-desktop | Phone top-bar/drawer layout; Windows uses DesktopDynamicTabsHost. |
| `v040-release-prep-and-docs` | 0.4.0 (+0.4.1) | version-bookkeeping | Version, docs and AGENTS rules. |
| `v041-workmanager-namespace-guard` | 0.4.1 | android-only | Android WorkManager bytecode guard. |
| `v040-android-instrumented-and-structure-tests` | 0.4.0 (+0.4.1) | tests-only | androidTest and Android source-text structure tests. |
| `v040-android-predictive-back-nav-host` | 0.4.0 (+0.4.1) | android-only | Predictive-back card return and Navigation3 host; Windows navigation is desktop-owned. |
| `v042-settings-core-combine-order` | 0.4.2 | android-only | Android SettingsViewModel combine() index bug; Windows keeps appearance prefs in DesktopThemePrefs. |
| `v042-phone-orientation-lock` | 0.4.2 | android-only | ActivityInfo orientation lock (Root already excluded it). |
| `v042-playback-notification-color` | 0.4.2 | android-only | Android media notification accent. |
| `v042-release-chores` | 0.4.2 | version-bookkeeping | Version and changelog. |
| `v043-release-bump` | 0.4.3 | version-bookkeeping | Version and changelog. |
| `v045-external-original-webview-route` | 0.4.5 | superseded-by-desktop | Android WebView first-load route; Windows opens originals itself (DesktopShell.kt:1265). |
| `v045-android-instrumentation-fixtures` | 0.4.5 | tests-only | androidTest fixtures. |
| `v045-native-crash-diagnostics` | 0.4.5 | android-only | ApplicationExitInfo tombstone parsing. |
| `v045-wallpaper-color-compat` | 0.4.5 | superseded-by-desktop | WallpaperManager colours; Windows has its own palette pipeline. |
| `v045-release-bump` | 0.4.5 | version-bookkeeping | Version and changelog. |
| `v046-bundled-settings-profile` | 0.4.6 | android-only | Android first-install profile; it also turns liquid glass OFF by default, contradicting the Windows default. |
| `v046-release-bump` | 0.4.6 | version-bookkeeping | Version and changelog. |

### 6.2 applies-with-adaptation 但已被桌面自有代码取代 (5)

| 条目 | 发布 | 原因 |
|---|---|---|
| `v027-local-danmaku-append-timeline` | 0.2.7 | Fixed by desktop DanmakuScheduler/DanmakuOverlay (README.md:407, tests DanmakuLocalAppendTimelineTest). |
| `v028-player-chrome-compact` | 0.2.8 | Windows owns its control bar (DesktopWindowsVideoControlBar); phone dp tweaks do not map. |
| `v029-playback-stale-recovery-state-sync` | 0.2.9 | MPV recovery tickets (DesktopOriginalPlaybackRecovery, DesktopPlaybackRecoveryBudget) cover the generation semantics. |
| `v034b1-settings-ia-reorg` | 0.3.4-beta.1 | Windows settings tree is desktop-owned (DesktopSettingsTree); recommend not adopting the upstream layout. |
| `v037-settings-share-transfer-actions` | 0.3.7 | Windows owns settings share/import/export (DesktopSettingsShareDialog/Service/Catalog). |

### 6.3 适用性不明，未能分类 (4)

| 条目 | 发布 | 状态 | 为什么不明 |
|---|---|---|---|
| `v031-home-search-pill-36dp` | 0.3.1 | unknown | Windows header is not the Android HomeHeader composable; probably moot with liquid glass on. |
| `v038-large-screen-return-handoff` | 0.3.8 | missing | Depends on whether the large-screen UI task mounts the original LargeScreenVideoLayout and card transitions are active. |
| `v038-extra-large-three-pane-intro` | 0.3.8 | missing | Condition is isExtraLargeScreen, which a 4K 32-inch window may reach; depends on the mounted layout. |
| `v045-playback-page-ownership` | 0.4.5 | unknown | ExoPlayer instance ownership; Windows has one MPV session owned by Root; needs a regression test, not a port. |

### 6.4 已完成 (done-at-v046, 81)

这些条目在当前树中有携带的上游文件或生成器输出，源码层面至少与该改动一样新 (不代表运行验证)。ID 列表见 JSON 的 `excluded.doneAtV046SourceLevelIds`。

## 7. 无法确定 / What I could not determine

- Runtime behaviour of everything: all evidence is static (git blobs, generators, greps). Nothing was compiled or run, and every 'done' or 'partial' claim in the inputs is source-level only.
- Whether the Windows video-card open/return transition is actually mounted (docs conflict README.md:365 / WINDOWS.md:151 versus PARITY.md:355,363).
- Which Space screen is mounted on Windows (SpaceViewModel-based original versus the desktop-owned DesktopCompleteSpaceScreen); it decides the cost of space-profile-content-pager and part of msg-inbox-chat-repin.
- Whether an App access token is available to the Windows tray-notification poller (needed by v042-dynamic-notification-accuracy).
- Whether desktop/build/generated is current: several 'missing' verdicts (for example the card menu emoji) were read from generated output that may be older than the generator inputs.
- Whether the packaged notice manifest already ships the AndroidLiquidGlass/PiliPlus license texts through another path.
- Whether desktop-JVM artifacts for Haze 2.0.1 exist and are compatible with Compose 1.12.1 (not checkable offline).
- Whether Windows mouse input reaches detectPlayerTapGestures at runtime, and whether glass docks render correctly over the native mpv HWND canvas.
- Whether phone-width layouts are reachable from a normal Windows window (the phone-detail fixes are classified low on that inference only).
- Whether the related-video list on Windows really always waits for playback READY (inferred from the bandwidth=null binding and an admission comment).
- Whether the Android SponsorBlock server still accepts the old query-string vote.
- Four entries have unclear applicability and are NOT in the roadmap: v031-home-search-pill-36dp, v038-large-screen-return-handoff, v038-extra-large-three-pane-intro, v045-playback-page-ownership.
- Nine kept entries have status 'unknown'; the audit-unknown-entries slice exists to settle them (list in the JSON).
- Effort numbers are my heuristic (S=1, M=3, L=7, XL=15 person-days of source-only work); I did not re-read the commit hunks, and compile, Windows validation and real-account verification are not included.
- I did not re-derive the per-release classifications: they come from the task text (11 batch results). Several batch summaries report counts that differ slightly from their own rows (for example 0.2.6-0.2.8 says 21 done, its rows list 19); I used the rows.
- Hunk-level overlap between entries is not resolved: where several entries touch the same file I assigned a single primary slice and listed the others as 'also touches'.

## 8. 已核实的事实 / Verified in the tree

以下是我自己在只读模式下核对过的 (其余结论继承自输入的分类文本，未重新推导):

- All 195 distinct commit prefixes used in entries resolve with git rev-parse and lie inside 79e8fa30..d6bf8b16 (339 commits in range).
- Counts reconcile: 374 entries = 211 kept + 73 not-applicable + 5 superseded-by-desktop (applies-with-adaptation) + 4 unclear + 81 done.
- userVipPink has no definer in desktop/upstream-slices, desktop/src, desktop/tools or desktop/third-party (grep); it is called at five sites in three carried slices (v040-reply-medal ReplyComponents.kt:1634,1940; v041-dynamic-comment-identity DynamicCommentSheet.kt:1349,1566; v041-video-status-labels SpaceScreen.kt:3120). No UserVipBadge.kt exists anywhere in those trees.
- Carried AppSheetComponents (v037-window-modal-blur) declares AppBottomSheetMotion at line 372 but contains none of reducedMotion, dragSettleSpec, onDismissStarted.
- v030-live-stream slice LiveRepository.kt:1004 still sends type.name.lowercase() for the contribution-rank request.
- DesktopRepository.kt:96-99 applies Referer/Origin https://www.bilibili.com to every request host.
- DesktopDynamicTimeline.kt:183-184 shows the empty-state text whenever !busy && displayed.isEmpty() && error == null (no initialized guard).
- DesktopOriginalVideoOwnerNetworkBinding.kt:27 passes downstreamBandwidthKbps = null.
- desktop/build.gradle.kts:2383-2386 pins Haze 2.0.0-alpha03.
- Commit e46fe930d (adds the DesktopUserVipPink shim) exists, and the v046-comment-list slice files (BangumiCommentPanel, BangumiPlayerContent, BangumiPlayerScreen, ...) exist on branch desktop/source261-comment-closure-compile-repair, not on the integration HEAD 610218f8.
- Generator/test naming convention: desktop/tools/v04x_<name>.py with desktop/tools/tests/test_v04x_<name>_source.py; 135 slice directories under desktop/upstream-slices; .coordination/resource-policy.json says heavyBuildCurrentlyDeferred=true, buildExecuted=false.

## 9. 工作量说明 / Effort note

- 权重: S=1, M=3, L=7, XL=15 人天 (仅源码 + 生成器 + 桌面适配器 + Python 测试)。切片成本是我对整个切片的估计，不是条目成本之和 (共享文件的 re-pin 只做一次)。
- 总计约 285 人天，区间 171-456；这是数量级，不是排期。
- 不含: Gradle 编译、Windows 实机验证、真实账号验证、GPU/音频验收；按资源策略它们被推迟，且很可能与源码工作量同量级或更大。
- 建议: 每完成一个 T0/T1 切片就把它的已知符号缺口加入 `fnd-symbol-resolution-gate`，攒到一个一致的冻结源码计划后再一次性请求编译。

